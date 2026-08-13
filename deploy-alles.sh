#!/usr/bin/env bash
#
# hoer.jetzt - einmal alles ausrollen.
#
#   bash deploy-alles.sh
#
# Gedacht fuer den Wechsel von der alten JAR-Installation (systemd) auf den
# Docker-Stack, und danach fuer jedes weitere "alles neu bauen". Die Datenbank
# bleibt, wie sie ist - sie laeuft ausserhalb und wird nur weiterbenutzt.
#
# Der Ablauf:
#   1. Voraussetzungen pruefen
#   2. Alte Dienste finden und (nach Rueckfrage) stillegen
#   3. Datenbank sichern, solange noch nichts angefasst wurde
#   4. Alle vier Zweige auf den neuesten Stand holen
#   5. Konfiguration uebernehmen oder erfragen
#   6. Abbilder bauen und starten
#   7. Nachsehen, ob es laeuft
#
# Nichts davon loescht Daten. Alte Dienste werden gestoppt und deaktiviert,
# nicht deinstalliert - ein Rueckweg bleibt offen.

set -euo pipefail

REPO="${REPO:-https://github.com/MarcoEckerlin/hoer.jetzt.git}"
ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
UMGEBUNG="${ARBEIT}/.env"
SICHERUNG="${SICHERUNG:-/var/backups}"

ALTE_DIENSTE=(discordbot.service lavalink.service discordbot-music-brain.service)
ALTE_KONFIG="${ALTE_KONFIG:-/opt/discordbot/config/config.json}"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

ja() {
    local antwort=""
    read -r -p "    $1 (j/n) [${2:-j}]: " antwort || true
    [[ "${antwort:-${2:-j}}" =~ ^[jJ] ]]
}

# JSON-Wert lesen, ohne von jq abzuhaengen.
json() {
    python3 - "$1" "$2" "$3" <<'PY' 2>/dev/null || true
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as datei:
        daten = json.load(datei)
    for schluessel in sys.argv[2].split("."):
        daten = daten[schluessel]
    print(daten)
except Exception:
    print(sys.argv[3])
PY
}

# ------------------------------------------------------------------ 1

step "Voraussetzungen"
[[ "$(id -u)" -eq 0 ]] || fail "Bitte als root starten."
command -v git >/dev/null 2>&1 || fail "git fehlt."

if ! command -v docker >/dev/null 2>&1; then
    info "Docker fehlt - wird installiert."
    curl -fsSL https://get.docker.com | sh || fail "Docker-Installation fehlgeschlagen."
    systemctl enable --now docker >/dev/null 2>&1 || true
fi
docker compose version >/dev/null 2>&1 || fail "docker compose (v2) fehlt."
info "Docker: $(docker --version)"
info "Ziel:   ${ARBEIT}"

# ------------------------------------------------------------------ 2

step "Alte Installation"
GEFUNDEN=()
for dienst in "${ALTE_DIENSTE[@]}"; do
    if systemctl list-unit-files "$dienst" >/dev/null 2>&1 \
        && systemctl list-unit-files "$dienst" | grep -q "$dienst"; then
        zustand="$(systemctl is-active "$dienst" 2>/dev/null || true)"
        info "$(printf '%-34s %s' "$dienst" "$zustand")"
        GEFUNDEN+=("$dienst")
    fi
done

