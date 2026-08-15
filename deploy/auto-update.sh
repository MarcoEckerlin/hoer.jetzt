#!/usr/bin/env bash
#
# hoer.jetzt - naechtliches Update auf das jeweils neueste Release.
#
#   bash auto-update.sh              normal, wartet auf eine ruhige Minute
#   bash auto-update.sh --jetzt      sofort, ohne auf Zuhoerer zu ruecksichtigen
#   bash auto-update.sh --pruefen    nur nachsehen, nichts aendern
#
# Ein Release ist ein Tag "v..." auf main. Die Datei RELEASE darin sagt, welcher
# Stand von core, ai-radio und lavalink dazugehoert - die Zweige haben keine
# gemeinsame Historie, ein Tag allein koennte sie nicht zusammenhalten.
#
# Der ungetaggte Stand eines Zweiges wird bewusst ignoriert. Ein Push soll nicht
# in derselben Nacht auf einem Produktivsystem landen.

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
UMGEBUNG="${ARBEIT}/.env"
STAND="${ARBEIT}/.installiert"
PROTOKOLL="${PROTOKOLL:-/var/log/hoerjetzt-update.log}"
SPERRE="/var/lock/hoerjetzt-update.lock"

# Wartet auf eine Wiedergabepause: alle WARTE_TAKT Sekunden neu nachsehen,
# hoechstens WARTE_VERSUCHE mal. Danach bleibt es beim alten Stand und die
# naechste Nacht bekommt eine neue Gelegenheit.
WARTE_TAKT="${WARTE_TAKT:-900}"
WARTE_VERSUCHE="${WARTE_VERSUCHE:-8}"

NUR_PRUEFEN=0
SOFORT=0
for argument in "$@"; do
    case "$argument" in
        --pruefen) NUR_PRUEFEN=1 ;;
        --jetzt)   SOFORT=1 ;;
        *) echo "Unbekannt: ${argument}" >&2; exit 2 ;;
    esac
done

sagen() {
    printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$PROTOKOLL"
}

ende() {
    sagen "$*"
    exit 0
}

fehler() {
    sagen "FEHLER: $*"
    exit 1
}

# Compose haengt an den Dienstnamen eine laufende Nummer - der Container heisst
# also hoerjetzt-core-1, nicht hoerjetzt-core. Beides pruefen, damit es auch
# bei einer Installation ohne Compose stimmt.
kernbehaelter() {
    for name in hoerjetzt-core-1 hoerjetzt-core; do
        if docker inspect "$name" >/dev/null 2>&1; then
            printf '%s' "$name"
            return 0
        fi
    done
    printf '%s' "hoerjetzt-core-1"
}

mkdir -p "$(dirname "$PROTOKOLL")" "$(dirname "$SPERRE")" 2>/dev/null || true

# Zwei gleichzeitige Laeufe waeren ein halb gebautes System. Der zweite geht.
exec 9>"$SPERRE"
flock -n 9 || ende "Ein Update laeuft bereits - dieser Lauf entfaellt."

[[ -f "$UMGEBUNG" ]] || fehler "${UMGEBUNG} fehlt."

# Ist das Repository privat, steht in der .env eine SSH-Adresse. Die gilt dann
# auch hier - sonst liefe das naechtliche Update in eine Passwortabfrage.
if grep -q '^REPO=' "$UMGEBUNG"; then
    REPO="$(grep '^REPO=' "$UMGEBUNG" | cut -d= -f2-)"
    export REPO
fi
[[ -d "${ARBEIT}/main/.git" ]] || fehler "${ARBEIT}/main ist kein Arbeitsverzeichnis."

for zweig in main core ai-radio lavalink; do
    git config --global --add safe.directory "${ARBEIT}/${zweig}" 2>/dev/null || true
done

# ------------------------------------------------------------------ Release

git -C "${ARBEIT}/main" fetch -q --tags --force origin || fehler "GitHub nicht erreichbar."

NEUSTE="$(git -C "${ARBEIT}/main" tag -l 'v*' --sort=-v:refname | head -n1)"
[[ -n "$NEUSTE" ]] || ende "Kein Release getaggt - nichts zu tun."

AKTUELL="$(cat "$STAND" 2>/dev/null || true)"
if [[ "$NEUSTE" == "$AKTUELL" ]]; then
    ende "${NEUSTE} laeuft bereits."
fi

sagen "Neues Release: ${NEUSTE} (installiert: ${AKTUELL:-unbekannt})"
[[ "$NUR_PRUEFEN" -eq 1 ]] && ende "Nur geprueft, nichts geaendert."

# ------------------------------------------------------------------ Compose

