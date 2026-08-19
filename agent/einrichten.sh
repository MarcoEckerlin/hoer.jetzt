#!/usr/bin/env bash
#
# Richtet den Knoten-Agenten als systemd-Dienst ein.
#
#   bash agent/einrichten.sh
#
# Wird von install.sh und update.sh aufgerufen und laesst sich auch einzeln
# starten. Alle Angaben kommen aus der Umgebung, damit dasselbe Skript sowohl
# aus der Abfrage in install.sh als auch aus cloud-init heraus funktioniert -
# beim Autoscaling fragt niemand etwas ab.
#
# Erwartet:
#   HJ_AGENT_TOKEN       Geheimnis fuer den Agenten selbst
#   HJ_NODE_CONTAINER    Name des Lavalink-Containers
#   HJ_NODE_NAME         Name des Knotens, unter dem er beim Bot steht
#   HJ_NODE_ADDRESS      Adresse, unter der der Bot Lavalink erreicht
#   HJ_LAVALINK_PASSWORD Passwort des Knotens
#   HJ_NODE_TIER         free oder premium
# Optional:
#   HJ_CORE_URL          Adresse des Bots - ohne sie keine Selbstanmeldung
#   HJ_NODE_TOKEN        gemeinsames Geheimnis fuer die Selbstanmeldung
#   HJ_AGENT_PORT        Vorgabe 8099
#   HJ_AGENT_BIND        Vorgabe: private Adresse, sonst 0.0.0.0
#   HJ_AUTOSCALED        true, wenn das Autoscaling den Server erzeugt hat
#   HETZNER_SERVER_ID    Server-ID bei Hetzner

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KNOTEN_DIR="$(cd "${HIER}/.." && pwd)"
# Instanz, Dienstname, Konfigdatei und Port haengen zusammen.
#
# Zwei Lavalink-Container auf einem Host brauchen zwei Agenten - ein Agent
# verwaltet genau einen Container. Bisher schrieben beide Installationen in
# dieselbe Datei und dieselbe Unit: die zweite ueberschrieb die erste. Es lief
# genau ein Agent, und der zweite Knoten zeigte auf einen Port, an dem niemand
# fuer ihn zustaendig war.
#
# Instanz 1 behaelt die bisherigen Namen. Sonst muesste jede bestehende
# Installation angefasst werden, ohne dass sich fuer sie etwas aendert.
INSTANZ="${HJ_NODE_CONTAINER##*-}"
[[ "$INSTANZ" =~ ^[0-9]+$ ]] || INSTANZ=1

if [[ "$INSTANZ" == "1" ]]; then
    DIENST="hoerjetzt-knoten-agent"
else
    DIENST="hoerjetzt-knoten-agent-${INSTANZ}"
fi
UMGEBUNG="/etc/${DIENST}.env"

info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || fail "Der Agent wird als systemd-Dienst eingerichtet - bitte als root starten."
command -v python3 >/dev/null 2>&1 || fail "python3 fehlt. Auf Debian: apt install -y python3"

# Hier und nicht erst beim Start: ein Tippfehler im Agenten wuerde sonst zu
# einem Dienst fuehren, der in einer Neustartschleife haengt und dessen Grund
# nur im Journal steht.
python3 -m py_compile "${HIER}/agent.py" || fail "agent.py ist nicht uebersetzbar."

# Was schon einmal eingerichtet wurde, gilt weiter. Das macht den Aufruf aus
# update.sh heraus zu einem Einzeiler: vorhandene Werte bleiben, mitgegebene
# gewinnen. Ohne das muesste jede Aktualisierung erneut nach Token und Adresse
# fragen - und ein vergessenes Feld wuerde die Selbstanmeldung abschalten.
if [[ -f "$UMGEBUNG" ]]; then
    while IFS='=' read -r schluessel rest; do
        [[ "$schluessel" == HJ_* || "$schluessel" == HETZNER_* ]] || continue
        [[ -n "${!schluessel:-}" ]] && continue
        printf -v "$schluessel" '%s' "$rest"
        export "${schluessel?}"
    done < "$UMGEBUNG"
fi

: "${HJ_NODE_CONTAINER:?HJ_NODE_CONTAINER fehlt}"
: "${HJ_AGENT_TOKEN:?HJ_AGENT_TOKEN fehlt}"

# Der Knoten heisst wie die Maschine. Mehrere Knoten auf demselben Host
# bekommen die Instanznummer angehaengt - ohne das haetten sie denselben Namen,
# und der Bot verwirft bei doppelten Namen stillschweigend den zweiten.
if [[ -z "${HJ_NODE_NAME:-}" ]]; then
    HJ_NODE_NAME="$(hostname -s)"
    INSTANZ_NR="${HJ_NODE_CONTAINER##*-}"
    [[ "$INSTANZ_NR" =~ ^[0-9]+$ && "$INSTANZ_NR" != "1" ]] && HJ_NODE_NAME="${HJ_NODE_NAME}-${INSTANZ_NR}"
fi
# Je Instanz ein eigener Port: 8099, 8100, 8101 ... Zwei Prozesse koennen
# denselben Port nicht binden - der zweite Agent kam bisher gar nicht hoch.
HJ_AGENT_PORT="${HJ_AGENT_PORT:-$(( 8098 + INSTANZ ))}"

