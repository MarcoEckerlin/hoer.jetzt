#!/usr/bin/env python3
"""
hoer.jetzt - Knoten-Agent

Ein kleiner Dienst auf dem Knoten-Host. Er tut genau vier Dinge:

    GET  /zustand         wie es dem Knoten geht
    POST /neustart        Container neu starten
    POST /aktualisieren   Zweig holen, neu bauen, neu starten
    GET  /protokoll       die letzten Zeilen des Aktualisierungslaufs

Dazu meldet er den Knoten beim Bot an und schickt im Takt ein Lebenszeichen.

Warum ein Dienst auf dem Host und kein Container
------------------------------------------------
Ein Container haette den Docker-Socket gebraucht, um den Lavalink-Container
neu zu starten - und wer den Socket hat, ist auf dem Host ohnehin root. Der
Sicherheitsgewinn waere also null gewesen, der Aufwand aber echt: fuer
"aktualisieren" braucht es zusaetzlich git und das Knotenverzeichnis, beides
liegt auf dem Host. Ein Container haette beides hineingereicht bekommen
muessen und waere am Ende ein Host-Prozess mit Extraschritten.

Warum nur die Standardbibliothek
--------------------------------
Der Agent laeuft auf frisch aufgesetzten Maschinen, auf denen das Autoscaling
gerade erst cloud-init durchlaufen hat. Jede Abhaengigkeit ist dort ein
weiterer Schritt, der scheitern kann - und ein Agent, der sich nicht starten
laesst, ist genau dann weg, wenn man ihn braucht.
"""

import hmac
import json
import os
import shlex
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1"

# ---------------------------------------------------------------- Umgebung

def wert(name, vorgabe=""):
    return (os.environ.get(name) or vorgabe).strip()


AGENT_TOKEN = wert("HJ_AGENT_TOKEN")
AGENT_PORT = int(wert("HJ_AGENT_PORT", "8099"))
AGENT_BIND = wert("HJ_AGENT_BIND", "0.0.0.0")

CONTAINER = wert("HJ_NODE_CONTAINER", "hoerjetzt-lavalink-1")

# Der Knoten heisst wie die Maschine. Frueher hiess er "free-1", "free-2" -
# auf einem Host eindeutig, ueber alle Hosts hinweg aber nicht: zwei Maschinen
# mit je einem ersten Knoten hiessen beide "free-1", und bei doppelten Namen
# verwirft der Bot stillschweigend den zweiten. Bei automatisch erzeugten
# Servern faellt das erst recht ins Gewicht - dort vergibt niemand von Hand
# eine Nummer.
KNOTEN_NAME = wert("HJ_NODE_NAME") or socket.gethostname().split(".")[0]
KNOTEN_VERZEICHNIS = wert("HJ_NODE_DIR", "/opt/hoerjetzt-node")

CORE_URL = wert("HJ_CORE_URL").rstrip("/")
NODE_TOKEN = wert("HJ_NODE_TOKEN")
KNOTEN_ADRESSE = wert("HJ_NODE_ADDRESS")
KNOTEN_PASSWORT = wert("HJ_LAVALINK_PASSWORD")
KNOTEN_STUFE = wert("HJ_NODE_TIER", "free")
AGENT_URL = wert("HJ_AGENT_URL")
HETZNER_ID = wert("HETZNER_SERVER_ID")
VOM_AUTOSCALING = wert("HJ_AUTOSCALED", "false").lower() == "true"

LAUFPROTOKOLL = "/var/log/hoerjetzt-knoten-agent-update.log"
LAUF_UNIT = "hoerjetzt-knoten-update"


# ---------------------------------------------------------------- Werkzeuge

def rufe(befehl, zeitlimit=30):
    """Fuehrt einen Befehl aus und liefert (rueckgabe, ausgabe)."""
    try:
        fertig = subprocess.run(
            befehl if isinstance(befehl, list) else shlex.split(befehl),
            capture_output=True, text=True, timeout=zeitlimit
        )
        return fertig.returncode, (fertig.stdout + fertig.stderr).strip()
    except subprocess.TimeoutExpired:
        return 124, "Zeitlimit ueberschritten."
    except FileNotFoundError as fehler:
        return 127, str(fehler)


def container_zustand():
    code, ausgabe = rufe(
        ["docker", "inspect", "-f",
         "{{.State.Status}}|{{.State.StartedAt}}|{{.Config.Image}}|{{.RestartCount}}",
         CONTAINER]
    )
    if code != 0:
        return {"vorhanden": False, "status": "fehlt", "meldung": ausgabe[:400]}

    teile = (ausgabe.splitlines()[0] if ausgabe else "").split("|")
    while len(teile) < 4:
        teile.append("")
    return {
        "vorhanden": True,
        "status": teile[0],
        "gestartet": teile[1],
        "abbild": teile[2],
        "neustarts": teile[3],
    }


