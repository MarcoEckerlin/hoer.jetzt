#!/usr/bin/env bash
#
# hoer.jetzt - eine leere Hetzner-Maschine zu einer Node machen.
#
#   bash <(curl -fsSL https://raw.githubusercontent.com/MarcoEckerlin/hoer.jetzt/main/deploy/hetzner-autoinstall.sh)
#
# oder, wenn das Repository privat ist, erst zugang-einrichten.sh und dann
# dieses Skript aus dem Klon heraus.
#
# Es fragt nur nach dem, was es nicht selbst herausfinden kann. Alles andere -
# private Adresse, Rechnername, Speicher, Kerne - liest es aus der Maschine.

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
REPO="${REPO:-https://github.com/MarcoEckerlin/hoer.jetzt.git}"

step()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info()  { printf '    %s\n' "$*"; }
warn()  { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail()  { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

frage() {
    local __v="$1" __t="$2" __d="${3:-}" __e=""
    if [[ -n "$__d" ]]; then
        read -r -p "    ${__t} [${__d}]: " __e || true
        __e="${__e:-$__d}"
    else
        while [[ -z "$__e" ]]; do read -r -p "    ${__t}: " __e || true; done
    fi
    printf -v "$__v" '%s' "$__e"
}
geheim() {
    local __v="$1" __t="$2" __e=""
    while [[ -z "$__e" ]]; do read -r -s -p "    ${__t}: " __e || true; echo; done
    printf -v "$__v" '%s' "$__e"
}
ja() {
    local __a=""
    read -r -p "    $1 (j/n) [${2:-j}]: " __a || true
    [[ "${__a:-${2:-j}}" =~ ^[jJ] ]]
}
# Kein "tr ... | head -c": head schliesst die Pipe, tr bekommt SIGPIPE, und
# unter "set -o pipefail" endet das Skript wortlos.
zufall() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32; }

[[ "$(id -u)" -eq 0 ]] || fail "Bitte als root starten."

cat <<'KOPF'

    hoer.jetzt - Node einrichten

    Diese Maschine bekommt: Docker, die Zweige des Projekts, eine
    Konfiguration und eine Firewall. Danach laeuft entweder ein
    vollstaendiger Stack oder nur ein Audio-Knoten.

KOPF

# ------------------------------------------------------------------ 1  Lage

step "Maschine"
KERNE="$(nproc)"
SPEICHER_MB="$(free -m | awk '/^Mem:/ {print $2}')"
RECHNER="$(hostname -s)"
info "$(printf '%-14s %s' "Rechner" "$RECHNER")"
info "$(printf '%-14s %s Kerne, %s MB' "Ausstattung" "$KERNE" "$SPEICHER_MB")"

# Hetzner haengt das private Netz an eine zweite Schnittstelle. Die oeffentliche
# ist die mit der Standardroute - alles andere mit einer 10.x-Adresse ist
# privat. Das automatisch zu finden erspart die haeufigste Fehleingabe.
OEFFENTLICH="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}' || true)"
PRIVAT="$(ip -4 -o addr show 2>/dev/null \
    | awk '{print $4}' | cut -d/ -f1 \
    | grep -E '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.)' \
    | grep -v "^${OEFFENTLICH}$" | head -n1 || true)"

info "$(printf '%-14s %s' "Oeffentlich" "${OEFFENTLICH:-unbekannt}")"
if [[ -n "$PRIVAT" ]]; then
    info "$(printf '%-14s %s  (Hetzner Private Network)' "Privat" "$PRIVAT")"
else
    warn "Keine private Adresse gefunden."
    warn "Ohne privates Netz muessen Datenbank und Steuerung ueber das offene"
    warn "Netz laufen - das will man nicht. In der Hetzner-Konsole ein"
    warn "Private Network anlegen, diesen Server hineinhaengen, neu starten."
    ja "Trotzdem weitermachen?" n || exit 1
fi

# ------------------------------------------------------------------ 2  Rolle

step "Was soll auf diese Maschine?"
info "  1) Vollstaendiger Stack - Bot, Weboberflaeche, Datenbank, ein Audio-Knoten"
info "  2) Nur ein Audio-Knoten - fuer zusaetzliche Wiedergabekapazitaet"
frage ROLLE "Auswahl" "1"
case "$ROLLE" in
    1|2) ;;
    *) fail "Bitte 1 oder 2." ;;
esac

# ------------------------------------------------------------------ 3  Pakete

step "Grundausstattung"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq git curl ca-certificates ufw >/dev/null
info "git, curl, ufw"

if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh >/dev/null 2>&1 || fail "Docker-Installation fehlgeschlagen."
    systemctl enable --now docker >/dev/null 2>&1 || true
fi
docker compose version >/dev/null 2>&1 || fail "docker compose (v2) fehlt."
info "$(docker --version)"

