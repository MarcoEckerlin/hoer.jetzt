#!/usr/bin/env bash
#
# hoer.jetzt - Agent.
#
# Laeuft auf jeder Node, alle 60 Sekunden per systemd-Timer. Er meldet sich
# beim Controller, bekommt zurueck welche Shards und welches Release diese Node
# fahren soll, und setzt beides um.
#
# ---------------------------------------------------------------------------
# Warum ein Skript und kein Dienst
#
# Der Agent muss "docker compose" auf dem Host ausfuehren. Ein Dienst im
# Container braeuchte dafuer den Docker-Socket - und wer den hat, ist auf dem
# Host root. Fuer eine Aufgabe, die einmal pro Minute drei Dateien anfasst, ist
# das ein absurder Tausch. Als Skript unter systemd laeuft er dort, wo er
# ohnehin hingehoert, und laesst sich mit journalctl lesen wie alles andere.
# ---------------------------------------------------------------------------

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
UMGEBUNG="${UMGEBUNG:-${ARBEIT}/.env}"

sagen() { printf '[agent] %s\n' "$*"; }
fehler() { printf '[agent] FEHLER: %s\n' "$*" >&2; exit 1; }

[[ -f "$UMGEBUNG" ]] || fehler "${UMGEBUNG} nicht gefunden."
# shellcheck disable=SC1090
set -a; source "$UMGEBUNG"; set +a

: "${HJ_CONTROLLER_URL:?HJ_CONTROLLER_URL setzen - Adresse der Steuer-Node}"
: "${HJ_CONTROLLER_TOKEN:?HJ_CONTROLLER_TOKEN setzen}"
KNOTEN="${HJ_NODE_NAME:-$(hostname -s)}"
NODE_NR="${HJ_NODE_NR:-1}"
PRIVAT_IP="${HJ_PRIVAT_IP:-}"

cd "${ARBEIT}/main/deploy/docker" 2>/dev/null || fehler "${ARBEIT}/main/deploy/docker fehlt."

# ------------------------------------------------------------------ Zustand

# Was laeuft gerade? Bewusst knapp - der Controller braucht kein Abbild der
# Maschine, sondern die Antwort auf "geht es dieser Node gut".
laufend="$(docker compose ps --status running --format '{{.Service}}' 2>/dev/null | paste -sd, - || echo "")"
version="$(grep '^version=' "${ARBEIT}/main/RELEASE" 2>/dev/null | cut -d= -f2 || echo unbekannt)"
last="$(cut -d' ' -f1-3 < /proc/loadavg)"
speicher="$(free -m | awk '/^Mem:/ {print $3 "/" $2 " MB"}')"
platte="$(df -h --output=pcent "${ARBEIT}" | tail -1 | tr -d ' %')"

zustand="$(printf '{"dienste":"%s","last":"%s","speicher":"%s","platte_prozent":%s}' \
    "$laufend" "$last" "$speicher" "${platte:-0}")"

# ------------------------------------------------------------------ Melden

antwort="$(curl -fsS --max-time 15 \
    -X POST "${HJ_CONTROLLER_URL%/}/api/verbund/anmelden" \
    -H "Authorization: Bearer ${HJ_CONTROLLER_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$(printf '{"nodeName":"%s","privatIp":"%s","nodeNr":%s,"releaseVersion":"%s","zustandJson":%s}' \
        "$KNOTEN" "$PRIVAT_IP" "$NODE_NR" "$version" "$zustand")" \
    2>/dev/null)" || {
    # Der Controller ist nicht erreichbar. Das ist kein Grund, irgendetwas zu
    # aendern: die Node laeuft weiter mit dem, was sie hat. Ein Agent, der bei
    # Funkstille anfaengt umzubauen, ist gefaehrlicher als gar keiner.
    sagen "Controller nicht erreichbar - keine Aenderung."
    exit 0
}

lies() {
    printf '%s' "$antwort" | python3 -c "
import json, sys
try:
    print(json.load(sys.stdin).get('$1', '') or '')
except Exception:
    print('')
"
}

soll_version="$(lies zielRelease)"
soll_gesamt="$(lies shardsGesamt)"
soll_von="$(lies shardsVon)"
soll_bis="$(lies shardsBis)"

sagen "Node ${KNOTEN}: Release ${version} -> ${soll_version:-unveraendert}, Shards ${soll_von:-?}-${soll_bis:-?} von ${soll_gesamt:-?}"

# ------------------------------------------------------------------ Umsetzen

geaendert=0

# Shard-Aufteilung in die .env schreiben. Nur wenn sie sich unterscheidet -
# sonst schriebe der Agent die Datei jede Minute neu.
setze() {
    local schluessel="$1" wert="$2"
    [[ -n "$wert" ]] || return 0
    if grep -q "^${schluessel}=" "$UMGEBUNG"; then
        local ist
        ist="$(grep "^${schluessel}=" "$UMGEBUNG" | head -1 | cut -d= -f2-)"
        [[ "$ist" == "$wert" ]] && return 0
        sed -i "s|^${schluessel}=.*|${schluessel}=${wert}|" "$UMGEBUNG"
    else
        printf '%s=%s\n' "$schluessel" "$wert" >> "$UMGEBUNG"
    fi
    sagen "${schluessel}=${wert}"
    geaendert=1
}

setze HJ_SHARDS_GESAMT "$soll_gesamt"
setze HJ_SHARD_VON "$soll_von"
setze HJ_SHARD_BIS "$soll_bis"

# Release nachziehen. Das macht auto-update.sh, das dabei auf Ruhe wartet -
# ein Shard mitten in laufender Wiedergabe neu zu starten reisst den Ton ab.
if [[ -n "$soll_version" && "$soll_version" != "$version" ]]; then
    sagen "Release ${version} -> ${soll_version}, uebergebe an auto-update.sh"
    bash "${ARBEIT}/main/deploy/auto-update.sh" || sagen "Update meldete einen Fehler - siehe Log."
    geaendert=0   # auto-update.sh startet selbst neu
elif [[ "$geaendert" -eq 1 ]]; then
    sagen "Shard-Aufteilung geaendert - core neu starten."
    cp "$UMGEBUNG" .env
    docker compose up -d core
fi

sagen "Fertig."
