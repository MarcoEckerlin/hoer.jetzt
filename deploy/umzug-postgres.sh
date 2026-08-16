#!/usr/bin/env bash
#
# hoer.jetzt - von MariaDB nach PostgreSQL umziehen.
#
#   bash umzug-postgres.sh
#
# Holt die Daten aus der bisherigen MariaDB und schreibt sie in die neue
# PostgreSQL. Das Schema legt der Bot beim ersten Start selbst an - dieses
# Skript bringt nur den Inhalt hinueber.
#
# Es ist bewusst zweistufig: erst wird gelesen und geprueft, dann geschrieben.
# Bricht etwas ab, steht die alte Datenbank unveraendert da und der alte Stand
# laeuft weiter. Es gibt keinen Punkt, an dem beide kaputt waeren.

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
UMGEBUNG="${UMGEBUNG:-${ARBEIT}/.env}"
SICHERUNG="${SICHERUNG:-/var/backups/hoerjetzt-umzug-$(date +%F-%H%M)}"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f "$UMGEBUNG" ]] || fail "${UMGEBUNG} nicht gefunden."
# shellcheck disable=SC1090
set -a; source "$UMGEBUNG"; set +a

: "${ALT_DB_HOST:?ALT_DB_HOST setzen - Adresse der bisherigen MariaDB}"
: "${ALT_DB_NAME:=${HJ_DB_NAME:-discordbot}}"
: "${ALT_DB_USER:?ALT_DB_USER setzen}"
: "${ALT_DB_PASSWORD:?ALT_DB_PASSWORD setzen}"

command -v docker >/dev/null 2>&1 || fail "Docker fehlt."
mkdir -p "$SICHERUNG"

# ------------------------------------------------------------------ 1

step "Alten Stand sichern"
# --skip-ssl, weil die alte Installation haeufig ohne Zertifikat laeuft und
# neuere Clients sonst von sich aus TLS verlangen.
docker run --rm mariadb:11 mariadb-dump \
    --single-transaction --skip-ssl --no-tablespaces \
    -h "$ALT_DB_HOST" -u "$ALT_DB_USER" -p"$ALT_DB_PASSWORD" "$ALT_DB_NAME" \
    | gzip > "${SICHERUNG}/mariadb.sql.gz" || fail "Sicherung fehlgeschlagen."
info "$(du -h "${SICHERUNG}/mariadb.sql.gz" | cut -f1) nach ${SICHERUNG}/mariadb.sql.gz"

# ------------------------------------------------------------------ 2

step "Neue Datenbank vorbereiten"
cd "${ARBEIT}/main/deploy/docker"
cp "$UMGEBUNG" .env
docker compose up -d postgres || fail "PostgreSQL startet nicht."

for i in $(seq 1 30); do
    if docker compose exec -T postgres pg_isready -U "${HJ_DB_USER:-discordbot}" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
docker compose exec -T postgres pg_isready -U "${HJ_DB_USER:-discordbot}" >/dev/null 2>&1 \
    || fail "PostgreSQL antwortet nicht."
info "PostgreSQL laeuft."

step "Schema anlegen"
info "Dafuer startet der Bot einmal kurz - er legt die Tabellen selbst an."
docker compose up -d core
sleep 25
TABELLEN="$(docker compose exec -T postgres psql -U "${HJ_DB_USER:-discordbot}" \
    -d "${HJ_DB_NAME:-discordbot}" -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")"
docker compose stop core >/dev/null
info "${TABELLEN} Tabellen angelegt."
[[ "${TABELLEN:-0}" -ge 16 ]] || fail "Es fehlen Tabellen - Log ansehen: docker compose logs core"

# ------------------------------------------------------------------ 3

step "Daten uebertragen"
info "pgloader liest direkt aus MariaDB und schreibt nach PostgreSQL."
info "Das Schema steht schon, deshalb nur die Inhalte."

NETZ="$(docker network ls --format '{{.Name}}' | grep -E 'hoerjetzt' | head -n1)"
[[ -n "$NETZ" ]] || fail "Docker-Netz des Stacks nicht gefunden."

cat > "${SICHERUNG}/umzug.load" <<LOAD
LOAD DATABASE
    FROM mysql://${ALT_DB_USER}:${ALT_DB_PASSWORD}@${ALT_DB_HOST}/${ALT_DB_NAME}
    INTO postgresql://${HJ_DB_USER:-discordbot}:${HJ_DB_PASSWORD}@postgres/${HJ_DB_NAME:-discordbot}

