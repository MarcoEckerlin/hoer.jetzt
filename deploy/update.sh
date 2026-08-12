#!/usr/bin/env bash
#
# Wird DIREKT AUF DEM SERVER ausgefuehrt und baut den Bot aus einem
# entpackten Quellverzeichnis neu.
#
#   bash deploy/update.sh
#
# Erwartet, dass das aktuelle Verzeichnis das Projektwurzelverzeichnis ist
# (also die pom.xml enthaelt).
#
# Der Ablauf ist bewusst defensiv:
#   - vorhandene JARs und die Lavalink-Konfiguration werden vorher gesichert
#   - das Lavalink-Passwort aus der laufenden Installation bleibt erhalten
#   - startet ein Dienst nicht, wird automatisch zurueckgerollt

set -euo pipefail

BOT_DIR="/opt/discordbot"
BRAIN_DIR="/opt/discordbot-music-brain"
LAVALINK_DIR="/opt/lavalink"

BOT_JAR="DiscordBot-alpha-1.0.jar"
BRAIN_JAR="discordbot-music-brain.jar"

BACKUP_ROOT="/opt/discordbot-backups"
BACKUP_DIR="${BACKUP_ROOT}/$(date +%Y%m%d-%H%M%S)"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f pom.xml ]] || fail "pom.xml nicht gefunden. Bitte im Projektwurzelverzeichnis ausfuehren."
[[ "$(id -u)" -eq 0 ]] || fail "Bitte als root ausfuehren."

PROJECT_ROOT="$(pwd)"

# ------------------------------------------------------------ Werkzeuge

step "Build-Werkzeuge pruefen"

export DEBIAN_FRONTEND=noninteractive
APT_UPDATED=0

apt_install() {
    if [[ "${APT_UPDATED}" -eq 0 ]]; then
        apt-get update -qq
        APT_UPDATED=1
    fi
    apt-get install -y -qq "$@"
}

# Auf dem Server war nur ein JRE installiert. Maven greift dann auf einen
# Ersatz-Compiler zurueck, der Java 21 nicht kennt, und bricht mit
# "Releaseversion 17 nicht unterstuetzt" ab. Deshalb wird hier gezielt auf ein
# vorhandenes javac geprueft, nicht nur auf java.
if ! command -v javac >/dev/null 2>&1 && [[ ! -x /usr/lib/jvm/java-21-openjdk-amd64/bin/javac ]]; then
    info "Kein JDK gefunden (nur JRE) - installiere openjdk-21-jdk-headless..."
    apt_install openjdk-21-jdk-headless
fi

if [[ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/javac ]]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
    export PATH="${JAVA_HOME}/bin:${PATH}"
fi

command -v javac >/dev/null 2>&1 || fail "Es ist weiterhin kein javac verfuegbar."
info "Java:  $(java -version 2>&1 | head -1)"
info "javac: $(javac -version 2>&1)"

if ! command -v mvn >/dev/null 2>&1; then
    info "Maven fehlt, wird installiert..."
    apt_install maven
fi
info "Maven: $(mvn -v 2>&1 | head -1)"

# ------------------------------------------------------------ Build

step "Hauptanwendung bauen"
cd "${PROJECT_ROOT}"
mvn -B -DskipTests clean package

step "Music-Brain-Service bauen"
cd "${PROJECT_ROOT}/music-brain-service"
mvn -B -DskipTests clean package
cd "${PROJECT_ROOT}"

NEW_BOT_JAR="${PROJECT_ROOT}/target/${BOT_JAR}"
[[ -f "${NEW_BOT_JAR}" ]] || fail "Bot-JAR wurde nicht erzeugt."

NEW_BRAIN_JAR=""
for candidate in "${PROJECT_ROOT}/music-brain-service/target/${BRAIN_JAR}" \
                 "${PROJECT_ROOT}/music-brain-service/target/${BRAIN_JAR}.jar"; do
    [[ -f "${candidate}" ]] && NEW_BRAIN_JAR="${candidate}" && break
done
[[ -n "${NEW_BRAIN_JAR}" ]] || fail "Music-Brain-JAR wurde nicht erzeugt."

info "Bot-JAR:   $(du -h "${NEW_BOT_JAR}" | cut -f1)"
info "Brain-JAR: $(du -h "${NEW_BRAIN_JAR}" | cut -f1)"

# ------------------------------------------------------------ Sicherung

step "Aktuelle Installation sichern"
mkdir -p "${BACKUP_DIR}"
for f in "${BOT_DIR}/${BOT_JAR}" "${BRAIN_DIR}/${BRAIN_JAR}" "${LAVALINK_DIR}/application.yml"; do
    [[ -f "$f" ]] && cp -a "$f" "${BACKUP_DIR}/" && info "gesichert: $f"
