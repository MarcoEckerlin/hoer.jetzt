#!/usr/bin/env bash
#
# hoer.jetzt - die beiden 4096-Bit-Schluessel erzeugen.
#
#   bash schluessel.sh erzeugen     einmalig, legt alles an
#   bash schluessel.sh zeigen       Fingerabdruecke und Laufzeiten
#   bash schluessel.sh erneuern     neuen Update-Ausweis, gleiche CA
#
# Zwei Schluessel mit verschiedenen Aufgaben - sie loesen einander nicht ab:
#
#   Update-Ausweis   Ein Client-Zertifikat. Der Knoten weist sich damit bei
#                    Caddy aus, bevor er ueberhaupt an die Registry kommt.
#                    Docker kann das von sich aus, curl auch. Es ersetzt das
#                    Passwort - nicht zusaetzlich, sondern anstelle.
#
#   Tresor-Schluessel Ein Schluesselpaar. Der Server bekommt nur den
#                    oeffentlichen Teil und kann damit verschluesseln. Aufmachen
#                    kann den Tresor nur, wer den privaten Teil hat - und der
#                    liegt auf diesem Server bewusst nicht.
#
# RSA-4096 kann direkt nur rund 470 Byte verschluesseln, der Tresor ist
# groesser. Deshalb CMS: ein zufaelliger AES-256-Schluessel fuer die Daten,
# und nur der wird mit RSA verpackt. Das macht openssl selbst.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "${HIER}/lib.sh"

# Nicht ins Auslieferungsverzeichnis: was hier liegt, darf Caddy nie
# ausliefern koennen. Der private Teil des Tresors verlaesst diesen Ordner
# ueberhaupt nur auf dem Weg zu einem Knoten - und zwar von Hand.
LAGER="${HIER}/schluessel"
JAHRE=20

command -v openssl >/dev/null 2>&1 || fail "openssl fehlt (Paket openssl)."

BEFEHL="${1:-}"

# ------------------------------------------------------------------ zeigen

if [[ "$BEFEHL" == "zeigen" ]]; then
    [[ -d "$LAGER" ]] || fail "${LAGER} gibt es nicht - erst 'erzeugen'."
    step "Schluessel"
    for name in ca update-ausweis tresor; do
        datei="${LAGER}/${name}.crt"
        [[ -f "$datei" ]] || { warn "$(printf '%-16s %s' "$name" "fehlt")"; continue; }
        bis="$(openssl x509 -in "$datei" -noout -enddate | cut -d= -f2-)"
        bits="$(openssl x509 -in "$datei" -noout -text | grep -m1 -o '[0-9]\+ bit')"
        finger="$(openssl x509 -in "$datei" -noout -fingerprint -sha256 | cut -d= -f2- | tr -d ':' | cut -c1-16)"
        info "$(printf '%-16s %-9s bis %s  %s' "$name" "$bits" "$bis" "$finger")"
    done
    echo
    exit 0
fi

[[ "$BEFEHL" == "erzeugen" || "$BEFEHL" == "erneuern" ]] || {
    sed -n '3,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
}

mkdir -p "$LAGER"
chmod 700 "$LAGER"

# Ohne Passphrase: den Ausweis benutzt ein Timer um 03:00 und den Tresor ein
# Installationsskript. Eine Passphrase muesste daneben im Klartext liegen und
# waere dann keine mehr. Der Schutz ist der Dateimodus und der Ort.
erzeuge_paar() {
    local name="$1" cn="$2" tage="$3"
    openssl req -x509 -newkey rsa:4096 -sha256 -days "$tage" -nodes \
        -keyout "${LAGER}/${name}.key" -out "${LAGER}/${name}.crt" \
        -subj "/CN=${cn}" >/dev/null 2>&1 \
        || fail "${name} liess sich nicht erzeugen."
    chmod 600 "${LAGER}/${name}.key"
    chmod 644 "${LAGER}/${name}.crt"
}

# ------------------------------------------------------------------ CA

if [[ "$BEFEHL" == "erzeugen" ]]; then
    if [[ -f "${LAGER}/ca.key" ]]; then
        warn "Es gibt bereits eine CA in ${LAGER}."
        ja "Alles neu? Jeder vorhandene Knoten verliert damit seinen Zugang." n \
            || fail "Abgebrochen."
    fi

    step "Ausweisstelle"
    # Eine eigene kleine CA, nur fuer diesen Zweck. Caddy prueft die Ausweise
    # der Knoten dagegen. Mit ihr laesst sich spaeter ein zweiter Ausweis
    # ausstellen, ohne den ersten anzufassen.
    erzeuge_paar ca "hoer.jetzt Knoten-CA" $((365 * JAHRE))
    info "ca.crt / ca.key (4096 Bit, ${JAHRE} Jahre)"

    step "Tresor-Schluessel"
    erzeuge_paar tresor "hoer.jetzt Tresor" $((365 * JAHRE))
    info "tresor.crt (verschluesselt) / tresor.key (macht auf)"
