#!/usr/bin/env bash
#
# hoer.jetzt - Passwort der Updater-Oberflaeche setzen.
#
#   bash passwort.sh                  neues erzeugen und einmal anzeigen
#   bash passwort.sh --stdin          eigenes von der Standardeingabe lesen
#   bash passwort.sh --datei /pfad    eigenes aus einer Datei lesen
#   bash passwort.sh --name <name>    zusaetzlich den Benutzernamen aendern
#
# ---------------------------------------------------------------------------
# Wozu das noetig ist
#
# einrichten.sh erzeugt das Passwort, zeigt es EINMAL am Ende an und legt nur
# den bcrypt-Hash in der .env ab. Der Klartext steht danach nirgends mehr -
# so soll es sein.
#
# Nur: bricht das Einrichten zwischen dem Schreiben der .env und der
# Schlussanzeige ab, ist es weg, bevor es jemand gesehen hat. Genau das
# passiert, wenn ein altes Forgejo-Volume den Neuaufbau ueberlebt und das
# Verwaltungskonto schon existiert.
#
# Bis dahin gab es dafuer nur einen Weg: den ganzen Server neu aufsetzen. Fuer
# ein vergessenes Passwort einer Weboberflaeche ist das absurd.
#
# Kein Argument fuer das Passwort selbst - es stuende in der Shell-Historie
# und in "ps aux". Deshalb Standardeingabe, Datei oder erzeugen lassen.
# ---------------------------------------------------------------------------

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
. "${HIER}/lib.sh"
UMGEBUNG="${HIER}/.env"

NEUER_NAME=""
PASSWORT=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --stdin)
            IFS= read -r PASSWORT || true
            [[ -n "$PASSWORT" ]] || fail "Nichts auf der Standardeingabe."
            shift ;;
        --datei)
            datei="${2:?Datei angeben}"
            [[ -r "$datei" ]] || fail "Nicht lesbar: ${datei}"
            IFS= read -r PASSWORT < "$datei" || true
            [[ -n "$PASSWORT" ]] || fail "Datei ist leer: ${datei}"
            shred -u "$datei" 2>/dev/null || { : > "$datei"; rm -f "$datei"; }
            info "Datei gelesen und entfernt."
            shift 2 ;;
        --name) NEUER_NAME="${2:?Name angeben}"; shift 2 ;;
        -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fail "Unbekannte Angabe: $1" ;;
    esac
done

[[ "$(id -u)" -eq 0 ]] || fail "Bitte als root starten."
[[ -f "$UMGEBUNG" ]] || fail "${UMGEBUNG} fehlt - erst einrichten.sh laufen lassen."

ERZEUGT=false
if [[ -z "$PASSWORT" ]]; then
    PASSWORT="$(zufall)"
    ERZEUGT=true
elif [[ ${#PASSWORT} -lt 12 ]]; then
    warn "Kuerzer als zwoelf Zeichen. Hinter dieser Oberflaeche liegen"
    warn "Freigaben, Tresor und die Release-Steuerung."
    ja "Trotzdem nehmen?" n || fail "Abgebrochen."
fi

step "Hashen"
docker pull -q caddy:2-alpine >/dev/null 2>&1 || fail "caddy:2-alpine nicht ladbar."

# Der Zeilenumbruch ist Pflicht: caddy liest ohne --plaintext bis zum ersten
# \n und schneidet es ab. Ohne Umbruch endet der Aufruf mit "Error: EOF".
HASH="$(printf '%s\n' "$PASSWORT" | docker run --rm -i caddy:2-alpine caddy hash-password 2>/dev/null || true)"
if [[ ! "$HASH" =~ ^\$2[aby]\$ ]]; then
    warn "caddy nimmt die Standardeingabe nicht - Rueckfall auf --plaintext."
    warn "Das Passwort ist dabei kurz in 'ps aux' sichtbar."
    HASH="$(docker run --rm caddy:2-alpine caddy hash-password --plaintext "$PASSWORT")"
fi
[[ "$HASH" =~ ^\$2[aby]\$ ]] || fail "Hashen fehlgeschlagen: ${HASH:-<nichts>}"
info "bcrypt."

step "Umgebungsdatei"
# Compose ersetzt in der .env Variablen - aus "$2a$14$..." wuerden Bruchstuecke
# plus Leerstrings, und Caddy bekaeme einen kaputten Hash. Verdoppeltes
# Dollarzeichen ist die Fluchtform.
GESCHUETZT="$(printf '%s' "$HASH" | sed 's/\$/$$/g')"

SICHERUNG="${UMGEBUNG}.$(date '+%Y%m%d%H%M%S')"
cp "$UMGEBUNG" "$SICHERUNG"
chmod 600 "$SICHERUNG"

# sed mit dem Hash als Ersetzung ginge schief: er enthaelt Schraegstriche und
# Sonderzeichen. Zeilenweise neu schreiben ist umstaendlicher und richtig.
{
    while IFS= read -r zeile || [[ -n "$zeile" ]]; do
        case "$zeile" in
            HJ_VERWALTER_HASH=*) printf 'HJ_VERWALTER_HASH=%s\n' "$GESCHUETZT" ;;
            HJ_VERWALTER_NAME=*)
                if [[ -n "$NEUER_NAME" ]]; then
                    printf 'HJ_VERWALTER_NAME=%s\n' "$NEUER_NAME"
                else
                    printf '%s\n' "$zeile"
                fi ;;
            *) printf '%s\n' "$zeile" ;;
        esac
    done < "$SICHERUNG"
} > "$UMGEBUNG"
chmod 600 "$UMGEBUNG"

