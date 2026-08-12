#!/usr/bin/env bash
#
# Einrichtung auf einem frischen Debian- oder Ubuntu-Server.
#
#   bash install.sh
#
# Das Skript erkennt selbst, womit es arbeitet:
#
#   Paketmodus   liegen die JARs neben dem Skript, wird nichts gebaut.
#                Auf dem Server reicht dann eine Java-Laufzeit.
#   Quellmodus   liegt daneben ein Projekt mit pom.xml, wird daraus gebaut.
#                Dafuer holt das Skript JDK und Maven.
#
# Danach fragt es in fuenf Abschnitten alles ab, richtet Datenbank, Dienste
# und Konfiguration ein und startet den Stack. Mehrfach ausfuehrbar: gesetzte
# Werte kommen als Vorgabe, bestehende Daten bleiben unangetastet.

set -euo pipefail

# ------------------------------------------------------------ Konstanten

BOT_DIR="/opt/discordbot"
BRAIN_DIR="/opt/discordbot-music-brain"
LAVALINK_DIR="/opt/lavalink"
SRC_DIR="/opt/discordbot-src"

BOT_JAR="DiscordBot-alpha-1.0.jar"
BRAIN_JAR="discordbot-music-brain.jar"

BOT_USER="discordbot"
LAVALINK_USER="lavalink"

LAVALINK_URL="https://github.com/lavalink-devs/Lavalink/releases/latest/download/Lavalink.jar"

PAKET="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Im Quellmodus liegt das Skript in deploy/, das Projekt eine Ebene hoeher.
PROJECT_ROOT="$(cd "${PAKET}/.." && pwd)"

# ------------------------------------------------------------ Ausgabe

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

# ------------------------------------------------------------ Dialog

# ask VARIABLE "Frage" "Vorgabe"
ask() {
    local __var="$1" __frage="$2" __vorgabe="${3:-}" __eingabe=""
    if [[ -n "$__vorgabe" ]]; then
        read -r -p "    ${__frage} [${__vorgabe}]: " __eingabe || true
        __eingabe="${__eingabe:-$__vorgabe}"
    else
        while [[ -z "$__eingabe" ]]; do
            read -r -p "    ${__frage}: " __eingabe || true
        done
    fi
    printf -v "$__var" '%s' "$__eingabe"
}

# ask_secret VARIABLE "Frage" [vorhandener_wert]
# Leere Eingabe behaelt den vorhandenen Wert, damit ein zweiter Lauf
# nicht verlangt, dass man den Bot-Token erneut heraussucht.
ask_secret() {
    local __var="$1" __frage="$2" __vorhanden="${3:-}" __eingabe=""
    if [[ -n "$__vorhanden" ]]; then
        read -r -s -p "    ${__frage} [unveraendert lassen: Enter]: " __eingabe || true
        echo
        __eingabe="${__eingabe:-$__vorhanden}"
    else
        while [[ -z "$__eingabe" ]]; do
            read -r -s -p "    ${__frage}: " __eingabe || true
            echo
        done
    fi
    printf -v "$__var" '%s' "$__eingabe"
}

# ask_yes_no VARIABLE "Frage" "j"|"n"
ask_yes_no() {
    local __var="$1" __frage="$2" __vorgabe="${3:-j}" __eingabe=""
    while true; do
        read -r -p "    ${__frage} (j/n) [${__vorgabe}]: " __eingabe || true
        __eingabe="${__eingabe:-$__vorgabe}"
        case "${__eingabe,,}" in
            j|ja|y|yes) printf -v "$__var" '%s' "ja";   return 0 ;;
            n|nein|no)  printf -v "$__var" '%s' "nein"; return 0 ;;
            *) warn "Bitte j oder n." ;;
        esac
    done
}

zufallswort() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32; }

