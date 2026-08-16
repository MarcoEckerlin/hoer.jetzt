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
    while :; do
        __e=""
        while [[ -z "$__e" ]]; do read -r -s -p "    ${__t}: " __e || true; echo; done

        # Ein Dollarzeichen in der .env ist nicht bloss unschoen, es kommt
        # falsch an: Docker Compose ersetzt "$name" durch eine leere
        # Zeichenkette und warnt nur beilaeufig ("variable is not set").
        # Das Passwort im Container ist dann ein anderes als das hier
        # eingetippte - und der Fehler faellt erst auf, wenn sich jemand von
        # aussen verbinden will.
        if [[ "$__e" == *'$'* ]]; then
            warn "Dollarzeichen gehen in der .env nicht - Docker Compose ersetzt sie."
            warn "Bitte einen Wert ohne \$ waehlen."
            continue
        fi
        break
    done
    printf -v "$__v" '%s' "$__e"
}

# Kein "tr ... | head -c" - head schliesst die Pipe, tr faengt SIGPIPE und
# unter "set -o pipefail" bricht das ganze Skript wortlos ab.
zufall() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32; }

# Ja/Nein-Abfrage.
#
# Fehlte hier bisher - das uebrige Skript fragt mit einem read von Hand. Eine
# fehlende Funktion in einer if-Bedingung ist besonders unangenehm: "set -e"
# greift dort nicht, die Bedingung gilt schlicht als falsch, und die
# Installation laeuft mit der stillen Vorgabe weiter.
ja() {
    local __a=""
    read -r -p "    $1 (j/n) [${2:-j}]: " __a || true
    [[ "${__a:-${2:-j}}" =~ ^[jJ] ]]
}

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

export GIT_TERMINAL_PROMPT=0

step "Zweige holen"
mkdir -p "$ARBEIT"
for zweig in core ai-radio lavalink web; do
    if [[ -d "${ARBEIT}/${zweig}/.git" ]]; then
        git -C "${ARBEIT}/${zweig}" fetch -q origin "$zweig" \
            || fail "Zweig ${zweig} liess sich nicht holen. Bei einer Passwortabfrage: gespeicherten Zugang loeschen (git config --global --unset-all credential.helper)."
        git -C "${ARBEIT}/${zweig}" reset -q --hard "origin/${zweig}"
        info "${zweig}: aktualisiert"
    else
        git clone -q -b "$zweig" --single-branch "$REPO" "${ARBEIT}/${zweig}" \
            || fail "Zweig ${zweig} nicht erreichbar. Ist das Repository oeffentlich?"
        info "${zweig}: geholt"
    fi
done

step "Konfiguration"
# Die Vorgaben stammten noch aus der MariaDB-Zeit: 127.0.0.1 und Port 3306.
# Beides ist seit dem Umzug falsch und fuehrt geradewegs in eine Installation,
# die nicht startet - der Bot laeuft im Container, die Datenbank heisst dort
# "postgres" und lauscht auf 5432. Wer eine Datenbank ausserhalb betreibt,
# traegt sie weiterhin von Hand ein.
frage HJ_DB_HOST     "Datenbank-Adresse" "postgres"
frage HJ_DB_PORT     "Port"              "5432"
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

COMPOSE_DATEI="docker-compose.yml"
COMPOSE=(-f "$COMPOSE_DATEI")
# Die Spock-Datei kommt weiter unten dazu, sobald feststeht, ob diese Node zu
# einem Verbund gehoert - hier ist die Frage noch nicht gestellt.

# Der Betriebsbereich ist kein Bestandteil der Grundinstallation. Er zeigt den
# ganzen Verbund und setzt dessen Ziel - das gehoert auf eine Maschine, nicht
# auf jede. Wer ihn ueberall mitinstalliert, hat ueberall eine Tuer, die er
# ueberall zuhalten muss.
# Mehrere Hosts verbinden sich ueber das private Netz des Anbieters - bei
# Hetzner ein Private Network, das der Konsole entstammt und nichts kostet.
# Frueher lief das ueber einen Tailscale-Sidecar; der ist mit Stufe 6 des
# Umbaus entfallen. Wer keinen privaten Netzbereich hat, begrenzt Port 2333
# per Firewall auf die Adresse des Bots.
echo
info "Nummer dieser Node im Verbund - sie bestimmt den Zahlenraum, aus dem die"
info "Datenbank ihre fortlaufenden Nummern vergibt. Jede Node eine eigene."
frage HJ_NODE_NR "Node-Nummer" "1"
PRIVAT_IP="$(ip -4 -o addr show 2>/dev/null | awk '{print $4}' | cut -d/ -f1 \
    | grep -E '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.)' | head -n1 || true)"
