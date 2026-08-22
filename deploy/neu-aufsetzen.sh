#!/usr/bin/env bash
#
# hoer.jetzt - einen Knoten vollstaendig zuruecksetzen.
#
#   bash neu-aufsetzen.sh              alles weg, mit Rueckfrage
#   bash neu-aufsetzen.sh --pruefen    nur zeigen, was wegkaeme
#   bash neu-aufsetzen.sh --ja         ohne Rueckfrage
#   bash neu-aufsetzen.sh --abbilder   zusaetzlich die geladenen Abbilder
#   bash neu-aufsetzen.sh --ohne-sicherung   keine letzte Sicherung anlegen
#
# Danach ist die Maschine so weit zurueck, wie es ohne Neuinstallation geht.
# Aufsetzen wieder mit dem Einzeiler aus der Oberflaeche.
#
# ---------------------------------------------------------------------------
# Was hier schiefgehen kann, wenn man es von Hand macht
#
# 1. Das Spock-Volume bleibt stehen.
#    "docker compose down --volumes" entfernt nur, was in den MITGEGEBENEN
#    Dateien deklariert ist. Ohne das Spock-Overlay ueberlebt pgdaten-spock -
#    und der frisch aufgesetzte Knoten findet die alten Daten wieder vor,
#    obwohl man ihn geloescht zu haben glaubte. Deshalb werden die Volumes
#    hier zusaetzlich beim Namen entfernt.
#
# 2. Das Skript loescht sich selbst.
#    Es liegt in ${ARBEIT}/main/deploy und soll ${ARBEIT} entfernen. bash
#    liest ein Skript waehrend der Ausfuehrung haeppchenweise nach; ist die
#    Datei mittendrin weg, fuehrt es Bruchstuecke aus. Also erst in eine
#    Kopie ausserhalb umziehen.
#
# 3. Die Zeitgeber bleiben.
#    hj-agent.timer laeuft danach jede Minute weiter und protokolliert, dass
#    es ${ARBEIT} nicht findet. Sieht aus wie ein Fehler, ist ein Rest.
#
# ---------------------------------------------------------------------------
# Was NICHT passiert
#
# Der Knoten meldet sich beim Update-Server nicht ab - dafuer gibt es keinen
# Weg von hier aus. Sein Eintrag bleibt unter "Verwalten" stehen und muss dort
# entfernt werden, sonst zeigt die Liste eine Maschine, die es nicht mehr gibt.
# ---------------------------------------------------------------------------

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
PROJEKT="${PROJEKT:-hoerjetzt}"

# ------------------------------------------------------------ Selbst umziehen
#
# Vor allem anderen, und vor der Argumentauswertung: nach dem exec faengt das
# Skript von vorn an, und "$@" muss dann noch vollstaendig sein.
if [[ -z "${HJ_UMGEZOGEN:-}" ]]; then
    EIGEN="$(readlink -f "${BASH_SOURCE[0]:-}" 2>/dev/null || true)"
    if [[ -n "$EIGEN" && "$EIGEN" == "${ARBEIT}/"* ]]; then
        KOPIE="$(mktemp)"
        cp "$EIGEN" "$KOPIE"
        export HJ_UMGEZOGEN=1
        exec bash "$KOPIE" "$@"
    fi
fi

NUR_PRUEFEN=false
OHNE_RUECKFRAGE=false
ABBILDER=false
SICHERN=true

for arg in "$@"; do
    case "$arg" in
        --pruefen)        NUR_PRUEFEN=true ;;
        --ja)             OHNE_RUECKFRAGE=true ;;
        --abbilder)       ABBILDER=true ;;
        --ohne-sicherung) SICHERN=false ;;
        -h|--help)        sed -n '2,13p' "$0"; exit 0 ;;
        *)                printf 'Unbekannte Angabe: %s\n' "$arg" >&2; exit 1 ;;
    esac
done

[[ "$(id -u)" -eq 0 ]] || { echo "Bitte als root starten." >&2; exit 1; }

