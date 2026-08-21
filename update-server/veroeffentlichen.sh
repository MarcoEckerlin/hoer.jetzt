#!/usr/bin/env bash
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

# Nur ein Modul bauen - gesetzt von hoer-update.sh.
#
# Gebaut und geschoben wird dann allein dieses Abbild. Das Manifest bleibt
# trotzdem vollstaendig: die uebrigen Zeilen behalten ihren bisherigen Wert,
# sonst stuende ein Knoten mit anderen Modulen ploetzlich ohne Stand da und
# auto-update.sh braeche mit "Das Manifest nennt keinen Stand fuer ..." ab.
ALLE_KOMPONENTEN=("${KOMPONENTEN[@]}")
if [[ -n "${HJ_NUR_MODUL:-}" ]]; then
    KOMPONENTEN=("$HJ_NUR_MODUL")
fi

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

# ------------------------------------------------------ 1b  Quellen bereitlegen
#
# Die Komponenten liegen in eigenen Zweigen, nicht in main.
#
# Auf einem Server, der ueber install-update-server.sh aufgesetzt wurde, ist
# nur "main" ausgecheckt - der Rest fehlt, und "docker build" endet in
#
#   unable to prepare context: path "/opt/hoerjetzt/core" not found
#
# Die Forgejo-Pipeline holt sie sich selbst; von Hand aufgerufen tat das
# niemand. Also holt das Skript sie jetzt selbst, auf genau den Stand, den
# RELEASE nennt.
#
# Auf den Stand aus RELEASE und nicht auf die Zweigspitze: sonst baute ein
# Release Code mit, der noch gar nicht dazugehoert - und die Versionsnummer
# behauptete etwas, das nie so zusammen geprueft wurde.
quellen_bereitlegen() {
    local teil stand url
    url="$(git -C "${QUELLEN}/main" remote get-url origin 2>/dev/null || true)"
    [[ -n "$url" ]] || fail "${QUELLEN}/main ist keine Arbeitskopie - woher sollen die Zweige kommen?"

    for teil in "${ALLE_KOMPONENTEN[@]}"; do
        stand="$(grep "^${teil}=" "$MANIFEST_QUELLE" | cut -d= -f2- || true)"
        [[ -n "$stand" ]] || fail "RELEASE nennt keinen Stand fuer ${teil}."

        if [[ ! -d "${QUELLEN}/${teil}/.git" ]]; then
            info "${teil}: Zweig wird geholt"
            git clone -q --branch "$teil" --single-branch "$url" "${QUELLEN}/${teil}" \
                || fail "Zweig ${teil} liess sich nicht holen.
       Braucht dieser Server einen Lesezugang zu ${url}?"
        fi

        # Genau den Stand aus RELEASE. --hard, weil der Baum hier eine
        # Bauflaeche ist und keine Werkstatt: oertliche Aenderungen daran
        # waeren ohnehin nicht nachvollziehbar.
        git -C "${QUELLEN}/${teil}" fetch -q origin "$stand" 2>/dev/null \
            || git -C "${QUELLEN}/${teil}" fetch -q origin "$teil" \
            || fail "${teil}: konnte ${stand} nicht holen."
        git -C "${QUELLEN}/${teil}" checkout -q --detach "$stand" \
            || fail "${teil}: Stand ${stand} gibt es nicht.
       Steht er wirklich auf dem Server, von dem geklont wird?"

        [[ -f "${QUELLEN}/${teil}/Dockerfile" ]] \
            || fail "${QUELLEN}/${teil}/Dockerfile fehlt - falscher Zweig?"
        info "$(printf '%-10s %s' "$teil" "${stand:0:12}")"
    done
}

if [[ "$NUR_MANIFEST" -eq 0 ]]; then
    step "Quellen bereitlegen"
    quellen_bereitlegen
fi

# ------------------------------------------------------ 1c  An der Registry anmelden
#
# Nicht voraussetzen, sondern herstellen.
#
# Bis hierher verliess sich das Skript darauf, dass einrichten.sh einmal
# "docker login" gemacht hat. Das haelt nicht: die Anmeldung haengt am Port
# (127.0.0.1:8091), und der aendert sich beim Umkonfigurieren. Ausserdem
# raeumt neu-aufsetzen.sh sie bewusst weg.
#
# Ergebnis war eine Meldung, die nach einem Rechteproblem der Registry
# aussieht, obwohl nur eine Anmeldung fehlt:
#
#   no basic auth credentials
#
# Das Passwort steht in derselben .env, die dieses Skript ohnehin liest -
# es gibt keinen Grund, danach zu fragen oder es vorauszusetzen.
step "An der Registry anmelden"
PW_KNOTEN_LOKAL="$(grep '^HJ_TOKEN_KNOTEN=' "${HIER}/.env" 2>/dev/null | cut -d= -f2- || true)"
[[ -n "$PW_KNOTEN_LOKAL" ]] \
    || fail "Kein HJ_TOKEN_KNOTEN in ${HIER}/.env - erst einrichten.sh laufen lassen."