[[ -n "$PRIVAT_IP" ]] && info "Private Adresse gefunden: ${PRIVAT_IP}"

# Replikation und Sitzungen gehoeren zusammen abgefragt.
#
# Beides musste man bisher hinterher von Hand in die .env schreiben - und
# genau das war die Fehlerquelle: ohne HJ_SPOCK laeuft ein Standard-Postgres
# ohne die Erweiterung, ohne HJ_SESSION_STORE liegen die Sitzungen je Node im
# Arbeitsspeicher und die Anmeldung bricht beim Wechsel ab. Wer den Verbund
# aufsetzt, will beides; wer eine einzelne Maschine betreibt, braucht keins
# von beiden.
HJ_SPOCK=""
HJ_SESSION_STORE=""
echo
info "Mehrere Nodes im Verbund gleichen ihre Datenbanken ueber Spock ab."
info "Dafuer braucht Postgres ein anderes Abbild und einen Port im privaten Netz."

# Die Frage wird immer gestellt, auch ohne erkannte private Adresse.
#
# Vorher hing sie an "[[ -n $PRIVAT_IP ]] &&" - wurde keine gefunden, blieb
# die Frage einfach aus, HJ_SPOCK leer, und die Installation lief scheinbar
# sauber durch. Sichtbar wurde es erst Stunden spaeter beim ersten
# spock-einrichten.sh: "extension spock is not available". Eine
# uebersprungene Frage ist die teuerste Art, eine Vorgabe zu setzen.
if [[ -z "$PRIVAT_IP" ]]; then
    warn "Keine private Adresse gefunden. Fuer einen Verbund muss eine da sein -"
    warn "bei Hetzner ein Private Network in der Konsole."
fi

if ja "Diese Node ist Teil eines Verbunds?" n; then
    [[ -n "$PRIVAT_IP" ]] || fail "Ohne private Adresse kein Verbund - erst ein Private Network zuweisen."
    HJ_SPOCK="true"
    HJ_SESSION_STORE="datenbank"
    # Ohne diese Datei laeuft ein Standard-Postgres ohne die Erweiterung, und
    # der Port bleibt im Docker-Netz - die andere Node kaeme nicht heran.
    COMPOSE+=(-f "docker-compose.spock.yml")
    info "Nach der Installation auf jeder Node einmal:"
    info "    bash deploy/spock-einrichten.sh anlegen"
    info "und danach kreuzweise 'verbinden' - siehe ANLEITUNG.md."
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
HJ_REDIS_HOST=redis
HJ_REDIS_PORT=6379
HJ_NODE_NR=${HJ_NODE_NR}
HJ_PRIVAT_IP=${PRIVAT_IP}
HJ_SPOCK=${HJ_SPOCK}
HJ_SESSION_STORE=${HJ_SESSION_STORE}
ENV
chmod 600 "$UMGEBUNG"
info "$UMGEBUNG (enthaelt Token und Passwoerter, Rechte 0600)"

step "Bauen und starten"
cd "${ARBEIT}/main/deploy/docker" 2>/dev/null || cd "$(dirname "${BASH_SOURCE[0]}")/deploy/docker"
cp "$UMGEBUNG" .env
docker compose "${COMPOSE[@]}" build || fail "Build fehlgeschlagen."
docker compose "${COMPOSE[@]}" up -d || fail "Start fehlgeschlagen."

sleep 20
docker compose "${COMPOSE[@]}" ps

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
info "Spaeter: docker compose ${COMPOSE[*]} <befehl>"
echo
info "Anleitung: ANLEITUNG.md"
echo
