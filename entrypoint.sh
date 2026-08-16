#!/bin/sh
#
# Baut application.yml aus der Vorlage und den Umgebungsvariablen.
#
# Warum nicht direkt Lavalinks eigene Env-Unterstuetzung? Sie deckt die
# Plugin-Abschnitte nicht vollstaendig ab, und ein einziger Weg ist leichter
# zu erklaeren als zwei, die sich ueberlagern.

set -eu

: "${LAVALINK_SERVER_PASSWORD:?LAVALINK_SERVER_PASSWORD muss gesetzt sein}"
: "${LAVALINK_PORT:=2333}"
: "${LAVALINK_ADDRESS:=0.0.0.0}"
: "${LAVALINK_TIER:=free}"
: "${YOUTUBE_PLUGIN_VERSION:=1.18.2}"
# Zwischen zwei Veroeffentlichungen liegen Monate, YouTube aendert sein
# Player-Skript aber im Wochentakt. Bricht die Entschluesselung
# ("Must find sig function from script"), hilft ein Entwicklungsstand:
# YOUTUBE_PLUGIN_SNAPSHOT=true und als Version den Commit-Hash von
# https://maven.lavalink.dev/snapshots/dev/lavalink/youtube/youtube-plugin/
: "${YOUTUBE_PLUGIN_SNAPSHOT:=false}"

# --- Klangqualitaet ---------------------------------------------------------
# Drei Voreinstellungen statt vier einzelner Zahlen. Wer es genauer will,
# setzt die Variablen darunter direkt - sie gewinnen gegen die Voreinstellung.
#
#   hoch     bester Encoder und Resampler. Braucht am meisten CPU.
#   mittel   kaum hoerbar schlechter, spuerbar weniger Last.
#   sparsam  fuer schwache Hardware oder viele gleichzeitige Wiedergaben.
#
# Wichtig: die Bitrate des Discord-Sprachkanals deckelt alles davon. Ein Kanal
# mit 64 kbit/s klingt nicht besser, egal was hier steht.
: "${LAVALINK_QUALITAET:=hoch}"

case "$LAVALINK_QUALITAET" in
    hoch)
        : "${LAVALINK_OPUS_QUALITY:=10}"
        : "${LAVALINK_RESAMPLING:=HIGH}"
        : "${LAVALINK_BUFFER_MS:=500}"
        : "${LAVALINK_FRAME_BUFFER_MS:=8000}"
        ;;
    mittel)
        : "${LAVALINK_OPUS_QUALITY:=8}"
        : "${LAVALINK_RESAMPLING:=MEDIUM}"
        : "${LAVALINK_BUFFER_MS:=400}"
        : "${LAVALINK_FRAME_BUFFER_MS:=6000}"
        ;;
    sparsam)
        : "${LAVALINK_OPUS_QUALITY:=5}"
        : "${LAVALINK_RESAMPLING:=LOW}"
        : "${LAVALINK_BUFFER_MS:=400}"
        : "${LAVALINK_FRAME_BUFFER_MS:=5000}"
        ;;
    *)
        echo "Unbekannte Qualitaetsstufe '${LAVALINK_QUALITAET}' - erlaubt: hoch, mittel, sparsam." >&2
        exit 1
        ;;
esac

# envsubst sieht nur exportierte Variablen. Ohne das hier setzt ":=" sie zwar
# in der Shell, in der Vorlage bleibt aber ein leerer Wert stehen - und der
# Plugin-Download landet auf einer URL ohne Versionsnummer.
export LAVALINK_SERVER_PASSWORD LAVALINK_PORT LAVALINK_ADDRESS LAVALINK_TIER
export YOUTUBE_PLUGIN_VERSION YOUTUBE_PLUGIN_SNAPSHOT
export LAVALINK_OPUS_QUALITY LAVALINK_RESAMPLING
export LAVALINK_BUFFER_MS LAVALINK_FRAME_BUFFER_MS

