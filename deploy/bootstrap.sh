#!/usr/bin/env bash
#
# hoer.jetzt - Bootstrap.
#
#   curl -fsSL https://repository.hoer.jetzt/bootstrap | bash -s -- \
#        --rolle node --modules core,lavalink --kennung core-3 --token hj-....
#
#   --rolle node|controller|update-server
#   --modules <liste>     nur bei --rolle node
#   --kennung, --token    aus dem Updater
#   --zweig <name>        Vorgabe: main
#
# ---------------------------------------------------------------------------
# Was dieses Skript tut und was nicht
#
# Es holt genau so viel, wie noetig ist, um den richtigen Installer starten zu
# koennen - Voraussetzungen und den Quellstand der Deploy-Skripte. Alles
# Weitere macht der Installer.
#
# Die Aufteilung hat einen Grund: dieses Skript wird durch eine Pipe in bash
# gegeben. Was hier steht, laesst sich vorher nicht lesen und nachher nicht
# nachvollziehen. Deshalb bleibt es kurz und trifft keine Entscheidungen, die
# man spaeter bereut.
#
# ---------------------------------------------------------------------------
# Warum kein Geheimnis in der URL
#
# Der Aufsetz-Token steht als Argument, nicht im Pfad. URLs landen in
# Zugriffsprotokollen, in der Shell-Historie des Aufrufers und in jedem
# Zwischenspeicher unterwegs. Der Token ist zwar kurzlebig, aber zwei Stunden
# reichen, wenn er in einem Protokoll steht, das jemand liest.
# ---------------------------------------------------------------------------

set -euo pipefail

ROLLE=""
ZWEIG="main"
UPDATE_HOST="${HJ_UPDATE_HOST:-repository.hoer.jetzt}"
DURCHREICHEN=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rolle)       ROLLE="${2:?Rolle angeben}"; shift 2 ;;
        --zweig)       ZWEIG="${2:?Zweig angeben}"; shift 2 ;;
        --update-host) UPDATE_HOST="${2:?Adresse angeben}"; shift 2 ;;
        *)             DURCHREICHEN+=("$1"); shift ;;
    esac
done

sagen() { printf '[bootstrap] %s\n' "$*"; }
fehler() { printf '[bootstrap] FEHLER: %s\n' "$*" >&2; exit 1; }

[[ -n "$ROLLE" ]] || fehler "--rolle angeben: node, controller oder update-server"
[[ "$(id -u)" == "0" ]] || fehler "Als root ausfuehren."

# Der Update-Server kann sich nicht von sich selbst holen.
#
# Alles Uebrige bezieht seinen Stand von repository.hoer.jetzt. Fuer die
# Rolle update-server gibt es diese Adresse aber noch gar nicht - sie entsteht
# ja gerade erst. Deshalb geht dieser eine Weg ueber GitHub, und deshalb steht
# er in einem eigenen Skript.
#
# Frueher lief er hier durch dieselbe Kette wie die anderen und haette sich
# beim Herunterladen selbst gesucht.
if [[ "$ROLLE" == "update-server" ]]; then
    sagen "Rolle update-server - Quelle ist GitHub, nicht der Update-Server."
    HOLEN="$(mktemp)"
    trap 'rm -f "$HOLEN"' EXIT
    if ! curl -fsSL \
        "https://raw.githubusercontent.com/MarcoEckerlin/hoer.jetzt/${ZWEIG}/deploy/install-update-server.sh" \
        -o "$HOLEN"; then
        fehler "install-update-server.sh nicht von GitHub ladbar."
    fi
    exec bash "$HOLEN" --zweig "$ZWEIG" "${DURCHREICHEN[@]}"
fi

# ------------------------------------------------------ Voraussetzungen

sagen "Voraussetzungen pruefen"

if command -v apt-get >/dev/null; then
    fehlend=()
    for w in curl openssl tar ca-certificates util-linux python3; do
        dpkg -s "$w" >/dev/null 2>&1 || fehlend+=("$w")
    done
    if [[ ${#fehlend[@]} -gt 0 ]]; then
        sagen "Nachinstallieren: ${fehlend[*]}"
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y -qq "${fehlend[@]}"
    fi
else
    # Kein apt: dann muss es von Hand da sein. Raten waere hier falsch - ein
    # Bootstrap, der auf einem unbekannten System Pakete installiert, ist
    # genau das, was man einem Skript aus einer Pipe nicht zutrauen will.
    for w in curl openssl tar; do
        command -v "$w" >/dev/null || fehler "${w} fehlt und apt-get gibt es nicht."
    done
fi

if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
    sagen "Docker einrichten"
    curl -fsSL https://get.docker.com | sh
    docker compose version >/dev/null 2>&1 || fehler "Docker Compose fehlt weiterhin."
fi

# ------------------------------------------------------- Skripte holen

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
mkdir -p "${ARBEIT}"

sagen "Deploy-Stand holen (${ZWEIG})"

# Ueber /knoten/ - der einzige Bereich, den ein noch nicht freigeschalteter
# Rechner erreicht. Das Aufsetz-Passwort fragt curl selbst ab, damit es nicht
# in der Shell-Historie landet; das "-u knoten" ohne Doppelpunkt ist Absicht.
if ! curl -fsSL -u knoten "https://${UPDATE_HOST}/knoten/${ZWEIG}.tar.gz" \
        -o "${ARBEIT}/deploy.tar.gz"; then
    fehler "Deploy-Stand nicht abrufbar von ${UPDATE_HOST}."
fi

tar -C "${ARBEIT}" -xzf "${ARBEIT}/deploy.tar.gz"
rm -f "${ARBEIT}/deploy.tar.gz"

# ------------------------------------------------------------ Weiter

INSTALL="${ARBEIT}/${ZWEIG}/deploy"
[[ -d "$INSTALL" ]] || fehler "${INSTALL} fehlt - falscher Zweig?"

case "$ROLLE" in
    node)
        sagen "Weiter mit install-node.sh"
        exec bash "${INSTALL}/install-node.sh" --update-host "$UPDATE_HOST" "${DURCHREICHEN[@]}"
        ;;
    controller)
        sagen "Weiter mit install-controller.sh"
        exec bash "${INSTALL}/install-controller.sh" --update-host "$UPDATE_HOST" "${DURCHREICHEN[@]}"
        ;;
    *)
        fehler "Unbekannte Rolle: ${ROLLE}"
        ;;
esac