# Gegenprobe: steht der Hash wirklich drin, und liest Compose ihn unveraendert
# zurueck? Ohne das faellt eine kaputte .env erst beim naechsten Anmelden auf.
grep -q '^HJ_VERWALTER_HASH=' "$UMGEBUNG" || {
    cp "$SICHERUNG" "$UMGEBUNG"
    fail "HJ_VERWALTER_HASH fehlt jetzt - zurueckgesetzt. Stand die Zeile in der .env?"
}
if command -v docker >/dev/null 2>&1; then
    GELESEN="$(docker compose -f "${HIER}/docker-compose.yml" config 2>/dev/null \
               | grep -o 'HJ_VERWALTER_HASH: .*' | head -1 | cut -d' ' -f2- || true)"

    # Die Ausgabe von "docker compose config" ist selbst eine Compose-Datei -
    # ein einzelnes Dollarzeichen erscheint darin wieder verdoppelt, damit
    # sie sich erneut einlesen laesst. Vor dem Vergleich also zurueckdrehen.
    #
    # Die erste Fassung verglich ungefiltert und schlug deshalb IMMER an:
    #   gelesen:  $$2a$$14$$HD32...
    #   erwartet: $2a$14$HD32...
    # Das sah nach einer kaputten .env aus, war aber nur die Fluchtform der
    # Ausgabe. Die Maskierung selbst ist richtig - Compose liest .env-Werte
    # mit Variablenersetzung, sonst wuerden $2a und $14 zu Leerstrings.
    GELESEN="$(printf '%s' "$GELESEN" | sed 's/\$\$/$/g')"

    if [[ -n "$GELESEN" && "$GELESEN" != "$HASH" ]]; then
        cp "$SICHERUNG" "$UMGEBUNG"
        fail "Compose liest den Hash veraendert zurueck - zurueckgesetzt.
       gelesen:  ${GELESEN}
       erwartet: ${HASH}"
    fi
    if [[ -n "$GELESEN" ]]; then
        info "Compose liest den Hash unveraendert zurueck."
    fi
fi
info "${UMGEBUNG} (0600), Sicherung: ${SICHERUNG}"

step "Updater neu starten"
docker compose -f "${HIER}/docker-compose.yml" up -d updater >/dev/null 2>&1 \
    || warn "Neustart fehlgeschlagen - von Hand: docker compose up -d updater"
info "Der neue Zugang gilt sofort."

NAME="$(grep '^HJ_VERWALTER_NAME=' "$UMGEBUNG" | cut -d= -f2- || echo verwalter)"
PORT="$(grep '^HJ_PULT_PORT=' "$UMGEBUNG" | cut -d= -f2- || echo 8090)"
BIND="$(grep '^HJ_PULT_BIND=' "$UMGEBUNG" | cut -d= -f2- || echo 127.0.0.1)"

echo
echo "  ----------------------------------------------------------------"
echo "   Benutzer:  ${NAME}"
if $ERZEUGT; then
echo "   Passwort:  ${PASSWORT}"
echo
echo "   Wird nicht wieder angezeigt. Gespeichert ist nur der Hash."
else
echo "   Passwort:  <das angegebene>"
fi
echo
if [[ "$BIND" == "0.0.0.0" ]]; then
echo "   http://$(hostname -I 2>/dev/null | awk '{print $1}'):${PORT}/"
else
echo "   ssh -L ${PORT}:${BIND}:${PORT} root@$(hostname -I 2>/dev/null | awk '{print $1}')"
echo "   danach http://127.0.0.1:${PORT}/"
fi
echo "  ----------------------------------------------------------------"
echo