# Container koennen haeufig keine Namen aufloesen, weil /etc/resolv.conf des
# Hosts auf 127.0.0.53 zeigt (systemd-resolved) - das laeuft im Container ins
# Leere. Der Fehler zeigt sich spaeter als "UnknownHostException", versteckt
# hinter einer Spring-Meldung.
if ! docker run --rm alpine:3 getent hosts github.com >/dev/null 2>&1; then
    warn "Container koennen keine Namen aufloesen - trage feste Namensserver ein."
    mkdir -p /etc/docker
    if [[ -s /etc/docker/daemon.json ]]; then
        python3 - <<'PYTHON'
import json, pathlib
pfad = pathlib.Path("/etc/docker/daemon.json")
daten = json.loads(pfad.read_text() or "{}")
daten.setdefault("dns", ["1.1.1.1", "8.8.8.8"])
pfad.write_text(json.dumps(daten, indent=2))
PYTHON
    else
        printf '{\n  "dns": ["1.1.1.1", "8.8.8.8"]\n}\n' > /etc/docker/daemon.json
    fi
    systemctl restart docker
    sleep 3
fi

# ------------------------------------------------------------------ 4  Code

# Nie nach einem Passwort fragen. Das Repository ist oeffentlich; kommt hier
# trotzdem eine Abfrage, liegt ein kaputter Zugangsspeicher vor - und ein
# Skript, das darauf wartet, haengt, bis jemand nachsieht.
export GIT_TERMINAL_PROMPT=0

step "Zweige holen"
mkdir -p "$ARBEIT"
ZWEIGE=(main core ai-radio lavalink web)
[[ "$ROLLE" == "2" ]] && ZWEIGE=(lavalink)

for zweig in "${ZWEIGE[@]}"; do
    ziel="${ARBEIT}/${zweig}"
    [[ "$ROLLE" == "2" ]] && ziel="/opt/hoerjetzt-node"
    if [[ -d "${ziel}/.git" ]]; then
        # Frueher stand hier ein "&&" und darunter die Erfolgsmeldung ohne
        # Bedingung: schlug der Abgleich fehl, meldete das Skript trotzdem
        # "aktualisiert" und lief mit dem alten Stand weiter. Ein Installer,
        # der Fehlschlaege als Erfolg meldet, ist schlimmer als keiner.
        if git -C "$ziel" fetch -q origin "$zweig" \
           && git -C "$ziel" reset -q --hard "origin/${zweig}"; then
            info "${zweig}: aktualisiert"
        else
            fail "Zweig ${zweig} liess sich nicht abgleichen. Bei einer Passwortabfrage: das Repository ist oeffentlich, ein gespeicherter Zugang stoert. Mit 'git config --global --unset-all credential.helper' und 'rm -f ~/.git-credentials' loeschen."
        fi
    else
        if ! git clone -q -b "$zweig" --single-branch "$REPO" "$ziel" 2>/dev/null; then
            # Zwei sehr verschiedene Faelle, die frueher dieselbe Meldung
            # bekamen: das Repository ist zu (dann kommt man ueberhaupt nicht
            # heran), oder es ist offen und dieser Zweig fehlt schlicht - weil
            # der Stand auf GitHub aelter ist als dieses Skript. Wer im zweiten
            # Fall zugang-einrichten.sh laufen laesst, sucht eine Stunde am
            # falschen Ende.
            if git ls-remote --heads "$REPO" >/dev/null 2>&1; then
                fail "Zweig ${zweig} gibt es im Repository nicht. Dieses Skript gehoert zu einem neueren Release - erst den aktuellen Stand hochladen (git push origin ${zweig})."
            fi
            fail "Repository nicht erreichbar. Privat? Dann erst zugang-einrichten.sh."
        fi
        info "${zweig}: geholt"
    fi
done

if [[ "$ROLLE" == "2" ]]; then
    step "Audio-Knoten einrichten"
    info "Weiter geht es mit dem Installer des Knotens - er fragt nach Stufe,"
    info "Qualitaet und YouTube-Anmeldung."
    echo
    exec bash /opt/hoerjetzt-node/install.sh
fi

# ------------------------------------------------------------------ 5  Konfig

step "Konfiguration"
UMGEBUNG="${ARBEIT}/.env"
if [[ -f "$UMGEBUNG" ]]; then
    info "${UMGEBUNG} gibt es schon - wird nicht ueberschrieben."
else
    info "Die Angaben stehen im Discord Developer Portal unter deiner Anwendung."
    geheim BOT_TOKEN "Bot-Token"
    frage CLIENT_ID "Client-ID"
    geheim CLIENT_SECRET "Client-Secret"
    frage BASIS_URL "Oeffentliche Adresse der Weboberflaeche" "https://${RECHNER}"
    echo
    frage NODE_NR "Nummer dieser Node im Verbund" "1"
    frage NODE_NAME "Name dieser Node" "$RECHNER"

    DB_PASS="$(zufall)"
    LAVA_PASS="$(zufall)"

    umask 077
    cat > "$UMGEBUNG" <<UMG
