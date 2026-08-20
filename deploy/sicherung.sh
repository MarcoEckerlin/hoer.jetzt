#!/usr/bin/env bash
#
# hoer.jetzt - Datenbanksicherung.
#
#   bash sicherung.sh              sichern und zum Update-Server schieben
#   bash sicherung.sh --nur-lokal  nur hier ablegen
#   bash sicherung.sh --liste      was da ist
#
# Laeuft alle drei Stunden per systemd-Timer auf beiden Controllern.
#
# ---------------------------------------------------------------------------
# Warum auf beiden Controllern, obwohl sie dasselbe replizieren
#
# Spock ist Multi-Master-Replikation, keine Sicherung. Sie schuetzt gegen den
# Ausfall einer Maschine und gegen gar nichts sonst: ein versehentliches
# DELETE wird binnen Sekunden getreu auf den zweiten Controller uebertragen.
# Zwei unabhaengige Sicherungen sind deshalb kein doppelter Aufwand, sondern
# der eigentliche Schutz.
#
# ---------------------------------------------------------------------------
# Warum pg_dump und kein Dateikopie des Volumes
#
# Eine Kopie der Datendateien im laufenden Betrieb ergibt einen Stand, den es
# nie gab - halb geschriebene Seiten, offene Transaktionen. Sie laesst sich
# einspielen und faellt trotzdem irgendwann auf. pg_dump liest in einer
# Transaktion und liefert einen Stand, den es wirklich gab.
# ---------------------------------------------------------------------------

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=agent/agent-lib.sh
. "${HIER}/agent/agent-lib.sh"

ABLAGE="${ABLAGE:-${ARBEIT}/sicherungen}"
BEHALTEN="${BEHALTEN:-16}"   # 16 x 3 Stunden = zwei Tage vor Ort
NUR_LOKAL=false

case "${1:-}" in
    --nur-lokal) NUR_LOKAL=true ;;
    --liste)
        ls -lh "$ABLAGE" 2>/dev/null || sagen "Noch keine Sicherungen."
        exit 0
        ;;
esac

umgebung_lesen || fehler "${UMGEBUNG} nicht lesbar."
mkdir -p "$ABLAGE"

mit_sperre || exit 0

ZEIT="$(date -u +%Y%m%dT%H%M%SZ)"
KNOTEN="${HJ_KNOTEN_KENNUNG:-$(hostname -s)}"
ZIEL="${ABLAGE}/${KNOTEN}-${ZEIT}.sql.gz"

# ------------------------------------------------------------------ Sichern

sagen "Sichere ${HJ_DB_NAME:-discordbot} nach ${ZIEL}"

# In den Postgres-Container hinein, nicht ueber das Netz: so wird kein
# Passwort auf der Kommandozeile sichtbar, und es braucht keinen
# pg_dump-Client auf dem Host, der zur Serverfassung passt.
if ! compose exec -T postgres pg_dump \
        -U "${HJ_DB_USER:-discordbot}" \
        -d "${HJ_DB_NAME:-discordbot}" \
        --no-owner --no-privileges \
        | gzip -9 > "${ZIEL}.teil"; then
    rm -f "${ZIEL}.teil"
    fehler "pg_dump fehlgeschlagen - nichts geschrieben."
fi

# Erst umbenennen, wenn es vollstaendig ist. Ein abgebrochener Lauf
# hinterlaesst sonst eine Datei, die aussieht wie eine Sicherung und keine
# ist - und die man ausgerechnet dann findet, wenn man sie braucht.
mv "${ZIEL}.teil" "$ZIEL"
chmod 600 "$ZIEL"

GROESSE="$(du -h "$ZIEL" | cut -f1)"

# Eine leere oder winzige Sicherung ist ein Fehlschlag, den pg_dump nicht
# meldet - etwa wenn die Datenbank noch leer ist oder der Benutzer nichts
# sehen darf. 1 KB ist grosszuegig: ein Dump nur mit Schema ist groesser.
if [[ "$(stat -c%s "$ZIEL")" -lt 1024 ]]; then
    warnen "Sicherung ist nur $(stat -c%s "$ZIEL") Bytes gross - stimmt das?"
fi

sagen "Sicherung fertig (${GROESSE})."

# ------------------------------------------------------------- Hochladen

if ! $NUR_LOKAL && [[ -n "${HJ_KNOTEN_GEHEIMNIS:-}" ]]; then
    sagen "Uebertrage zum Update-Server"
    if curl -fsS --max-time 600 \
            -u "${HJ_KNOTEN_KENNUNG}:${HJ_KNOTEN_GEHEIMNIS}" \
            -H "Content-Type: application/gzip" \
            --data-binary "@${ZIEL}" \
            "https://${HJ_UPDATE_HOST}/sicherung/$(basename "$ZIEL")" >/dev/null; then
        sagen "Uebertragen."
    else
        # Kein Abbruch: die lokale Sicherung ist geschrieben und gueltig. Ein
        # nicht erreichbarer Update-Server darf nicht dazu fuehren, dass gar
        # nicht gesichert wird.
        warnen "Uebertragung fehlgeschlagen - die lokale Sicherung liegt vor."
    fi
fi

# ------------------------------------------------------------- Aufraeumen

# Erst nach dem Hochladen. Andersherum koennte ein Lauf die vorletzte
# Sicherung wegraeumen und die neue anschliessend nicht uebertragen.
anzahl="$(find "$ABLAGE" -name "${KNOTEN}-*.sql.gz" | wc -l)"
if [[ "$anzahl" -gt "$BEHALTEN" ]]; then
    find "$ABLAGE" -name "${KNOTEN}-*.sql.gz" -printf '%T@ %p\n' \
        | sort -n | head -n "$((anzahl - BEHALTEN))" | cut -d' ' -f2- \
        | while read -r alt; do
            rm -f "$alt"
            sagen "Alte Sicherung entfernt: $(basename "$alt")"
        done
fi

sagen "Fertig."
