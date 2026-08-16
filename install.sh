#!/usr/bin/env bash
#
# hoer.jetzt lavalink - Audio-Knoten
#
#   bash install.sh
#
# Baut das Abbild aus diesem Zweig und startet den Container. Erwartet nichts
# ausser Docker - Java, Maven und Abhaengigkeiten stecken im Bauabbild.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Wird beim Fragen nach der Instanz gesetzt. Mehrere Knoten auf einem Host
# brauchen verschiedene Containernamen und verschiedene Ports.
NAME="hoerjetzt-lavalink"
INSTANZ="1"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

docker_pruefen() {
    if ! command -v docker >/dev/null 2>&1; then
        step "Docker installieren"
        [[ "$(id -u)" -eq 0 ]] || fail "Docker fehlt - dann bitte als root starten."
        curl -fsSL https://get.docker.com | sh || fail "Docker-Installation fehlgeschlagen."
        systemctl enable --now docker >/dev/null 2>&1 || true
    fi
    docker compose version >/dev/null 2>&1 || fail "docker compose (v2) fehlt."
    info "Docker: $(docker --version)"
}

# frage VARIABLE "Text" "Vorgabe"
frage() {
    local __v="$1" __t="$2" __d="${3:-}" __e=""
    if [[ -n "$__d" ]]; then
        read -r -p "    ${__t} [${__d}]: " __e || true
        __e="${__e:-$__d}"
    else
        while [[ -z "$__e" ]]; do read -r -p "    ${__t}: " __e || true; done
    fi
    printf -v "$__v" '%s' "$__e"
}

geheim() {
    local __v="$1" __t="$2" __e=""
    while [[ -z "$__e" ]]; do read -r -s -p "    ${__t}: " __e || true; echo; done
    printf -v "$__v" '%s' "$__e"
}

# Kein "tr ... | head -c" - head schliesst die Pipe, tr faengt SIGPIPE und
# unter "set -o pipefail" bricht das ganze Skript wortlos ab.
ja() {
    local __a=""
    read -r -p "    $1 (j/n) [${2:-j}]: " __a || true
    [[ "${__a:-${2:-j}}" =~ ^[jJ] ]]
}

zufall() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32; }

# Wie frage, aber eine leere Antwort ist erlaubt.
frage_leer() {
    local __v="$1" __t="$2" __e=""
    read -r -p "    ${__t}: " __e || true
    printf -v "$__v" '%s' "$__e"
}

# Ergaenzt fehlendes Schema und fehlenden Port, damit "172.19.1.21" reicht.
modelladresse() {
    local __v="$1" __e="${!1}"
    if [[ -n "$__e" ]]; then
        [[ "$__e" =~ ^https?:// ]] || __e="http://${__e}"
        [[ "${__e#*://}" == *:* ]] || __e="${__e}:11434"
        printf -v "$__v" '%s' "${__e%/}"
    fi
    return 0
}





docker_pruefen

step "Konfiguration"
info "Mehrere Knoten auf demselben Host sind moeglich - sie brauchen nur"
info "verschiedene Nummern. Container und Port richten sich danach."
frage INSTANZ "Nummer dieses Knotens" "1"
case "$INSTANZ" in
    ''|*[!0-9]*) fail "Die Nummer muss eine Zahl sein." ;;
esac
NAME="hoerjetzt-lavalink-${INSTANZ}"
info "Container: ${NAME}"

if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
    warn "Ein Container mit diesem Namen laeuft bereits und wird ersetzt."
fi

echo
info "Die Stufe entscheidet, welche Server auf diesem Knoten landen."
info "Premium-Knoten gehoeren auf staerkere Hardware und bleiben bewusst leer."
frage LAVALINK_TIER "Stufe (free oder premium)" "free"
case "$LAVALINK_TIER" in
    free|premium) ;;
    *) fail "Stufe muss free oder premium sein." ;;
esac

echo
info "Klangqualitaet: hoch, mittel oder sparsam."
info "Der Unterschied ist vor allem CPU-Last - die Bitrate des Discord-Kanals"
info "deckelt den Klang ohnehin."
frage LAVALINK_QUALITAET "Qualitaet" "$([[ "$LAVALINK_TIER" == "premium" ]] && echo hoch || echo mittel)"
case "$LAVALINK_QUALITAET" in
    hoch|mittel|sparsam) ;;
    *) fail "Qualitaet muss hoch, mittel oder sparsam sein." ;;
