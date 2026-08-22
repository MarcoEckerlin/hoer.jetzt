#!/usr/bin/env bash
#
# hoer.jetzt - modularer Knoten-Installer.
#
#   bash install-node.sh --modules core
#   bash install-node.sh --modules lavalink
#   bash install-node.sh --modules core,lavalink,ki-radio
#
#   --kennung <name>    Kennung dieses Knotens (aus dem Updater)
#   --token <hj-...>    Aufsetz-Token (aus dem Updater, gilt zwei Stunden)
#   --update-host <fqdn>  Vorgabe: repository.hoer.jetzt
#   --web-bind <adr>    Lauschadresse der Weboberflaeche (Vorgabe 0.0.0.0)
#   --web-port <port>   Host-Port der Weboberflaeche (Vorgabe 8080)
#   --privat-ip <adr>   Lauschadresse von Lavalink; sonst selbst ermittelt
#   --node-nr <n>       Zahlenraum eines Controllers; sonst aus der Kennung
#   --pruefen           nur nachsehen, nichts aendern
#
# ---------------------------------------------------------------------------
# Mehrfach ausfuehren ist sicher
#
# Jeder Schritt fragt zuerst, ob er noetig ist. Ein zweiter Lauf ergaenzt, was
# fehlt, und laesst alles andere in Ruhe - insbesondere Schluessel, .env und
# Datenbank. Das ist keine Bequemlichkeit: bei einer abgebrochenen Installation
# ist "noch einmal laufen lassen" der erste Griff, und wenn der etwas kaputt
# macht, ist es der letzte.
#
# ---------------------------------------------------------------------------
# Die Reihenfolge ist nicht beliebig
#
#   1. Voraussetzungen (Docker, openssl, curl)
#   2. Schluesselpaar - der private Teil bleibt hier, immer
#   3. Anmelden: Token gegen dauerhaftes Geheimnis
#   4. Oeffentlichen Schluessel hinterlegen
#   5. Tresor holen - erst jetzt moeglich, vorher gibt es nichts zu oeffnen
#   6. Module eintragen, Dienste starten
#
# Schritt 2 vor Schritt 3, weil der Server beim Anmelden schon wissen soll,
# wohin er spaeter verschluesseln kann. Und Schritt 5 nach Schritt 4, weil der
# Tresor sonst an einen Schluessel ginge, den es hier noch nicht gibt.
# ---------------------------------------------------------------------------

set -euo pipefail

# Dieses Skript laedt Nachbardateien und braucht deshalb einen Dateinamen.
#
# Bei "curl ... | bash" gibt es den nicht - BASH_SOURCE ist leer, und "set -u"
# brach hier mit "unbound variable" ab. Das ist die richtige Entscheidung mit
# der falschen Meldung: der Weg funktioniert wirklich nicht, aber der Grund
# stand nirgends. Wer ihn sah, suchte den Fehler im Skript.
if [[ -z "${BASH_SOURCE[0]:-}" ]]; then
    echo "Dieses Skript laedt Dateien aus seinem eigenen Verzeichnis und" >&2
    echo "kann nicht ueber eine Pipe laufen. Erst herunterladen:" >&2
    echo >&2
    echo "  curl -fsSLu knoten https://<update-server>/knoten/aufsetzen.sh -o a.sh" >&2
    echo "  bash a.sh" >&2
    exit 1
fi
HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
UMGEBUNG="${ARBEIT}/.env"
MODULDATEI="${ARBEIT}/module"
SCHLUESSEL="${ARBEIT}/knoten.key"
SPERRE="${ARBEIT}/.sperre"

# shellcheck source=agent/agent-lib.sh
. "${HIER}/agent/agent-lib.sh"

MODULE_WUNSCH=""
KENNUNG=""
TOKEN=""
HJ_UPDATE_HOST="${HJ_UPDATE_HOST:-repository.hoer.jetzt}"
NUR_PRUEFEN=false
# Lauschadresse der Weboberflaeche. 0.0.0.0, weil ein frisch aufgesetzter
# Knoten erreichbar sein soll - siehe die Begruendung weiter unten.
WEB_BIND="${HJ_WEB_BIND:-0.0.0.0}"
WEB_PORT_HOST="${HJ_WEB_PORT_HOST:-8080}"
# Private Adresse fuer Lavalink. Leer heisst: selbst ermitteln.
PRIVAT_IP="${HJ_PRIVAT_IP:-}"
# Zahlenraum eines Controllers. Leer heisst: aus der Kennung ableiten.
NODE_NR="${HJ_NODE_NR:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --modules|--module) MODULE_WUNSCH="${2:?Modulliste angeben}"; shift 2 ;;
        --kennung)          KENNUNG="${2:?Kennung angeben}"; shift 2 ;;
        --token)            TOKEN="${2:?Token angeben}"; shift 2 ;;
        --update-host)      HJ_UPDATE_HOST="${2:?Adresse angeben}"; shift 2 ;;
        --web-bind)         WEB_BIND="${2:?Adresse angeben}"; shift 2 ;;
        --web-port)         WEB_PORT_HOST="${2:?Port angeben}"; shift 2 ;;
        --privat-ip)        PRIVAT_IP="${2:?Adresse angeben}"; shift 2 ;;
        --node-nr)          NODE_NR="${2:?Nummer angeben}"; shift 2 ;;
        --pruefen)          NUR_PRUEFEN=true; shift ;;
        -h|--help)          sed -n '2,20p' "$0"; exit 0 ;;
        *)                  fehler "Unbekannte Angabe: $1" ;;
    esac