# Ohne Angabe an die private Adresse binden, wenn es eine gibt. Ein Agent, der
# neu starten und aktualisieren kann, hat im offenen Netz nichts verloren -
# das Token ist dort die einzige Huerde.
if [[ -z "${HJ_AGENT_BIND:-}" ]]; then
    # Docker-Bruecken ausdruecklich ueberspringen.
    #
    # Sie liegen im selben privaten Bereich (172.16/12) und stehen bei
    # "ip addr" oft vor der echten Adresse. Der Agent band dann an 172.18.0.1,
    # meldete brav "lauscht auf 172.18.0.1:8099" - und der Bot kam nie an ihn
    # heran. Diese Bruecken gehoeren dem Host allein; von aussen ist dort
    # nichts erreichbar.
    adressen() {
        ip -4 -o addr show scope global 2>/dev/null \
            | grep -vE ' (docker[0-9]*|br-[0-9a-f]+|veth[0-9a-f]+|virbr[0-9]*) ' \
            | awk '{print $4}' | cut -d/ -f1
    }

    HJ_AGENT_BIND="$(adressen | grep -E '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.)' | head -n1 || true)"

    # Keine private Adresse? Dann ist der Knoten ohnehin nur oeffentlich
    # erreichbar - dort gezielt binden statt auf allen Adressen.
    [[ -n "$HJ_AGENT_BIND" ]] || HJ_AGENT_BIND="$(adressen | head -n1 || true)"
    [[ -n "$HJ_AGENT_BIND" ]] || HJ_AGENT_BIND="0.0.0.0"

    if [[ ! "$HJ_AGENT_BIND" =~ ^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.) ]]; then
        warn "Der Agent lauscht auf ${HJ_AGENT_BIND} - das ist keine private Adresse."
        warn "    Unbedingt per Firewall auf die Adresse des Bots begrenzen;"
        warn "    das Token ist dort die einzige Huerde."
    fi
fi

# Die Adresse, unter der der Bot den Agenten spaeter anspricht. Bindet er an
# 0.0.0.0, taugt das nicht als Adresse - dann die oeffentliche nehmen.
if [[ -z "${HJ_AGENT_URL:-}" ]]; then
    if [[ "$HJ_AGENT_BIND" != "0.0.0.0" ]]; then
        HJ_AGENT_URL="http://${HJ_AGENT_BIND}:${HJ_AGENT_PORT}"
    else
        OEFFENTLICH="$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -n1 || true)"
        HJ_AGENT_URL="http://${OEFFENTLICH:-127.0.0.1}:${HJ_AGENT_PORT}"
    fi
fi

umask 077
cat > "$UMGEBUNG" <<EOF
# Von agent/einrichten.sh geschrieben. Enthaelt Geheimnisse - chmod 600.
HJ_AGENT_TOKEN=${HJ_AGENT_TOKEN}
HJ_AGENT_PORT=${HJ_AGENT_PORT}
HJ_AGENT_BIND=${HJ_AGENT_BIND}
HJ_AGENT_URL=${HJ_AGENT_URL}
HJ_NODE_CONTAINER=${HJ_NODE_CONTAINER}
HJ_NODE_NAME=${HJ_NODE_NAME}
HJ_NODE_DIR=${KNOTEN_DIR}
HJ_NODE_ADDRESS=${HJ_NODE_ADDRESS:-}
HJ_LAVALINK_PASSWORD=${HJ_LAVALINK_PASSWORD:-}
HJ_NODE_TIER=${HJ_NODE_TIER:-free}
HJ_CORE_URL=${HJ_CORE_URL:-}
HJ_NODE_TOKEN=${HJ_NODE_TOKEN:-}
HJ_AUTOSCALED=${HJ_AUTOSCALED:-false}
HETZNER_SERVER_ID=${HETZNER_SERVER_ID:-}
EOF
chmod 600 "$UMGEBUNG"

# Die Unit zeigt fest auf /opt/hoerjetzt-node. Liegt der Zweig woanders, muss
# der Pfad mit - sonst startet der Dienst ins Leere.
sed -e "s#/opt/hoerjetzt-node#${KNOTEN_DIR}#g" \
    -e "s#/etc/hoerjetzt-knoten-agent.env#${UMGEBUNG}#g" \
    "${HIER}/hoerjetzt-knoten-agent.service" \
    > "/etc/systemd/system/${DIENST}.service"

systemctl daemon-reload
systemctl enable --now "$DIENST" >/dev/null 2>&1 || fail "Agent laesst sich nicht starten."
systemctl restart "$DIENST"

sleep 2
if curl -fsS -m 5 "http://127.0.0.1:${HJ_AGENT_PORT}/gesund" >/dev/null 2>&1; then
    info "Agent laeuft auf ${HJ_AGENT_URL}"
else
    warn "Agent antwortet noch nicht: journalctl -u ${DIENST} -n 30"
fi

if [[ -n "${HJ_CORE_URL:-}" && -n "${HJ_NODE_TOKEN:-}" ]]; then
    info "Selbstanmeldung beim Bot laeuft - im Adminbereich taucht der Knoten von selbst auf."
else
    warn "Ohne HJ_CORE_URL und HJ_NODE_TOKEN meldet sich der Knoten nicht selbst."
    warn "Dann bleibt der Eintrag im Adminbereich von Hand noetig."
fi
