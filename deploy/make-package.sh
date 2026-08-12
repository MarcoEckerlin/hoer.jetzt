#!/usr/bin/env bash
#
# Schnuert aus den gebauten JARs ein fertiges Installationspaket.
#
#   bash deploy/make-package.sh
#
# Ergebnis: discordbot-stack-<datum>.tar.gz - ein Archiv, das auf einen
# frischen Server kopiert, entpackt und mit install.sh gestartet wird.
# Auf dem Zielserver wird nichts gebaut und kein JDK gebraucht.
#
# Wird ohne vorhandene JARs aufgerufen, baut das Skript sie zuerst - dafuer
# braucht es dann hier (nicht auf dem Zielserver) Maven und ein JDK 21.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOT_JAR="DiscordBot-alpha-1.0.jar"
BRAIN_JAR="discordbot-music-brain.jar"
LAVALINK_URL="https://github.com/lavalink-devs/Lavalink/releases/latest/download/Lavalink.jar"

AUSGABE="${1:-${PROJECT_ROOT}}"
NAME="discordbot-stack"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

cd "$PROJECT_ROOT"

# ------------------------------------------------------------ JARs besorgen

step "JARs pruefen"

# Bevorzugt wird eine laufende Installation: die JARs dort sind die, die
# nachweislich starten. Sonst target/, sonst wird gebaut.
finde_jar() {
    local name="$1" installiert="$2" gebaut="$3"
    if [[ -f "$installiert" ]]; then
        echo "$installiert"
    elif [[ -f "$gebaut" ]]; then
        echo "$gebaut"
    else
        echo ""
    fi
}

BOT_QUELLE="$(finde_jar "$BOT_JAR" "/opt/discordbot/${BOT_JAR}" "${PROJECT_ROOT}/target/${BOT_JAR}")"
BRAIN_QUELLE="$(finde_jar "$BRAIN_JAR" "/opt/discordbot-music-brain/${BRAIN_JAR}" "${PROJECT_ROOT}/music-brain-service/target/${BRAIN_JAR}")"

if [[ -z "$BOT_QUELLE" || -z "$BRAIN_QUELLE" ]]; then
    step "Fehlende JARs bauen"
    command -v mvn >/dev/null 2>&1 || fail "Maven fehlt und es gibt keine gebauten JARs."
    command -v javac >/dev/null 2>&1 || fail "Ein JDK fehlt (nur JRE vorhanden)."
    [[ -n "$BOT_QUELLE" ]]   || { mvn -q -B -DskipTests package; BOT_QUELLE="${PROJECT_ROOT}/target/${BOT_JAR}"; }
    [[ -n "$BRAIN_QUELLE" ]] || { ( cd music-brain-service && mvn -q -B -DskipTests package ); \
                                  BRAIN_QUELLE="${PROJECT_ROOT}/music-brain-service/target/${BRAIN_JAR}"; }
fi

info "Bot:         ${BOT_QUELLE}"
info "Music-Brain: ${BRAIN_QUELLE}"

# ------------------------------------------------------------ Lavalink

step "Lavalink besorgen"

LAVALINK_QUELLE=""
for kandidat in "/opt/lavalink/Lavalink.jar" "${PROJECT_ROOT}/Lavalink.jar"; do
    [[ -f "$kandidat" ]] && { LAVALINK_QUELLE="$kandidat"; break; }
done

if [[ -z "$LAVALINK_QUELLE" ]]; then
    info "Lade Lavalink von GitHub..."
    curl -fsSL "$LAVALINK_URL" -o "${PROJECT_ROOT}/Lavalink.jar" || fail "Lavalink konnte nicht geladen werden."
    LAVALINK_QUELLE="${PROJECT_ROOT}/Lavalink.jar"
fi
info "Lavalink:    ${LAVALINK_QUELLE}"

# ------------------------------------------------------------ Zusammenstellen

step "Paket zusammenstellen"

BAU="$(mktemp -d)"
trap 'rm -rf "$BAU"' EXIT
ZIEL="${BAU}/${NAME}"
mkdir -p "$ZIEL"

install -m 0644 "$BOT_QUELLE"      "${ZIEL}/${BOT_JAR}"
install -m 0644 "$BRAIN_QUELLE"    "${ZIEL}/${BRAIN_JAR}"
install -m 0644 "$LAVALINK_QUELLE" "${ZIEL}/Lavalink.jar"
install -m 0644 "${PROJECT_ROOT}/deploy/debian/lavalink-application.yml" "${ZIEL}/lavalink-application.yml"
install -m 0755 "${PROJECT_ROOT}/deploy/install.sh" "${ZIEL}/install.sh"

# Die Dokumentation faehrt mit, damit auf dem Server nachschlagbar ist,
# was das Skript gerade tut.
# compgen statt -d: ein leeres Verzeichnis laesst das cp mit einem
# unaufgeloesten Glob scheitern und reisst wegen set -e alles mit.
if compgen -G "${PROJECT_ROOT}/Kontext/Installation/*.md" >/dev/null; then
    mkdir -p "${ZIEL}/dokumentation"
    cp "${PROJECT_ROOT}/Kontext/Installation/"*.md "${ZIEL}/dokumentation/"
    info "Dokumentation liegt bei."
else
    warn "Keine Dokumentation gefunden - Paket ohne dokumentation/."
fi

cat > "${ZIEL}/LIESMICH.txt" <<'TXT'
Discord-Bot - fertiges Installationspaket
=========================================

Auf einem frischen Debian- oder Ubuntu-Server als root:

    tar -xzf discordbot-stack-*.tar.gz
    cd discordbot-stack
    bash install.sh

Es wird nichts gebaut. Alle drei JARs liegen bei, das Skript installiert
nur noch Java-Laufzeit, Datenbank und die Dienste.

Bereitlegen:

  - Bot-Token, Client-ID und Client-Secret aus dem Discord Developer Portal
  - dort ausserdem "Server Members Intent" und "Message Content Intent"
    einschalten
  - falls die Datenbank auf einem anderen Server liegt: Adresse, Name und
    Zugangsdaten

Nach der Installation:

  1. Die vom Skript ausgegebene Redirect-URL im Developer Portal eintragen
  2. Bot einladen mit permissions=1101960178806
     und scope=bot%20applications.commands
  3. Einmal /admin aufrufen - der Eigentuemer der Anwendung wird dabei
     automatisch als Administrator eingetragen
  4. KI-Chat und AI-Radio je Server im Adminbereich freischalten

Mehr in dokumentation/ - bei Problemen 06-fehlersuche.md.
TXT

# Zeitstempel kommt von aussen, damit zwei Laeufe unterscheidbar bleiben.
STEMPEL="$(date +%Y%m%d)"
ARCHIV="${AUSGABE}/${NAME}-${STEMPEL}.tar.gz"

tar -czf "$ARCHIV" -C "$BAU" "$NAME"

step "Fertig"
info "$ARCHIV"
info "$(du -h "$ARCHIV" | cut -f1)"
echo
info "Inhalt:"
tar -tzf "$ARCHIV" | sed 's/^/      /'
echo
info "Auf dem Zielserver:"
info "    tar -xzf $(basename "$ARCHIV") && cd ${NAME} && bash install.sh"
echo