# --- Anmeldung bei YouTube ---------------------------------------------------
# Altersbeschraenkte Titel liefert YouTube nur an angemeldete Clients. Wer sie
# braucht, setzt YOUTUBE_OAUTH=true: beim ersten Start erscheint im Log ein
# Geraetecode. Nach der Bestaetigung im Browser steht dort der refreshToken -
# den in YOUTUBE_REFRESH_TOKEN eintragen, sonst wiederholt sich das bei jedem
# Neustart.
: "${YOUTUBE_OAUTH:=false}"
: "${YOUTUBE_REFRESH_TOKEN:=}"
: "${YOUTUBE_PO_TOKEN:=}"
: "${YOUTUBE_VISITOR_DATA:=}"

YOUTUBE_ZUSATZ=""
if [ "$YOUTUBE_OAUTH" = "true" ]; then
    YOUTUBE_ZUSATZ="    oauth:
      enabled: true
      skipInitialization: false"
    if [ -n "$YOUTUBE_REFRESH_TOKEN" ]; then
        YOUTUBE_ZUSATZ="    oauth:
      enabled: true
      refreshToken: \"${YOUTUBE_REFRESH_TOKEN}\"
      skipInitialization: true"
    fi
fi

if [ -n "$YOUTUBE_PO_TOKEN" ] && [ -n "$YOUTUBE_VISITOR_DATA" ]; then
    YOUTUBE_ZUSATZ="${YOUTUBE_ZUSATZ}
    pot:
      token: \"${YOUTUBE_PO_TOKEN}\"
      visitorData: \"${YOUTUBE_VISITOR_DATA}\""
fi

# --- Entschluesselung auslagern ----------------------------------------------
# YouTube verschluesselt die Stream-URL mit einer Funktion, die im Player-Skript
# steckt und sich staendig aendert. Das Plugin liest sie selbst aus - wenn das
# Skript zu neu ist, scheitert es:
#
#   Problematic YouTube player script .../base.js detected
#   (issue detected with script: must find sig function)
#
# Dann faellt nicht nur Altersbeschraenktes aus, sondern alles. Ein Cipher-
# Dienst (yt-cipher) macht diesen Schritt ausserhalb und wird viel oefter
# nachgezogen als eine Plugin-Veroeffentlichung.
: "${YT_CIPHER_URL:=}"
: "${YT_CIPHER_PASSWORD:=}"
: "${YT_CIPHER_USERAGENT:=hoerjetzt}"

if [ -n "$YT_CIPHER_URL" ]; then
    YOUTUBE_ZUSATZ="${YOUTUBE_ZUSATZ}
    remoteCipher:
      url: \"${YT_CIPHER_URL}\"
      userAgent: \"${YT_CIPHER_USERAGENT}\""
    if [ -n "$YT_CIPHER_PASSWORD" ]; then
        YOUTUBE_ZUSATZ="${YOUTUBE_ZUSATZ}
      password: \"${YT_CIPHER_PASSWORD}\""
    fi
fi
export YOUTUBE_ZUSATZ

envsubst < /opt/Lavalink/application.yml.template > /opt/Lavalink/application.yml

echo "==> Lavalink startet"
echo "    Stufe:  ${LAVALINK_TIER}"
echo "    Adresse: ${LAVALINK_ADDRESS}:${LAVALINK_PORT}"
echo "    Klang:  ${LAVALINK_QUALITAET} (Opus ${LAVALINK_OPUS_QUALITY}, Resampling ${LAVALINK_RESAMPLING})"
if [ "$YOUTUBE_OAUTH" = "true" ]; then
    if [ -n "$YOUTUBE_REFRESH_TOKEN" ]; then
        echo "    YouTube: angemeldet (Token hinterlegt)"
    else
        echo "    YouTube: Anmeldung noetig - Geraetecode erscheint gleich im Log"
    fi
else
    echo "    YouTube: ohne Anmeldung (altersbeschraenkte Titel koennen fehlen)"
fi
if [ -n "$YT_CIPHER_URL" ]; then
    echo "    Cipher:  ${YT_CIPHER_URL}"
else
    echo "    Cipher:  im Plugin (bei 'must find sig function' YT_CIPHER_URL setzen)"
fi
echo "    Plugin:  ${YOUTUBE_PLUGIN_VERSION} (snapshot=${YOUTUBE_PLUGIN_SNAPSHOT})"

exec java -XX:MaxRAMPercentage=75 -jar /opt/Lavalink/Lavalink.jar
