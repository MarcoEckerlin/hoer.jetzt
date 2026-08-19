# hoer.jetzt — Update-Server

Löst GitHub als Bezugsquelle ab. Läuft zuhause, liefert **Abbilder statt
Quellcode**.

Erreichbar unter `https://update.system.hoer.jetzt`.

---

## Warum nicht einfach ein privates Repository

Weil das den Kern des Problems nicht anfasst. Unter GitHub baut **jeder Knoten
selbst** — und dafür braucht er den vollständigen Quellbaum samt Historie,
Maven und ein JDK. Auf einem Audio-Knoten, der nichts weiter tut als Opus zu
kodieren, liegt damit der gesamte Quellcode. Wer eine dieser Maschinen
aufmacht, hat alles.

Der Update-Server dreht das um:

| | GitHub, bisher | Update-Server |
|---|---|---|
| Was der Knoten bekommt | Quellcode aller fünf Zweige | fertige Container |
| Was er dafür braucht | git, Maven, JDK, ~2 GB | Docker |
| Dauer eines Updates | 2–3 Minuten Build | Sekunden, nur Laden |
| Rückweg | Rebuild des alten Standes | ein Abbild-Tag |
| Zugang | SSH-Deploy-Key pro Host | Client-Zertifikat |

---

## Die drei Zugänge

Einer zum Tippen, zwei zum Mitnehmen.

### 1 — Knoten-Passwort

Kurz, Form `hj-XXXX-XXXX-XXXX-XXXX`. Alphabet ohne `0/O` und `1/l/I`, damit es
sich fehlerfrei abtippen lässt. Rund 80 Bit.

Öffnet **nur** `/knoten/` — das Installationsskript und die Compose-Dateien.
Keinen Schlüssel, kein Zugangsdatum. Wird beim Aufsetzen einmal gebraucht und
danach nirgends gespeichert.

```bash
curl -fsSLu knoten https://update.system.hoer.jetzt/knoten/aufsetzen.sh -o a.sh && bash a.sh
```

Das `-u knoten` ohne Doppelpunkt ist Absicht: curl fragt das Passwort selbst
ab, statt es in die Shell-Historie zu schreiben.

### 2 — Update-Ausweis (RSA-4096)

Ein Client-Zertifikat, ausgestellt von einer eigenen kleinen CA. Öffnet
`/v2/` (die Abbilder), `/release/` und `/tresor/`.

Bleibt dauerhaft auf dem Knoten. Docker legt ihn von sich aus vor, sobald er
unter `/etc/docker/certs.d/<host>/client.cert` liegt — **kein `docker login`,
kein Passwort im Speicher**. Der private Schlüssel verlässt die Platte nie; er
beweist nur, dass er da ist.

### 3 — Tresor-Schlüssel (RSA-4096)

Macht die gemeinsamen Zugangsdaten auf. Der Server bekommt nur den
öffentlichen Teil (`tresor.crt`) und kann damit verschlüsseln — **aufmachen
kann er den Tresor nicht.**

Selbst wer einen gültigen Ausweis hat und den Tresor herunterlädt, bekommt
einen CMS-Umschlag, den er nicht öffnet.

### Was das kostet

Das dauerhaft gespeicherte Zugangsdatum (der Ausweis) ist das harmloseste: er
zieht Container, die der Knoten ohnehin ausführt. Genau umgekehrt zu vorher,
wo der dauerhaft hinterlegte Deploy-Key den ganzen Quellcode aufschloss.

**Die Einschränkung, damit sie ausgesprochen ist:** wer das Knoten-Passwort
hat, kann den Installationsvorgang starten — und der fragt anschließend nach
den beiden Schlüsseln. Ohne sie kommt er nicht weiter. Die drei Stufen sind
also wirklich drei, aber das Passwort ist der Türöffner zum Verfahren, nicht
zu den Daten. `einrichten.sh` prüft das ausdrücklich: eine der Proben stellt
sicher, dass ein gültiger **Ausweis** `/knoten/` *nicht* öffnet und umgekehrt.

---

## Aufbau

```
                    Caddy  (TLS, client_auth: verify_if_given)
                      |
   /v2/*      ------> Forgejo-Registry      Ausweis nötig
   /release/* ------> Volume "ausliefern"   Ausweis nötig
   /tresor/*  ------> Volume "ausliefern"   Ausweis nötig + Tresor-Schlüssel
   /knoten/*  ------> Volume "ausliefern"   Passwort
```

