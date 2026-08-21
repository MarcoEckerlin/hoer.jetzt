#!/usr/bin/env bash
#
# hoer.jetzt - Agent.
#
# Laeuft auf jedem Knoten, jede Minute per systemd-Timer. Er meldet den
# Zustand, holt Ziel-Release und Shard-Aufteilung und setzt beides um - je
# Modul getrennt.
#
#   bash hj-agent.sh                       der normale Lauf
#   bash hj-agent.sh --wartung "Grund"     diesen Knoten in Wartung setzen
#   bash hj-agent.sh --betrieb             Wartung beenden
#   bash hj-agent.sh --zustand             nur anzeigen, nichts aendern
#
# ---------------------------------------------------------------------------
# Warum ein Skript und kein Dienst
#
# Der Agent muss "docker compose" auf dem Host ausfuehren. Ein Dienst im
# Container braeuchte dafuer den Docker-Socket - und wer den hat, ist auf dem
# Host root. Fuer eine Aufgabe, die einmal pro Minute ein paar Dateien anfasst,
# ist das ein absurder Tausch. Als Skript unter systemd laeuft er dort, wo er
# hingehoert, und laesst sich mit journalctl lesen wie alles andere.
#
# ---------------------------------------------------------------------------
# Was sich gegenueber der ersten Fassung geaendert hat
#
# Vorher verwaltete der Agent genau einen Stapel: er startete "core" neu, und
# das war es. Auf einem Knoten mit Core UND Lavalink UND KI-Radio ist das zu
# grob - ein Lavalink-Update darf den Bot nicht mitreissen, und ein Modul in
# Wartung muss stehen bleiben, waehrend die anderen weiterlaufen.
#
# Deshalb: Module einzeln, eine Sperre gegen ueberlappende Laeufe, und ein
# Wartungszustand, den beide Meldestellen kennen.
# ---------------------------------------------------------------------------

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=agent-lib.sh
. "${HIER}/agent-lib.sh"

WARTUNGSDATEI="${ARBEIT}/.wartung"
AGENT_VERSION="2"

# ------------------------------------------------------------ Handschalter

case "${1:-}" in
    --wartung)
        printf '%s\n' "${2:-von Hand}" > "$WARTUNGSDATEI"
        sagen "Wartung eingeschaltet: ${2:-von Hand}"
        sagen "Laufende Aufgaben werden nicht abgebrochen - es kommen nur keine neuen dazu."
        exit 0
        ;;
    --betrieb)
        rm -f "$WARTUNGSDATEI"
        sagen "Wartung beendet."
        exit 0
        ;;
    --zustand)
        umgebung_lesen || true
        printf 'Knoten:  %s\n' "${HJ_KNOTEN_KENNUNG:-<nicht angemeldet>}"
        printf 'Module:  %s\n' "$(module_lesen)"
        printf 'Wartung: %s\n' "$([[ -f "$WARTUNGSDATEI" ]] && cat "$WARTUNGSDATEI" || echo nein)"
        printf 'Zustand: %s\n' "$(zustand_sammeln)"
        exit 0
        ;;
esac

# ------------------------------------------------------------------ Vorlauf

[[ -f "$UMGEBUNG" ]] || fehler "${UMGEBUNG} nicht gefunden."
umgebung_lesen

MODULE="$(module_lesen)"
[[ -n "$MODULE" ]] || fehler "Keine Module in ${MODULDATEI}. install-node hat nicht sauber beendet."

KNOTEN="${HJ_KNOTEN_KENNUNG:-${HJ_NODE_NAME:-$(hostname -s)}}"
IN_WARTUNG=false
[[ -f "$WARTUNGSDATEI" ]] && IN_WARTUNG=true

# Ab hier wird angefasst - also erst die Sperre.
mit_sperre || exit 0

# ------------------------------------------------------------------- Melden

version="$(grep '^version=' "${ARBEIT}/main/RELEASE" 2>/dev/null | cut -d= -f2 || echo unbekannt)"

# Zwei Meldestellen, und das ist Absicht.
#
# Der Controller kennt den Live-Zustand im Minutentakt und verteilt Shards.
# Der Update-Server kennt das Ergebnis des letzten Update-Laufs. Beide
# benutzen dieselbe Kennung, damit nicht zwei Listen entstehen, die dasselbe
# meinen. Faellt eine Stelle aus, laeuft die andere weiter - ein Knoten, der
# wegen einer unerreichbaren Meldestelle stehenbleibt, waere das schlechtere
# Verhalten.

soll_version=""
soll_gesamt=""; soll_von=""; soll_bis=""