esac

# ---------------------------------------------------------------- YouTube
#
# Bisher fehlte das hier ganz: ein eigenstaendiger Knoten lief immer ohne
# Anmeldung und ohne Cipher-Dienst - und damit mit genau den Luecken, die im
# Hauptstack laengst geschlossen sind.

YOUTUBE_OAUTH=false
YOUTUBE_REFRESH_TOKEN=""
YT_CIPHER_URL=""
YT_CIPHER_PASSWORD=""
CIPHER_EIGEN=0

step "YouTube"
info "Ohne Anmeldung liefert YouTube altersbeschraenkte Titel nicht aus"
info "(Rammstein und aehnliche Kataloge). Mit Anmeldung schon."
info "Dafuer ein Wegwerf-Konto nehmen - nicht das eigene."
if ja "Bei YouTube anmelden?" n; then
    YOUTUBE_OAUTH=true
    echo
    info "Laeuft anderswo schon ein angemeldeter Knoten, kann dessen"
    info "Refresh-Token hier wiederverwendet werden - dann entfaellt die"
    info "Bestaetigung im Browser."
    frage_leer YOUTUBE_REFRESH_TOKEN "Refresh-Token (leer = neu anmelden)"
    if [[ -z "$YOUTUBE_REFRESH_TOKEN" ]]; then
        info "Nach dem Start steht ein Geraetecode im Log:"
        info "    docker logs -f ${NAME}"
    fi
fi

echo
info "YouTube verschluesselt die Stream-Adresse mit einer Funktion aus seinem"
info "Player-Skript. Aendert YouTube das Skript, scheitert das Plugin daran"
info "('must find sig function') - und dann spielt gar nichts mehr, auch"
info "unbeschraenkte Titel nicht. Ein Cipher-Dienst macht diesen Schritt"
info "ausserhalb und wird viel schneller nachgezogen als das Plugin."
echo
info "  1) eigenen Cipher-Dienst hier mitstarten  (empfohlen)"
info "  2) vorhandenen Dienst mitbenutzen"
info "  3) keinen"
frage CIPHER_WAHL "Auswahl" "1"
case "$CIPHER_WAHL" in
    1)
        CIPHER_EIGEN=1
        YT_CIPHER_PASSWORD="$(zufall)"
        ;;
    2)
        frage YT_CIPHER_URL "Adresse (z.B. http://10.0.0.5:8001)"
        frage_leer YT_CIPHER_PASSWORD "Passwort des Dienstes (leer = keins)"
        ;;
    3)
        warn "Ohne Cipher-Dienst haengt alles am Alter des Plugins."
        ;;
    *) fail "Bitte 1, 2 oder 3." ;;
esac

echo

# Bevorzugt an die private Adresse binden, wenn es eine gibt. Dann ist der
# Knoten fuer die anderen Maschinen erreichbar, aber nicht aus dem Internet -
# und das Passwort muss nicht der einzige Schutz sein.
PRIVAT_IP="$(ip -4 -o addr show 2>/dev/null | awk '{print $4}' | cut -d/ -f1 \
    | grep -E '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.)' | head -n1 || true)"

LAVALINK_PORT=2333
frage LAVALINK_PORT "Port" "$((2332 + INSTANZ))"
if [[ -n "$PRIVAT_IP" ]]; then
    info "Private Adresse gefunden: ${PRIVAT_IP}"
    frage LAVALINK_BIND "Auf welcher Adresse lauschen" "$PRIVAT_IP"
else
    warn "Keine private Adresse - der Knoten muss ueber das offene Netz erreichbar sein."
    warn "Dann unbedingt per Firewall auf die Adresse des Bots begrenzen."
    frage LAVALINK_BIND "Auf welcher Adresse lauschen" "0.0.0.0"
fi

read -r -p "    Passwort automatisch erzeugen? (j/n) [j]: " AUTO || true
if [[ "${AUTO:-j}" =~ ^[jJ] ]]; then
    HJ_LAVALINK_PASSWORD="$(zufall)"
else
    geheim HJ_LAVALINK_PASSWORD "Passwort"
fi

# ---------------------------------------------------------------- Anbindung
#
# Bisher endete die Installation mit einem Zettel: Name, Adresse und Passwort
# von Hand in den Adminbereich uebertragen. Wer das vergass, hatte einen
# laufenden Knoten, den der Bot nicht kannte - ohne jede Fehlermeldung, denn
# aus dessen Sicht existierte er nicht.

