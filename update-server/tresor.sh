#!/usr/bin/env bash
#
# hoer.jetzt - der Tresor. Die Zugangsdaten, die alle Knoten teilen.
#
#   bash tresor.sh fuellen [profil]    anlegen oder ueberschreiben
#   bash tresor.sh zeigen  [profil]    nachsehen, was drin steht
#   bash tresor.sh stand               was es gibt, ohne aufzumachen
#
# Zwei Profile, und der Unterschied ist der eigentliche Gewinn gegenueber
# frueher:
#
#   voll       Datenbank, Bot-Token, Client-Secret, Sprachmodell, Lavalink.
#              Bekommt nur ein Host, auf dem der Kern laeuft.
#   lavalink   Nur das Lavalink-Passwort. Ein Audio-Knoten braucht nichts
#              weiter - und soll auch nichts weiter bekommen. Unter GitHub lag
#              auf jedem Knoten der vollstaendige Quellbaum; wer einen davon
#              aufmachte, hatte alles.
#
# Der Tresor liegt im Klartext im Auslieferungsverzeichnis. Geschuetzt ist er
# durch dasselbe wie die Abbilder: das Knoten-Passwort und eine
# freigeschaltete Adresse.
#
# Frueher war er an einen eigenen Schluessel gerichtet, den dieser Server
# nicht hatte - er konnte die Zugangsdaten also selbst nicht lesen. Das ist
# jetzt nicht mehr so. Dafuer braucht ein Knoten nur noch ein Passwort statt
# Passwort und Schluesseldatei.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "${HIER}/lib.sh"


BEFEHL="${1:-}"
PROFIL="${2:-voll}"
case "$PROFIL" in
    voll|lavalink) ;;
    *) fail "Profil muss voll oder lavalink sein." ;;
esac
ZIEL="tresor/${PROFIL}.env"

# Kein Umschlag mehr. Der Tresor liegt im Klartext im Auslieferungsverzeichnis
# und ist durch dasselbe geschuetzt wie die Abbilder: Knoten-Passwort plus
# freigeschaltete Adresse.
#
# Was damit wegfaellt: der Knoten braucht keinen zweiten Schluessel, um an
# seine Zugangsdaten zu kommen - ein Passwort reicht fuer alles.
#
# Was damit verloren geht, offen gesagt: vorher konnte dieser Server die
# Zugangsdaten selbst nicht lesen. Jetzt kann er es. Wer das zurueck will,
# braucht wieder ein Schluesselpaar und einen zweiten Schluessel auf jedem
# Knoten.

# ------------------------------------------------------------------ stand

if [[ "$BEFEHL" == "stand" ]]; then
    step "Tresor"
    for p in voll lavalink; do
        if aus_gibt_es "tresor/${p}.env"; then
            groesse="$(aus_lesen "tresor/${p}.env" | wc -c)"
            info "$(printf '%-10s %s Bytes' "$p" "$groesse")"
        else
            warn "$(printf '%-10s %s' "$p" "fehlt")"
        fi
    done
    echo
    exit 0
fi

# ------------------------------------------------------------------ zeigen

if [[ "$BEFEHL" == "zeigen" ]]; then
    aus_gibt_es "$ZIEL" || fail "Es gibt keinen Tresor fuer das Profil ${PROFIL}."


    step "Inhalt ${PROFIL}"
    if ! aus_lesen "$ZIEL" | sed 's/^/    /'; then
        fail "Lesen fehlgeschlagen."
    fi
    echo
    exit 0
fi

[[ "$BEFEHL" == "fuellen" ]] || {
    sed -n '3,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
}

# ------------------------------------------------------------------ fuellen


cat <<'KOPF'

  ------------------------------------------------------------------
   Tresor befuellen
  ------------------------------------------------------------------

  Was hier hineinkommt, bekommt jeder neue Knoten mit dem passenden
  Profil - ohne dass du es abtippen musst.

KOPF

HJ_UPDATE_HOST=""
[[ -f "${HIER}/.env" ]] && HJ_UPDATE_HOST="$(grep '^HJ_UPDATE_HOST=' "${HIER}/.env" | cut -d= -f2- || true)"

