#!/usr/bin/env bash
#
# hoer.jetzt - Gesamtinstallation
#
#   bash install.sh
#
# Baut das Abbild aus diesem Zweig und startet den Container. Erwartet nichts
# ausser Docker - Java, Maven und Abhaengigkeiten stecken im Bauabbild.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAME="hoerjetzt"

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

REPO="${REPO:-https://github.com/MarcoEckerlin/hoer.jetzt.git}"
ARBEIT="${ARBEIT:-/opt/hoerjetzt}"

docker_pruefen
command -v git >/dev/null 2>&1 || fail "git fehlt."

cat <<'KOPF'

  ------------------------------------------------------------------
   hoer.jetzt - alles auf einem Host
  ------------------------------------------------------------------

  Holt die drei Komponentenzweige, baut die Abbilder und startet den
  Stack. Die Datenbank laeuft ausserhalb und wird nur eingetragen.

  Fuer verteilte Knoten stattdessen auf dem jeweiligen Host:
      git clone -b lavalink <repo> knoten && cd knoten && bash install.sh

KOPF

step "Zweige holen"
mkdir -p "$ARBEIT"
for zweig in core ai-radio lavalink; do
    if [[ -d "${ARBEIT}/${zweig}/.git" ]]; then
        git -C "${ARBEIT}/${zweig}" fetch -q origin "$zweig"
        git -C "${ARBEIT}/${zweig}" reset -q --hard "origin/${zweig}"
        info "${zweig}: aktualisiert"
    else
        git clone -q -b "$zweig" --single-branch "$REPO" "${ARBEIT}/${zweig}" \
            || fail "Zweig ${zweig} nicht erreichbar. Ist das Repository oeffentlich?"
        info "${zweig}: geholt"
    fi
done

step "Konfiguration"
frage HJ_DB_HOST     "Datenbank-Adresse" "127.0.0.1"
frage HJ_DB_PORT     "Port"              "3306"
frage HJ_DB_NAME     "Datenbank"         "discordbot"
frage HJ_DB_USER     "Benutzer"          "discordbot"
geheim HJ_DB_PASSWORD "Passwort"
frage HJ_BOT_ID      "Instanz-Nummer"    "1"
echo
geheim HJ_BOT_TOKEN             "Bot-Token"
frage  HJ_DISCORD_CLIENT_ID     "Client-ID"
geheim HJ_DISCORD_CLIENT_SECRET "Client-Secret"
echo
frage HJ_WEB_BASE_URL "Oeffentliche Adresse" "http://$(hostname -I 2>/dev/null | awk '{print $1}')"
HJ_WEB_BASE_URL="${HJ_WEB_BASE_URL%/}"
frage HJ_WEB_BIND     "Auf welcher Adresse lauschen" "127.0.0.1"

# Belegte Ports sind der haeufigste Grund, warum der Start abbricht - lieber
# vorher fragen als hinterher eine Fehlermeldung von Docker deuten.
HJ_WEB_PORT_HOST=8080
if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ':8080 '; then
    warn "Port 8080 ist auf diesem Host bereits belegt."
    frage HJ_WEB_PORT_HOST "Anderer Port fuer die Weboberflaeche" "8086"
fi
echo
info "Sprachmodell fuer KI-Chat und AI-Radio. Leer lassen, wenn keines da ist."
info "Adresse reicht - http:// und Port 11434 werden ergaenzt."
frage_leer HJ_LLM_OLLAMA_URL "Sprachmodell"
modelladresse HJ_LLM_OLLAMA_URL
if [[ -n "$HJ_LLM_OLLAMA_URL" ]]; then
    info "-> ${HJ_LLM_OLLAMA_URL}"
    frage HJ_LLM_MODEL "Modell" "qwen3:8b"
else
    HJ_LLM_MODEL=""
    info "Ohne Sprachmodell: KI-Chat und AI-Radio bleiben aus."
fi

echo
info "Tailscale verbindet mehrere Hosts zu einem privaten Netz. Audio-Knoten"
info "auf anderen Hosts brauchen dann keinen offenen Port mehr."
read -r -p "    Tailscale-Netz nutzen? (j/n) [n]: " TS_JN || true
TS_AUTHKEY=""; TS_HOSTNAME="hoerjetzt-core"; COMPOSE_DATEI="docker-compose.yml"
if [[ "${TS_JN:-n}" =~ ^[jJ] ]]; then
    frage TS_HOSTNAME "Name in diesem Netz" "hoerjetzt-core"
    info "Auth-Key erzeugen: Tailscale-Adminbereich -> Settings -> Keys."
    info "Sinnvoll: Reusable an, Ephemeral aus. Tags erst vergeben, wenn sie"
    info "in den ACLs unter tagOwners stehen - sonst scheitert die Anmeldung."
    geheim TS_AUTHKEY "Auth-Key (tskey-auth-...)"
    COMPOSE_DATEI="docker-compose.tailscale.yml"
    [[ -e /dev/net/tun ]] || modprobe tun 2>/dev/null || true
    [[ -e /dev/net/tun ]] || fail "/dev/net/tun fehlt - auf dem Host 'modprobe tun'."