# ask_port VARIABLE "Frage" "Vorgabe" - akzeptiert nur gueltige Portnummern,
# weil der Wert unquotiert in die JSON-Konfiguration geschrieben wird.
ask_port() {
    local __var="$1" __frage="$2" __vorgabe="$3" __wert=""
    while true; do
        ask __wert "$__frage" "$__vorgabe"
        if [[ "$__wert" =~ ^[0-9]+$ ]] && (( __wert >= 1 && __wert <= 65535 )); then
            printf -v "$__var" '%s' "$__wert"
            return 0
        fi
        warn "Bitte eine Portnummer zwischen 1 und 65535 angeben."
    done
}

# Auf aelteren Debian-Versionen heisst der Client mysql statt mariadb.
db_client() {
    if command -v mariadb >/dev/null 2>&1; then echo mariadb; else echo mysql; fi
}

# JSON-Wert aus einer bestehenden Konfiguration lesen (leer, wenn nicht da).
json_wert() {
    local datei="$1" pfad="$2"
    [[ -f "$datei" ]] || { echo ""; return 0; }
    python3 - "$datei" "$pfad" <<'PY' 2>/dev/null || echo ""
import json, sys
try:
    daten = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print(""); raise SystemExit
wert = daten
for teil in sys.argv[2].split("."):
    if not isinstance(wert, dict) or teil not in wert:
        print(""); raise SystemExit
    wert = wert[teil]
print("" if wert is None else wert)
PY
}

# ------------------------------------------------------------ Vorpruefung

[[ "$(id -u)" -eq 0 ]] || fail "Bitte als root ausfuehren (sudo bash install.sh)."
command -v apt-get >/dev/null 2>&1 || fail "Dieses Skript ist fuer Debian und Ubuntu gedacht."

# Der Modus entscheidet ueber Pakete, Bauen und Herkunft der JARs.
if [[ -f "${PAKET}/${BOT_JAR}" ]]; then
    MODUS="paket"
    LAVALINK_VORLAGE="${PAKET}/lavalink-application.yml"
    for datei in "${BRAIN_JAR}" "Lavalink.jar" "lavalink-application.yml"; do
        [[ -f "${PAKET}/${datei}" ]] || fail "${datei} fehlt im Paket. Ist das Archiv vollstaendig entpackt?"
    done
elif [[ -f "${PROJECT_ROOT}/pom.xml" ]]; then
    MODUS="quelle"
    LAVALINK_VORLAGE="${PROJECT_ROOT}/deploy/debian/lavalink-application.yml"
    [[ -f "$LAVALINK_VORLAGE" ]] || fail "deploy/debian/lavalink-application.yml fehlt."
else
    fail "Weder JARs noch pom.xml gefunden. Das Skript gehoert in ein entpacktes Paket oder in deploy/ des Projekts."
fi

cat <<'KOPF'

  ------------------------------------------------------------------
   Einrichtung des Discord-Bots
  ------------------------------------------------------------------

  Abgefragt wird der Reihe nach:

    1. Datenbank          lokal oder auf einem anderen Server
    2. Discord            Bot-Token und Anmeldung ueber Discord
    3. Weboberflaeche     Port, Adresse, Reverse Proxy
    4. Audio              Lavalink
    5. Zusatzdienste      Music-Brain und Sprachmodell

  Mit Strg+C kann jederzeit abgebrochen werden. Bis zum letzten
  Abschnitt wird nichts dauerhaft veraendert.

KOPF

BOT_CONFIG="${BOT_DIR}/config/config.json"
BRAIN_CONFIG="${BRAIN_DIR}/config/config.json"

# ============================================================ 1. Datenbank

step "1. Datenbank"

DB_HOST_ALT="$(json_wert "$BOT_CONFIG" database.host)"
DB_PORT_ALT="$(json_wert "$BOT_CONFIG" database.port)"
DB_NAME_ALT="$(json_wert "$BOT_CONFIG" database.name)"
DB_USER_ALT="$(json_wert "$BOT_CONFIG" database.user)"
DB_PASS_ALT="$(json_wert "$BOT_CONFIG" database.password)"