done

# Die private Adresse einmal ermitteln - sie wird an zwei Stellen gebraucht:
# beim Melden an den Update-Server und als Lauschadresse von Lavalink.
#
# Docker-eigene Bruecken ausschliessen: die liegen selbst im privaten Bereich
# (172.17.x) und waeren hier die falsche Antwort.
if [[ -z "$PRIVAT_IP" ]]; then
    PRIVAT_IP="$(ip -4 -o addr show scope global 2>/dev/null \
        | awk '$2 !~ /^(docker|br-|veth|lo)/ {print $4}' \
        | cut -d/ -f1 \
        | grep -E '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' \
        | head -1 || true)"
fi
export HJ_UPDATE_HOST

# ------------------------------------------------------- Module aufloesen

# Abhaengigkeiten. Heute genau eine, aber die Stelle dafuer gibt es jetzt:
# jedes Modul zieht den Agenten nach sich, und der ist kein Modul, sondern
# immer da. Waere er ein Modul, koennte man ihn abwaehlen - und haette einen
# Knoten, der sich nie wieder meldet.
module_aufloesen() {
    local roh="$1" ergebnis=""
    local m
    for m in ${roh//,/ }; do
        m="$(echo "$m" | tr '[:upper:]' '[:lower:]' | tr '_' '-')"
        [[ "$m" == "ai-radio" ]] && m="ki-radio"
        case "$m" in
            core|lavalink|ki-radio) ergebnis="${ergebnis} ${m}" ;;
            agent)
                # "Nur der Agent" - ein Host unter Verwaltung, der (noch)
                # nichts betreibt. Kein eigenes Modul in der Liste, sondern
                # die leere Liste: der Agent gehoert ohnehin immer dazu.
                #
                # Das ist der erste Schritt der Migration aus Abschnitt 69:
                # einen bestehenden Server aufnehmen, ohne an seinen
                # laufenden Diensten etwas zu aendern. Module lassen sich
                # danach im Updater zuschalten.
                ;;
            controller)
                # Der Controller bleibt "controller" - nicht "core".
                #
                # Frueher wurde er hier auf core abgebildet. Das war zu grob:
                # der Tresor gab ihm daraufhin das Core-Profil, und der Knoten
                # galt ueberall als gewoehnlicher Core-Knoten.
                #
                # Er faehrt zwar dieselben Container (siehe dienste_von), aber
                # in anderer Rolle: HJ_ROLLE=controller schaltet den
                # Discord-Bot ab. Uebrig bleiben Webseite, Datenbank und die
                # Steuerung der uebrigen Knoten.
                #
                # Ohne diese Unterscheidung meldete sich der Controller mit
                # demselben Bot-Token bei Discord an wie die Core-Knoten -
                # Discord verteilt die Ereignisse dann auf beide, und Befehle
                # landen mal hier, mal dort.
                ergebnis="${ergebnis} controller"
                umgebung_setzen HJ_ROLLE controller || true
                ;;
            *) fehler "Unbekanntes Modul: ${m} (moeglich: core, lavalink, ki-radio, controller, agent)" ;;
        esac
    done
    echo "$ergebnis" | tr ' ' '\n' | grep -v '^$' | sort -u | paste -sd' ' -
}

# ---------------------------------------------------------------- Pruefen

schritt() { printf '\n== %s\n' "$*"; }
gut()     { printf '   ok   %s\n' "$*"; }
fehlt()   { printf '   --   %s\n' "$*"; }