sagen() { printf '  %s\n' "$*"; }
kopf()  { printf '\n== %s\n' "$*"; }

wert() { grep "^$1=" "${ARBEIT}/.env" 2>/dev/null | cut -d= -f2- | head -n1 || true; }

KENNUNG="$(wert HJ_KNOTEN_KENNUNG)"
UPDATE_HOST="$(wert HJ_UPDATE_HOST)"
DB_USER="$(wert HJ_DB_USER)"; DB_USER="${DB_USER:-discordbot}"
DB_NAME="$(wert HJ_DB_NAME)"; DB_NAME="${DB_NAME:-discordbot}"

# ------------------------------------------------------------ Bestandsaufnahme

kopf "Was weggeworfen wird"

sagen "Knoten:     ${KENNUNG:-(keine Kennung in der .env)}"
sagen "Verzeichnis ${ARBEIT}"

CONTAINER="$(docker ps -aq --filter "label=com.docker.compose.project=${PROJEKT}" 2>/dev/null | wc -l || echo 0)"
sagen "Container:  ${CONTAINER}"

VOLUMES="$(docker volume ls -q 2>/dev/null | grep -E "^${PROJEKT}_" || true)"
if [[ -n "$VOLUMES" ]]; then
    printf '  Volumes:\n'
    while read -r v; do
        [[ -n "$v" ]] || continue
        groesse="$(docker run --rm -v "${v}:/v" alpine:3 du -sh /v </dev/null 2>/dev/null | cut -f1 || echo "?")"
        printf '    %-32s %s\n' "$v" "$groesse"
    done <<< "$VOLUMES"
else
    sagen "Volumes:    keine"
fi

ZEITGEBER="$(ls /etc/systemd/system/hj-*.timer /etc/systemd/system/hoerjetzt-*.timer \
             2>/dev/null || true)"
# grep -c liefert bei null Treffern die 0 UND einen Fehlerstatus. Ein
# "|| echo 0" dahinter druckt die Null deshalb ein zweites Mal.
ANZAHL_ZEITGEBER="$(printf '%s\n' "$ZEITGEBER" | grep -c . || true)"
sagen "Zeitgeber:  ${ANZAHL_ZEITGEBER:-0}"

if $ABBILDER; then
    sagen "Abbilder:   auch die geladenen Abbilder von ${UPDATE_HOST:-der Registry}"
fi

printf '\n  Danach von Hand noetig:\n\n'
printf '    Knoten unter "Verwalten" entfernen und neu anlegen\n'
printf '    Aufsetz-Einzeiler von dort ausfuehren\n\n'

if $NUR_PRUEFEN; then
    sagen "Nur nachgesehen - nichts angefasst."
    exit 0
fi

# ---------------------------------------------------------- Letzte Sicherung
#
# Vor der Rueckfrage, nicht danach: wer hier "loeschen" tippt, hat die
# Sicherung dann schon. Sie landet in /root und nicht in ${ARBEIT} - das
# Verzeichnis ist gleich weg.
if $SICHERN && [[ "$CONTAINER" -gt 0 ]]; then
    kopf "Letzte Sicherung"
    ZIEL="/root/${KENNUNG:-knoten}-vor-loeschen-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
    if docker exec "$(docker ps -q --filter "label=com.docker.compose.project=${PROJEKT}" \
                                  --filter "label=com.docker.compose.service=postgres" \
                      | head -1)" \
            pg_dump -U "$DB_USER" -d "$DB_NAME" --no-owner --no-privileges 2>/dev/null \
            | gzip -9 > "${ZIEL}.teil" && [[ -s "${ZIEL}.teil" ]]; then
        mv "${ZIEL}.teil" "$ZIEL"
        chmod 600 "$ZIEL"
        sagen "$(printf '%s (%s)' "$ZIEL" "$(du -h "$ZIEL" | cut -f1)")"
    else
        rm -f "${ZIEL}.teil"
        sagen "Keine Sicherung moeglich - laeuft hier ueberhaupt eine Datenbank?"
        sagen "Mit --ohne-sicherung ueberspringt man diesen Schritt."
        if ! $OHNE_RUECKFRAGE; then
            printf '  Trotzdem weiter? "ja" eintippen: '
            read -r a
            [[ "$a" == "ja" ]] || { echo "  Abgebrochen - nichts angefasst."; exit 1; }
        fi
    fi