fi

# ------------------------------------------------------------------ Ausweis

[[ -f "${LAGER}/ca.key" ]] || fail "Keine CA vorhanden - erst 'erzeugen'."

step "Update-Ausweis"
openssl req -new -newkey rsa:4096 -nodes \
    -keyout "${LAGER}/update-ausweis.key" \
    -out "${LAGER}/update-ausweis.csr" \
    -subj "/CN=hoerjetzt-knoten" >/dev/null 2>&1 \
    || fail "Antrag liess sich nicht stellen."

# extendedKeyUsage clientAuth: ohne diese Erweiterung weist Caddy das
# Zertifikat ab, obwohl die Signatur stimmt - und die Meldung nennt den Grund
# nicht.
#
# Eine richtige Datei, keine Prozessersetzung: <(...) liefert /dev/fd/63, und
# nicht jede openssl-Bauart kann daraus lesen. Dieselbe Falle wie bei
# "-pass fd:".
ERW="$(mktemp)"
printf 'extendedKeyUsage=clientAuth\nkeyUsage=digitalSignature,keyEncipherment\n' > "$ERW"

openssl x509 -req -in "${LAGER}/update-ausweis.csr" \
    -CA "${LAGER}/ca.crt" -CAkey "${LAGER}/ca.key" -CAcreateserial \
    -out "${LAGER}/update-ausweis.crt" -days $((365 * JAHRE)) -sha256 \
    -extfile "$ERW" >/dev/null 2>&1 \
    || { rm -f "$ERW"; fail "Ausweis liess sich nicht ausstellen."; }

rm -f "$ERW" "${LAGER}/update-ausweis.csr"
chmod 600 "${LAGER}/update-ausweis.key"
chmod 644 "${LAGER}/update-ausweis.crt"
info "update-ausweis.crt / update-ausweis.key (4096 Bit)"

# ------------------------------------------------------------------ Probe

step "Gegenprobe"
if openssl verify -CAfile "${LAGER}/ca.crt" "${LAGER}/update-ausweis.crt" >/dev/null 2>&1; then
    info "Ausweis gilt gegen die CA."
else
    fail "Der Ausweis prueft sich nicht gegen die eigene CA - das waere spaeter ein 403 ohne Erklaerung."
fi

# Der Tresor ist nur so viel wert wie sein Rueckweg. Einmal hin und zurueck,
# bevor irgendwer sich darauf verlaesst.
PROBE="$(mktemp)"; ZURUECK="$(mktemp)"
trap 'rm -f "$PROBE" "$ZURUECK"' EXIT
printf 'HJ_PROBE=mit=gleich und leer\n' > "$PROBE"
openssl cms -encrypt -aes-256-cbc -binary -outform PEM \
    -in "$PROBE" -out "${PROBE}.enc" "${LAGER}/tresor.crt" 2>/dev/null \
    || fail "Tresor-Zertifikat verschluesselt nicht."
# -binary auch hier: ohne das macht CMS aus jedem Zeilenende ein CRLF, und
# jeder Wert im Tresor bekaeme ein Wagenruecklauf-Zeichen angehaengt. Ein
# Passwort mit unsichtbarem CR am Ende ist der Fehler, den man drei Stunden
# lang woanders sucht.
openssl cms -decrypt -binary -inform PEM \
    -in "${PROBE}.enc" -inkey "${LAGER}/tresor.key" -out "$ZURUECK" 2>/dev/null \
    || fail "Tresor-Schluessel macht den eigenen Umschlag nicht auf."
cmp -s "$PROBE" "$ZURUECK" || fail "Der Rueckweg liefert etwas anderes als das Original."
rm -f "${PROBE}.enc"
info "Tresor: hin und zurueck, Byte fuer Byte."

# ------------------------------------------------------------------ Ende

step "Was ein Knoten braucht"
cat <<ENDE

    Diese drei Dateien - und sonst nichts von hier:

      ${LAGER}/update-ausweis.crt
      ${LAGER}/update-ausweis.key
      ${LAGER}/tresor.key

    Der Rest bleibt auf diesem Server. Vor allem ca.key: damit liessen
    sich beliebige weitere Ausweise ausstellen.

ENDE
info "Uebersicht: bash schluessel.sh zeigen"
echo