if $NUR_PRUEFEN; then
    schritt "Zustand"
    [[ -d "$ARBEIT" ]]        && gut "Arbeitsverzeichnis ${ARBEIT}"      || fehlt "Arbeitsverzeichnis fehlt"
    [[ -f "$UMGEBUNG" ]]      && gut ".env vorhanden"                    || fehlt ".env fehlt"
    [[ -f "$SCHLUESSEL" ]]    && gut "Schluesselpaar vorhanden"          || fehlt "Schluesselpaar fehlt"
    [[ -f "$MODULDATEI" ]]    && gut "Module: $(module_lesen)"           || fehlt "Modulliste fehlt"
    command -v docker >/dev/null && gut "Docker"                          || fehlt "Docker fehlt"
    umgebung_lesen 2>/dev/null || true
    [[ -n "${HJ_KNOTEN_KENNUNG:-}" ]] && gut "angemeldet als ${HJ_KNOTEN_KENNUNG}" \
                                      || fehlt "nicht angemeldet"
    exit 0
fi

[[ -n "$MODULE_WUNSCH" ]] || fehler "--modules angeben, z.B. --modules core,lavalink"
MODULE="$(module_aufloesen "$MODULE_WUNSCH")"

mkdir -p "$ARBEIT"
mit_sperre || fehler "Ein anderer Lauf arbeitet gerade."

sagen "Module: ${MODULE}"

# ------------------------------------------------- 1. Voraussetzungen

schritt "Voraussetzungen"

for werkzeug in curl openssl; do
    command -v "$werkzeug" >/dev/null || fehler "${werkzeug} fehlt - erst nachinstallieren."
    gut "$werkzeug"
done

if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
    gut "Docker mit Compose"
else
    sagen "Docker fehlt - wird installiert."
    curl -fsSL https://get.docker.com | sh
    docker compose version >/dev/null 2>&1 || fehler "Docker Compose fehlt weiterhin."
    gut "Docker eingerichtet"
fi

# ---------------------------------------------------- 2. Schluesselpaar

schritt "Schluesselpaar"

if [[ -f "$SCHLUESSEL" ]]; then
    # NIE ueberschreiben. Ein neuer Schluessel macht jeden Tresor unlesbar,
    # der an den alten gerichtet war - und der zweite Lauf eines Installers
    # ist genau der Moment, in dem das unbemerkt passieren wuerde.
    gut "vorhanden - bleibt unangetastet"
else
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$SCHLUESSEL" 2>/dev/null
    chmod 600 "$SCHLUESSEL"
    gut "erzeugt (3072 Bit, bleibt auf diesem Host)"
fi
OEFFENTLICH="$(openssl rsa -in "$SCHLUESSEL" -pubout 2>/dev/null)"

# ---------------------------------------------------------- 3. Anmelden

schritt "Anmeldung beim Update-Server"

umgebung_lesen 2>/dev/null || true

# Gilt das gespeicherte Geheimnis ueberhaupt noch?
#
# Frueher wurde die Anmeldung uebersprungen, sobald irgendein Geheimnis in
# der .env stand. Das ist die haeufigste Falle beim Neuanlegen: der Knoten
# wird im Updater entfernt und neu angelegt, der Host behaelt aber sein altes
# Geheimnis. Der Installer meldete dann "bereits angemeldet" und lief in den
# naechsten Schritt - der mit 401 abbrach.
#
# Die Meldung dort zeigte auf die Adressfreigabe. Sie ist aber nicht das
# Problem: 401 heisst "Anmeldung stimmt nicht", eine gesperrte Adresse waere
# 403. Wer der Meldung folgt, sucht am falschen Ende.
#
# Also nachsehen, statt zu vermuten. Ein Abruf, der Anmeldung braucht,
# genuegt - er kostet nichts und beantwortet die Frage eindeutig.
GEHEIMNIS_GILT=false
if [[ -n "${HJ_KNOTEN_GEHEIMNIS:-}" && -n "${HJ_KNOTEN_KENNUNG:-}" ]]; then
    if us_holen "/release/aktuell" >/dev/null 2>&1; then
        GEHEIMNIS_GILT=true
    fi
fi

if $GEHEIMNIS_GILT; then
    gut "bereits angemeldet als ${HJ_KNOTEN_KENNUNG}"