step "Allgemein"
frage HJ_UPDATE_HOST "Adresse des Update-Servers" "${HJ_UPDATE_HOST:-update.system.hoer.jetzt}"

# Kein Passwort im Tresor: das Knoten-Passwort braucht der Knoten schon, um
# ihn ueberhaupt zu holen. Hier steht nur, wohin er sich wenden soll.
INHALT="HJ_UPDATE_HOST=${HJ_UPDATE_HOST}"

step "Lavalink"
info "Dasselbe Passwort auf allen Knoten - der Kern spricht sie damit an."
geheim HJ_LAVALINK_PASSWORD "Passwort Lavalink"
INHALT="${INHALT}
HJ_LAVALINK_PASSWORD=${HJ_LAVALINK_PASSWORD}"

if [[ "$PROFIL" == "voll" ]]; then
    step "Datenbank"
    frage  HJ_DB_HOST     "Adresse"   "127.0.0.1"
    frage  HJ_DB_PORT     "Port"      "3306"
    frage  HJ_DB_NAME     "Datenbank" "discordbot"
    frage  HJ_DB_USER     "Benutzer"  "discordbot"
    geheim HJ_DB_PASSWORD "Passwort"

    step "Discord"
    geheim HJ_BOT_TOKEN             "Bot-Token"
    frage  HJ_DISCORD_CLIENT_ID     "Client-ID"
    geheim HJ_DISCORD_CLIENT_SECRET "Client-Secret"

    step "Sprachmodell"
    info "Leer lassen, wenn keines da ist - KI-Chat und AI-Radio bleiben dann aus."
    frage_leer HJ_LLM_OLLAMA_URL "Adresse"
    if [[ -n "$HJ_LLM_OLLAMA_URL" ]]; then
        [[ "$HJ_LLM_OLLAMA_URL" =~ ^https?:// ]] || HJ_LLM_OLLAMA_URL="http://${HJ_LLM_OLLAMA_URL}"
        [[ "${HJ_LLM_OLLAMA_URL#*://}" == *:* ]] || HJ_LLM_OLLAMA_URL="${HJ_LLM_OLLAMA_URL}:11434"
        HJ_LLM_OLLAMA_URL="${HJ_LLM_OLLAMA_URL%/}"
        info "-> ${HJ_LLM_OLLAMA_URL}"
        frage HJ_LLM_MODEL "Modell" "qwen3:8b"
    else
        HJ_LLM_MODEL=""
    fi

    INHALT="${INHALT}
HJ_DB_HOST=${HJ_DB_HOST}
HJ_DB_PORT=${HJ_DB_PORT}
HJ_DB_NAME=${HJ_DB_NAME}
HJ_DB_USER=${HJ_DB_USER}
HJ_DB_PASSWORD=${HJ_DB_PASSWORD}
HJ_BOT_TOKEN=${HJ_BOT_TOKEN}
HJ_DISCORD_CLIENT_ID=${HJ_DISCORD_CLIENT_ID}
HJ_DISCORD_CLIENT_SECRET=${HJ_DISCORD_CLIENT_SECRET}
HJ_LLM_OLLAMA_URL=${HJ_LLM_OLLAMA_URL}
HJ_LLM_MODEL=${HJ_LLM_MODEL}"
fi

# ------------------------------------------------------------------ Ablegen

step "Ablegen"
UMSCHLAG="$(mktemp)"
trap 'rm -f "$UMSCHLAG"' EXIT
printf '%s\n' "$INHALT" > "$UMSCHLAG"

aus_schreiben "$ZIEL" < "$UMSCHLAG" || fail "Tresor liess sich nicht ablegen."
info "$(printf '%s: %s Zeilen, %s Bytes' \
       "$ZIEL" "$(printf '%s\n' "$INHALT" | wc -l)" "$(wc -c < "$UMSCHLAG")")"

step "Fertig"
info "Ein Knoten holt ihn beim Aufsetzen selbst - mit dem Knoten-Passwort:"
info "    https://${HJ_UPDATE_HOST}/tresor/${PROFIL}.env"
echo
if [[ "$PROFIL" == "voll" ]]; then
    warn "Noch nicht befuellt: das Profil lavalink."
    warn "    bash tresor.sh fuellen lavalink"
    echo
fi
