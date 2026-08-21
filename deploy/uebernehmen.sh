#!/usr/bin/env bash
#
# hoer.jetzt - eine Datenbanksicherung in diesen Knoten einspielen.
#
#   bash uebernehmen.sh --datei core-1-20260822T101500Z.sql.gz
#   bash uebernehmen.sh --datei <datei> --pruefen    nur nachsehen
#
# Gedacht fuer zwei Faelle:
#
#   - Umzug. Die Daten liegen auf einem Core-Knoten und sollen auf den
#     Controller, der ab jetzt die Datenbank fuehrt.
#   - Rueckholen. Eine Sicherung von sicherung.sh wieder einspielen.
#
# ---------------------------------------------------------------------------
# Warum ein Skript und keine Befehlskette
#
# Die Kette ist kurz und an drei Stellen falsch zu machen, und zwar leise:
#
#   1. Compose ohne das Spock-Overlay aufrufen. Dann greift die Basisdatei
#      auf das Volume "postgres-daten" statt auf "pgdaten-spock" - Postgres
#      startet auf einem LEEREN Volume, und es sieht aus, als seien die Daten
#      weg. compose() aus agent-lib.sh nimmt das Overlay von selbst mit.
#
#   2. Einspielen, waehrend der Bot laeuft. Er schreibt weiter, und was er
#      waehrend des Einspielens anlegt, steht danach halb drin.
#
#   3. Auf ein bestehendes Schema einspielen. Der Kern legt seine Tabellen
#      beim Start selbst an (CREATE TABLE IF NOT EXISTS), die Ziel-Datenbank
#      ist also nie leer. Ein pg_dump enthaelt aber CREATE TABLE ohne
#      IF NOT EXISTS - das bricht mittendrin ab und hinterlaesst einen
#      halb eingespielten Stand.
#
# ---------------------------------------------------------------------------
# Alles oder nichts
#
# Das Loeschen des Schemas und das Einspielen laufen in EINER Transaktion.
# Bricht das Einspielen ab, wird auch das Loeschen zurueckgenommen: die alte
# Datenbank steht danach unveraendert da. Ohne das waere der schlechteste
# Ausgang eine leere Datenbank plus eine Fehlermeldung.
#
# Zusaetzlich wird vorher der jetzige Stand gesichert. Doppelt gemoppelt -
# aber der Moment, in dem man merkt, dass man die falsche Datei erwischt hat,
# liegt hinter dem Einspielen.
# ---------------------------------------------------------------------------

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=agent/agent-lib.sh
. "${HIER}/agent/agent-lib.sh"

# Gleichlautend zu install-node.sh - agent-lib.sh kennt sie nicht.
schritt() { printf '\n== %s\n' "$*"; }

DATEI=""
NUR_PRUEFEN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --datei)   DATEI="${2:?Datei angeben}"; shift 2 ;;
        --pruefen) NUR_PRUEFEN=true; shift ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *)         fehler "Unbekannte Angabe: $1" ;;
    esac
done

[[ -n "$DATEI" ]]   || fehler "--datei angeben."
[[ -f "$DATEI" ]]   || fehler "${DATEI} gibt es nicht."
[[ "$(id -u)" -eq 0 ]] || fehler "Bitte als root starten."

umgebung_lesen || fehler "${UMGEBUNG} nicht lesbar - ist das ein eingerichteter Knoten?"

DB_NAME="${HJ_DB_NAME:-discordbot}"
DB_USER="${HJ_DB_USER:-discordbot}"
ABLAGE="${ABLAGE:-${ARBEIT}/sicherungen}"

psql_() { compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" "$@"; }

# ----------------------------------------------------------------- Nachsehen

schritt "Nachsehen"

# Ist die Datei ueberhaupt heil? Ein abgebrochener Download ist der haeufigste
# Grund, warum ein Einspielen mittendrin endet - und das merkt man sonst erst,
# wenn das Schema schon weg ist.
if [[ "$DATEI" == *.gz ]]; then
    gzip -t "$DATEI" 2>/dev/null || fehler "${DATEI} ist beschaedigt (gzip -t schlaegt fehl)."
    LESEN=(gzip -dc "$DATEI")
else
    LESEN=(cat "$DATEI")
fi

GROESSE="$(du -h "$DATEI" | cut -f1)"
sagen "Datei: ${DATEI} (${GROESSE})"

# Sieht es nach einem pg_dump aus? Eine Datei, die etwas anderes enthaelt,
# wuerde erst nach dem DROP SCHEMA auffallen.
if ! "${LESEN[@]}" | head -50 | grep -q "PostgreSQL database dump"; then
    fehler "${DATEI} sieht nicht nach einem pg_dump aus.
       Erwartet wird die Ausgabe von sicherung.sh (pg_dump, --no-owner)."
fi
TABELLEN_IN_DATEI="$("${LESEN[@]}" | grep -c '^CREATE TABLE' || true)"
sagen "Enthaelt ${TABELLEN_IN_DATEI} Tabellen."

# Laeuft Postgres, und welches Volume haengt dran? Die Antwort auf die zweite
# Frage ist der Unterschied zwischen "leer" und "die Daten sind weg".
compose ps postgres >/dev/null 2>&1 || fehler "Postgres laeuft nicht - erst 'compose up -d postgres'."
JETZT_TABELLEN="$(psql_ -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'" 2>/dev/null || echo "?")"
sagen "In der Datenbank stehen jetzt ${JETZT_TABELLEN} Tabellen."