else
    if [[ -n "${HJ_KNOTEN_GEHEIMNIS:-}" ]]; then
        # Es lag eines da, es gilt nur nicht mehr.
        warnen "Das gespeicherte Geheimnis von ${HJ_KNOTEN_KENNUNG:-diesem Host} gilt nicht mehr."
        warnen "Der Knoten wurde vermutlich im Updater entfernt und neu angelegt."
        [[ -n "$TOKEN" ]] || fehler \
            "Ohne --token laesst sich das nicht heilen.
       Im Updater unter 'Verwalten' einen neuen Aufsetz-Token erzeugen und
       diesen Befehl damit erneut ausfuehren."
        warnen "Wird mit dem angegebenen Token neu angemeldet."
    fi

    [[ -n "$KENNUNG" ]] || fehler "--kennung fehlt. Im Updater unter Verwalten anlegen."
    [[ -n "$TOKEN" ]]   || fehler "--token fehlt. Im Updater unter Verwalten erzeugen."

    antwort="$(curl -fsS --max-time 30 \
        -H "Content-Type: application/json" \
        -d "$(printf '{"kennung":"%s","token":"%s","rechnername":"%s","privatIp":"%s","ipv4":"%s","agentVersion":"2"}' \
              "$KENNUNG" "$TOKEN" "$(hostname -f 2>/dev/null || hostname)" \
              "$PRIVAT_IP" "$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo '')")" \
        "https://${HJ_UPDATE_HOST}/anmelden")" || fehler \
        "Anmeldung abgewiesen. Token abgelaufen (zwei Stunden) oder schon benutzt? Im Updater neu erzeugen."

    GEHEIMNIS="$(printf '%s' "$antwort" | sed -n 's/.*"geheimnis"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    [[ -n "$GEHEIMNIS" ]] || fehler "Antwort ohne Geheimnis: ${antwort}"

    touch "$UMGEBUNG" && chmod 600 "$UMGEBUNG"
    umgebung_setzen HJ_KNOTEN_KENNUNG   "$KENNUNG"   || true
    umgebung_setzen HJ_KNOTEN_GEHEIMNIS "$GEHEIMNIS" || true
    umgebung_setzen HJ_UPDATE_HOST      "$HJ_UPDATE_HOST" || true
    umgebung_lesen
    gut "angemeldet als ${KENNUNG}"
fi

# Woher die Abbilder kommen.
#
# Der Registry-Pfad ist der Update-Server plus Organisation - dieselbe
# Adresse, an der sich der Knoten gerade angemeldet hat.
#
# Ohne diese Zeile fiel der Hauptstack auf ghcr.io/marcoeckerlin/hoerjetzt
# zurueck. Das ist nicht unsere Registry: der Pull endete in "denied", und
# Compose versuchte daraufhin, die Abbilder aus dem Quellbaum zu BAUEN -
# den es auf einem Knoten nicht gibt:
#
#   unable to prepare context: path "/opt/hoerjetzt/core" not found
#
# Drei Meldungen, drei Irrwege, eine fehlende Variable. Die Compose-Dateien
# verlangen sie jetzt ausdruecklich, statt still auf etwas Fremdes zu zeigen.
umgebung_setzen HJ_REGISTRY "${HJ_UPDATE_HOST}/hoerjetzt" || true

# ------------------------------------------------- Lauschadresse der Weboberflaeche
#
# Die Compose-Datei bindet auf "${HJ_WEB_BIND:-127.0.0.1}". Dieser Vorgabewert
# ist richtig fuer einen Host mit eigenem Reverse-Proxy davor - und falsch
# fuer alles andere.
#
# install-node.sh hat den Wert nie gesetzt. Ein ueber bootstrap aufgesetzter
# Knoten lauschte damit nur auf 127.0.0.1: die Weboberflaeche war weder ueber
# die IP noch ueber einen Loadbalancer erreichbar. Von aussen sah es aus wie
# ein toter Dienst, dabei lief der Container und antwortete - nur eben
# ausschliesslich sich selbst.
#
# Der Fehler ist still: docker ps zeigt den Container als "Up", und im Log
# steht nichts. Sichtbar wird er erst in
#
#   ss -tlnp | grep 8080     ->   127.0.0.1:8080 statt 0.0.0.0:8080
#
# Nur wo die Weboberflaeche ueberhaupt laeuft. Ein Audio-Knoten hat keine,
# und ein Schluessel in der .env, den nichts liest, ist eine Spur, der
# irgendwann jemand nachgeht.
if printf '%s' "$MODULE" | grep -qE '(^| )(core|controller)( |$)'; then
    umgebung_setzen HJ_WEB_BIND      "$WEB_BIND"      || true
    umgebung_setzen HJ_WEB_PORT_HOST "$WEB_PORT_HOST" || true

    # Docker umgeht ufw bei veroeffentlichten Ports: -p landet direkt in
    # iptables, egal was ufw sagt. Bei 0.0.0.0 muss die Grenze deshalb
    # ausserhalb des Hosts liegen - bei Hetzner die Cloud-Firewall, die
    # davon unberuehrt ist. Wer stattdessen einen Proxy auf demselben Host
    # faehrt, gibt "--web-bind 127.0.0.1" an.
    if [[ "$WEB_BIND" == "0.0.0.0" ]]; then
        sagen "Weboberflaeche auf 0.0.0.0:${WEB_PORT_HOST} - die Firewall muss"
        sagen "ausserhalb des Hosts stehen (ufw greift bei Docker-Ports nicht)."
    else
        sagen "Weboberflaeche auf ${WEB_BIND}:${WEB_PORT_HOST}."
    fi
