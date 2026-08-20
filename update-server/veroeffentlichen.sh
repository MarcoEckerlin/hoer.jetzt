            -t "${REGISTRY_PUSH}/${teil}:${VERSION}" \
            -t "${REGISTRY_PUSH}/${teil}:latest" \
# hoer.jetzt - ein Release bauen und ausliefern.
#
#   bash veroeffentlichen.sh                 Version aus RELEASE nehmen
#   bash veroeffentlichen.sh 2026.08.19.01   Version vorgeben
#   bash veroeffentlichen.sh --nur-manifest  nichts bauen, nur umschalten
#
# Laeuft an zwei Stellen mit demselben Code: von Hand auf dem Update-Server,
# oder im Runner, wenn ein Tag gesetzt wurde. Erwartet die Zweige nebeneinander
# unter QUELLEN - so, wie install.sh sie auch auf einem Host ablegt:
#
#   ${QUELLEN}/main        RELEASE, deploy/docker/*.yml
#   ${QUELLEN}/core        Dockerfile
#   ${QUELLEN}/ai-radio    Dockerfile
#   ${QUELLEN}/lavalink    Dockerfile
#   ${QUELLEN}/web         Dockerfile
#
# Reihenfolge ist Absicht: erst alle Abbilder bauen und hochladen, ganz zum
# Schluss das Manifest umschreiben. Bricht ein Build ab, zeigt das Manifest
# weiter auf das vorige Release und kein Knoten merkt etwas davon. Ein
# Manifest, das auf ein Abbild zeigt, das es nicht gibt, waere dagegen ein
# Ausfall auf allen Hosts gleichzeitig - jede Nacht um drei aufs Neue.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "${HIER}/lib.sh"

QUELLEN="${QUELLEN:-$(cd "${HIER}/../.." && pwd)}"
KOMPONENTEN=(core ai-radio lavalink web)

NUR_MANIFEST=0
VERSION=""
for argument in "$@"; do
    case "$argument" in
        --nur-manifest) NUR_MANIFEST=1 ;;
        -*) fail "Unbekannt: ${argument}" ;;
        *)  VERSION="$argument" ;;
    esac
done

command -v docker >/dev/null 2>&1 || fail "Docker fehlt."

# ------------------------------------------------------------------ 1  Lage

HJ_UPDATE_HOST="${HJ_UPDATE_HOST:-}"
if [[ -z "$HJ_UPDATE_HOST" && -f "${HIER}/.env" ]]; then
    HJ_UPDATE_HOST="$(grep '^HJ_UPDATE_HOST=' "${HIER}/.env" | cut -d= -f2- || true)"
fi
[[ -n "$HJ_UPDATE_HOST" ]] || fail "HJ_UPDATE_HOST ist nicht gesetzt und steht in keiner .env."
# Zwei Namen fuer dieselbe Registry, und sie fallen auseinander:
#
#   REGISTRY_PUSH   Der Weg von diesem Host aus - direkt auf den internen
#                   Port, ohne Umweg ueber den Nginx Proxy Manager. Der
#                   Umweg ginge zum Router hinaus und wieder herein, und
#                   NAT-Hairpin koennen viele Anschluesse nicht.
#
#   REGISTRY        Der Name, der ins Manifest kommt. Den benutzen die
#                   Knoten, und die kommen von aussen - also durch den NPM.
#
# Frueher war beides derselbe Name, weil ein /etc/hosts-Eintrag ihn auf
# diesem Host nach innen bog. Das geht nicht mehr: der Port gehoert zum
# Namen, und /etc/hosts kennt keine Ports.
HJ_PORT_INTERN="${HJ_PORT_INTERN:-}"
if [[ -z "$HJ_PORT_INTERN" && -f "${HIER}/.env" ]]; then
    HJ_PORT_INTERN="$(grep '^HJ_PORT_INTERN=' "${HIER}/.env" | cut -d= -f2- || true)"
fi
HJ_PORT_INTERN="${HJ_PORT_INTERN:-8091}"

REGISTRY="${HJ_UPDATE_HOST}/hoerjetzt"
REGISTRY_PUSH="127.0.0.1:${HJ_PORT_INTERN}/hoerjetzt"

MANIFEST_QUELLE="${QUELLEN}/main/RELEASE"
[[ -f "$MANIFEST_QUELLE" ]] || fail "${MANIFEST_QUELLE} fehlt - stimmt QUELLEN?"

if [[ -z "$VERSION" ]]; then
    VERSION="$(grep '^version=' "$MANIFEST_QUELLE" | cut -d= -f2- || true)"
fi
[[ -n "$VERSION" ]] || fail "Keine Version - weder als Argument noch in RELEASE."

step "Release ${VERSION}"
info "$(printf '%-12s %s' "Registry" "$REGISTRY")"
info "$(printf '%-12s %s' "Hochladen" "$REGISTRY_PUSH")"
info "$(printf '%-12s %s' "Quellen" "$QUELLEN")"

# ------------------------------------------------------------------ 2  Bauen