fi

# ------------------------------------------------------------------ Rueckfrage

if ! $OHNE_RUECKFRAGE; then
    printf '  Zum Fortfahren "loeschen" eintippen: '
    read -r antwort
    [[ "$antwort" == "loeschen" ]] || { echo "  Abgebrochen - nichts angefasst."; exit 1; }
fi

# ---------------------------------------------------------------- Zeitgeber

kopf "Zeitgeber anhalten"
for t in hj-agent hj-sicherung hoerjetzt-update; do
    systemctl disable --now "${t}.timer" >/dev/null 2>&1 || true
    rm -f "/etc/systemd/system/${t}.timer" "/etc/systemd/system/${t}.service"
done
systemctl daemon-reload >/dev/null 2>&1 || true
sagen "angehalten und entfernt"

# --------------------------------------------------------- Container, Volumes

kopf "Container und Volumes"

# Erst der saubere Weg. Er scheitert, wenn die .env unvollstaendig ist -
# deshalb danach der direkte.
if [[ -f "${ARBEIT}/main/deploy/docker/docker-compose.yml" ]]; then
    (
        set +u
        # shellcheck disable=SC1091
        [[ -f "${ARBEIT}/.env" ]] && { set -a; . "${ARBEIT}/.env"; set +a; }
        cd "${ARBEIT}/main/deploy/docker"
        dateien=(-f docker-compose.yml)
        [[ -f docker-compose.spock.yml ]] && dateien+=(-f docker-compose.spock.yml)
        docker compose "${dateien[@]}" down --volumes --remove-orphans
    ) >/dev/null 2>&1 || true
fi

# Und jetzt beim Namen - unabhaengig davon, ob oben etwas geklappt hat und
# ob die Overlays mitgegeben wurden. pgdaten-spock ueberlebt sonst.
docker ps -aq --filter "label=com.docker.compose.project=${PROJEKT}" 2>/dev/null \
    | xargs -r docker rm -f >/dev/null 2>&1 || true

weg=0
while read -r v; do
    [[ -n "$v" ]] || continue
    docker volume rm -f "$v" </dev/null >/dev/null 2>&1 && weg=$((weg + 1)) || true
done <<< "$(docker volume ls -q 2>/dev/null | grep -E "^${PROJEKT}_" || true)"
sagen "${weg} Volumes entfernt"

docker network rm "${PROJEKT}_hoerjetzt" >/dev/null 2>&1 || true

# ------------------------------------------------------------------ Abbilder

if $ABBILDER && [[ -n "$UPDATE_HOST" ]]; then
    kopf "Abbilder"
    docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null \
        | grep "^${UPDATE_HOST}/" \
        | xargs -r docker rmi -f >/dev/null 2>&1 || true
    sagen "entfernt"
fi

# --------------------------------------------------------- Anmeldung und Reste

kopf "Anmeldung und Reste"
[[ -n "$UPDATE_HOST" ]] && docker logout "$UPDATE_HOST" >/dev/null 2>&1 || true
rm -f /var/log/hoerjetzt-update.log /var/lock/hoerjetzt-update.lock
rm -rf "$ARBEIT"
sagen "${ARBEIT}, Protokoll und Registry-Anmeldung weg"

# ---------------------------------------------------------------------- Fertig

kopf "Fertig"
sagen "Die Maschine ist zurueckgesetzt."
if $SICHERN; then
    sagen "Die letzte Sicherung liegt in /root - die ueberlebt das Aufsetzen."
fi
sagen ""
sagen "Neu aufsetzen: Knoten unter \"Verwalten\" entfernen, neu anlegen,"
sagen "und den Einzeiler von dort ausfuehren."