fi

# ------------------------------------------------------- Private Adresse
#
# Sobald es eine gibt, gehoert sie in die .env - nicht nur auf Audio-Knoten.
# Gebraucht wird sie an vier Stellen:
#
#   - Lavalink bindet darauf (docker-compose.yml)
#   - das Spock-Overlay bindet Postgres darauf
#   - spock-einrichten.sh verlangt sie und bricht sonst ab
#   - der Herzschlag meldet sie, damit man in der Oberflaeche sieht,
#     welche Maschine das eigentlich ist
#
# Vorher stand das Setzen INNERHALB des Lavalink-Blocks. Ein Controller bekam
# sie deshalb nie, und spock-einrichten.sh endete mit
#
#   HJ_PRIVAT_IP: HJ_PRIVAT_IP setzen - die 10.x-Adresse dieser Maschine
#
# auf einer Maschine, die eine hat.
if [[ -n "$PRIVAT_IP" ]]; then
    if umgebung_setzen HJ_PRIVAT_IP "$PRIVAT_IP"; then
        gut "Private Adresse ${PRIVAT_IP}"
    else
        gut "Private Adresse ${PRIVAT_IP} - unveraendert"
    fi
fi

# ------------------------------------------------- Lauschadresse von Lavalink
#
# Dieselbe Falle wie bei der Weboberflaeche, nur eine Ebene tiefer: die
# Compose-Datei bindet Lavalink auf "${HJ_PRIVAT_IP:-127.0.0.1}:2333", und
# gesetzt hat den Wert niemand. Ein Audio-Knoten haette also nur sich selbst
# bedient - der Kern auf einem anderen Host kaeme nicht an ihn heran, und im
# Adminbereich stuende er als "nicht erreichbar", waehrend der Container
# laeuft und das Log leer ist.
#
# Warum die private Adresse und nicht 0.0.0.0: Lavalinks einziger Schutz ist
# das Passwort. Docker umgeht ufw bei veroeffentlichten Ports, ein -p landet
# direkt in iptables. Auf 0.0.0.0 stuende der Dienst damit offen im Netz,
# egal was ufw sagt.
#
# Findet sich keine private Adresse, bricht es ab. Das ist Absicht: ein
# Knoten, der still auf 127.0.0.1 lauscht, kostet mehr Zeit als eine
# Fehlermeldung beim Aufsetzen.
if printf '%s' "$MODULE" | grep -qE '(^| )lavalink( |$)'; then
    [[ -n "$PRIVAT_IP" ]] || fehler "Keine private Adresse gefunden.
       Lavalink wuerde auf 127.0.0.1 lauschen und waere fuer den Kern
       unerreichbar. Adresse angeben: --privat-ip 10.0.0.5
       Vorhandene Adressen:
$(ip -4 -o addr show scope global 2>/dev/null | awk '{printf "         %-10s %s\n", $2, $4}')"

    sagen "Lavalink auf ${PRIVAT_IP}:2333 - nur aus dem privaten Netz."
fi

