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

# Die .env wird gelesen, nicht ausgefuehrt.
#
# Vorher stand hier "source". Damit ist die Datei ein Shell-Skript: ein
# Dollarzeichen in einem Passwort wird als Variable gelesen, und unter
# "set -u" bricht das Skript mit "K4vz4D6tZN: unbound variable" ab - einer
# Meldung, die nach einem Fehler im Skript aussieht und in Wahrheit ein
# gueltiges Passwort ist. Ein Passwort mit einem Backtick darin waere sogar
# ausgefuehrt worden.
#
# printf -v setzt den Wert buchstaeblich, ohne jede Ersetzung.
umgebung_lesen() {
    local zeile schluessel wert
    while IFS= read -r zeile || [[ -n "$zeile" ]]; do
        [[ "$zeile" =~ ^[[:space:]]*# ]] && continue
        [[ "$zeile" == *=* ]] || continue
        schluessel="${zeile%%=*}"
        wert="${zeile#*=}"
        schluessel="${schluessel//[[:space:]]/}"
        [[ "$schluessel" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
        printf -v "$schluessel" '%s' "$wert"
        export "${schluessel?}"
    done < "$1"
}

umgebung_lesen "$UMGEBUNG"

: "${HJ_NODE_NR:?HJ_NODE_NR in der .env setzen - jede Node braucht eine eigene Nummer}"
: "${HJ_PRIVAT_IP:?HJ_PRIVAT_IP setzen - die 10.x-Adresse dieser Maschine im Hetzner-Netz}"
DB_NAME="${HJ_DB_NAME:-discordbot}"
DB_USER="${HJ_DB_USER:-discordbot}"
DB_PASS="${HJ_DB_PASSWORD:?Datenbank-Passwort fehlt}"
KNOTEN="node${HJ_NODE_NR}"

# Fuer die Hinweistexte: mit welchen -f der Nutzer den Stack ansprechen muss.
# Wer HJ_SPOCK gesetzt hat, braucht beide Dateien - ein blosses
# "docker compose ps" arbeitet sonst mit einer anderen Dienstliste als der,
# die tatsaechlich laeuft.
COMPOSE_HINWEIS=""
[[ "${HJ_SPOCK:-}" == "true" ]] && COMPOSE_HINWEIS="-f docker-compose.yml -f docker-compose.spock.yml"

cd "${ARBEIT}/main/deploy/docker"

# psql im Container - so muss auf dem Host kein Client liegen.
psql_() {
    docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

erreichbar() {
    docker compose exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1
}

# Eine Zahl abfragen - und darauf bestehen, dass es eine ist.
#
# Vorher wurde das Ergebnis direkt verglichen. Schlug die Abfrage fehl, kam
# eine leere Zeichenkette zurueck (der Fehler geht nach stderr), und
# [[ "" != "0" ]] war wahr - das Skript meldete dann "Gibt es schon." und
# uebersprang genau den Schritt, den es haette tun sollen. Auf einer Node ohne
# Spock sah eine gescheiterte Einrichtung damit aus wie eine gelungene.
zahl() {
    local ergebnis
    ergebnis="$(psql_ -tAc "$1" 2>/dev/null || true)"
    ergebnis="$(printf '%s' "$ergebnis" | tr -d '[:space:]')"
    [[ "$ergebnis" =~ ^[0-9]+$ ]] || return 1
    printf '%s' "$ergebnis"
}

spock_vorhanden() {
    [[ "$(zahl "SELECT count(*) FROM pg_extension WHERE extname='spock'" || echo 0)" != "0" ]]
}

erreichbar || fail "PostgreSQL antwortet nicht. Laeuft der Stack?"

case "${1:-}" in

anlegen)
    # Liegen die Daten ueberhaupt im Volume?
    #
    # Das pgEdge-Abbild bringt PGDATA=/var/lib/pgsql/16/data mit, das Volume
    # haengt aber unter /var/lib/postgresql/data. Faellt das auseinander,
    # schreibt PostgreSQL in die Container-Schicht und jedes Neuerzeugen des
    # Containers loescht die Datenbank - lautlos, denn ein leeres Verzeichnis
    # fuehrt zu einem frischen initdb. Das kostet einen Abend, wenn man es
    # nicht weiss, und eine Sekunde, wenn man danach fragt.
    step "Ablageort der Daten"
    DATENPFAD="$(psql_ -tAc 'SHOW data_directory' 2>/dev/null | tr -d '[:space:]' || true)"
    if [[ "$DATENPFAD" != "/var/lib/postgresql/data" ]]; then
        warn "PostgreSQL schreibt nach ${DATENPFAD:-unbekannt}."
        warn "    Dort haengt kein Volume - die Datenbank ueberlebt kein"
        warn "    'docker compose up --force-recreate'. In docker-compose.spock.yml"
        warn "    gehoert PGDATA=/var/lib/postgresql/data."
        warn ""
        warn "    Erst das in Ordnung bringen, sonst ist der Verbund beim"
        warn "    naechsten Neustart wieder weg."
        echo
    else
        info "${DATENPFAD} - liegt im Volume."
    fi

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

    # Erst nachsehen, ob ueberhaupt etwas da ist. "0 Tabellen im Abgleich"
    # sieht nach einem Spock-Problem aus, hat aber meistens einen banaleren
    # Grund: die Datenbank ist leer, weil der Bot noch nicht gestartet ist -
    # oder weil er auf eine andere Datenbank zeigt als dieses Skript.
    VORHANDEN="$(zahl "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'r'" || echo 0)"
    if [[ "$VORHANDEN" == "0" ]]; then
        warn "In ${DB_NAME}.public liegt keine einzige Tabelle."
        warn "    Der Abgleich haette dann nichts zu tun - der Bot hat das Schema"
        warn "    noch nicht angelegt. Nachsehen (Verzeichnis beachten, die"
        warn "    Compose-Datei liegt in docker/, nicht hier):"
        warn ""
        warn "      cd ${ARBEIT}/main/deploy/docker"
        warn "      docker compose ${COMPOSE_HINWEIS} ps core"
        warn "      docker compose ${COMPOSE_HINWEIS} logs --tail 60 core"
        warn ""
        warn "    Danach 'anlegen' hier noch einmal laufen lassen."
    fi

    # Tabelle fuer Tabelle, nicht in einem Rutsch.
    #
    # repset_add_all_tables() bricht beim ersten Problem ab - eine einzige
    # Tabelle ohne Primaerschluessel genuegt, und *keine* Tabelle landet im
    # Abgleich. Das sah dann aus wie "Spock tut nichts", war aber ein
    # fehlender Schluessel an einer Stelle. Einzeln aufgenommen bleibt der
    # Schaden auf die betroffene Tabelle begrenzt, und man sieht, welche es
    # war.
    #
    # Muss nach jedem Schema-Zuwachs erneut laufen: eine Tabelle, die es beim
    # letzten Lauf noch nicht gab, wird nicht von selbst mitgenommen.
    ZUGEFUEGT=0
    UEBERSPRUNGEN=""
    while read -r tabelle; do
        [[ -n "$tabelle" ]] || continue
        if psql_ -qtAc "SELECT spock.repset_add_table('default', '${tabelle}');" >/dev/null 2>&1; then
            ZUGEFUEGT=$((ZUGEFUEGT + 1))
        else
            UEBERSPRUNGEN="${UEBERSPRUNGEN} ${tabelle}"
        fi
    done < <(psql_ -tAc "
        SELECT c.relname
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r'
          AND NOT EXISTS (SELECT 1 FROM spock.tables t WHERE t.relid = c.oid)
        ORDER BY c.relname" 2>/dev/null || true)

    IM_ABGLEICH="$(zahl "SELECT count(*) FROM spock.tables" || echo 0)"
    info "${IM_ABGLEICH} von ${VORHANDEN} Tabellen im Abgleich (${ZUGEFUEGT} neu)"

    if [[ -n "$UEBERSPRUNGEN" ]]; then
        warn "Nicht aufgenommen:${UEBERSPRUNGEN}"
        warn "    Fast immer ein fehlender Primaerschluessel. Spock repliziert"
        warn "    UPDATE und DELETE nur mit Schluessel - ohne ihn waere nicht"
        warn "    bestimmbar, welche Zeile gemeint ist."
    fi

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

    # Die Nummer gehoert zur *anderen* Node. Wer hier die eigene eintraegt,
    # legt ein Abonnement namens "von_node1" auf Node 1 an - das laeuft, zeigt
    # aber auf die falsche Maschine und faellt erst auf, wenn Daten fehlen.
    if [[ "$ZIEL_NR" == "$HJ_NODE_NR" ]]; then
        fail "Das ist die eigene Nummer (${HJ_NODE_NR}). Gemeint ist die Nummer der anderen Node."
    fi

    spock_vorhanden || fail "Spock ist hier nicht eingerichtet - erst 'anlegen' auf dieser Node."

    step "Abonnement ${ABO}"
    vorhanden="$(zahl "SELECT count(*) FROM spock.subscription WHERE sub_name='${ABO}'")" \
        || fail "spock.subscription ist nicht lesbar - lief 'anlegen' auf dieser Node durch?"

    if [[ "$vorhanden" != "0" ]]; then
        info "Gibt es schon."
    else
        # Vor dem Anlegen nachsehen, ob die Gegenstelle ueberhaupt antwortet.
        # sub_create legt sonst ein Abonnement an, das dauerhaft im Fehler
        # steht - und die Ursache ("Connection refused") steht dann nur im
        # Protokoll des Apply-Workers.
        if ! docker compose exec -T postgres pg_isready -h "$ZIEL_IP" -p 5432 >/dev/null 2>&1; then
            fail "$(printf '%s\n' \
                "${ZIEL_IP}:5432 antwortet nicht." \
                "    Auf der anderen Node muss der Stack mit HJ_SPOCK=true laufen -" \
                "    nur docker-compose.spock.yml veroeffentlicht den Port auf der" \
                "    privaten Adresse. Ohne das ist Postgres dort nur im Docker-Netz.")"
        fi
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