fi

HJ_LAVALINK_PASSWORD="$(zufall)"

step "Umgebungsdatei schreiben"
UMGEBUNG="${ARBEIT}/.env"
cat > "$UMGEBUNG" <<ENV
HJ_BOT_TOKEN=${HJ_BOT_TOKEN}
HJ_DISCORD_CLIENT_ID=${HJ_DISCORD_CLIENT_ID}
HJ_DISCORD_CLIENT_SECRET=${HJ_DISCORD_CLIENT_SECRET}
HJ_WEB_BASE_URL=${HJ_WEB_BASE_URL}
HJ_WEB_BIND=${HJ_WEB_BIND}
HJ_DB_HOST=${HJ_DB_HOST}
HJ_DB_PORT=${HJ_DB_PORT}
HJ_DB_NAME=${HJ_DB_NAME}
HJ_DB_USER=${HJ_DB_USER}
HJ_DB_PASSWORD=${HJ_DB_PASSWORD}
HJ_BOT_ID=${HJ_BOT_ID}
HJ_LAVALINK_PASSWORD=${HJ_LAVALINK_PASSWORD}
HJ_LLM_OLLAMA_URL=${HJ_LLM_OLLAMA_URL}
HJ_LLM_MODEL=${HJ_LLM_MODEL}
HJ_WEB_PORT_HOST=${HJ_WEB_PORT_HOST}
TS_AUTHKEY=${TS_AUTHKEY}
TS_HOSTNAME=${TS_HOSTNAME}
ENV
chmod 600 "$UMGEBUNG"
info "$UMGEBUNG (enthaelt Token und Passwoerter, Rechte 0600)"

step "Bauen und starten"
cd "${ARBEIT}/main/deploy/docker" 2>/dev/null || cd "$(dirname "${BASH_SOURCE[0]}")/deploy/docker"
cp "$UMGEBUNG" .env
docker compose -f "$COMPOSE_DATEI" build || fail "Build fehlgeschlagen."
docker compose -f "$COMPOSE_DATEI" up -d || fail "Start fehlgeschlagen."

sleep 20
docker compose -f "$COMPOSE_DATEI" ps
if [[ "$COMPOSE_DATEI" == *tailscale* ]]; then
    TS_IP="$(docker exec hoerjetzt-tailscale tailscale ip -4 2>/dev/null | head -n1 || true)"
    [[ -n "$TS_IP" ]] && info "Im Tailnet: ${TS_HOSTNAME} (${TS_IP})"
fi

step "Auto-Update"
info "Ein Systemd-Timer kann jede Nacht um 03:00 auf das neueste Release"
info "aktualisieren. Er wartet dabei auf eine Wiedergabepause und nimmt nur"
info "getaggte Staende - ein Push landet also nicht sofort auf diesem Host."
read -r -p "    Auto-Update einrichten? (j/n) [j]: " AU || true
if [[ "${AU:-j}" =~ ^[jJ] ]]; then
    install -m 0644 "${ARBEIT}/main/deploy/systemd/hoerjetzt-update.service" /etc/systemd/system/
    install -m 0644 "${ARBEIT}/main/deploy/systemd/hoerjetzt-update.timer" /etc/systemd/system/
    systemctl daemon-reload
    systemctl enable --now hoerjetzt-update.timer >/dev/null 2>&1 || true
    # Der laufende Stand gilt als installiert, sonst baut der Timer heute Nacht
    # dasselbe noch einmal.
    if [[ -f "${ARBEIT}/main/RELEASE" ]]; then
        printf 'v%s\n' "$(grep '^version=' "${ARBEIT}/main/RELEASE" | cut -d= -f2-)" > "${ARBEIT}/.installiert"
    fi
    info "Naechster Lauf: $(systemctl show -p NextElapseUSecRealtime --value hoerjetzt-update.timer 2>/dev/null || echo '03:00')"
    info "Von Hand: bash ${ARBEIT}/main/deploy/auto-update.sh --pruefen"
else
    info "Uebersprungen. Spaeter: systemctl enable --now hoerjetzt-update.timer"
fi

step "Fertig"
info "Weboberflaeche: ${HJ_WEB_BASE_URL}"
echo
info "Noch zu tun:"
info "  1. ${HJ_WEB_BASE_URL}/auth/discord/callback im Developer Portal eintragen"
info "  2. Bot einladen: permissions=1101960178806"
info "  3. Einmal /admin aufrufen"
info "  4. Audio-Knoten im Adminbereich eintragen, Passwort steht in ${UMGEBUNG}"
info ""
info "Spaeter: docker compose -f ${COMPOSE_DATEI} <befehl>"
echo
info "Anleitung: ANLEITUNG.md"
echo