if [[ "$NUR_MANIFEST" -eq 0 ]]; then
    for teil in "${KOMPONENTEN[@]}"; do
        [[ -f "${QUELLEN}/${teil}/Dockerfile" ]] \
            || fail "${QUELLEN}/${teil}/Dockerfile fehlt."
    done

    for teil in "${KOMPONENTEN[@]}"; do
        step "Bauen: ${teil}"
        # Zwei Marken auf demselben Abbild. Die Version ist die, auf die das
        # Manifest zeigt und die einen Rueckweg offen haelt; "latest" ist nur
        # fuer den Menschen an der Kommandozeile.
        docker build \
            -t "${REGISTRY_PUSH}/${teil}:${VERSION}" \
            -t "${REGISTRY_PUSH}/${teil}:latest" \
            "${QUELLEN}/${teil}" \
            || fail "${teil} liess sich nicht bauen."

        step "Hochladen: ${teil}"
        docker push "${REGISTRY_PUSH}/${teil}:${VERSION}" || fail "${teil} liess sich nicht hochladen."
        docker push "${REGISTRY_PUSH}/${teil}:latest"     || fail "${teil}: latest fehlgeschlagen."
        info "${REGISTRY}/${teil}:${VERSION}"
    done
else
    info "Uebersprungen: bauen und hochladen."
fi

# ------------------------------------------------------------------ 3  Pakete
#
# Was ein Knoten braucht, um sich aufzusetzen: die Compose-Dateien und die
# Skripte. Kein Quellcode - der bleibt hier.

step "Knotenpakete schnueren"
PACK="$(mktemp -d)"
trap 'rm -rf "$PACK"' EXIT

mkdir -p "${PACK}/voll/docker" "${PACK}/lavalink/docker"

# Vollstaendiger Stack: alle Compose-Dateien und das Update-Werkzeug.
cp "${QUELLEN}/main/deploy/docker/"*.yml            "${PACK}/voll/docker/"
cp "${QUELLEN}/main/deploy/auto-update.sh"          "${PACK}/voll/"
cp -r "${QUELLEN}/main/deploy/systemd"              "${PACK}/voll/"

# Audio-Knoten: nur was einen Lavalink startet. Bewusst ohne
# docker-compose.yml - darin stehen Bot-Token und Datenbank, und ein
# Audio-Knoten hat mit beidem nichts zu tun.
cp "${QUELLEN}/main/deploy/docker/docker-compose.lavalink.yml" "${PACK}/lavalink/docker/"
cp "${QUELLEN}/main/deploy/docker/docker-compose.nodes.yml"    "${PACK}/lavalink/docker/" 2>/dev/null || true
cp "${QUELLEN}/main/deploy/auto-update.sh"                     "${PACK}/lavalink/"
cp -r "${QUELLEN}/main/deploy/systemd"                         "${PACK}/lavalink/"

for profil in voll lavalink; do
    tar -C "${PACK}/${profil}" -czf "${PACK}/${profil}.tar.gz" .
    aus_schreiben "knoten/${profil}.tar.gz" < "${PACK}/${profil}.tar.gz" \
        || fail "Paket ${profil} liess sich nicht ablegen."
    info "$(printf '%-10s %s Bytes' "$profil" "$(stat -c%s "${PACK}/${profil}.tar.gz")")"
done

# Das Installationsskript liegt einzeln daneben, nicht im Paket: es wird
# geholt, bevor es ein Paket gibt - das ist ja gerade seine Aufgabe.
aus_schreiben "knoten/aufsetzen.sh" < "${QUELLEN}/main/deploy/knoten-aufsetzen.sh" \
    || fail "aufsetzen.sh liess sich nicht ablegen."
info "aufsetzen.sh"

# ------------------------------------------------------------------ 4  Manifest
#
# Erst jetzt. Ab diesem Augenblick holen sich die Knoten das neue Release.

step "Manifest umschalten"
{
    printf '# hoer.jetzt - welches Release gerade gilt.\n'
    printf '# Geschrieben von veroeffentlichen.sh am %s.\n' "$(date '+%Y-%m-%d %H:%M:%S')"
    printf '#\n'
    printf '# Zeilen bitte nicht umsortieren - auto-update.sh liest sie mit grep.\n'
    printf 'version=%s\n' "$VERSION"
    printf 'registry=%s\n' "$REGISTRY"
    for teil in "${KOMPONENTEN[@]}"; do
        printf '%s=%s\n' "$teil" "$VERSION"
    done
} | aus_schreiben "release/aktuell" || fail "Manifest liess sich nicht schreiben."

info "release/aktuell zeigt auf ${VERSION}"

# ------------------------------------------------------------------ 5  Probe

step "Gegenprobe"
GELESEN="$(aus_lesen release/aktuell | grep '^version=' | cut -d= -f2- || true)"
[[ "$GELESEN" == "$VERSION" ]] || fail "Das Manifest liest sich als '${GELESEN}' zurueck."
info "Manifest liest sich sauber zurueck."

for profil in voll lavalink; do
    aus_gibt_es "knoten/${profil}.tar.gz" || fail "knoten/${profil}.tar.gz fehlt."
done
info "Beide Knotenpakete liegen bereit."

step "Fertig"
info "Die Knoten ziehen es in der naechsten Nacht - oder sofort mit:"
info "    bash /opt/hoerjetzt/main/deploy/auto-update.sh --jetzt"
echo
