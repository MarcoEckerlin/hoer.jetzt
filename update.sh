#!/usr/bin/env bash
#
# hoer.jetzt lavalink - Knoten aktualisieren.
#
#   bash update.sh              alle Knoten auf diesem Host
#   bash update.sh 2            nur hoerjetzt-lavalink-2
#
# Holt den neuesten Stand, baut das Abbild und ersetzt die Container - mit
# genau den Einstellungen, die sie schon haben. Passwort, Stufe, Port und
# Qualitaet werden aus dem laufenden Container gelesen, nicht neu abgefragt:
# ein geaendertes Passwort wuerde den Eintrag im Adminbereich ungueltig machen.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NUR="${1:-}"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "Docker fehlt."
[[ -f "${HIER}/Dockerfile" ]] || fail "${HIER} ist kein lavalink-Arbeitsverzeichnis."

# ------------------------------------------------------------------ Suchen

step "Knoten auf diesem Host"
if [[ -n "$NUR" ]]; then
    KNOTEN=("hoerjetzt-lavalink-${NUR}")
else
    mapfile -t KNOTEN < <(docker ps -a --format '{{.Names}}' | grep -E '^hoerjetzt-lavalink(-[0-9]+)?$' || true)
fi

[[ ${#KNOTEN[@]} -gt 0 ]] || fail "Kein Container gefunden. Erst install.sh laufen lassen."
for name in "${KNOTEN[@]}"; do
    docker inspect "$name" >/dev/null 2>&1 || fail "${name} gibt es nicht."
    info "$name"
done

# ------------------------------------------------------------------ Holen

step "Neuen Stand holen"
git config --global --add safe.directory "$HIER" 2>/dev/null || true
if [[ -d "${HIER}/.git" ]]; then
    ALT="$(git -C "$HIER" rev-parse --short HEAD)"
    git -C "$HIER" fetch -q origin lavalink || fail "GitHub nicht erreichbar."
    # reset statt pull: Releases werden neu gebaut und force-gepusht, ein
    # Vorspulen ist deshalb nicht immer moeglich.
    git -C "$HIER" reset -q --hard origin/lavalink
    NEU="$(git -C "$HIER" rev-parse --short HEAD)"
    if [[ "$ALT" == "$NEU" ]]; then
        info "Bereits auf ${NEU} - es wird trotzdem neu gebaut."
    else
        info "${ALT} -> ${NEU}"
    fi
else
    warn "Kein Git-Arbeitsverzeichnis - es wird nur neu gebaut."
fi

step "Abbild bauen"
docker build -t "hoerjetzt-lavalink:latest" "$HIER" || fail "Build fehlgeschlagen."

# ------------------------------------------------------------------ Cipher
#
# Der Cipher-Dienst ist der Teil, der am schnellsten veraltet: er haelt mit
# YouTubes Player-Skript Schritt. Ein Update, das ihn stehen laesst, behebt
# genau das Problem nicht, wegen dem man aktualisiert.

mapfile -t CIPHER < <(docker ps -a --format '{{.Names}}' | grep -E '^hoerjetzt-cipher-[0-9]+$' || true)
if [[ ${#CIPHER[@]} -gt 0 ]]; then
    step "Cipher-Dienst erneuern"
    docker pull -q ghcr.io/kikkia/yt-cipher:master >/dev/null || warn "Abbild nicht erreichbar - der alte Stand bleibt."
    for dienst in "${CIPHER[@]}"; do
        C_NETZ="$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$dienst")"
        C_TOKEN="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$dienst" \
            | grep '^API_TOKEN=' | head -n1 | cut -d= -f2- || true)"
        docker rm -f "$dienst" >/dev/null 2>&1 || true
        docker run -d --name "$dienst" --restart unless-stopped \
            --network "$C_NETZ" \
            -e PORT=8001 \
            -e API_TOKEN="$C_TOKEN" \
            -e OVERRIDE_PLAYER_VARIANT=IAS \
            ghcr.io/kikkia/yt-cipher:master >/dev/null || fail "${dienst} startet nicht."
        info "${dienst} erneuert (${C_NETZ})"
    done
fi

# ------------------------------------------------------------------ Ersetzen

# Liest eine Umgebungsvariable aus einem laufenden Container.
umgebung() {
    docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" \
        | grep "^${2}=" | head -n1 | cut -d= -f2- || true
}

for name in "${KNOTEN[@]}"; do
    step "Ersetzen: ${name}"

    PASSWORT="$(umgebung "$name" LAVALINK_SERVER_PASSWORD)"
    [[ -n "$PASSWORT" ]] || fail "${name} hat kein Passwort in der Umgebung - bitte install.sh nehmen."

    STUFE="$(umgebung "$name" LAVALINK_TIER)"
    QUALITAET="$(umgebung "$name" LAVALINK_QUALITAET)"
    OAUTH="$(umgebung "$name" YOUTUBE_OAUTH)"
    TOKEN="$(umgebung "$name" YOUTUBE_REFRESH_TOKEN)"
    PLUGIN="$(umgebung "$name" YOUTUBE_PLUGIN_VERSION)"
    PLUGIN_SNAP="$(umgebung "$name" YOUTUBE_PLUGIN_SNAPSHOT)"
    CIPHER_URL="$(umgebung "$name" YT_CIPHER_URL)"
    CIPHER_PW="$(umgebung "$name" YT_CIPHER_PASSWORD)"
    CIPHER_UA="$(umgebung "$name" YT_CIPHER_USERAGENT)"
    NETZ="$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$name")"
    PORTS="$(docker inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{.HostIp}}:{{.HostPort}}:{{$p}} {{end}}{{end}}' "$name")"

    ARGUMENTE=()
    if [[ "$NETZ" == container:* ]]; then
        # Teilt sich den Namensraum eines Tailscale-Sidecars - dann gehoert
        # kein Port hierher, der Sidecar traegt ihn.
        ARGUMENTE+=(--network "$NETZ")
        info "Netz: ${NETZ}"
    else
        # Ein eigenes Netz (fuer den Cipher-Dienst nebenan) muss mit, sonst
        # findet Lavalink ihn nach dem Neustart nicht mehr am Namen.
        case "$NETZ" in
            ""|default|bridge|host|none) ;;
            *) ARGUMENTE+=(--network "$NETZ"); info "Netz: ${NETZ}" ;;
        esac
        for eintrag in $PORTS; do
            ARGUMENTE+=(-p "${eintrag%/tcp}")
        done
        info "Ports: ${PORTS:-keine}"
    fi

    info "Stufe: ${STUFE:-free}, Qualitaet: ${QUALITAET:-hoch}"
    info "YouTube: $([[ "$OAUTH" == "true" ]] && echo angemeldet || echo "ohne Anmeldung")${CIPHER_URL:+, Cipher ${CIPHER_URL}}"

    docker rm -f "$name" >/dev/null 2>&1 || true
    docker run -d --name "$name" --restart unless-stopped \
        "${ARGUMENTE[@]}" \
        -e LAVALINK_SERVER_PASSWORD="$PASSWORT" \
        -e LAVALINK_TIER="${STUFE:-free}" \
        -e LAVALINK_PORT=2333 \
        -e LAVALINK_QUALITAET="${QUALITAET:-hoch}" \
        -e YOUTUBE_OAUTH="${OAUTH:-false}" \
        -e YOUTUBE_REFRESH_TOKEN="$TOKEN" \
        -e YOUTUBE_PLUGIN_VERSION="${PLUGIN:-1.18.2}" \
        -e YOUTUBE_PLUGIN_SNAPSHOT="${PLUGIN_SNAP:-false}" \
        -e YT_CIPHER_URL="$CIPHER_URL" \
        -e YT_CIPHER_PASSWORD="$CIPHER_PW" \
        -e YT_CIPHER_USERAGENT="${CIPHER_UA:-hoerjetzt}" \
        "hoerjetzt-lavalink:latest" >/dev/null || fail "${name} startet nicht."

    info "Gestartet."
done

# ------------------------------------------------------------------ Nachsehen

step "Nachsehen"
sleep 12
for name in "${KNOTEN[@]}"; do
    zustand="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || echo fehlt)"
    if [[ "$zustand" == "running" ]]; then
        info "$(printf '%-26s %s' "$name" "laeuft")"
    else
        warn "$(printf '%-26s %s' "$name" "$zustand")"
        warn "    docker logs --tail 40 ${name}"
    fi
done

# ------------------------------------------------------------------ Agent

# Der Agent wird mitgezogen, wenn er schon eingerichtet ist. einrichten.sh
# liest die bestehenden Werte aus /etc/hoerjetzt-agent.env - es fragt also
# nichts erneut ab und schaltet insbesondere die Selbstanmeldung nicht ab.
if [[ -f /etc/hoerjetzt-agent.env ]]; then
    step "Knoten-Agent"
    bash "${HIER}/agent/einrichten.sh" || warn "Agent liess sich nicht erneuern - der Knoten laeuft trotzdem."
else
    info "Kein Knoten-Agent eingerichtet. Nachruesten: bash agent/einrichten.sh"
fi

echo
info "Der Bot merkt die Unterbrechung: laufende Server ziehen auf einen anderen"
info "Knoten um und kommen zurueck, sobald dieser wieder da ist."
info "Alte Abbilder aufraeumen: docker image prune -f"
echo