info "Die Datenbank kann auf diesem Server laufen oder auf einem anderen."
info "Bei einer entfernten Datenbank wird hier nichts installiert."
ask_yes_no DB_LOKAL "MariaDB auf diesem Server installieren?" \
    "$([[ -z "$DB_HOST_ALT" || "$DB_HOST_ALT" == "127.0.0.1" || "$DB_HOST_ALT" == "localhost" ]] && echo j || echo n)"

if [[ "$DB_LOKAL" == "ja" ]]; then
    DB_HOST="127.0.0.1"
    DB_PORT="3306"
else
    ask DB_HOST "Adresse des Datenbankservers" "${DB_HOST_ALT:-}"
    ask_port DB_PORT "Port" "${DB_PORT_ALT:-3306}"
fi

ask DB_NAME "Name der Datenbank" "${DB_NAME_ALT:-discordbot}"
ask DB_USER "Benutzername"       "${DB_USER_ALT:-discordbot}"

if [[ -n "$DB_PASS_ALT" ]]; then
    ask_secret DB_PASS "Passwort" "$DB_PASS_ALT"
else
    ask_yes_no DB_PASS_AUTO "Passwort automatisch erzeugen?" "j"
    if [[ "$DB_PASS_AUTO" == "ja" ]]; then
        DB_PASS="$(zufallswort)"
        info "Passwort erzeugt. Es steht spaeter in ${BOT_CONFIG}."
    else
        ask_secret DB_PASS "Passwort"
    fi
fi

# ============================================================ 2. Discord

step "2. Discord"

TOKEN_ALT="$(json_wert "$BOT_CONFIG" bot.token)"
CLIENT_ID_ALT="$(json_wert "$BOT_CONFIG" webinterface.discord_client_id)"
CLIENT_SECRET_ALT="$(json_wert "$BOT_CONFIG" webinterface.discord_client_secret)"

info "Alle Werte stammen aus dem Discord Developer Portal:"
info "https://discord.com/developers/applications"
ask_secret BOT_TOKEN "Bot-Token" "$TOKEN_ALT"
ask CLIENT_ID "Client-ID der Anwendung" "${CLIENT_ID_ALT:-}"
ask_secret CLIENT_SECRET "Client-Secret" "$CLIENT_SECRET_ALT"

# ============================================================ 3. Web

step "3. Weboberflaeche"

WEB_PORT_ALT="$(json_wert "$BOT_CONFIG" webinterface.port)"
BASE_URL_ALT="$(json_wert "$BOT_CONFIG" webinterface.base_url)"

ask_port WEB_PORT "Port der Weboberflaeche" "${WEB_PORT_ALT:-8080}"
info "Die Adresse muss von aussen erreichbar sein und exakt so im"
info "Discord Developer Portal als Redirect hinterlegt werden."
ask BASE_URL "Oeffentliche Adresse" "${BASE_URL_ALT:-http://$(hostname -I | awk '{print $1}')}"
BASE_URL="${BASE_URL%/}"
REDIRECT_URI="${BASE_URL}/auth/discord/callback"
info "Redirect-URI: ${REDIRECT_URI}"

ask_yes_no NGINX_INSTALL "nginx als Reverse Proxy auf Port 80 einrichten?" "j"

# ============================================================ 4. Audio

step "4. Audio"