step "Anbindung an den Bot"
info "Traegt sich der Knoten selbst ein, entfaellt der Eintrag im Adminbereich."
info "Dafuer braucht es die Adresse des Bots und das gemeinsame Geheimnis"
info "HJ_NODE_TOKEN aus dessen .env."
HJ_CORE_URL=""
HJ_NODE_TOKEN=""
if ja "Selbst beim Bot anmelden?"; then
    frage HJ_CORE_URL "Adresse des Bots (z.B. https://hoer.jetzt)"
    geheim HJ_NODE_TOKEN "HJ_NODE_TOKEN"
    HJ_CORE_URL="${HJ_CORE_URL%/}"
else
    warn "Dann bleibt der Eintrag im Adminbereich von Hand noetig."
fi

HJ_AGENT_TOKEN="$(zufall)"
frage HJ_AGENT_PORT "Port des Knoten-Agenten" "$((8098 + INSTANZ))"

step "Abbild bauen"
docker build -t "${NAME}:latest" "$HIER" || fail "Build fehlgeschlagen."

# Lavalink laedt das YouTube-Plugin beim ersten Start selbst herunter. Kann der
# Container keine Namen aufloesen, scheitert das - und zwar mit einer Meldung
# ueber Spring-Beans, die den Grund gut versteckt. Deshalb vorher nachsehen.
step "Namensaufloesung im Container"
if docker run --rm --entrypoint sh "${NAME}:latest" -c \
        'getent hosts maven.lavalink.dev >/dev/null' >/dev/null 2>&1; then
    info "maven.lavalink.dev ist erreichbar."
else
    warn "Container koennen maven.lavalink.dev nicht aufloesen."
    warn "Meist zeigt /etc/resolv.conf des Hosts auf 127.0.0.53 (systemd-resolved),"
    warn "das im Container ins Leere laeuft."
    if ja "Docker feste Namensserver eintragen (1.1.1.1, 8.8.8.8)?"; then
        mkdir -p /etc/docker
        if [[ -s /etc/docker/daemon.json ]]; then
            python3 - <<'PYTHON'
import json, pathlib
pfad = pathlib.Path("/etc/docker/daemon.json")
daten = json.loads(pfad.read_text() or "{}")
daten.setdefault("dns", ["1.1.1.1", "8.8.8.8"])
pfad.write_text(json.dumps(daten, indent=2))
PYTHON
        else
            printf '{\n  "dns": ["1.1.1.1", "8.8.8.8"]\n}\n' > /etc/docker/daemon.json
        fi
        systemctl restart docker
        sleep 3
        info "Docker neu gestartet."
    else
        warn "Ohne Namensaufloesung startet Lavalink nicht."
    fi
fi


# Der Sidecar gehoert zur Instanz - sonst uebernaehme der zweite Knoten den
# Namensraum des ersten und beide lauschten auf demselben Port.

NETZ=(-p "${LAVALINK_BIND}:${LAVALINK_PORT}:2333")

CIPHER_NAME="hoerjetzt-cipher-${INSTANZ}"
DOCKER_NETZ="hoerjetzt-knoten-${INSTANZ}"

if [[ "$CIPHER_EIGEN" -eq 1 ]]; then
    step "Cipher-Dienst"
    docker rm -f "$CIPHER_NAME" >/dev/null 2>&1 || true
    # Ein eigenes Docker-Netz, damit Lavalink den Dienst am Namen findet.
    # Bewusst ohne -p: der Dienst gehoert nicht ins offene Netz.
    docker network create "$DOCKER_NETZ" >/dev/null 2>&1 || true
    CIPHER_NETZ=(--network "$DOCKER_NETZ")
    NETZ+=(--network "$DOCKER_NETZ")
    YT_CIPHER_URL="http://${CIPHER_NAME}:8001"
    docker run -d --name "$CIPHER_NAME" --restart unless-stopped \
        "${CIPHER_NETZ[@]}" \
        -e PORT=8001 \
        -e API_TOKEN="$YT_CIPHER_PASSWORD" \
        -e OVERRIDE_PLAYER_VARIANT=IAS \
        ghcr.io/kikkia/yt-cipher:master >/dev/null || fail "Cipher-Dienst startet nicht."
    info "Laeuft als ${CIPHER_NAME}, erreichbar unter ${YT_CIPHER_URL}"
fi