# Erzeugt von hetzner-autoinstall.sh am $(date -Is)
HJ_BOT_TOKEN=${BOT_TOKEN}
HJ_BOT_ID=1
HJ_DISCORD_CLIENT_ID=${CLIENT_ID}
HJ_DISCORD_CLIENT_SECRET=${CLIENT_SECRET}
HJ_WEB_BASE_URL=${BASIS_URL}
HJ_WEB_BIND=0.0.0.0
HJ_WEB_PORT_HOST=8080

HJ_DB_HOST=postgres
HJ_DB_PORT=5432
HJ_DB_NAME=discordbot
HJ_DB_USER=discordbot
HJ_DB_PASSWORD=${DB_PASS}

HJ_REDIS_HOST=redis
HJ_REDIS_PORT=6379

HJ_LAVALINK_PASSWORD=${LAVA_PASS}
HJ_LAVALINK_WATCH_SECONDS=30
HJ_LAVALINK_FREE_OVERFLOW=true

HJ_NODE_NR=${NODE_NR}
HJ_NODE_NAME=${NODE_NAME}
HJ_PRIVAT_IP=${PRIVAT}

# Zentrale Steuerung. Auf der Steuer-Node ein Token erzeugen und hier wie auf
# allen anderen Nodes eintragen:  openssl rand -hex 32
HJ_CONTROLLER_URL=
HJ_CONTROLLER_TOKEN=
UMG
    chmod 600 "$UMGEBUNG"
    info "${UMGEBUNG} angelegt (nur fuer root lesbar)."
    info "Datenbank- und Lavalink-Passwort wurden erzeugt."
fi

# ------------------------------------------------------------------ 6  Firewall

step "Firewall"
# Der Grundsatz: von aussen nur SSH und Web, im privaten Netz alles. Ohne die
# zweite Regel finden sich die Nodes nicht; ohne die erste steht die Datenbank
# im offenen Netz.
ufw --force reset >/dev/null 2>&1 || true
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
info "offen nach aussen: 22, 80, 443"

if [[ -n "$PRIVAT" ]]; then
    NETZ="$(ip -4 -o addr show | awk -v ip="$PRIVAT" '$4 ~ ip {print $4}' | head -n1)"

    # Hetzner haengt die private Adresse je nach Aufbau mit /32 an die
    # Schnittstelle und legt das eigentliche Netz als Route dazu. Aus einem
    # /32 ergaebe sich eine Firewallregel, die nur diese eine Maschine
    # erlaubt - also genau niemanden. Dann lieber die Route fragen.
    if [[ "$NETZ" == */32 ]]; then
        NETZ="$(ip -4 route show dev "$(ip -4 -o addr show | awk -v ip="$PRIVAT" '$4 ~ ip {print $2}' | head -n1)" \
            | awk '$1 ~ /\// && $1 !~ /^default/ {print $1; exit}' || true)"
        [[ -n "$NETZ" ]] || NETZ="10.0.0.0/8"
        warn "Private Adresse steht als /32 - nehme das Netz aus der Route: ${NETZ}"
    fi

    BEREICH="$(python3 -c "
import ipaddress
print(ipaddress.ip_network('${NETZ}', strict=False))
" 2>/dev/null || echo "10.0.0.0/8")"

    if [[ "$BEREICH" == */32 ]]; then
        warn "Netz laesst sich nicht bestimmen - nehme 10.0.0.0/8."
        BEREICH="10.0.0.0/8"
    fi

    ufw allow from "$BEREICH" >/dev/null
    info "offen im privaten Netz (${BEREICH}): alles"
else
    warn "Kein privates Netz - Datenbank und Steuerung bleiben nur lokal erreichbar."
fi

ufw --force enable >/dev/null
info "Firewall aktiv."

# Docker umgeht ufw normalerweise: veroeffentlichte Ports landen direkt in
# iptables und sind offen, egal was ufw sagt. Deshalb bindet der Stack seine
# Ports an bestimmte Adressen statt an 0.0.0.0 - siehe HJ_WEB_BIND und
# HJ_PRIVAT_IP in der .env.
warn "Docker umgeht ufw bei veroeffentlichten Ports. Der Stack bindet deshalb"
warn "gezielt - pruefe nach dem Start mit:  ss -tlnp | grep docker"

# ------------------------------------------------------------------ 7  Start

step "Stack starten"
cd "${ARBEIT}/main/deploy/docker"
cp "$UMGEBUNG" .env
docker compose up -d --build || fail "Der Stack startet nicht. Log: docker compose logs"

step "Nachsehen"
sleep 20
docker compose ps

step "Fertig"
echo
info "Weboberflaeche:  http://${OEFFENTLICH:-localhost}:8080"
info "Konfiguration:   ${UMGEBUNG}"
info "Logs:            cd ${ARBEIT}/main/deploy/docker && docker compose logs -f core"
echo
info "Als Naechstes:"
info "  1. Redirect-URI im Discord Developer Portal eintragen:"
info "     ${BASIS_URL:-<adresse>}/auth/discord/callback"
info "  2. Reverse-Proxy mit Zertifikat davor - Discord verlangt https."
info "  3. Bei mehreren Nodes: Agent einrichten, siehe BEFEHLE.md."
echo