`verify_if_given` statt `require_and_verify`: `/knoten/` muss ohne Ausweis
erreichbar sein — ein frischer Knoten hat ja noch keinen. Der Zwang steht
deshalb an den drei Pfaden, nicht am Anschluss.

Forgejos Oberfläche und das Git lauschen nur auf `127.0.0.1`. Für die
Verwaltung: `ssh -L 3000:127.0.0.1:3000`.

`ausliefern` ist ein benanntes Docker-Volume, kein Pfad auf dem Host — die
Bauschritte des CI-Runners laufen in eigenen Containern und kennen keine
Hostpfade.

---

## Einrichten

```bash
bash einrichten.sh
```

Erzeugt die Schlüssel und das Passwort, legt Forgejo an, meldet den Runner an,
startet Caddy — und prüft sich am Ende selbst. Die letzte Probe lädt ein
Testabbild hoch und wieder herunter; sie fällt auf alles herein, was die
anderen nicht sehen (falscher Dateiname in `certs.d`, fehlender
`/etc/hosts`-Eintrag, ein Leserecht, das Forgejo doch verlangt).

Dann den Tresor befüllen:

```bash
bash tresor.sh fuellen voll
bash tresor.sh fuellen lavalink
```

Der Unterschied ist der eigentliche Gewinn: `lavalink` enthält nur das
Lavalink-Passwort. Ein Audio-Knoten bekommt weder Datenbank noch Bot-Token —
er braucht beides nicht.

```bash
bash tresor.sh stand           # was da ist, ohne aufzumachen
bash tresor.sh zeigen voll     # nachsehen, braucht tresor.key
bash schluessel.sh zeigen      # Fingerabdrücke und Laufzeiten
```

---

## Ein Release veröffentlichen

Wie bisher: `RELEASE` auf den gewünschten Stand bringen, Tag setzen, pushen.
Den Rest macht der Runner.

Von Hand:

```bash
bash veroeffentlichen.sh                  # Version aus RELEASE
bash veroeffentlichen.sh 2026.08.19.01    # Version vorgeben
bash veroeffentlichen.sh --nur-manifest   # nur umschalten, nichts bauen
```

Die Reihenfolge ist Absicht: erst **alle** Abbilder bauen und hochladen, ganz
zum Schluss das Manifest umschreiben. Bricht ein Build ab, zeigt das Manifest
weiter auf das vorige Release und kein Knoten merkt etwas. Ein Manifest, das
auf ein fehlendes Abbild zeigt, wäre ein Ausfall auf allen Hosts gleichzeitig
— jede Nacht um drei aufs Neue.

---

## Der Umzug — der eine Handgriff, der bleibt

Der Ausweis kann nicht über GitHub kommen; ein privates Repository ist kein
Geheimnisspeicher. Ablauf:

1. Update-Server aufsetzen, Tresor befüllen, ein Release veröffentlichen.
2. Ein **letztes** Release über GitHub ausrollen — es enthält die neue
   `deploy/auto-update.sh`, die den Update-Server statt GitHub befragt.
3. Auf jedem Host einmal `knoten-aufsetzen.sh` laufen lassen. Es erkennt eine
   vorhandene `.env`, sichert sie und ergänzt nur die Werte aus dem Tresor —
   `HJ_SPOCK`, eigene Ports und Tailscale-Angaben bleiben stehen.
4. Probe: `bash /opt/hoerjetzt/main/deploy/auto-update.sh --pruefen`

Fehlt der Ausweis, bricht `auto-update.sh` mit genau dieser Anleitung ab statt
stillschweigend nichts zu tun.

---

## Wenn ein Update schiefgeht

```bash
bash /opt/hoerjetzt/main/deploy/auto-update.sh --zurueck
```

Setzt auf das zuvor gelaufene Release zurück — in Sekunden, weil das alte
Abbild noch da ist. `image prune` räumt nur weg, was älter als eine Woche ist;
ein `-f` ohne Filter hätte den Rückweg sofort mitgenommen.

---

## Wenn ein Knoten verloren geht

Alle Knoten teilen sich einen Ausweis. Geht eine Maschine verloren, ist der
Weg:

```bash
bash schluessel.sh erneuern    # neuer Ausweis, gleiche CA
```

und den neuen auf die verbliebenen Hosts bringen. Die CA bleibt, der alte
Ausweis gilt weiter — für echte Sperrung bräuchte es eine Sperrliste oder
einen Ausweis je Knoten. Beides ist mit dieser CA jederzeit nachrüstbar; für
den jetzigen Umfang wäre es Aufwand ohne Gegenwert.