def lavalink_version():
    """Fragt Lavalink im Container selbst - von aussen waere das Passwort noetig."""
    code, ausgabe = rufe(
        ["docker", "exec", CONTAINER, "curl", "-fsS", "-m", "4",
         "http://127.0.0.1:2333/version"], zeitlimit=12
    )
    return ausgabe.strip()[:120] if code == 0 else ""


def systemlast():
    try:
        eins, fuenf, fuenfzehn = os.getloadavg()
        kerne = os.cpu_count() or 1
        return {"last1": round(eins / kerne, 3), "last5": round(fuenf / kerne, 3), "kerne": kerne}
    except OSError:
        return {}


def zustand():
    return {
        "name": KNOTEN_NAME,
        "container": CONTAINER,
        "agentVersion": VERSION,
        "docker": container_zustand(),
        "lavalink": lavalink_version(),
        "system": systemlast(),
        "aktualisierungLaeuft": lauf_aktiv(),
        "zeit": int(time.time()),
    }


# ---------------------------------------------------------------- Aktionen

def neustart():
    code, ausgabe = rufe(["docker", "restart", CONTAINER], zeitlimit=90)
    if code != 0:
        return False, ausgabe[:400] or "Neustart fehlgeschlagen."
    return True, "Container %s neu gestartet." % CONTAINER


def lauf_aktiv():
    code, ausgabe = rufe(["systemctl", "is-active", LAUF_UNIT], zeitlimit=10)
    return code == 0 and ausgabe.strip() == "active"