# ------------------------------------------------ Controller: Nummer und Spock
#
# Ein Controller braucht beides, und beides muss VOR dem ersten Start stehen.
#
# HJ_NODE_NR
# ----------
# schema-postgres.sql legt an:
#
#   CREATE SEQUENCE IF NOT EXISTS hj_id_seq START WITH ${HJ_NODE_NR} INCREMENT BY 1000
#
# Node 1 vergibt 1, 1001, 2001 - Node 2 vergibt 2, 1002, 2002. So kollidieren
# zwei Schreiber nicht, obwohl beide fortlaufende Nummern vergeben.
#
# Der Vorgabewert ist 1. Bisher setzte ihn niemand, also standen ALLE Knoten
# auf 1 - unter Replikation heisst das doppelte Schluessel, sobald beide
# schreiben. Das faellt nicht beim Einrichten auf, sondern im Betrieb.
#
# "IF NOT EXISTS" ist der Grund fuer "vor dem ersten Start": steht die Sequenz
# einmal, aendert ein spaeteres HJ_NODE_NR gar nichts mehr.
#
# Die Nummer kommt aus der Kennung - controller-1 wird 1, controller-2 wird 2.
# Das ist vorhersagbar und braucht keine Rueckfrage beim Update-Server. Wer
# anders benennt, gibt sie mit --node-nr an.
if printf '%s' "$MODULE" | grep -qE '(^| )controller( |$)'; then

    if [[ -z "$NODE_NR" ]]; then
        NODE_NR="$(printf '%s' "$KENNUNG" | grep -oE '[0-9]+$' || true)"
    fi
    [[ -n "$NODE_NR" ]] || fehler "Kein Zahlenraum fuer diesen Controller.
       Die Kennung '${KENNUNG}' endet nicht auf einer Zahl, aus der sich
       HJ_NODE_NR ableiten liesse. Zwei Controller mit derselben Nummer
       vergeben dieselben Schluessel, sobald sie replizieren.
       Angeben mit: --node-nr 2"
    [[ "$NODE_NR" =~ ^[0-9]{1,3}$ && "$NODE_NR" -ge 1 ]] || fehler \
        "HJ_NODE_NR muss zwischen 1 und 999 liegen (ist: ${NODE_NR}).
       Der Schritt der Sequenz ist 1000 - groessere Nummern ueberlappen."

    if umgebung_setzen HJ_NODE_NR "$NODE_NR"; then
        gut "Zahlenraum ${NODE_NR} (vergibt ${NODE_NR}, $((NODE_NR + 1000)), $((NODE_NR + 2000)) ...)"
    else
        gut "Zahlenraum ${NODE_NR} - unveraendert"
    fi

    # Ohne private Adresse laeuft die Replikation nicht: das Spock-Overlay
    # bindet Postgres darauf, und spock-einrichten.sh bricht ohne sie ab.
    # Kein Grund, das Aufsetzen abzubrechen - ein Controller ohne privates
    # Netz ist ein zulaessiger Einzelbetrieb.
    if [[ -z "$PRIVAT_IP" ]]; then
        warnen "Keine private Adresse - Replikation ist damit nicht moeglich."
        sagen "  Das Spock-Overlay bindet Postgres auf HJ_PRIVAT_IP, und"
        sagen "  spock-einrichten.sh verlangt sie. Falls es ein privates Netz"
        sagen "  gibt, das hier nicht erkannt wurde: --privat-ip 10.0.0.5"
    fi

    # ------------------------------------------------------------------ Spock
    #
    # Und hier wird es heikel: das Spock-Overlay benutzt ein anderes Abbild
    # UND ein anderes Volume (pgdaten-spock statt postgres-daten). Einfach
    # einschalten heisst auf einem laufenden Controller: Postgres startet auf
    # einem leeren Volume, und es sieht aus, als seien die Daten weg.
    #
    # install-node.sh ist ausdruecklich mehrfach ausfuehrbar. Genau deshalb
    # darf es das nicht selbst tun, wenn schon Daten daliegen - der zweite
    # Lauf waere sonst der teuerste.
    if grep -q '^HJ_SPOCK=true' "$UMGEBUNG" 2>/dev/null; then
        gut "Replikation (Spock) ist aktiv"
    elif docker volume inspect hoerjetzt_postgres-daten >/dev/null 2>&1 \
         && [[ -n "$(docker run --rm -v hoerjetzt_postgres-daten:/v alpine:3 \
                     sh -c 'ls -A /v 2>/dev/null | head -1' 2>/dev/null)" ]]; then
        warnen "Replikation NICHT eingeschaltet - es liegen schon Daten."
        sagen "  Spock benutzt ein anderes Volume (pgdaten-spock). Umschalten"
        sagen "  ohne Umzug hiesse: leere Datenbank. Der Weg mit Daten:"
        sagen ""
        sagen "    bash ${HIER}/sicherung.sh --nur-lokal"
        sagen "    echo HJ_SPOCK=true >> ${UMGEBUNG}"
        sagen "    bash ${HIER}/install-node.sh --kennung ${KENNUNG} --modules ${MODULE// /,}"
        sagen "    bash ${HIER}/uebernehmen.sh --datei ${ARBEIT}/sicherungen/<datei>"
        sagen "    bash ${HIER}/spock-einrichten.sh anlegen"
    else
        umgebung_setzen HJ_SPOCK true || true
        gut "Replikation (Spock) eingeschaltet - leere Datenbank, gefahrlos"
        sagen "  Koppeln mit dem anderen Controller danach:"
        sagen "    bash ${HIER}/spock-einrichten.sh anlegen"
        sagen "    bash ${HIER}/spock-einrichten.sh verbinden <ip-des-anderen> <dessen-nummer>"
    fi
fi

# ------------------------------------------- 4. Schluessel hinterlegen

schritt "Oeffentlichen Schluessel hinterlegen"

# Jedes Mal, nicht nur beim ersten Lauf: hinterlegen ist billig, und ein
# Knoten, dessen Schluessel im Server fehlt, bekommt sonst beim Tresorabruf
# eine Meldung, die niemand erwartet.
if us_senden "/schluessel" "$(printf '{"zweck":"TRESOR","oeffentlich":%s}' \
        "$(printf '%s' "$OEFFENTLICH" | sed ':a;N;$!ba;s/\n/\\n/g;s/^/"/;s/$/"/')")" >/dev/null; then
    gut "hinterlegt"
