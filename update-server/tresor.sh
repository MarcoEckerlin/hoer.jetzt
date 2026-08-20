#!/usr/bin/env bash
#
# hoer.jetzt - der Tresor. Die Zugangsdaten, die alle Knoten teilen.
#
#   bash tresor.sh fuellen [profil]    anlegen oder ueberschreiben
#   bash tresor.sh zeigen  [profil]    nachsehen, was drin steht
#   bash tresor.sh stand               was es gibt, ohne aufzumachen
#
# Ein Profil je Modul, und die Trennung ist der eigentliche Gewinn gegenueber
# frueher:
#
#   voll        Datenbank, Bot-Token, Client-Secret, Sprachmodell, Lavalink.
#   (= core)    Bekommt nur ein Host, auf dem der Kern laeuft.
#   lavalink    Nur das Lavalink-Passwort. Ein Audio-Knoten braucht nichts
#               weiter - und soll auch nichts weiter bekommen. Unter GitHub lag
#               auf jedem Knoten der vollstaendige Quellbaum; wer einen davon
#               aufmachte, hatte alles.
#   ki-radio    Nur, was das KI-Radio braucht.
#   controller  Zusaetzlich das, was nur die Steuer-Node kennen muss.
#
# Welches Profil ein Knoten bekommt, entscheidet nicht er selbst, sondern
# seine Module im Updater - siehe Faehigkeit.java.
#
# Der Tresor wird beim Abruf an den oeffentlichen Schluessel des fragenden
# Knotens gerichtet - zwei Knoten bekommen zwei verschiedene Antworten, und
# keiner kann die des anderen oeffnen. Siehe Umschlag.java und
# deploy/agent/tresor-oeffnen.sh.
#
# Hier auf dem Server liegt er im Klartext: der Updater muss ihn lesen
# koennen, um ihn verschluesseln zu koennen. Geschuetzt ist er durch die
# Dateirechte und dadurch, dass nur der Updater an das Volume kommt.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "${HIER}/lib.sh"


