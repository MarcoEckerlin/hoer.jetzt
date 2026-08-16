#!/usr/bin/env bash
#
# hoer.jetzt ai-radio - Music-Brain
#
#   bash install.sh
#
# Baut das Abbild aus diesem Zweig und startet den Container. Erwartet nichts
# ausser Docker - Java, Maven und Abhaengigkeiten stecken im Bauabbild.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAME="hoerjetzt-ai-radio"

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
info "Dieselbe Datenbank wie der Bot."
frage HJ_DB_HOST     "Datenbank-Adresse" "127.0.0.1"
frage HJ_DB_PORT     "Port"              "3306"
frage HJ_DB_NAME     "Datenbank"         "discordbot"
frage HJ_DB_USER     "Benutzer"          "discordbot"
geheim HJ_DB_PASSWORD "Passwort"
frage HJ_BOT_ID      "Instanz-Nummer"    "1"
echo
info "Ohne Sprachmodell liefert der Dienst keine Vorschlaege - das AI-Radio"
info "spielt dann einen festen Ersatzmix. Leer lassen ist in Ordnung."
frage HJ_LLM_OLLAMA_URL "Adresse des Modellservers" "http://127.0.0.1:11434"
modelladresse HJ_LLM_OLLAMA_URL
frage HJ_LLM_MODEL      "Modell"                    "phi3.5"

step "Abbild bauen"
docker build -t "${NAME}:latest" "$HIER" || fail "Build fehlgeschlagen."

step "Container starten"
docker rm -f "$NAME" >/dev/null 2>&1 || true
# Kein Port nach aussen: nur der Bot spricht mit diesem Dienst.
docker run -d --name "$NAME" --restart unless-stopped \
    -p 127.0.0.1:8091:8091 \
    -e HJ_DB_HOST="$HJ_DB_HOST" -e HJ_DB_PORT="$HJ_DB_PORT" \
    -e HJ_DB_NAME="$HJ_DB_NAME" -e HJ_DB_USER="$HJ_DB_USER" \
    -e HJ_DB_PASSWORD="$HJ_DB_PASSWORD" \
    -e HJ_BOT_ID="$HJ_BOT_ID" \
    -e HJ_LISTEN_HOST=0.0.0.0 \
    -e HJ_LLM_OLLAMA_URL="$HJ_LLM_OLLAMA_URL" \
    -e HJ_LLM_MODEL="$HJ_LLM_MODEL" \
    "${NAME}:latest" || fail "Start fehlgeschlagen."

step "Fertig"
info "Erreichbar unter http://127.0.0.1:8091 - nur lokal."
info "Im Bot eintragen: HJ_MUSIC_BRAIN_BASE_URL"
info "Logs: docker logs -f ${NAME}"
echo