if [[ -n "${HJ_CONTROLLER_URL:-}" && -n "${HJ_CONTROLLER_TOKEN:-}" ]]; then
    antwort="$(curl -fsS --max-time 15 \
        -X POST "${HJ_CONTROLLER_URL%/}/api/verbund/anmelden" \
        -H "Authorization: Bearer ${HJ_CONTROLLER_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "$(printf '{"nodeName":"%s","privatIp":"%s","nodeNr":%s,"releaseVersion":"%s","wartung":%s,"zustandJson":%s}' \
            "$KNOTEN" "${HJ_PRIVAT_IP:-}" "${HJ_NODE_NR:-1}" "$version" \
            "$IN_WARTUNG" "$(zustand_sammeln)")" \
        2>/dev/null)" || antwort=""

    if [[ -n "$antwort" ]]; then
        lies() {
            printf '%s' "$antwort" | python3 -c "
import json, sys
try:
    print(json.load(sys.stdin).get('$1', '') or '')
except Exception:
    print('')
"
        }
        soll_version="$(lies zielRelease)"
        soll_gesamt="$(lies shardsGesamt)"
        soll_von="$(lies shardsVon)"
        soll_bis="$(lies shardsBis)"
    else
        # Keine Aenderung bei Funkstille. Ein Agent, der bei fehlender Antwort
        # anfaengt umzubauen, ist gefaehrlicher als gar keiner.
        sagen "Controller nicht erreichbar - keine Zuteilung uebernommen."
    fi
fi

# Herzschlag an den Update-Server. Traegt auch den Wartungszustand: dort
# entscheidet er darueber, ob dieser Knoten ein Update angeboten bekommt.
if [[ -n "${HJ_KNOTEN_GEHEIMNIS:-}" ]]; then
    us_senden "/melden" "$(printf \
        '{"kennung":"%s","name":"%s","profil":"%s","version":"%s","zustand":%s,"ergebnis":"%s","wartung":%s,"agentVersion":"%s"}' \
        "$KNOTEN" "${HJ_NODE_NAME:-$KNOTEN}" "$(module_lesen | tr ' ' '+')" \
        "$version" "$(zustand_sammeln)" "lauf" "$IN_WARTUNG" "$AGENT_VERSION")" \
        >/dev/null 2>&1 || sagen "Update-Server nicht erreichbar - Herzschlag ausgelassen."
fi

sagen "Knoten ${KNOTEN} [${MODULE}] Release ${version} -> ${soll_version:-unveraendert}"

# ------------------------------------------------------------------ Wartung

if $IN_WARTUNG; then
    # In Wartung wird gemeldet und beziehbar geblieben, aber nichts neu
    # gestartet und keine Zuteilung uebernommen. Genau das steht in Abschnitt
    # 63: der Knoten erreicht Update-Server und Controller weiterhin,
    # uebernimmt aber keine neuen produktiven Aufgaben.
    sagen "In Wartung ($(cat "$WARTUNGSDATEI")) - keine Aenderungen an den Diensten."
    exit 0
fi

# --------------------------------------------------------------- Zuteilung

geaendert=0
if hat_modul core; then
    # Shards gehen nur den Core etwas an. Ein reiner Audio-Knoten hat keine.
    #
    # Ein Controller ebenfalls nicht: er laeuft im Einzelbetrieb.
    #
    # Er betreibt keinen Discord-Bot (HJ_ROLLE=controller schaltet ihn ab),
    # also gibt es fuer ihn nichts zu shardsen. Bekaeme er trotzdem eine
    # Zuteilung, zaehlte er in der Gesamtrechnung mit - die uebrigen Knoten
    # teilten sich dann weniger Shards, als es Bots gibt, und ein Teil der
    # Discord-Server bliebe unbedient. Der Fehler saehe aus wie ein Ausfall
    # einzelner Server und nicht wie eine Rechnung, die nicht aufgeht.
    if [[ "${HJ_ROLLE:-}" == "controller" ]]; then
        : # Einzelbetrieb - keine Shard-Zuteilung.
    else
        umgebung_setzen HJ_SHARDS_GESAMT "$soll_gesamt" && geaendert=1
        umgebung_setzen HJ_SHARD_VON     "$soll_von"    && geaendert=1
        umgebung_setzen HJ_SHARD_BIS     "$soll_bis"    && geaendert=1
    fi
fi

# ----------------------------------------------------------------- Release

if [[ -n "$soll_version" && "$soll_version" != "$version" ]]; then
    sagen "Release ${version} -> ${soll_version}, uebergebe an auto-update.sh"
    # auto-update.sh wartet auf Ruhe, bevor es neu startet - einen Shard
    # mitten in laufender Wiedergabe neu zu starten reisst den Ton ab.
    if bash "${ARBEIT}/main/deploy/auto-update.sh"; then
        sagen "Update durchgelaufen."
    else
        warnen "Update meldete einen Fehler - siehe Log. Der Knoten laeuft weiter."
    fi
    # auto-update.sh startet selbst neu; der Neustart unten waere doppelt.
    exit 0
fi

# ------------------------------------------------------------- Neu starten

if [[ "$geaendert" -eq 1 ]]; then
    sagen "Shard-Aufteilung geaendert - core neu starten."
    # Compose liest die .env aus seinem eigenen Verzeichnis. Dort liegt
    # inzwischen ein Symlink auf dieselbe Datei; ein "cp" darauf braeche mit
    # "same file" ab, und unter "set -e" waere der Neustart darunter nie
    # gelaufen - der Agent haette die Aufteilung geschrieben und nie
    # angewendet.
    ziel="${ARBEIT}/main/deploy/docker/.env"
    if [[ ! -e "$ziel" ]] || ! [[ "$ziel" -ef "$UMGEBUNG" ]]; then
        cp "$UMGEBUNG" "$ziel"
    fi
    compose up -d core
fi

sagen "Fertig."