done
# Nur die letzten zehn Sicherungen behalten.
( cd "${BACKUP_ROOT}" && ls -1dt */ 2>/dev/null | tail -n +11 | xargs -r rm -rf ) || true

# ------------------------------------------------------------ Einspielen

step "Dienste stoppen"
systemctl stop discordbot.service discordbot-music-brain.service || true

step "Neue Artefakte einspielen"
install -m 0644 -o discordbot -g discordbot "${NEW_BOT_JAR}"   "${BOT_DIR}/${BOT_JAR}"
install -m 0644 -o discordbot -g discordbot "${NEW_BRAIN_JAR}" "${BRAIN_DIR}/${BRAIN_JAR}"

step "Lavalink-Konfiguration aktualisieren"
TEMPLATE="${PROJECT_ROOT}/package/discordbot-stack/lavalink/application.yml.template"
TARGET="${LAVALINK_DIR}/application.yml"

if [[ -f "${TEMPLATE}" ]]; then
    # Das bestehende Passwort muss erhalten bleiben - in der Vorlage steht nur
    # der Platzhalter, ein blindes Ueberschreiben wuerde die Verbindung
    # zwischen Bot und Lavalink kappen.
    CURRENT_PASSWORD="$(grep -oP '^\s*password:\s*"?\K[^"]*' "${TARGET}" 2>/dev/null | head -1 || true)"
    cp "${TEMPLATE}" "${TARGET}.new"

    if [[ -n "${CURRENT_PASSWORD}" ]]; then
        python3 - "${TARGET}.new" "${CURRENT_PASSWORD}" <<'PY'
import sys, re
path, password = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
text = re.sub(r'(?m)^(\s*)password:.*$', lambda m: f'{m.group(1)}password: "{password}"', text, count=1)
open(path, "w", encoding="utf-8").write(text)
PY
        info "Bestehendes Lavalink-Passwort uebernommen."
    else
        info "Kein Passwort in der alten Konfiguration gefunden - Vorlagenwert bleibt."
    fi

    mv "${TARGET}.new" "${TARGET}"
    chown lavalink:lavalink "${TARGET}"
    chmod 0644 "${TARGET}"
fi

# ------------------------------------------------------------ Start

step "Dienste starten"
systemctl restart lavalink.service
sleep 8
systemctl restart discordbot.service
systemctl restart discordbot-music-brain.service || true
sleep 15

step "Status"
FAILED=0
for service in lavalink discordbot discordbot-music-brain; do
    if systemctl is-active --quiet "${service}.service"; then
        info "OK      ${service}.service"
    else
        info "FEHLER  ${service}.service"
        [[ "${service}" != "discordbot-music-brain" ]] && FAILED=1
    fi
done

if [[ "${FAILED}" -eq 1 ]]; then
    step "Logs"
    journalctl -u discordbot.service -n 50 --no-pager || true
    journalctl -u lavalink.service -n 25 --no-pager || true

    step "Rollback"
    systemctl stop discordbot.service || true
    [[ -f "${BACKUP_DIR}/${BOT_JAR}" ]]        && cp -a "${BACKUP_DIR}/${BOT_JAR}" "${BOT_DIR}/${BOT_JAR}"
    [[ -f "${BACKUP_DIR}/${BRAIN_JAR}" ]]      && cp -a "${BACKUP_DIR}/${BRAIN_JAR}" "${BRAIN_DIR}/${BRAIN_JAR}"
    [[ -f "${BACKUP_DIR}/application.yml" ]]   && cp -a "${BACKUP_DIR}/application.yml" "${TARGET}"
    chown discordbot:discordbot "${BOT_DIR}/${BOT_JAR}" "${BRAIN_DIR}/${BRAIN_JAR}" 2>/dev/null || true
    chown lavalink:lavalink "${TARGET}" 2>/dev/null || true
    systemctl restart lavalink.service
    systemctl restart discordbot.service
    fail "Deploy fehlgeschlagen - vorherige Version wurde wiederhergestellt (${BACKUP_DIR})."
fi

step "Webinterface pruefen"
sleep 3
curl -fsS -o /dev/null -w '    HTTP %{http_code} nach %{time_total}s\n' http://127.0.0.1:8080/ \
    || info "Webinterface antwortet noch nicht - nach dem Start kann das kurz dauern."

step "Fertig"
info "Sicherung: ${BACKUP_DIR}"
info "Logs:      journalctl -u discordbot -f"