step "Container starten"

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --restart unless-stopped \
    "${NETZ[@]}" \
    -e LAVALINK_SERVER_PASSWORD="$HJ_LAVALINK_PASSWORD" \
    -e LAVALINK_TIER="$LAVALINK_TIER" \
    -e LAVALINK_QUALITAET="$LAVALINK_QUALITAET" \
    -e LAVALINK_PORT=2333 \
    -e YOUTUBE_OAUTH="$YOUTUBE_OAUTH" \
    -e YOUTUBE_REFRESH_TOKEN="$YOUTUBE_REFRESH_TOKEN" \
    -e YT_CIPHER_URL="$YT_CIPHER_URL" \
    -e YT_CIPHER_PASSWORD="$YT_CIPHER_PASSWORD" \
    -e YT_CIPHER_USERAGENT="hoerjetzt-${INSTANZ}" \
    "${NAME}:latest" || fail "Start fehlgeschlagen."

sleep 8
if docker exec "$NAME" curl -fsS -H "Authorization: ${HJ_LAVALINK_PASSWORD}" \
        http://127.0.0.1:2333/version >/dev/null 2>&1; then
    info "Knoten antwortet."
else
    warn "Knoten antwortet noch nicht - beim ersten Start werden Plugins geladen."
fi

step "Knoten-Agent"
info "Der Agent erlaubt Neustart und Aktualisierung dieses Knotens aus dem"
info "Webinterface heraus - und meldet ihn beim Bot an."

HJ_NODE_CONTAINER="$NAME" \
HJ_NODE_NAME="${LAVALINK_TIER}-${INSTANZ}" \
HJ_NODE_ADDRESS="http://${LAVALINK_BIND}:${LAVALINK_PORT}" \
HJ_LAVALINK_PASSWORD="$HJ_LAVALINK_PASSWORD" \
HJ_NODE_TIER="$LAVALINK_TIER" \
HJ_AGENT_TOKEN="$HJ_AGENT_TOKEN" \
HJ_AGENT_PORT="$HJ_AGENT_PORT" \
HJ_CORE_URL="$HJ_CORE_URL" \
HJ_NODE_TOKEN="$HJ_NODE_TOKEN" \
    bash "${HIER}/agent/einrichten.sh" || warn "Agent konnte nicht eingerichtet werden - der Knoten laeuft trotzdem."

step "Fertig"
echo
if [[ -n "$HJ_CORE_URL" ]]; then
    info "Der Knoten meldet sich selbst an und taucht im Adminbereich von allein auf."
    info "    Name:     ${LAVALINK_TIER}-${INSTANZ}"
    info "    Adresse:  http://${LAVALINK_BIND}:${LAVALINK_PORT}"
    info "    Stufe:    ${LAVALINK_TIER}"
    echo
    info "Dauert es laenger als eine Minute:"
    info "    journalctl -u hoerjetzt-agent -n 40"
else
    info "Im Adminbereich unter Lavalink eintragen:"
    info "    Name:     ${LAVALINK_TIER}-${INSTANZ}"
    info "    Adresse:  http://${LAVALINK_BIND}:${LAVALINK_PORT}"
    info "    Passwort: ${HJ_LAVALINK_PASSWORD}"
    info "    Stufe:    ${LAVALINK_TIER}"
    echo
    warn "Erst dieser Eintrag entscheidet, welche Server auf dem Knoten landen."
fi
echo
if [[ "$YOUTUBE_OAUTH" == "true" && -z "$YOUTUBE_REFRESH_TOKEN" ]]; then
    warn "YouTube-Anmeldung offen: im Log steht gleich ein Geraetecode."
    warn "Nach der Bestaetigung erscheint dort der refreshToken. Den sichern -"
    warn "sonst faengt das bei jedem Neustart von vorne an:"
    info "    docker logs -f ${NAME} | grep -iE 'oauth|refresh'"
    info "Danach eintragen und neu starten:"
    info "    docker rm -f ${NAME} && bash install.sh"
    echo
fi
if [[ "$CIPHER_EIGEN" -eq 1 ]]; then
    info "Cipher-Dienst: ${CIPHER_NAME} (${YT_CIPHER_URL})"
    info "Passwort:      ${YT_CIPHER_PASSWORD}"
    info "Weitere Knoten koennen ihn mitbenutzen - Auswahl 2 bei der Frage."
    echo
fi
info "Logs: docker logs -f ${NAME}"
echo