WITH data only, truncate, disable triggers,
     workers = 4, concurrency = 1

SET work_mem to '64MB', maintenance_work_mem to '256MB'

-- tinyint(1) ist in MariaDB ein Wahrheitswert, in PostgreSQL ein Fehler.
CAST type tinyint to boolean using tinyint-to-boolean,
     type datetime to timestamp,
     type date to date

-- Tabellen, die es nur in der alten Installation gab.
EXCLUDING TABLE NAMES MATCHING 'api_auth_passwordReset'
;
LOAD

docker run --rm --network "$NETZ" \
    -v "${SICHERUNG}:/arbeit" \
    dimitri/pgloader:latest pgloader /arbeit/umzug.load \
    || fail "Uebertragung fehlgeschlagen. Die alte Datenbank ist unveraendert."

# ------------------------------------------------------------------ 4

step "Gegenzaehlen"
FEHLT=0
for tabelle in settings deployments deployment_lavalink_nodes guild_module_settings \
               bot_admins guild_entitlements guild_role_permissions; do
    ALT="$(docker run --rm mariadb:11 mariadb --skip-ssl -N -B \
        -h "$ALT_DB_HOST" -u "$ALT_DB_USER" -p"$ALT_DB_PASSWORD" "$ALT_DB_NAME" \
        -e "SELECT count(*) FROM ${tabelle}" 2>/dev/null || echo "?")"
    NEU="$(docker compose exec -T postgres psql -U "${HJ_DB_USER:-discordbot}" \
        -d "${HJ_DB_NAME:-discordbot}" -tAc "SELECT count(*) FROM ${tabelle}" 2>/dev/null || echo "?")"
    if [[ "$ALT" == "$NEU" ]]; then
        info "$(printf '%-28s %6s = %-6s ok' "$tabelle" "$ALT" "$NEU")"
    else
        warn "$(printf '%-28s %6s > %-6s ABWEICHUNG' "$tabelle" "$ALT" "$NEU")"
        FEHLT=1
    fi
done

# Die Sequenz muss ueber den hoechsten uebernommenen Wert hinaus zeigen -
# sonst vergibt der naechste Eintrag eine Nummer, die es schon gibt.
step "Nummernvergabe nachziehen"
docker compose exec -T postgres psql -U "${HJ_DB_USER:-discordbot}" -d "${HJ_DB_NAME:-discordbot}" -q <<'SQL'
SELECT setval('hj_id_seq', GREATEST(
    (SELECT COALESCE(max(id), 0) FROM deployments),
    (SELECT COALESCE(max(id), 0) FROM deployment_lavalink_nodes),
    (SELECT COALESCE(max(id), 0) FROM ticket_transcripts),
    (SELECT COALESCE(max(id), 0) FROM music_track_events),
    (SELECT COALESCE(max(id), 0) FROM music_listener_events),
    (SELECT COALESCE(max(id), 0) FROM admin_audit_log),
    1) + 1000);
SQL
info "hj_id_seq steht ueber dem hoechsten uebernommenen Wert."

# Die Slug-Spalte gab es in der alten Datenbank nicht - aus dem JSON nachziehen.
step "Einladungs-Kurzlinks nachtragen"
docker compose exec -T postgres psql -U "${HJ_DB_USER:-discordbot}" -d "${HJ_DB_NAME:-discordbot}" -q <<'SQL'
UPDATE guild_module_settings
SET invite_slug = settings_json::json -> 'invite' ->> 'slug'
WHERE settings_json::json -> 'invite' ->> 'slug' IS NOT NULL
  AND settings_json::json -> 'invite' ->> 'slug' <> ''
  AND (settings_json::json -> 'invite' ->> 'enabled')::boolean;
SQL

step "Fertig"
if [[ "$FEHLT" -eq 1 ]]; then
    warn "Mindestens eine Tabelle weicht ab. NICHT umschalten, bevor das geklaert ist."
    warn "Der alte Stand laeuft unveraendert weiter."
    exit 1
fi
echo
info "Alle geprueften Tabellen stimmen ueberein."
info "Starten:   cd ${ARBEIT}/main/deploy/docker && docker compose up -d"
info "Sicherung: ${SICHERUNG}"
echo
warn "Die alte MariaDB einen Monat stehen lassen, bevor du sie abraeumst."
echo