def aktualisieren():
    """
    Stoesst den Aktualisierungslauf an - als eigene, fluechtige systemd-Unit.

    Der naheliegende Weg waere ein Thread im Agenten gewesen. Der geht hier
    nicht: update.sh erneuert am Ende auch den Agenten selbst, und
    "systemctl restart" raeumt die komplette Control-Group der Unit ab. Der
    Agent haette also mitten im Bauvorgang seinen eigenen Kindprozess
    erschlagen - und zwar genau dann, wenn schon alles Alte geloescht war.

    Als transiente Unit haengt der Lauf nicht mehr am Agenten und ueberlebt
    dessen Neustart. Nebeneffekt: "systemctl status hoerjetzt-knoten-update"
    zeigt auch nach einem Fehlschlag noch, was los war.
    """
    if lauf_aktiv():
        return False, "Es laeuft bereits eine Aktualisierung."

    # Releases werden force-gepusht - "git pull" scheitert hier an
    # divergierenden Zweigen. fetch + reset ist der einzige Weg, der in
    # diesem Repository funktioniert.
    skript = (
        "set -e; "
        "cd {dir}; "
        "echo '=== Zweig holen'; git fetch origin lavalink; "
        "echo '=== Auf den neuen Stand'; git reset --hard origin/lavalink; "
        "echo '=== Bauen und starten'; bash update.sh"
    ).format(dir=shlex.quote(KNOTEN_VERZEICHNIS))

    code, ausgabe = rufe([
        "systemd-run",
        "--collect",
        "--unit", LAUF_UNIT,
        "--description", "hoer.jetzt Knoten aktualisieren",
        "--property", "StandardOutput=append:" + LAUFPROTOKOLL,
        "--property", "StandardError=append:" + LAUFPROTOKOLL,
        "--property", "TimeoutStartSec=1800",
        "/bin/bash", "-c", skript,
    ], zeitlimit=20)

    if code != 0:
        return False, ausgabe[:400] or "systemd-run liess sich nicht starten."

    try:
        with open(LAUFPROTOKOLL, "a", encoding="utf-8") as datei:
            datei.write("\n\n########## Lauf gestartet %s\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
    except OSError:
        pass

    return True, "Aktualisierung gestartet - Fortschritt unter /protokoll."


def protokoll():
    try:
        with open(LAUFPROTOKOLL, "r", encoding="utf-8", errors="replace") as datei:
            return datei.read()[-8000:]
    except OSError:
        return "Noch kein Aktualisierungslauf."


# ---------------------------------------------------------------- Anmeldung

def beim_bot_melden(pfad, nutzlast):
    if not CORE_URL or not NODE_TOKEN:
        return False, "Core-Adresse oder Knoten-Token fehlt."

    anfrage = urllib.request.Request(
        "%s/api/nodes/%s" % (CORE_URL, pfad),
        data=json.dumps(nutzlast).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer %s" % NODE_TOKEN,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(anfrage, timeout=15) as antwort:
            return True, antwort.read().decode("utf-8", "replace")[:300]
    except urllib.error.HTTPError as fehler:
        return False, "HTTP %s: %s" % (fehler.code, fehler.read().decode("utf-8", "replace")[:300])
    except OSError as fehler:
        return False, str(fehler)


def anmelden():
    nutzlast = {
        "name": KNOTEN_NAME,
        "adresse": KNOTEN_ADRESSE,
        "passwort": KNOTEN_PASSWORT,
        "stufe": KNOTEN_STUFE,
        "agentUrl": AGENT_URL,
        "autoscaling": VOM_AUTOSCALING,
    }
    if HETZNER_ID:
        nutzlast["hetznerId"] = HETZNER_ID
    return beim_bot_melden("register", nutzlast)


def anmeldeschleife():
    """
    Meldet den Knoten an und haelt ihn angemeldet.

    Der erste Versuch scheitert oefter, als man denkt: bei einem frisch
    erzeugten Server laeuft der Agent, bevor das Netz vollstaendig steht.
    Deshalb wird wiederholt, statt einmal zu scheitern und still zu bleiben.
    """
    angemeldet = False
    while True:
        if not angemeldet:
            geklappt, meldung = anmelden()
            angemeldet = geklappt
            print("[Agent] Anmeldung: %s" % meldung, flush=True)
            time.sleep(10 if not geklappt else 60)
            continue

        geklappt, meldung = beim_bot_melden("heartbeat", {"name": KNOTEN_NAME})
        if not geklappt:
            # Der Bot war weg oder wurde neu aufgesetzt - dann noch einmal
            # vollstaendig anmelden statt weiter ins Leere zu klopfen.
            print("[Agent] Lebenszeichen fehlgeschlagen: %s" % meldung, flush=True)
            angemeldet = False
        time.sleep(60)


# ---------------------------------------------------------------- HTTP

class Griff(BaseHTTPRequestHandler):
    server_version = "hoerjetzt-knoten-agent/" + VERSION

    def log_message(self, format, *args):
        # Der Standard schreibt jede Anfrage nach stderr - bei einem
        # Lebenszeichen pro Minute laeuft das Journal damit voll.
        pass

    def _antwort(self, code, inhalt, typ="application/json"):
        koerper = (json.dumps(inhalt) if typ == "application/json" else inhalt).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", typ + "; charset=utf-8")
        self.send_header("Content-Length", str(len(koerper)))
        self.end_headers()
        self.wfile.write(koerper)

    def _befugt(self):
        if not AGENT_TOKEN:
            self._antwort(503, {"fehler": "Agent hat kein Token - Dienst bleibt zu."})
            return False

        kopf = self.headers.get("Authorization", "")
        mitgeschickt = kopf[7:].strip() if kopf[:7].lower() == "bearer " else ""

        # Zeitkonstant vergleichen: bei einem einfachen == verraet die Laufzeit,
        # wie viele Zeichen gestimmt haben.
        if not hmac.compare_digest(mitgeschickt, AGENT_TOKEN):
            self._antwort(401, {"fehler": "Falsches oder fehlendes Token."})
            return False
        return True

    def do_GET(self):
        if self.path == "/gesund":
            # Absichtlich ohne Token: der Loadbalancer und cloud-init muessen
            # sehen koennen, ob der Agent lebt, ohne ein Geheimnis zu kennen.
            self._antwort(200, {"status": "ok", "agentVersion": VERSION})
            return
        if not self._befugt():
            return
        if self.path == "/zustand":
            self._antwort(200, zustand())
        elif self.path == "/protokoll":
            self._antwort(200, protokoll(), typ="text/plain")
        else:
            self._antwort(404, {"fehler": "Unbekannt."})

    def do_POST(self):
        if not self._befugt():
            return
        if self.path == "/neustart":
            geklappt, meldung = neustart()
            self._antwort(200 if geklappt else 500, {"ok": geklappt, "meldung": meldung})
        elif self.path == "/aktualisieren":
            geklappt, meldung = aktualisieren()
            self._antwort(200 if geklappt else 409, {"ok": geklappt, "meldung": meldung})
        elif self.path == "/anmelden":
            geklappt, meldung = anmelden()
            self._antwort(200 if geklappt else 502, {"ok": geklappt, "meldung": meldung})
        else:
            self._antwort(404, {"fehler": "Unbekannt."})


def main():
    if not AGENT_TOKEN:
        print("[Agent] HJ_AGENT_TOKEN fehlt - der Dienst laeuft, weist aber alles ab.", flush=True)

    if CORE_URL and NODE_TOKEN:
        threading.Thread(target=anmeldeschleife, daemon=True).start()
    else:
        print("[Agent] Ohne HJ_CORE_URL/HJ_NODE_TOKEN keine Selbstanmeldung.", flush=True)

    server = ThreadingHTTPServer((AGENT_BIND, AGENT_PORT), Griff)
    print("[Agent] lauscht auf %s:%s fuer %s" % (AGENT_BIND, AGENT_PORT, CONTAINER), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
