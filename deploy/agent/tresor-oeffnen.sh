#!/usr/bin/env bash
#
# hoer.jetzt - Tresor oeffnen.
#
#   bash tresor-oeffnen.sh <umschlag> <privater-schluessel>
#
# Liest einen HJTRESOR1-Umschlag und gibt den Klartext auf die Standardausgabe.
# Der Gegenpart zu Umschlag.java im Updater.
#
# ---------------------------------------------------------------------------
# Warum openssl und kein fertiges Werkzeug
#
# Der Umschlag ist bewusst so gebaut, dass dieses Skript ihn ohne zusaetzliche
# Software oeffnen kann. Auf einem Audio-Knoten soll nichts liegen, was dort
# nicht gebraucht wird - und openssl ist auf jeder Debian-Installation da.
#
#   CMS waere das Naheliegende, kann aber das JDK des Servers nicht ohne
#   Fremdbibliothek. AES-GCM waere die bessere Betriebsart, aber "openssl enc"
#   kann den Authentifizierungsanhang nicht. Bleibt AES-256-CBC mit
#   HMAC-SHA256 als Encrypt-then-MAC.
#
# ---------------------------------------------------------------------------
# Die Reihenfolge ist Sicherheit, nicht Geschmack
#
# Der HMAC wird geprueft, BEVOR entschluesselt wird. Andersherum verraet das
# Auffuellmuster von CBC einem Angreifer, der Umschlaege veraendern und die
# Reaktion beobachten kann, den Klartext Byte fuer Byte. Wer dieses Skript
# aendert: diese Reihenfolge nicht antasten.
# ---------------------------------------------------------------------------

set -euo pipefail

UMSCHLAG="${1:?Umschlagdatei angeben}"
SCHLUESSEL="${2:-/opt/hoerjetzt/knoten.key}"

[[ -r "$UMSCHLAG" ]]   || { echo "Umschlag nicht lesbar: $UMSCHLAG" >&2; exit 1; }
[[ -r "$SCHLUESSEL" ]] || { echo "Schluessel nicht lesbar: $SCHLUESSEL" >&2; exit 1; }

command -v openssl >/dev/null || { echo "openssl fehlt." >&2; exit 1; }

# Alles Fluechtige in ein eigenes Verzeichnis mit engen Rechten. Ohne das
# laegen Sitzungsschluessel und Klartext im allgemein lesbaren /tmp - und
# genau das soll dieses Verfahren ja verhindern.
ARBEIT="$(mktemp -d)"
chmod 700 "$ARBEIT"
trap 'rm -rf "$ARBEIT"' EXIT

KENNUNG="$(sed -n '1p' "$UMSCHLAG" | tr -d '\r')"
if [[ "$KENNUNG" != "HJTRESOR1" ]]; then
    echo "Kein HJTRESOR1-Umschlag (gefunden: ${KENNUNG})." >&2
    echo "Ein aelterer Tresor lag im Klartext - dann ist der Knoten noch nicht umgestellt." >&2
    exit 1
fi

sed -n '2p' "$UMSCHLAG" | tr -d '\r' | base64 -d > "${ARBEIT}/wurzel.enc"
sed -n '3p' "$UMSCHLAG" | tr -d '\r' | base64 -d > "${ARBEIT}/iv.bin"
sed -n '4p' "$UMSCHLAG" | tr -d '\r' | base64 -d > "${ARBEIT}/geheim.bin"
MAC_SOLL="$(sed -n '5p' "$UMSCHLAG" | tr -d '\r')"

# ------------------------------------------------------------------ 1. Wurzel

if ! openssl pkeyutl -decrypt -inkey "$SCHLUESSEL" \
        -in "${ARBEIT}/wurzel.enc" -out "${ARBEIT}/wurzel.bin" \
        -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 2>"${ARBEIT}/fehler"; then
    echo "Der Umschlag ist nicht an diesen Knoten gerichtet." >&2
    echo "Das ist der Normalfall nach einem Schluesseltausch: im Updater unter" >&2
    echo "Knoten den oeffentlichen Schluessel dieses Hosts neu hinterlegen." >&2
    sed 's/^/    /' "${ARBEIT}/fehler" >&2
    exit 1
fi

# ---------------------------------------------------------- 2. Schluessel

# sha256(zweck || 0x00 || wurzel) - dieselbe Ableitung wie in Umschlag.java.
ableiten() {
    printf '%s\x00' "$1" > "${ARBEIT}/vor"
    cat "${ARBEIT}/wurzel.bin" >> "${ARBEIT}/vor"
    openssl dgst -sha256 -binary "${ARBEIT}/vor" | xxd -p -c 64
}

AES_SCHLUESSEL="$(ableiten aes)"
MAC_SCHLUESSEL="$(ableiten mac)"

# ------------------------------------------------------------- 3. Pruefen

cat "${ARBEIT}/iv.bin" "${ARBEIT}/geheim.bin" > "${ARBEIT}/mac.eingabe"
MAC_IST="$(openssl dgst -sha256 -mac HMAC -macopt "hexkey:${MAC_SCHLUESSEL}" \
           -binary "${ARBEIT}/mac.eingabe" | base64 -w0)"

if [[ "$MAC_IST" != "$MAC_SOLL" ]]; then
    echo "Pruefsumme stimmt nicht - der Umschlag wurde unterwegs veraendert." >&2
    echo "Es wird nichts entschluesselt." >&2
    exit 1
fi

# -------------------------------------------------------- 4. Entschluesseln

openssl enc -d -aes-256-cbc \
    -K "$AES_SCHLUESSEL" \
    -iv "$(xxd -p -c 32 "${ARBEIT}/iv.bin")" \
    -in "${ARBEIT}/geheim.bin"
