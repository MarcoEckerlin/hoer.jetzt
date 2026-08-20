#!/usr/bin/env bash
#
# hoer.jetzt - Releases veroeffentlichen. Abschnitte 36 und 61.
#
#   hoer-update release <version>              alles bauen und veroeffentlichen
#   hoer-update release <version> --module core   nur ein Modul
#   hoer-update stand                          was gerade gilt
#   hoer-update liste                          welche Releases es gab
#
# ---------------------------------------------------------------------------
# Warum das nur eine Huelle ist
#
# Die Arbeit macht veroeffentlichen.sh und nur das. Zwei Programme, die
# dasselbe tun, laufen frueher oder spaeter auseinander - und zwar an der
# Stelle, an der es niemand nachprueft.
#
# Was diese Huelle beitraegt, ist der Name und die Form aus der
# Spezifikation: ein Befehl mit Unterbefehlen, --module und --version. Der
# Weg dahin war bisher "bash veroeffentlichen.sh 2026.08.21.01", und den muss
# man wissen.
#
# ---------------------------------------------------------------------------
# Was --module tut und was nicht
#
# Es baut und schiebt nur das genannte Abbild. Das Manifest bleibt dabei
# vollstaendig: die uebrigen Zeilen behalten ihre bisherige Version, damit
# ein Knoten mit anderen Modulen nicht ploetzlich ohne Stand dasteht.
#
# Ein Release je Modul mit eigener Versionsnummer waere die Alternative. Die
# hat dieses Projekt bewusst nicht: RELEASE haelt die Zweige zusammen, und
# vier unabhaengige Zaehler machen die Frage "was laeuft gerade zusammen"
# unbeantwortbar.
# ---------------------------------------------------------------------------

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
. "${HIER}/lib.sh"

BEFEHL="${1:-}"
shift || true

VERSION=""
MODUL=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --module)  MODUL="${2:?Modul angeben}"; shift 2 ;;
        --version) VERSION="${2:?Version angeben}"; shift 2 ;;
        -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]:-$0}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)        fail "Unbekannte Angabe: $1" ;;
        *)         [[ -n "$VERSION" ]] && fail "Zwei Versionen angegeben: ${VERSION} und $1"
                   VERSION="$1"; shift ;;
    esac
done

case "$BEFEHL" in

    release)
        [[ -n "$VERSION" ]] || fail "Version angeben:  hoer-update release 2026.08.21.01"

        # Form pruefen, bevor gebaut wird.
        #
        # Eine Version wird zum Abbild-Tag. Grossbuchstaben und Sonderzeichen
        # sind dort teils verboten und teils erlaubt-aber-verwirrend; der
        # Fehler faellt sonst erst nach dem Bauen auf, also nach Minuten.
        [[ "$VERSION" =~ ^[a-z0-9][a-z0-9._-]*$ ]] \
            || fail "Version taugt nicht als Abbild-Tag: ${VERSION}
       Erlaubt sind Kleinbuchstaben, Ziffern, Punkt, Strich, Unterstrich.
       Ueblich hier: 2026.08.21.01"

        if [[ -n "$MODUL" ]]; then
            case "$MODUL" in
                core|lavalink|web|ai-radio|ki-radio) ;;
                *) fail "Unbekanntes Modul: ${MODUL} (core, lavalink, web, ki-radio)" ;;
            esac
            [[ "$MODUL" == "ki-radio" ]] && MODUL="ai-radio"
            export HJ_NUR_MODUL="$MODUL"
            info "Nur ${MODUL} - die uebrigen Zeilen des Manifests bleiben."
        fi

        exec bash "${HIER}/veroeffentlichen.sh" "$VERSION"
        ;;

    stand)
        step "Was gerade gilt"
        if aus_gibt_es "release/aktuell"; then
            aus_lesen "release/aktuell" | grep -vE '^\s*#|^\s*$' | sed 's/^/    /'
        else
            warn "Noch nichts veroeffentlicht."
        fi
        echo
        ;;

    liste)
        step "Veroeffentlichte Abbilder"
        # Ueber die Registry, nicht ueber eine eigene Liste: was dort liegt,
        # ist die Wahrheit. Eine mitgefuehrte Liste waere eine zweite Quelle,
        # die irgendwann nicht mehr stimmt.
        PW="$(grep '^HJ_TOKEN_KNOTEN=' "${HIER}/.env" 2>/dev/null | cut -d= -f2- || true)"
        PORT="$(grep '^HJ_PORT_INTERN=' "${HIER}/.env" 2>/dev/null | cut -d= -f2- || echo 8091)"
        [[ -n "$PW" ]] || fail "Kein Knoten-Passwort in der .env - erst einrichten.sh laufen lassen."
        for teil in core lavalink web ai-radio; do
            marken="$(curl -fsS -u "knoten:${PW}" \
                      "http://127.0.0.1:${PORT}/v2/hoerjetzt/${teil}/tags/list" 2>/dev/null \
                      | tr ',' '\n' | grep -oE '"[0-9][^"]*"' | tr -d '"' | sort -r | head -5 || true)"
            if [[ -n "$marken" ]]; then
                printf '    %-10s %s\n' "$teil" "$(echo "$marken" | paste -sd' ' -)"
            else
                printf '    %-10s %s\n' "$teil" "(nichts)"
            fi
        done
        echo
        ;;

    ""|-h|--help)
        sed -n '2,9p' "${BASH_SOURCE[0]:-$0}" | sed 's/^# \{0,1\}//'
        ;;

    *)
        fail "Unbekannter Befehl: ${BEFEHL} (release, stand, liste)"
        ;;
esac