if [[ ${#GEFUNDEN[@]} -eq 0 ]]; then
    info "Keine alten Dienste gefunden."
else
    warn "Diese Dienste belegen dieselben Ports wie der neue Stack (8080, 2333, 8091)."
    warn "Solange sie laufen, kann der Stack nicht starten."
    if ja "Jetzt stoppen und aus dem Autostart nehmen?"; then
        for dienst in "${GEFUNDEN[@]}"; do
            systemctl stop "$dienst" 2>/dev/null || true
            systemctl disable "$dienst" 2>/dev/null || true
            info "${dienst} gestoppt"
        done
        info "Rueckweg: systemctl enable --now <dienst>"
    else
        fail "Ohne das Stillegen geht es nicht weiter."
    fi
fi

# ------------------------------------------------------------------ 3

step "Datenbank sichern"
if [[ -f "$ALTE_KONFIG" ]] && command -v mariadb-dump >/dev/null 2>&1; then
    DB_HOST="$(json "$ALTE_KONFIG" database.host 127.0.0.1)"
    DB_PORT="$(json "$ALTE_KONFIG" database.port 3306)"
    DB_NAME="$(json "$ALTE_KONFIG" database.name discordbot)"
    DB_USER="$(json "$ALTE_KONFIG" database.user discordbot)"
    DB_PASS="$(json "$ALTE_KONFIG" database.password '')"

    mkdir -p "$SICHERUNG"
    ZIEL="${SICHERUNG}/${DB_NAME}-$(date +%F-%H%M).sql.gz"
    if MYSQL_PWD="$DB_PASS" mariadb-dump --single-transaction --skip-ssl \
            -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME" 2>/dev/null | gzip > "$ZIEL"; then
        info "$(du -h "$ZIEL" | cut -f1) nach ${ZIEL}"
    else
        rm -f "$ZIEL"
        warn "Sicherung fehlgeschlagen - Zugangsdaten aus ${ALTE_KONFIG} pruefen."
        ja "Trotzdem weitermachen?" n || fail "Abgebrochen."
    fi
else
    warn "Keine Sicherung moeglich (${ALTE_KONFIG} oder mariadb-dump fehlt)."
    warn "Das Schema wird nur ergaenzt, nie geloescht - aber eine Sicherung ist eine Sicherung."
    ja "Ohne Sicherung weitermachen?" j || fail "Abgebrochen."
fi

# ------------------------------------------------------------------ 4

step "Zweige holen"
mkdir -p "$ARBEIT"
for zweig in main core ai-radio lavalink; do
    ziel="${ARBEIT}/${zweig}"
    if [[ -d "${ziel}/.git" ]]; then
        git config --global --add safe.directory "$ziel" 2>/dev/null || true
        git -C "$ziel" fetch -q origin "$zweig" || fail "Zweig ${zweig} nicht erreichbar."
        git -C "$ziel" reset -q --hard "origin/${zweig}"
        info "$(printf '%-9s %s' "$zweig" "$(git -C "$ziel" log -1 --format='%h %s')")"
    else
        git clone -q -b "$zweig" --single-branch "$REPO" "$ziel" \
            || fail "Zweig ${zweig} nicht erreichbar. Ist das Repository oeffentlich?"
        git config --global --add safe.directory "$ziel" 2>/dev/null || true
        info "$(printf '%-9s %s' "$zweig" "geholt")"
    fi
done

# ------------------------------------------------------------------ 5

step "Konfiguration"
if [[ -f "$UMGEBUNG" ]] && grep -q '^HJ_BOT_TOKEN=.\+' "$UMGEBUNG"; then
    info "${UMGEBUNG} ist vorhanden und wird weiterbenutzt."
    info "Aendern: nano ${UMGEBUNG}, danach dieses Skript erneut starten."
elif [[ -f "$ALTE_KONFIG" ]]; then
    info "Keine .env, aber die alte ${ALTE_KONFIG} - daraus wird uebernommen."
    HJ_LAVALINK_PASSWORD="$(json "$ALTE_KONFIG" lavalink.password '')"
    [[ -n "$HJ_LAVALINK_PASSWORD" ]] || HJ_LAVALINK_PASSWORD="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32)"

    BASIS="$(json "$ALTE_KONFIG" webinterface.base_url '')"
    umask 077
    cat > "$UMGEBUNG" <<ENV
HJ_BOT_TOKEN=$(json "$ALTE_KONFIG" bot.token '')
HJ_DISCORD_CLIENT_ID=$(json "$ALTE_KONFIG" webinterface.discord_client_id '')
HJ_DISCORD_CLIENT_SECRET=$(json "$ALTE_KONFIG" webinterface.discord_client_secret '')
HJ_WEB_BASE_URL=${BASIS%/}
HJ_WEB_BIND=127.0.0.1
HJ_DB_HOST=$(json "$ALTE_KONFIG" database.host 127.0.0.1)
HJ_DB_PORT=$(json "$ALTE_KONFIG" database.port 3306)
HJ_DB_NAME=$(json "$ALTE_KONFIG" database.name discordbot)
HJ_DB_USER=$(json "$ALTE_KONFIG" database.user discordbot)
HJ_DB_PASSWORD=$(json "$ALTE_KONFIG" database.password '')
HJ_BOT_ID=$(json "$ALTE_KONFIG" bot_id 1)
HJ_LAVALINK_PASSWORD=${HJ_LAVALINK_PASSWORD}
HJ_LLM_OLLAMA_URL=$(json "$ALTE_KONFIG" llm.ollama_url '')
HJ_LLM_MODEL=$(json "$ALTE_KONFIG" llm.model '')
ENV
    chmod 600 "$UMGEBUNG"
    info "${UMGEBUNG} geschrieben (Rechte 0600)"
    warn "Bitte einmal durchsehen - besonders HJ_WEB_BIND, wenn kein Proxy davor sitzt:"
    warn "    nano ${UMGEBUNG}"
    ja "Passt es so?" j || fail "Dann bitte anpassen und erneut starten."
else
    info "Weder .env noch alte Konfiguration - der Installer fragt alles ab."
    bash "${ARBEIT}/main/install.sh"
    exit 0
fi

# ------------------------------------------------------------------ 6

COMPOSE_DATEI="docker-compose.yml"
if grep -q '^TS_AUTHKEY=.\+' "$UMGEBUNG" 2>/dev/null; then
    COMPOSE_DATEI="docker-compose.tailscale.yml"
    info "Tailscale-Key gefunden - es wird ${COMPOSE_DATEI} verwendet."
    [[ -e /dev/net/tun ]] || modprobe tun 2>/dev/null || true
    [[ -e /dev/net/tun ]] || fail "/dev/net/tun fehlt - auf dem Host 'modprobe tun'."
fi

# Aeltere .env-Dateien kennen den Host-Port noch nicht. Ohne Eintrag gilt 8080.
if ! grep -q '^HJ_WEB_PORT_HOST=' "$UMGEBUNG"; then
    if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ':8080 '; then
        warn "Port 8080 ist belegt und HJ_WEB_PORT_HOST steht nicht in der .env."
        frage_port=""
        read -r -p "    Anderer Port fuer die Weboberflaeche [8086]: " frage_port || true
        printf 'HJ_WEB_PORT_HOST=%s\n' "${frage_port:-8086}" >> "$UMGEBUNG"
        info "HJ_WEB_PORT_HOST=${frage_port:-8086} ergaenzt."
    fi
fi

step "Bauen"
cd "${ARBEIT}/main/deploy/docker"
cp "$UMGEBUNG" .env
chmod 600 .env
docker compose -f "$COMPOSE_DATEI" build --pull || fail "Build fehlgeschlagen."

step "Starten"
docker compose -f "$COMPOSE_DATEI" up -d --remove-orphans || fail "Start fehlgeschlagen."

# ------------------------------------------------------------------ 7

step "Nachsehen"
sleep 25
docker compose -f "$COMPOSE_DATEI" ps

echo
LAEUFT=1
for behaelter in hoerjetzt-core-1 hoerjetzt-ai-radio-1; do
    zustand="$(docker inspect -f '{{.State.Status}}' "$behaelter" 2>/dev/null || echo "fehlt")"
    if [[ "$zustand" == "running" ]]; then
        info "$(printf '%-22s %s' "$behaelter" "laeuft")"
    else
        warn "$(printf '%-22s %s' "$behaelter" "$zustand")"
        LAEUFT=0
    fi
done

if [[ "$COMPOSE_DATEI" == *tailscale* ]]; then
    TS_IP="$(docker exec hoerjetzt-tailscale tailscale ip -4 2>/dev/null | head -n1 || true)"
    [[ -n "$TS_IP" ]] && info "$(printf '%-22s %s' "Im Tailnet" "$TS_IP")"
fi

step "Fertig"
BASIS="$(grep '^HJ_WEB_BASE_URL=' "$UMGEBUNG" | cut -d= -f2-)"
info "Weboberflaeche: ${BASIS}"
echo
if [[ "$LAEUFT" -eq 0 ]]; then
    warn "Etwas laeuft noch nicht. Erster Blick:"
    warn "    docker logs --tail 80 hoerjetzt-core-1"
    echo
fi
info "Noch zu tun:"
info "  1. Audio-Knoten im Adminbereich pruefen - Stufe und Obergrenze sind neu"
info "  2. Premium je Server unter Adminbereich -> Server zuteilen"
info "  3. Passwort des mitlaufenden Knotens steht in ${UMGEBUNG}"
echo
info "Logs:     docker compose -f ${COMPOSE_DATEI} logs -f"
info "Neu:      docker compose -f ${COMPOSE_DATEI} up -d --build"
info "Anleitung: ${ARBEIT}/main/ANLEITUNG.md"
echo