if [[ -f "${ARBEIT}/.spock" || "${HJ_SPOCK:-}" == "true" ]]; then
    warnen "Dieser Knoten faehrt Spock-Replikation."
    warnen "Nach dem Einspielen muessen die Replikationssaetze neu gebaut werden:"
    warnen "    bash ${HIER}/spock-einrichten.sh"
    warnen "Sonst repliziert er einen Stand, den der andere Knoten nicht kennt."
fi

if $NUR_PRUEFEN; then
    sagen "Nur nachgesehen - nichts geaendert."
    exit 0
fi

# ------------------------------------------------------------------ Anhalten

schritt "Schreiber anhalten"

# Postgres bleibt, alles was schreibt geht. Waehrend des Einspielens darf
# niemand sonst an der Datenbank sein.
compose stop core web >/dev/null 2>&1 || true
sagen "core und web angehalten."

# Ab hier wieder anfahren, egal wie es ausgeht. Ein Knoten, der nach einem
# fehlgeschlagenen Einspielen auch noch stillsteht, ist der schlechtere
# Ausgang - die alte Datenbank ist ja unveraendert.
wieder_an() {
    schritt "Wieder anfahren"
    compose up -d core web >/dev/null 2>&1 || warnen "Start fehlgeschlagen - von Hand nachsehen."
    sagen "core und web laufen wieder."
}
trap wieder_an EXIT

# ------------------------------------------------------------------- Sichern

schritt "Jetzigen Stand sichern"

mkdir -p "$ABLAGE"
VORHER="${ABLAGE}/vor-uebernahme-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
if compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" \
        --no-owner --no-privileges | gzip -9 > "${VORHER}.teil"; then
    mv "${VORHER}.teil" "$VORHER"
    chmod 600 "$VORHER"
    sagen "Vorher-Stand: ${VORHER} ($(du -h "$VORHER" | cut -f1))"
else
    rm -f "${VORHER}.teil"
    fehler "Der jetzige Stand liess sich nicht sichern - ohne Rueckweg wird hier nichts eingespielt."
fi

# ---------------------------------------------------------------- Einspielen

schritt "Einspielen"

# DROP und Einspielen in EINER Transaktion. Bricht es ab, ist auch das DROP
# zurueckgenommen und die alte Datenbank steht unveraendert da.
#
# ON_ERROR_STOP=1 ist dafuer Bedingung: ohne das laeuft psql ueber Fehler
# hinweg, meldet am Ende Erfolg und hinterlaesst einen halben Stand.
if {
        printf 'DROP SCHEMA public CASCADE;\n'
        printf 'CREATE SCHEMA public;\n'
        printf 'ALTER SCHEMA public OWNER TO %s;\n' "$DB_USER"
        "${LESEN[@]}"
   } | psql_ -v ON_ERROR_STOP=1 --single-transaction -q; then
    sagen "Eingespielt."
else
    fehler "Einspielen fehlgeschlagen - die Transaktion wurde zurueckgenommen.
       Die Datenbank steht unveraendert da (${JETZT_TABELLEN} Tabellen).
       Der Vorher-Stand liegt zusaetzlich in ${VORHER}."
fi

# ------------------------------------------------------------------ Nachsehen

schritt "Nachsehen"

NACHHER="$(psql_ -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")"
sagen "In der Datenbank stehen jetzt ${NACHHER} Tabellen (vorher ${JETZT_TABELLEN})."

if [[ "$NACHHER" -lt "$TABELLEN_IN_DATEI" ]]; then
    warnen "Die Datei nannte ${TABELLEN_IN_DATEI} Tabellen, angekommen sind ${NACHHER}."
    warnen "Bitte nachsehen, bevor der Bot wieder Verkehr bekommt."
fi

# Ein paar Zeilenzahlen. Nicht zur Pruefung - zum Hinsehen: wer die Zahlen
# kennt, erkennt hier sofort, ob die richtige Datei eingespielt wurde.
psql_ -tAc "
    SELECT relname || ': ' || n_live_tup
      FROM pg_stat_user_tables
     WHERE n_live_tup > 0
     ORDER BY n_live_tup DESC
     LIMIT 8" 2>/dev/null | while read -r zeile; do
    [[ -n "$zeile" ]] && sagen "  $zeile"
done

sagen ""
sagen "Der Vorher-Stand liegt in ${VORHER} - zurueck damit:"
sagen "    bash ${HIER}/uebernehmen.sh --datei ${VORHER}"