BEFEHL="${1:-}"
PROFIL="${2:-voll}"
case "$PROFIL" in
    # "core" ist der neue Name fuer "voll" - der Updater bedient beide auf
    # dieselbe Datei (siehe Tresorausgabe.lesen). Der alte Name bleibt, weil
    # er in bestehenden Anleitungen steht.
    core) PROFIL="voll" ;;
    voll|lavalink|ki-radio|controller) ;;
    ai-radio) PROFIL="ki-radio" ;;
    *) fail "Profil muss voll (bzw. core), lavalink, ki-radio oder controller sein." ;;
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
    for p in voll lavalink ki-radio controller; do
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
frage HJ_UPDATE_HOST "Adresse des Update-Servers" "${HJ_UPDATE_HOST:-repository.hoer.jetzt}"

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
    info "Die Datenbank laeuft als Container IM Stack des Knotens."
    info "Die Adresse ist deshalb der Dienstname aus der Compose-Datei und"
    info "keine IP: 'postgres'. Docker loest ihn im internen Netz auf."
    info ""
    info "127.0.0.1 waere falsch - das ist im Container der Container selbst."
    info "Nur wer eine Datenbank ausserhalb des Stacks betreibt, traegt hier"
    info "deren Adresse ein."
    frage  HJ_DB_HOST     "Adresse"   "postgres"
    frage  HJ_DB_PORT     "Port"      "5432"
    frage  HJ_DB_NAME     "Datenbank" "discordbot"
    frage  HJ_DB_USER     "Benutzer"  "discordbot"
    geheim HJ_DB_PASSWORD "Passwort"

    # Die beiden haeufigsten Verwechslungen abfangen.
    #
    # Beide ergeben einen Knoten, der seine Datenbank nicht findet - und die
    # Meldung im Log zeigt dann auf die Datenbank, nicht auf den Tresor. Bis
    # jemand darauf kommt, vergeht Zeit, und der Wert steckt inzwischen in der
    # .env jedes Knotens, der ihn geholt hat.
    if [[ "$HJ_DB_HOST" == "127.0.0.1" || "$HJ_DB_HOST" == "localhost" ]]; then
        warn "127.0.0.1 zeigt im Container auf den Container selbst."
        warn "Fuer die Datenbank im Stack ist 'postgres' richtig."
        ja "Trotzdem so eintragen?" n || fail "Abgebrochen - noch einmal starten."
    fi
    if [[ "$HJ_DB_PORT" == "3306" ]]; then
        warn "3306 ist der Port von MariaDB. Der Stack faehrt PostgreSQL auf 5432."
        warn "Der Wert stammt vermutlich noch aus der Zeit vor der Umstellung."
        ja "Trotzdem 3306 eintragen?" n || fail "Abgebrochen - noch einmal starten."
    fi

    step "Discord"
    geheim HJ_BOT_TOKEN             "Bot-Token"
    frage  HJ_DISCORD_CLIENT_ID     "Client-ID"
    geheim HJ_DISCORD_CLIENT_SECRET "Client-Secret"

    step "Oeffentliche Adresse"
    info "Unter welcher Adresse die Weboberflaeche des Bots erreichbar ist."
    info "Discord schickt die OAuth-Rueckleitung dorthin - stimmt sie nicht,"
    info "scheitert die Anmeldung mit 'redirect_uri mismatch'."
    info ""
    info "Mit https:// davor, ohne Schraegstrich am Ende."
    frage HJ_WEB_BASE_URL "Adresse" "${HJ_WEB_BASE_URL:-https://hoer.jetzt}"
    # Schraegstrich am Ende abschneiden: der Core haengt seine Pfade an, und
    # aus zwei Schraegstrichen wird kein gueltiger Rueckleitungs-URI.
    HJ_WEB_BASE_URL="${HJ_WEB_BASE_URL%/}"
    if [[ ! "$HJ_WEB_BASE_URL" =~ ^https?:// ]]; then
        warn "Ohne Schema ist das keine Adresse - https:// wird ergaenzt."
        HJ_WEB_BASE_URL="https://${HJ_WEB_BASE_URL}"
    fi
    info "-> ${HJ_WEB_BASE_URL}"

    step "Sprachmodell"
    info "Leer lassen, wenn keines da ist - KI-Chat und KI-Radio bleiben dann aus."
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

    # ------------------------------------------------------------------
    # Schluessel fuer die Zugangsdaten der Server-Betreiber
    # ------------------------------------------------------------------
    #
    # Damit verschluesselt der Bot die API-Token, die ein Discord-Server-
    # Betreiber fuer seinen eigenen KI-Endpunkt hinterlegt - siehe
    # Geheimtext.java im Core. Er wird nicht abgefragt, sondern erzeugt: es
    # gibt keinen Grund, ihn zu kennen, und ein getippter waere schwaecher
    # als einer aus /dev/urandom.
    #
    # ------------------------------------------------------------------
    # Ein vorhandener Schluessel wird UEBERNOMMEN, nie ersetzt.
    #
    # Wuerde beim erneuten Befuellen ein neuer erzeugt, waeren saemtliche
    # bereits gespeicherten Token nicht mehr zu entschluesseln - und zwar
    # stillschweigend: der KI-Chat scheiterte danach mit "Token abgelehnt",
    # und niemand kaeme auf den Tresor als Ursache. Deshalb wird zuerst
    # gelesen und nur bei Bedarf erzeugt.
    # ------------------------------------------------------------------
    HJ_GEHEIMNIS_SCHLUESSEL=""
    if aus_gibt_es "$ZIEL"; then
        HJ_GEHEIMNIS_SCHLUESSEL="$(aus_lesen "$ZIEL" \
            | grep '^HJ_GEHEIMNIS_SCHLUESSEL=' | cut -d= -f2- || true)"
    fi

    step "Schluessel fuer hinterlegte Zugangsdaten"
    if [[ -n "$HJ_GEHEIMNIS_SCHLUESSEL" ]]; then
        info "Vorhandener wird uebernommen (${#HJ_GEHEIMNIS_SCHLUESSEL} Zeichen)."
        info "Ein neuer wuerde alle bereits hinterlegten API-Token unlesbar machen."
    else
        HJ_GEHEIMNIS_SCHLUESSEL="$(openssl rand -base64 48 | tr -d '\n')"
        info "Neu erzeugt (${#HJ_GEHEIMNIS_SCHLUESSEL} Zeichen). Wird nie abgefragt."
    fi

    INHALT="${INHALT}
HJ_DB_HOST=${HJ_DB_HOST}
HJ_DB_PORT=${HJ_DB_PORT}
HJ_DB_NAME=${HJ_DB_NAME}
HJ_DB_USER=${HJ_DB_USER}
HJ_DB_PASSWORD=${HJ_DB_PASSWORD}
HJ_BOT_TOKEN=${HJ_BOT_TOKEN}
HJ_DISCORD_CLIENT_ID=${HJ_DISCORD_CLIENT_ID}
HJ_WEB_BASE_URL=${HJ_WEB_BASE_URL}
HJ_DISCORD_CLIENT_SECRET=${HJ_DISCORD_CLIENT_SECRET}
HJ_LLM_OLLAMA_URL=${HJ_LLM_OLLAMA_URL}
HJ_LLM_MODEL=${HJ_LLM_MODEL}
HJ_GEHEIMNIS_SCHLUESSEL=${HJ_GEHEIMNIS_SCHLUESSEL}"
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
