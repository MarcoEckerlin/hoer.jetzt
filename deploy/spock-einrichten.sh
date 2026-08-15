#!/usr/bin/env bash
#
# hoer.jetzt - Multi-Master-Replikation einrichten (pgEdge Spock).
#
#   bash spock-einrichten.sh anlegen              # diese Node bekanntmachen
#   bash spock-einrichten.sh verbinden <ip> <nr>  # mit einer anderen Node koppeln
#   bash spock-einrichten.sh zeigen               # Zustand ansehen
#   bash spock-einrichten.sh pruefen              # Schreibprobe in beide Richtungen
#
# ---------------------------------------------------------------------------
# Was hier passiert - und warum es gefahrlos ist
#
# Multi-Master ist gefuerchtet, weil zwei Standorte dieselbe Zeile aendern und
# irgendwer entscheiden muss, wer gewinnt. Bei "last writer wins" verliert
# still jemand seine Daten, und man merkt es Wochen spaeter.
#
# Hier entsteht dieser Fall nicht: Discord teilt den Bot in Shards, und ein
# Server wird von genau einem Shard bedient. Also schreibt fuer jeden Server
# nur ein Prozess, also nur eine Node. Die Regel dazu steht in
# UMBAU-ARCHITEKTUR.md, Abschnitt 1 - wer sie bricht, holt sich die Konflikte
# zurueck.
#
# Was trotzdem kollidieren koennte, sind fortlaufende Nummern. Deshalb vergibt
# jede Node aus einem eigenen Zahlenraum (HJ_NODE_NR, siehe schema-postgres.sql).
# ---------------------------------------------------------------------------

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
UMGEBUNG="${UMGEBUNG:-${ARBEIT}/.env}"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f "$UMGEBUNG" ]] || fail "${UMGEBUNG} nicht gefunden."
# shellcheck disable=SC1090
set -a; source "$UMGEBUNG"; set +a

: "${HJ_NODE_NR:?HJ_NODE_NR in der .env setzen - jede Node braucht eine eigene Nummer}"
: "${HJ_PRIVAT_IP:?HJ_PRIVAT_IP setzen - die 10.x-Adresse dieser Maschine im Hetzner-Netz}"
DB_NAME="${HJ_DB_NAME:-discordbot}"
DB_USER="${HJ_DB_USER:-discordbot}"
DB_PASS="${HJ_DB_PASSWORD:?Datenbank-Passwort fehlt}"
KNOTEN="node${HJ_NODE_NR}"

cd "${ARBEIT}/main/deploy/docker"

# psql im Container - so muss auf dem Host kein Client liegen.
psql_() {
    docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

erreichbar() {
    docker compose exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1
}

erreichbar || fail "PostgreSQL antwortet nicht. Laeuft der Stack?"

case "${1:-}" in

anlegen)
    step "Erweiterung"
    # Schlaegt hier "could not open extension control file" fehl, laeuft ein
    # Standard-Postgres statt des Spock-Abbilds - siehe docker-compose.spock.yml.
    psql_ -c "CREATE EXTENSION IF NOT EXISTS spock;" \
        || fail "Spock fehlt in diesem Abbild. docker-compose.spock.yml mitgeben."
    info "$(psql_ -tAc "SELECT 'Spock ' || extversion FROM pg_extension WHERE extname='spock'")"

    step "Diese Node bekanntmachen"
    if [[ "$(psql_ -tAc "SELECT count(*) FROM spock.node WHERE node_name='${KNOTEN}'")" == "0" ]]; then
        psql_ -c "SELECT spock.node_create(
                      node_name := '${KNOTEN}',
                      dsn := 'host=${HJ_PRIVAT_IP} port=5432 dbname=${DB_NAME} user=${DB_USER} password=${DB_PASS}');"
        info "Node ${KNOTEN} angelegt (${HJ_PRIVAT_IP})."
    else
        info "Node ${KNOTEN} gibt es schon."
    fi

    step "Tabellen in den Abgleich aufnehmen"
    # Muss nach jedem Schema-Zuwachs erneut laufen: eine Tabelle, die es beim
    # Anlegen noch nicht gab, wird nicht von selbst mitgenommen.
    psql_ -c "SELECT spock.repset_add_all_tables('default', ARRAY['public']);"
    info "$(psql_ -tAc "SELECT count(*) || ' Tabellen im Abgleich' FROM spock.tables") "

    warn "Tabellen ohne Primaerschluessel koennen nicht abgeglichen werden."
    psql_ -tAc "
        SELECT '    ohne Schluessel: ' || string_agg(c.relname, ', ')
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r'
          AND NOT EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indisprimary)"
    ;;