else
    # Was hier schiefgehen kann, und wie man es auseinanderhaelt:
    #
    #   401  Die Anmeldung stimmt nicht. Meist ein Geheimnis, das nicht mehr
    #        gilt - der Knoten wurde im Updater neu angelegt.
    #   403  Die Anmeldung stimmt, die Adresse ist gesperrt oder nicht frei.
    #   404  Der Pfad gibt es nicht - falscher Update-Server oder alter Stand.
    #
    # Die alte Meldung nannte nur die Adresse. Wer 401 bekam, suchte
    # daraufhin in den Freigaben - und fand dort nichts, weil dort nichts war.
    fehler "Schluessel liess sich nicht hinterlegen.

       Bei 401 stimmt die Anmeldung nicht: im Updater unter 'Verwalten' einen
       neuen Aufsetz-Token erzeugen und diesen Befehl damit wiederholen.

       Bei 403 ist die Adresse dieses Hosts nicht freigeschaltet. Unter
       'Freigaben' eintragen - die oeffentliche IPv4 dieses Hosts lautet:
       $(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo '<nicht ermittelbar>')"
fi

# ------------------------------------------------------------ 5. Tresor

schritt "Zugangsdaten"

# Welches Profil ein Modul braucht. Ein Audio-Knoten bekommt nur das
# Lavalink-Profil - er soll nichts weiter kennen.
for m in $MODULE; do
    case "$m" in
        core)     profil="core" ;;
        lavalink) profil="lavalink" ;;
        ki-radio) profil="ki-radio" ;;
        # Der Controller bekommt sein eigenes Profil - tresor.sh kennt es
        # bereits. Es enthaelt zusaetzlich, was nur die Steuer-Node braucht,
        # und laesst weg, was nur der Bot braucht.
        controller) profil="controller" ;;
        *)        continue ;;
    esac
    teil="${ARBEIT}/tresor-${profil}.env"
    if tresor_holen "$profil" "$teil"; then
        # In die .env uebernehmen, ohne Vorhandenes zu ueberschreiben:
        # HJ_SPOCK, eigene Ports und Tailscale-Angaben gehoeren dem Host.
        while IFS= read -r zeile; do
            [[ "$zeile" =~ ^[[:space:]]*# ]] && continue
            [[ "$zeile" == *=* ]] || continue
            umgebung_setzen "${zeile%%=*}" "${zeile#*=}" >/dev/null || true
        done < "$teil"
        rm -f "$teil"
        gut "Profil ${profil}"
    else
        # Zwei Ursachen, und die Meldung darf sie nicht vermengen:
        #
        #   - der Abruf scheiterte (Modul nicht zugeteilt, Adresse gesperrt)
        #   - er klappte, aber im Tresor stehen keine Werte
        #
        # Im zweiten Fall hat tresor_pruefen die fehlenden Schluessel bereits
        # einzeln genannt. Hier steht nur noch, dass es daran lag.
        fehler "Tresor-Profil '${profil}' unbrauchbar.

       Entweder ist es leer - dann auf dem Update-Server:
           bash update-server/tresor.sh fuellen ${profil}

       Oder der Abruf wurde abgewiesen. Dann im Updater nachsehen:
           ist dem Knoten das Modul zugeteilt, und ist seine Adresse frei?"
    fi
done

# ------------------------------------------------------------ 6. Starten

schritt "Module eintragen und starten"

module_schreiben "$MODULE"
gut "Modulliste: $(module_lesen)"

# Der Agent gehoert immer dazu - siehe module_aufloesen.
if [[ -d /etc/systemd/system ]]; then
    install -m644 "${HIER}/systemd/hj-agent.service" /etc/systemd/system/ 2>/dev/null || true
    install -m644 "${HIER}/systemd/hj-agent.timer"   /etc/systemd/system/ 2>/dev/null || true
    systemctl daemon-reload 2>/dev/null || true
    systemctl enable --now hj-agent.timer 2>/dev/null || true
    gut "Agent laeuft im Minutentakt"
fi

# Die Datenbanksicherung - nur wo eine Datenbank laeuft.
#
# hj-sicherung.timer lag seit jeher in deploy/systemd/, wurde aber nie
# installiert. Auf keinem Knoten ist je eine Sicherung entstanden; sicherung.sh
# gab es, aufgerufen hat es niemand.
#
# Nicht auf Audio-Knoten: die haben keine Datenbank, und ein Timer, der alle
# drei Stunden mit "pg_dump: kein Container" scheitert, verstopft nur das
# Journal und trainiert einen darauf, Fehler zu uebersehen.
if [[ -d /etc/systemd/system ]] && printf '%s' "$MODULE" | grep -qE '(^| )(core|controller)( |$)'; then
    install -m644 "${HIER}/systemd/hj-sicherung.service" /etc/systemd/system/ 2>/dev/null || true
    install -m644 "${HIER}/systemd/hj-sicherung.timer"   /etc/systemd/system/ 2>/dev/null || true
    systemctl daemon-reload 2>/dev/null || true
    systemctl enable --now hj-sicherung.timer 2>/dev/null || true
    gut "Sicherung alle drei Stunden (00,03,06...:07 Uhr)"
fi

# Gibt es ueberhaupt ein Release?
#
# Ohne veroeffentlichte Abbilder gibt es nichts zu ziehen. Der Versuch endet
# in einer Kette irrefuehrender Meldungen - "denied", dann "unable to prepare
# context" -, weil Compose nach dem gescheiterten Pull versucht, aus dem
# Quellbaum zu bauen. Den gibt es auf einem Knoten nicht, und das ist auch
# richtig so: er soll Abbilder bekommen, keinen Quellcode.
#
# Bei "nur Agent" (leere Modulliste) faellt das weg: dieser Host betreibt
# nichts und braucht folglich kein Release.
if [[ -n "$MODULE" ]]; then
    MANIFEST="$(us_holen "/release/aktuell" 2>/dev/null || true)"
    if [[ -z "$MANIFEST" || "$MANIFEST" == *"noch nichts veroeffentlicht"* ]]; then
        fehler "Auf dem Update-Server ist noch kein Release veroeffentlicht.

       Ohne Abbilder kann dieser Knoten nichts starten. Auf dem
       Update-Server nachholen:

           bash update-server/veroeffentlichen.sh <version>

       Der Knoten ist bereits angemeldet und eingetragen - nach dem
       Veroeffentlichen genuegt hier:

           bash install-node.sh --modules ${MODULE// /,}"
    fi
fi

if [[ -z "$MODULE" ]]; then
    gut "Kein Modul - dieser Host ist unter Verwaltung, betreibt aber nichts."
    gut "Module lassen sich im Updater zuschalten; der Agent holt sie ab."
    echo
    exit 0
fi

# An der Registry anmelden, bevor irgendetwas gezogen wird.
#
# Ohne das versucht Docker es anonym - und scheitert an unserem Torwaechter
# mit einer Meldung, die nach einem Problem der Registry aussieht:
#
#   failed to fetch anonymous token: 401 Unauthorized
#
# Danach faellt Compose auf "bauen" zurueck und meldet
# "unable to prepare context: path /opt/hoerjetzt/lavalink not found" -
# drei Irrwege von der eigentlichen Ursache entfernt.
#
# Mit der eigenen Kennung, nicht mit einem gemeinsamen Passwort: daran haengt,
# welche Abbilder dieser Knoten ueberhaupt ziehen darf.
if ! printf '%s' "$HJ_KNOTEN_GEHEIMNIS" | docker login "$HJ_UPDATE_HOST" \
        -u "$HJ_KNOTEN_KENNUNG" --password-stdin >/dev/null 2>&1; then
    fehler "Anmeldung an der Registry ${HJ_UPDATE_HOST} fehlgeschlagen.

       Als: ${HJ_KNOTEN_KENNUNG}

       Ist die Adresse dieses Hosts unter 'Freigaben' eingetragen? Die
       oeffentliche IPv4 lautet:
       $(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo '<nicht ermittelbar>')"
fi
gut "an der Registry angemeldet"

dienste=""
for m in $MODULE; do
    dienste="${dienste} $(dienste_von "$m")"
done
sagen "Dienste: ${dienste# }"

# Die Werte dorthin bringen, wo Compose sie liest. Ohne das wirkt nichts,
# was dieses Skript in die .env geschrieben hat - siehe
# umgebung_uebertragen in agent-lib.sh.
umgebung_uebertragen
sagen "Umgebung ins Compose-Verzeichnis uebertragen."

if [[ -n "${dienste// /}" ]]; then
    # shellcheck disable=SC2086
    #
    # up -d und nicht restart: Portbindungen entstehen beim ANLEGEN des
    # Containers. Ein Neustart behaelt die alten - eine geaenderte
    # Lauschadresse wird damit nie wirksam, und es sieht aus, als haette
    # die Aenderung nichts bewirkt.
    compose up -d $dienste
fi

schritt "Fertig"
sagen "Knoten ${HJ_KNOTEN_KENNUNG} mit [${MODULE}] eingerichtet."
sagen "Zustand ansehen: bash ${HIER}/agent/hj-agent.sh --zustand"