LAVALINK_PASS_ALT=""
if [[ -f "${LAVALINK_DIR}/application.yml" ]]; then
    LAVALINK_PASS_ALT="$(grep -oP '^\s*password:\s*"?\K[^"\r]+' "${LAVALINK_DIR}/application.yml" | head -1 || true)"
fi

ask_yes_no LAVALINK_LOKAL "Lavalink auf diesem Server installieren?" "j"
if [[ "$LAVALINK_LOKAL" == "ja" ]]; then
    LAVALINK_URI="http://127.0.0.1:2333"
    if [[ -n "$LAVALINK_PASS_ALT" ]]; then
        LAVALINK_PASS="$LAVALINK_PASS_ALT"
        info "Vorhandenes Lavalink-Passwort wird uebernommen."
    else
        LAVALINK_PASS="$(zufallswort)"
        info "Lavalink-Passwort erzeugt."
    fi
else
    ask LAVALINK_URI "Adresse des Lavalink-Knotens" "http://127.0.0.1:2333"
    ask_secret LAVALINK_PASS "Lavalink-Passwort" "$LAVALINK_PASS_ALT"
fi

# ============================================================ 5. Zusatz

step "5. Zusatzdienste"

info "Music-Brain schlaegt Titel fuer das AI-Radio vor."
info "Es laeuft nur auf 127.0.0.1 und ist von aussen nicht erreichbar."
ask_yes_no BRAIN_INSTALL "Music-Brain mitinstallieren?" "j"

info "Fuer KI-Chat und AI-Radio wird ein Sprachmodell benoetigt."
info "Ohne Modell bleiben beide Funktionen einfach aus."
ask_yes_no LLM_NUTZEN "Ein Sprachmodell anbinden?" "n"
OLLAMA_INSTALL="nein"
if [[ "$LLM_NUTZEN" == "ja" ]]; then
    info "Laeuft schon ein Modellserver, wird nur die Adresse eingetragen."
    info "Sonst kann Ollama hier mitinstalliert werden - dafuer sollten"
    info "mindestens 8 GB RAM frei sein, sonst wird es sehr langsam."
    ask_yes_no OLLAMA_INSTALL "Ollama auf diesem Server installieren?" "n"
    if [[ "$OLLAMA_INSTALL" == "ja" ]]; then
        LLM_URL="http://127.0.0.1:11434"
        ask LLM_MODEL "Welches Modell laden?" "phi3.5"
    else
        ask LLM_URL   "Adresse des Modellservers (Ollama)" "$(json_wert "$BOT_CONFIG" llm.ollama_url)"
        LLM_URL="${LLM_URL:-http://127.0.0.1:11434}"
        ask LLM_MODEL "Modellname" "$(json_wert "$BOT_CONFIG" llm.model)"
        LLM_MODEL="${LLM_MODEL:-phi-3.5-mini-instruct}"
    fi
else
    LLM_URL="http://127.0.0.1:11434"
    LLM_MODEL="phi-3.5-mini-instruct"
fi

# ============================================================ Zusammenfassung

step "Zusammenfassung"

printf '    %-24s %s\n' "Modus"          "$([[ "$MODUS" == "quelle" ]] && echo "aus Quellcode bauen" || echo "fertiges Paket, kein Build")"
printf '    %-24s %s\n' "Datenbank"      "${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME} ($([[ "$DB_LOKAL" == "ja" ]] && echo "lokal, wird installiert" || echo "entfernt"))"
printf '    %-24s %s\n' "Weboberflaeche" "${BASE_URL} (Port ${WEB_PORT})"
printf '    %-24s %s\n' "Reverse Proxy"  "$([[ "$NGINX_INSTALL" == "ja" ]] && echo "nginx auf Port 80" || echo "keiner")"
printf '    %-24s %s\n' "Lavalink"       "${LAVALINK_URI} ($([[ "$LAVALINK_LOKAL" == "ja" ]] && echo "aus dem Paket" || echo "entfernt"))"
printf '    %-24s %s\n' "Music-Brain"    "$([[ "$BRAIN_INSTALL" == "ja" ]] && echo "Port 8091, nur lokal" || echo "nicht installiert")"
printf '    %-24s %s\n' "Sprachmodell"   "$([[ "$LLM_NUTZEN" == "ja" ]] && echo "${LLM_MODEL} auf ${LLM_URL}$([[ "$OLLAMA_INSTALL" == "ja" ]] && echo " (Ollama wird installiert)")" || echo "keines")"
echo
ask_yes_no WEITER "Jetzt installieren?" "j"
[[ "$WEITER" == "ja" ]] || { info "Abgebrochen. Es wurde nichts veraendert."; exit 0; }

# ============================================================ Pakete

step "Pakete installieren"

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl python3

if [[ "$MODUS" == "quelle" ]]; then
    # Ein JRE reicht nicht: ohne javac faellt Maven auf einen Ersatzcompiler
    # zurueck, der Java 21 nicht kennt, und bricht mit "Releaseversion 17
    # nicht unterstuetzt" ab.
    apt-get install -y -qq openjdk-21-jdk-headless maven
else
    apt-get install -y -qq openjdk-21-jre-headless
fi

if [[ "$DB_LOKAL" == "ja" ]]; then
    apt-get install -y -qq mariadb-server
else
    apt-get install -y -qq mariadb-client
fi

[[ "$NGINX_INSTALL" == "ja" ]] && apt-get install -y -qq nginx

command -v java >/dev/null 2>&1 || fail "Java wurde nicht installiert."
info "Java:  $(java -version 2>&1 | head -1)"
if [[ "$MODUS" == "quelle" ]]; then
    command -v javac >/dev/null 2>&1 || fail "Kein JDK gefunden. Maven kann ohne javac nicht bauen."
    info "Maven: $(mvn -v 2>/dev/null | head -1)"
fi

# ============================================================ Benutzer

step "Dienstbenutzer anlegen"

id -u "$BOT_USER" >/dev/null 2>&1 || \
    useradd --system --home "$BOT_DIR" --create-home --shell /usr/sbin/nologin "$BOT_USER"
if [[ "$LAVALINK_LOKAL" == "ja" ]]; then
    id -u "$LAVALINK_USER" >/dev/null 2>&1 || \
        useradd --system --home "$LAVALINK_DIR" --create-home --shell /usr/sbin/nologin "$LAVALINK_USER"
fi
info "Bereit."

# ============================================================ Datenbank

step "Datenbank einrichten"

if [[ "$DB_LOKAL" == "ja" ]]; then
    systemctl enable --now mariadb >/dev/null 2>&1 || true
    # Der Zugriff laeuft ueber den Unix-Socket als root, deshalb ist hier
    # kein Datenbankpasswort noetig.
    "$(db_client)" <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL
    info "Datenbank ${DB_NAME} und Benutzer ${DB_USER} sind eingerichtet."
else
    # --skip-ssl, weil viele interne MariaDB-Server ohne TLS laufen und der
    # Client sonst mit "SSL is required" abbricht.
    if "$(db_client)" --skip-ssl -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASS" \
            -e "USE \`${DB_NAME}\`;" >/dev/null 2>&1; then
        info "Verbindung zur entfernten Datenbank steht."
    else
        warn "Die Datenbank ist nicht erreichbar oder die Zugangsdaten stimmen nicht."
        warn "Auf dem Datenbankserver noetig:"
        warn "  CREATE DATABASE \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
        warn "  CREATE USER '${DB_USER}'@'%' IDENTIFIED BY '<passwort>';"
        warn "  GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'%';"
        ask_yes_no TROTZDEM "Trotzdem fortfahren?" "n"
        [[ "$TROTZDEM" == "ja" ]] || fail "Abgebrochen."
    fi
fi

# ============================================================ Bauen

if [[ "$MODUS" == "quelle" ]]; then
    step "Anwendung bauen"
    info "Das dauert beim ersten Mal einige Minuten."
    ( cd "$PROJECT_ROOT" && mvn -q -B -DskipTests package ) || fail "Der Build des Bots ist fehlgeschlagen."
    [[ -f "${PROJECT_ROOT}/target/${BOT_JAR}" ]] || fail "target/${BOT_JAR} wurde nicht erzeugt."
    BOT_QUELLE="${PROJECT_ROOT}/target/${BOT_JAR}"
    info "Bot gebaut."

    if [[ "$BRAIN_INSTALL" == "ja" ]]; then
        ( cd "${PROJECT_ROOT}/music-brain-service" && mvn -q -B -DskipTests package ) \
            || fail "Der Build von Music-Brain ist fehlgeschlagen."
        BRAIN_QUELLE="${PROJECT_ROOT}/music-brain-service/target/${BRAIN_JAR}"
        info "Music-Brain gebaut."
    fi
else
    BOT_QUELLE="${PAKET}/${BOT_JAR}"
    BRAIN_QUELLE="${PAKET}/${BRAIN_JAR}"
fi

# ============================================================ Dateien

step "Dateien einspielen"

mkdir -p "${BOT_DIR}/config"

# Der Quellstand bleibt liegen, damit spaetere Aktualisierungen ueber
# deploy/update.sh laufen koennen.
if [[ "$MODUS" == "quelle" && "$(readlink -f "$PROJECT_ROOT")" != "$(readlink -f "$SRC_DIR")" ]]; then
    mkdir -p "$SRC_DIR"
    rm -rf "${SRC_DIR:?}/"*
    cp -a "${PROJECT_ROOT}/." "${SRC_DIR}/"
fi
install -m 0644 "$BOT_QUELLE" "${BOT_DIR}/${BOT_JAR}"


cat > "$BOT_CONFIG" <<JSON
{
  "bot": {
    "token": "${BOT_TOKEN}",
    "activity": "",
    "status": "ONLINE"
  },
  "database": {
    "host": "${DB_HOST}",
    "port": ${DB_PORT},
    "name": "${DB_NAME}",
    "user": "${DB_USER}",
    "password": "${DB_PASS}"
  },
  "bot_id": 1,
  "deployment": {
    "key": "local",
    "display_name": "Hauptinstanz"
  },
  "webinterface": {
    "port": ${WEB_PORT},
    "base_url": "${BASE_URL}",
    "discord_client_id": "${CLIENT_ID}",
    "discord_client_secret": "${CLIENT_SECRET}",
    "redirect_uri": "${REDIRECT_URI}"
  },
  "lavalink": {
    "name": "main-node",
    "uri": "${LAVALINK_URI}",
    "password": "${LAVALINK_PASS}",
    "http_timeout_ms": 10000,
    "resume_enabled": true,
    "resume_timeout_seconds": 60
  },
  "llm": {
    "provider": "ollama",
    "ollama_url": "${LLM_URL}",
    "openai_base_url": "http://127.0.0.1:1234",
    "api_key": "",
    "model": "${LLM_MODEL}",
    "available_models": ["${LLM_MODEL}"],
    "timeout_ms": 30000,
    "temperature": 0.7,
    "max_tokens": 220,
    "history_turns": 6,
    "system_message": "Du bist ein hilfreicher Discord-Assistent. Antworte kurz, freundlich und auf Deutsch.",
    "tools_enabled": true
  },
  "music_brain": {
    "base_url": "http://127.0.0.1:8091",
    "request_timeout_ms": 15000,
    "batch_size": 12
  },
  "mcp": {
    "enabled": false,
    "token": ""
  }
}
JSON

chown -R "${BOT_USER}:${BOT_USER}" "$BOT_DIR"
# Der Token und beide Discord-Geheimnisse stehen hier im Klartext.
chmod 0600 "$BOT_CONFIG"
info "Konfiguration geschrieben: ${BOT_CONFIG}"

if [[ "$BRAIN_INSTALL" == "ja" ]]; then
    mkdir -p "${BRAIN_DIR}/config"
    install -m 0644 "$BRAIN_QUELLE" "${BRAIN_DIR}/${BRAIN_JAR}"
    cat > "$BRAIN_CONFIG" <<JSON
{
  "database": {
    "host": "${DB_HOST}",
    "port": ${DB_PORT},
    "name": "${DB_NAME}",
    "user": "${DB_USER}",
    "password": "${DB_PASS}"
  },
  "bot_id": 1,
  "listen_host": "127.0.0.1",
  "port": 8091,
  "history_days": 45,
  "batch_size": 12,
  "ollama": {
    "enabled": $([[ "$LLM_NUTZEN" == "ja" ]] && echo true || echo false),
    "url": "${LLM_URL}",
    "model": "${LLM_MODEL}",
    "timeout_ms": 30000
  }
}
JSON
    chown -R "${BOT_USER}:${BOT_USER}" "$BRAIN_DIR"
    chmod 0600 "$BRAIN_CONFIG"
    info "Music-Brain eingerichtet."
fi

# ============================================================ Lavalink

if [[ "$LAVALINK_LOKAL" == "ja" ]]; then
    step "Lavalink einrichten"
    mkdir -p "$LAVALINK_DIR"
    if [[ ! -f "${LAVALINK_DIR}/Lavalink.jar" ]]; then
        if [[ -f "${PAKET}/Lavalink.jar" ]]; then
            install -m 0644 "${PAKET}/Lavalink.jar" "${LAVALINK_DIR}/Lavalink.jar"
            info "Lavalink aus dem Paket eingespielt."
        else
            curl -fsSL "$LAVALINK_URL" -o "${LAVALINK_DIR}/Lavalink.jar" \
                || fail "Lavalink konnte nicht geladen werden."
            info "Lavalink geladen."
        fi
    else
        info "Vorhandenes Lavalink.jar bleibt."
    fi

    if [[ ! -f "${LAVALINK_DIR}/application.yml" ]]; then
        install -m 0640 "$LAVALINK_VORLAGE" "${LAVALINK_DIR}/application.yml"
    fi
    # Passwort in die Konfiguration eintragen, ohne den Rest anzufassen.
    python3 - "$LAVALINK_DIR/application.yml" "$LAVALINK_PASS" <<'PY'
import re, sys
pfad, passwort = sys.argv[1], sys.argv[2]
text = open(pfad, encoding="utf-8").read()
neu, treffer = re.subn(r'(?m)^(\s*password:\s*).*$', lambda m: m.group(1) + '"' + passwort + '"', text, count=1)
if treffer:
    open(pfad, "w", encoding="utf-8").write(neu)
PY
    chown -R "${LAVALINK_USER}:${LAVALINK_USER}" "$LAVALINK_DIR"
    chmod 0640 "${LAVALINK_DIR}/application.yml"
    info "Lavalink konfiguriert."
fi

# ============================================================ Ollama

if [[ "$OLLAMA_INSTALL" == "ja" ]]; then
    step "Sprachmodell einrichten"
    if command -v ollama >/dev/null 2>&1; then
        info "Ollama ist bereits installiert."
    else
        # Das offizielle Installationsskript legt auch gleich den
        # systemd-Dienst an. Ohne Internet schlaegt es fehl - dann laeuft
        # der Rest trotzdem weiter, nur ohne KI.
        if curl -fsSL https://ollama.com/install.sh | sh; then
            info "Ollama installiert."
        else
            warn "Ollama konnte nicht installiert werden. KI-Chat und AI-Radio bleiben aus."
            OLLAMA_INSTALL="nein"
        fi
    fi

    if [[ "$OLLAMA_INSTALL" == "ja" ]]; then
        systemctl enable --now ollama >/dev/null 2>&1 || true
        sleep 5
        info "Lade Modell ${LLM_MODEL} - das dauert je nach Groesse einige Minuten..."
        if ollama pull "$LLM_MODEL"; then
            info "Modell bereit."
        else
            warn "Das Modell konnte nicht geladen werden. Spaeter nachholen mit: ollama pull ${LLM_MODEL}"
        fi
    fi
fi

# ============================================================ Dienste

step "Dienste einrichten"

schreibe_unit() {
    local name="$1" beschreibung="$2" benutzer="$3" verzeichnis="$4" befehl="$5" nach="$6"
    cat > "/etc/systemd/system/${name}" <<UNIT
[Unit]
Description=${beschreibung}
After=network-online.target ${nach}
Wants=network-online.target ${nach}

[Service]
Type=simple
User=${benutzer}
Group=${benutzer}
WorkingDirectory=${verzeichnis}
ExecStart=${befehl}
Restart=always
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
UNIT
}

if [[ "$LAVALINK_LOKAL" == "ja" ]]; then
    schreibe_unit "lavalink.service" "Lavalink Audio Node" "$LAVALINK_USER" \
        "$LAVALINK_DIR" "/usr/bin/java -jar ${LAVALINK_DIR}/Lavalink.jar" ""
fi

schreibe_unit "discordbot.service" "Discord Bot" "$BOT_USER" \
    "$BOT_DIR" "/usr/bin/java -jar ${BOT_DIR}/${BOT_JAR}" \
    "$([[ "$LAVALINK_LOKAL" == "ja" ]] && echo lavalink.service || echo '')"

if [[ "$BRAIN_INSTALL" == "ja" ]]; then
    schreibe_unit "discordbot-music-brain.service" "Discord Bot Music Brain" "$BOT_USER" \
        "$BRAIN_DIR" "/usr/bin/java -Xms128m -Xmx384m -jar ${BRAIN_DIR}/${BRAIN_JAR}" ""
fi

systemctl daemon-reload
info "Units geschrieben."

# ============================================================ nginx

if [[ "$NGINX_INSTALL" == "ja" ]]; then
    step "Reverse Proxy einrichten"
    cat > /etc/nginx/sites-available/discordbot <<CONF
server {
    listen 80;
    listen [::]:80;
    server_name _;

    client_max_body_size 20m;

    location / {
        proxy_pass http://127.0.0.1:${WEB_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
CONF
    ln -sf /etc/nginx/sites-available/discordbot /etc/nginx/sites-enabled/discordbot
    rm -f /etc/nginx/sites-enabled/default
    nginx -t >/dev/null 2>&1 || fail "Die nginx-Konfiguration ist fehlerhaft."
    systemctl enable --now nginx >/dev/null 2>&1 || true
    systemctl reload nginx
    info "nginx leitet Port 80 auf ${WEB_PORT} weiter."
fi

# ============================================================ Start

step "Dienste starten"

starte() {
    local dienst="$1"
    systemctl enable "$dienst" >/dev/null 2>&1 || true
    systemctl restart "$dienst"
}

[[ "$LAVALINK_LOKAL" == "ja" ]] && { starte lavalink.service; sleep 8; }
[[ "$BRAIN_INSTALL"  == "ja" ]] && starte discordbot-music-brain.service
starte discordbot.service

sleep 12

FEHLER=0
for dienst in lavalink.service discordbot-music-brain.service discordbot.service; do
    [[ -f "/etc/systemd/system/${dienst}" ]] || continue
    if systemctl is-active --quiet "$dienst"; then
        printf '    \033[1;32mOK\033[0m    %s\n' "$dienst"
    else
        printf '    \033[1;31mFEHLER\033[0m %s\n' "$dienst"
        FEHLER=1
    fi
done

if [[ "$FEHLER" -eq 0 ]]; then
    if curl -fsS -o /dev/null --max-time 20 "http://127.0.0.1:${WEB_PORT}/"; then
        info "Die Weboberflaeche antwortet."
    else
        warn "Die Weboberflaeche antwortet noch nicht. Der erste Start dauert laenger."
    fi
fi

# ============================================================ Abschluss

step "Fertig"

cat <<ENDE

    Weboberflaeche   ${BASE_URL}
    Adminbereich     ${BASE_URL}/admin
    Serverpanel      ${BASE_URL}/dashboard

    Noch zu tun:

      1. Im Discord Developer Portal unter OAuth2 diese Redirect-URL
         eintragen:  ${REDIRECT_URI}
      2. Den Bot auf einen Server einladen.
      3. Einmal ${BASE_URL}/admin aufrufen. Wer die Anwendung im
         Developer Portal besitzt, wird dabei automatisch als
         Eigentuemer eingetragen und kann weitere Admins anlegen.
      4. KI-Chat und AI-Radio sind je Server gesperrt. Freigabe im
         Adminbereich unter Server.

    Logs      journalctl -u discordbot -f
    Neustart  systemctl restart discordbot
    Update    cd ${SRC_DIR} && bash deploy/update.sh

ENDE

[[ "$FEHLER" -eq 0 ]] || fail "Mindestens ein Dienst laeuft nicht. Bitte die Logs pruefen."