# Ueber die Standardeingabe, nicht als Argument: sonst stuende das Passwort
# in "ps aux", solange der Aufruf laeuft.
if printf '%s' "$PW_KNOTEN_LOKAL" \
        | docker login "127.0.0.1:${HJ_PORT_INTERN}" -u knoten --password-stdin >/dev/null 2>&1; then
    info "127.0.0.1:${HJ_PORT_INTERN}"
else
    fail "Anmeldung an der eigenen Registry fehlgeschlagen.

       Laeuft Caddy, und stimmt der Port?
           docker compose -f ${HIER}/docker-compose.yml ps
           curl -sI http://127.0.0.1:${HJ_PORT_INTERN}/v2/ | head -1

       Ein 401 dort ist richtig - das ist die Passwortabfrage. Kommt gar
       nichts, laeuft Caddy nicht oder lauscht woanders.

       (Ein Eintrag in /etc/docker/daemon.json ist hierfuer nicht noetig:
       127.0.0.1 gilt fuer Docker ohnehin als unsichere Registry.)"
fi

# ------------------------------------------------------------------ 2  Bauen

if [[ "$NUR_MANIFEST" -eq 0 ]]; then
    for teil in "${ALLE_KOMPONENTEN[@]}"; do
        gebaut=false
        for k in "${KOMPONENTEN[@]}"; do [[ "$k" == "$teil" ]] && gebaut=true; done
        if $gebaut; then
            printf '%s=%s\n' "$teil" "$VERSION"
        else
            # Nicht gebaut - den bisherigen Stand uebernehmen. Steht keiner
            # da, bleibt die Zeile weg; auto-update.sh sagt dann deutlich,
            # dass das Manifest keinen Stand fuer dieses Modul nennt.
            alt="$(printf '%s' "$VORHERIGES" | grep "^${teil}=" | cut -d= -f2- || true)"
            [[ -n "$alt" ]] && printf '%s=%s\n' "$teil" "$alt"
        fi
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
# Das Bootstrap-Skript liegt einzeln daneben, wie aufsetzen.sh: es wird
# geholt, bevor es ein Paket gibt. Caddy liefert es unter /bootstrap aus.
aus_schreiben "knoten/bootstrap.sh" < "${QUELLEN}/main/deploy/bootstrap.sh" \
    || fail "bootstrap.sh liess sich nicht ablegen."
info "bootstrap.sh"

aus_schreiben "knoten/aufsetzen.sh" < "${QUELLEN}/main/deploy/knoten-aufsetzen.sh" \
    || fail "aufsetzen.sh liess sich nicht ablegen."
info "aufsetzen.sh"

# --------------------------------------------------------------------------
# Das Deploy-Bundle - was bootstrap.sh holt
#
# bootstrap.sh laedt "knoten/<zweig>.tar.gz" und erwartet darin
# "<zweig>/deploy/...", weil es danach
# "${ARBEIT}/${ZWEIG}/deploy/install-node.sh" startet.
#
# Diese Datei hat lange gefehlt. Veroeffentlicht wurden nur die beiden
# Profilpakete (voll, lavalink) aus der Zeit vor den modularen Installern -
# bootstrap.sh lief damit ins Leere, und install-node.sh, die Einzelinstaller
# und der Agent kamen ueberhaupt nie auf einen Knoten.
#
# Aufgefallen beim Durchsehen, nicht im Betrieb: es hat noch niemand einen
# Knoten ueber diesen Weg aufgesetzt.
#
# Kein Quellcode und keine Zugangsdaten - deploy/ enthaelt nur Skripte,
# Compose-Dateien und systemd-Units. Die .env-Dateien sind ausgenommen, weil
# sie im Repository ohnehin nicht liegen.
ZWEIG_NAME="${ZWEIG_NAME:-main}"
mkdir -p "${PACK}/bundle/${ZWEIG_NAME}"
cp -r "${QUELLEN}/main/deploy" "${PACK}/bundle/${ZWEIG_NAME}/deploy"
find "${PACK}/bundle" -name ".env" -o -name "*.env" -o -name "*.key" | while read -r weg; do
    rm -f "$weg"
