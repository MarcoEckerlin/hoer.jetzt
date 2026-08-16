#!/usr/bin/env bash
#
# hoer.jetzt core - Bot und Weboberflaeche
#
#   bash install.sh
#
# Baut das Abbild aus diesem Zweig und startet den Container. Erwartet nichts
# ausser Docker - Java, Maven und Abhaengigkeiten stecken im Bauabbild.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAME="hoerjetzt-core"

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





docker_pruefen

step "Konfiguration"
info "Alles aus dem Discord Developer Portal:"
info "https://discord.com/developers/applications"
geheim HJ_BOT_TOKEN            "Bot-Token"
frage  HJ_DISCORD_CLIENT_ID    "Client-ID"
geheim HJ_DISCORD_CLIENT_SECRET "Client-Secret"
echo
frage  HJ_WEB_BASE_URL "Oeffentliche Adresse" "http://$(hostname -I 2>/dev/null | awk '{print $1}')"
HJ_WEB_BASE_URL="${HJ_WEB_BASE_URL%/}"
info "Redirect-URI: ${HJ_WEB_BASE_URL}/auth/discord/callback"
info "Diese Adresse muss zeichengenau im Developer Portal stehen."
echo
frage HJ_DB_HOST     "Datenbank-Adresse" "127.0.0.1"
frage HJ_DB_PORT     "Port"              "3306"
frage HJ_DB_NAME     "Datenbank"         "discordbot"
frage HJ_DB_USER     "Benutzer"          "discordbot"
geheim HJ_DB_PASSWORD "Passwort"
echo
frage HJ_BOT_ID "Instanz-Nummer (trennt mehrere Bots in einer Datenbank)" "1"
echo
echo
frage HJ_LAVALINK_URI "Adresse des Audio-Knotens" "http://127.0.0.1:2333"
geheim HJ_LAVALINK_PASSWORD "Passwort des Audio-Knotens"
frage HJ_WEB_BIND "Auf welcher Adresse lauschen (127.0.0.1 hinter Proxy)" "127.0.0.1"

step "Abbild bauen"
docker build -t "${NAME}:latest" "$HIER" || fail "Build fehlgeschlagen."

# Bei Tailscale traegt der Sidecar den Port - der Namensraum gehoert ihm.

step "Container starten"
NETZ=(-p "${HJ_WEB_BIND}:8080:8080")

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --restart unless-stopped \
    "${NETZ[@]}" \
    -e HJ_BOT_TOKEN="$HJ_BOT_TOKEN" \
    -e HJ_DISCORD_CLIENT_ID="$HJ_DISCORD_CLIENT_ID" \
    -e HJ_DISCORD_CLIENT_SECRET="$HJ_DISCORD_CLIENT_SECRET" \
    -e HJ_WEB_BASE_URL="$HJ_WEB_BASE_URL" \
    -e HJ_WEB_REDIRECT_URI="${HJ_WEB_BASE_URL}/auth/discord/callback" \
    -e HJ_WEB_PORT=8080 \
    -e HJ_DB_HOST="$HJ_DB_HOST" -e HJ_DB_PORT="$HJ_DB_PORT" \
    -e HJ_DB_NAME="$HJ_DB_NAME" -e HJ_DB_USER="$HJ_DB_USER" \
    -e HJ_DB_PASSWORD="$HJ_DB_PASSWORD" \
    -e HJ_BOT_ID="$HJ_BOT_ID" \
    -e HJ_LAVALINK_URI="$HJ_LAVALINK_URI" \
    -e HJ_LAVALINK_PASSWORD="$HJ_LAVALINK_PASSWORD" \
    "${NAME}:latest" || fail "Start fehlgeschlagen."

step "Fertig"
info "Weboberflaeche: ${HJ_WEB_BASE_URL}"
info "Adminbereich:   ${HJ_WEB_BASE_URL}/admin"
echo
info "Naechste Schritte:"
info "  1. Redirect-URI im Developer Portal eintragen"
info "  2. Bot einladen: permissions=1101960178806, scope bot + applications.commands"
info "  3. Einmal /admin aufrufen - du wirst automatisch als Eigentuemer eingetragen"
echo
info "Logs:     docker logs -f ${NAME}"
info "Neustart: docker restart ${NAME}"
echo