COMPOSE_DATEI="docker-compose.yml"
grep -q '^TS_AUTHKEY=.\+' "$UMGEBUNG" && COMPOSE_DATEI="docker-compose.tailscale.yml"
DOCKER="${ARBEIT}/main/deploy/docker"

# ------------------------------------------------------------------ Zuhoerer

# Fragt den mitlaufenden Knoten, wie viele Wiedergaben gerade aktiv sind.
# Kommt keine Antwort, wird das als "keine" gewertet: ein kaputter Knoten ist
# kein Grund, ein Update ewig aufzuschieben.
spielende() {
    local passwort antwort
    passwort="$(grep '^HJ_LAVALINK_PASSWORD=' "$UMGEBUNG" | cut -d= -f2- || true)"
    [[ -n "$passwort" ]] || { echo 0; return 0; }

    antwort="$(cd "$DOCKER" && docker compose -f "$COMPOSE_DATEI" exec -T lavalink-free-1 \
        curl -fsS -m 5 -H "Authorization: ${passwort}" \
        http://127.0.0.1:2333/v4/stats 2>/dev/null || true)"
    [[ -n "$antwort" ]] || { echo 0; return 0; }

    printf '%s' "$antwort" | python3 -c \
        'import json,sys; print(json.load(sys.stdin).get("playingPlayers", 0))' 2>/dev/null || echo 0
}

if [[ "$SOFORT" -eq 0 ]]; then
    versuch=1
    while :; do
        aktiv="$(spielende)"
        [[ "$aktiv" -eq 0 ]] && break

        if [[ "$versuch" -ge "$WARTE_VERSUCHE" ]]; then
            ende "Nach ${versuch} Versuchen laeuft immer noch Musik (${aktiv}) - Update verschoben."
        fi
        sagen "${aktiv} Wiedergabe(n) aktiv - neuer Versuch in $((WARTE_TAKT / 60)) Minuten (${versuch}/${WARTE_VERSUCHE})."
        sleep "$WARTE_TAKT"
        versuch=$((versuch + 1))
    done
    sagen "Niemand hoert gerade zu - los."
fi

# ------------------------------------------------------------------ Umstellen

git -C "${ARBEIT}/main" reset -q --hard "$NEUSTE" || fehler "main laesst sich nicht auf ${NEUSTE} stellen."

MANIFEST="${ARBEIT}/main/RELEASE"
[[ -f "$MANIFEST" ]] || fehler "RELEASE fehlt in ${NEUSTE}."

for zweig in core ai-radio lavalink; do
    ziel="$(grep "^${zweig}=" "$MANIFEST" | cut -d= -f2- || true)"
    [[ -n "$ziel" ]] || fehler "RELEASE nennt keinen Stand fuer ${zweig}."

    if [[ ! -d "${ARBEIT}/${zweig}/.git" ]]; then
        fehler "${ARBEIT}/${zweig} fehlt - bitte einmal install.sh laufen lassen."
    fi

    git -C "${ARBEIT}/${zweig}" fetch -q origin "$zweig" || fehler "Zweig ${zweig} nicht erreichbar."
    git -C "${ARBEIT}/${zweig}" reset -q --hard "$ziel" || fehler "${zweig} laesst sich nicht auf ${ziel} stellen."
    sagen "$(printf '%-9s %s' "$zweig" "${ziel:0:12}")"
done

# ------------------------------------------------------------------ Bauen

cd "$DOCKER"
cp "$UMGEBUNG" .env
chmod 600 .env

if ! docker compose -f "$COMPOSE_DATEI" build >>"$PROTOKOLL" 2>&1; then
    fehler "Build fehlgeschlagen - alter Stand laeuft weiter. Einzelheiten in ${PROTOKOLL}."
fi

# Erst hier wird umgeschaltet. Schlaegt der Build fehl, hat der laufende Stack
# nichts davon mitbekommen.
if ! docker compose -f "$COMPOSE_DATEI" up -d >>"$PROTOKOLL" 2>&1; then
    fehler "Start fehlgeschlagen - siehe ${PROTOKOLL}."
fi

printf '%s\n' "$NEUSTE" > "$STAND"

sleep 30
KERN="$(kernbehaelter)"
zustand="$(docker inspect -f '{{.State.Status}}' "$KERN" 2>/dev/null || echo fehlt)"
if [[ "$zustand" == "running" ]]; then
    sagen "${NEUSTE} laeuft."
else
    sagen "WARNUNG: core steht auf '${zustand}' - docker logs ${KERN}"
fi

# Aufgeraeumt wird erst nach dem erfolgreichen Start: die alten Abbilder sind
# bis dahin der Rueckweg.
docker image prune -f >/dev/null 2>&1 || true