done
tar -C "${PACK}/bundle" -czf "${PACK}/${ZWEIG_NAME}.tar.gz" .
aus_schreiben "knoten/${ZWEIG_NAME}.tar.gz" < "${PACK}/${ZWEIG_NAME}.tar.gz" \
    || fail "Deploy-Bundle liess sich nicht ablegen."
info "$(printf '%-10s %s Bytes  (%s Dateien)' "${ZWEIG_NAME}.tar.gz" \
        "$(stat -c%s "${PACK}/${ZWEIG_NAME}.tar.gz")" \
        "$(find "${PACK}/bundle" -type f | wc -l)")"

# Gegenprobe: enthaelt das Bundle wirklich, was bootstrap.sh gleich sucht?
# Ein Paket, dem der Installer fehlt, faellt sonst erst auf dem frischen
# Knoten auf - und dort steht niemand daneben.
for pflicht in deploy/install-node.sh deploy/install-core.sh \
               deploy/install-controller.sh deploy/install-lavalink.sh \
               deploy/agent/agent-lib.sh deploy/agent/tresor-oeffnen.sh \
               deploy/auto-update.sh; do
    [[ -f "${PACK}/bundle/${ZWEIG_NAME}/${pflicht}" ]] \
        || fail "Im Bundle fehlt ${pflicht} - bootstrap.sh wuerde daran scheitern."
done
info "Bundle vollstaendig geprueft."

# ------------------------------------------------------------------ 4  Manifest
#
# Erst jetzt. Ab diesem Augenblick holen sich die Knoten das neue Release.

step "Manifest umschalten"

# Das bisherige Manifest lesen, bevor es ueberschrieben wird.
#
# Wird nur ein Modul gebaut, uebernehmen die uebrigen Zeilen ihren alten
# Wert. Ohne das verloere ein Knoten mit anderen Modulen seinen Stand.
VORHERIGES="$(aus_lesen "release/aktuell" 2>/dev/null || true)"

# Abbild-Digests einsammeln.
#
# Ein Tag ist eine Beschriftung und kein Nachweis: "core:2026.08.21.01" kann
# morgen auf ein anderes Abbild zeigen, ohne dass sich der Name aendert. Der
# Digest ist der Hash des Abbilds selbst - zieht ein Knoten danach, bekommt er
# genau das, was hier veroeffentlicht wurde, oder gar nichts.
#
# Damit erfuellt Abschnitt 37 seinen Zweck ohne eigene Signaturkette: Docker
# prueft den Digest beim Ziehen selbst und bricht bei Abweichung ab.
DIGESTS=""
for teil in "${KOMPONENTEN[@]}"; do
    d="$(docker image inspect --format '{{index .RepoDigests 0}}' \
         "${REGISTRY_PUSH}/${teil}:${VERSION}" 2>/dev/null || true)"
    # Nur der Hash-Teil, ohne den Registry-Namen davor: der unterscheidet
    # sich zwischen Push-Adresse (127.0.0.1) und Zugriffsadresse.
    d="${d##*@}"
    if [[ "$d" == sha256:* ]]; then
        DIGESTS="${DIGESTS}${teil}_digest=${d}
"
        info "$(printf '%-10s %s' "$teil" "${d:0:23}...")"
    else
        warn "Kein Digest fuer ${teil} - der Knoten kann es nicht pruefen."
    fi
done

{
    printf '# hoer.jetzt - welches Release gerade gilt.\n'
    printf '# Geschrieben von veroeffentlichen.sh am %s.\n' "$(date '+%Y-%m-%d %H:%M:%S')"
    printf '#\n'
    printf '# Zeilen bitte nicht umsortieren - auto-update.sh liest sie mit grep.\n'
    printf '#\n'
    printf '# Die *_digest-Zeilen sind der Nachweis. Ein Tag ist nur eine\n'
    printf '# Beschriftung; der Digest ist der Hash des Abbilds. Zieht ein Knoten\n'
    printf '# danach, bekommt er genau dieses Abbild - oder gar keines.\n'
    printf 'version=%s\n' "$VERSION"
    printf 'registry=%s\n' "$REGISTRY"
    for teil in "${KOMPONENTEN[@]}"; do
        printf '%s=%s\n' "$teil" "$VERSION"
    done
    printf '%s' "$DIGESTS"
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