verbinden)
    ZIEL_IP="${2:?Adresse der anderen Node angeben}"
    ZIEL_NR="${3:?Nummer der anderen Node angeben}"
    ZIEL="node${ZIEL_NR}"
    ABO="von_${ZIEL}"

    step "Abonnement ${ABO}"
    if [[ "$(psql_ -tAc "SELECT count(*) FROM spock.subscription WHERE sub_name='${ABO}'")" != "0" ]]; then
        info "Gibt es schon."
    else
        psql_ -c "SELECT spock.sub_create(
                      subscription_name := '${ABO}',
                      provider_dsn := 'host=${ZIEL_IP} port=5432 dbname=${DB_NAME} user=${DB_USER} password=${DB_PASS}');"
        info "Angelegt. Warte auf den ersten Abgleich..."
        psql_ -c "SELECT spock.sub_wait_for_sync('${ABO}');" || warn "Abgleich dauert - Zustand mit 'zeigen' pruefen."
    fi

    echo
    warn "Das ist eine Richtung. Auf der anderen Node muss ebenfalls laufen:"
    warn "    bash spock-einrichten.sh verbinden ${HJ_PRIVAT_IP} ${HJ_NODE_NR}"
    warn "Ohne beide Richtungen ist es keine Multi-Master-Replikation, sondern"
    warn "eine Einbahnstrasse - und die faellt erst auf, wenn Daten fehlen."
    ;;

zeigen)
    step "Nodes"
    psql_ -c "SELECT node_id, node_name FROM spock.node ORDER BY node_id;"
    step "Abonnements"
    psql_ -c "SELECT sub_name, sub_enabled, sub_slot_name FROM spock.subscription ORDER BY sub_name;"
    step "Abgleichstand"
    psql_ -c "SELECT * FROM spock.sub_show_status();"
    step "Replikations-Slots"
    psql_ -c "SELECT slot_name, active, restart_lsn FROM pg_replication_slots;"
    ;;

pruefen)
    step "Schreibprobe"
    MARKE="probe-$(date +%s)-${KNOTEN}"
    psql_ -c "INSERT INTO logs (type, module, value, \"timestamp\")
              VALUES ('INFO', 'SPOCK', '${MARKE}', current_timestamp);"
    info "Auf dieser Node geschrieben: ${MARKE}"
    echo
    info "Auf JEDER anderen Node muss jetzt binnen Sekunden erscheinen:"
    info "    docker compose exec -T postgres psql -U ${DB_USER} -d ${DB_NAME} \\"
    info "        -c \"SELECT value FROM logs WHERE value = '${MARKE}'\""
    echo
    warn "Und dieselbe Probe von der anderen Node zurueck. Eine Richtung zu"
    warn "pruefen und die andere anzunehmen ist der haeufigste Fehler dabei."
    ;;

*)
    cat <<HILFE
hoer.jetzt - Multi-Master einrichten

  anlegen                    Spock aktivieren und diese Node bekanntmachen
  verbinden <ip> <nummer>    Abonnement auf eine andere Node
  zeigen                     Nodes, Abonnements, Abgleichstand
  pruefen                    Schreibprobe, die auf allen Nodes ankommen muss

Reihenfolge bei zwei Nodes (jeweils auf der genannten Maschine):

  Node 1:  bash spock-einrichten.sh anlegen
  Node 2:  bash spock-einrichten.sh anlegen
  Node 1:  bash spock-einrichten.sh verbinden <ip-von-node2> 2
  Node 2:  bash spock-einrichten.sh verbinden <ip-von-node1> 1
  beide:   bash spock-einrichten.sh pruefen

Bei einer dritten Node: sie abonniert alle vorhandenen, und alle vorhandenen
abonnieren sie. Bei N Nodes sind das N*(N-1) Abonnements - ab vier Maschinen
wird das unuebersichtlich, dann lohnt der Controller aus Stufe 5.
HILFE
    exit 1
    ;;
esac
