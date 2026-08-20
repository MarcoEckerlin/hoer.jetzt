#!/usr/bin/env bash
#
# hoer.jetzt - Update-Server neu aufsetzen.
#
#   bash neu-aufsetzen.sh              Container und Volumes weg
#   bash neu-aufsetzen.sh --maschine   zusaetzlich alle Spuren am Host
#   bash neu-aufsetzen.sh --ja         ohne Rueckfrage
#
# ---------------------------------------------------------------------------
# Warum nicht "docker volume prune"
#
# Weil das jedes ungenutzte Volume am Host trifft - auch von Diensten, die mit
# hoer.jetzt nichts zu tun haben. Wo nur dieser Stack laeuft, faellt es nicht
# auf; auf jeder anderen Maschine ist es stiller Datenverlust.
# "docker compose down --volumes" ist auf das Projekt begrenzt und genuegt.
#
# --maschine geht weiter: Quellen, Abbilder, Registry-Anmeldung und der
# insecure-registries-Eintrag in /etc/docker/daemon.json. Danach ist der Host
# so weit zurueckgesetzt, wie es ohne Neuinstallation des Systems geht.
# ---------------------------------------------------------------------------

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
MASCHINE=false
OHNE_RUECKFRAGE=false

for arg in "$@"; do
    case "$arg" in
        --maschine) MASCHINE=true ;;
        --ja)       OHNE_RUECKFRAGE=true ;;
        -h|--help)  sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unbekannt: $arg" >&2; exit 1 ;;
    esac
done

[[ "$(id -u)" -eq 0 ]] || { echo "Bitte als root starten." >&2; exit 1; }

# Sich selbst nicht loeschen.
#
# Dieses Skript liegt unter ${ARBEIT}. Mit --maschine faellt genau dieses
# Verzeichnis weg - und bash liest sein Skript waehrend der Ausfuehrung
# haeppchenweise nach. Also zuerst in eine Kopie ausserhalb umziehen.
if [[ -z "${HJ_UMGEZOGEN:-}" ]]; then
    EIGEN="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || true)"
    if [[ -n "$EIGEN" && "$EIGEN" == "${ARBEIT}/"* ]]; then
        KOPIE="$(mktemp)"
        cp "$EIGEN" "$KOPIE"
        export HJ_UMGEZOGEN=1
        exec bash "$KOPIE" "$@"
    fi
fi

# Wo die Compose-Datei liegt - falls sie noch liegt.
#
# Ist /opt/hoerjetzt schon geloescht, gibt es sie nicht mehr. Die Volumes
# leben dann trotzdem weiter: "docker compose down" braucht die Datei, und
# ohne sie bleiben sie als Waisen stehen. Genau das passiert, wenn jemand
# zuerst rm -rf /opt/hoerjetzt macht und danach neu installiert - der frische
# Stack findet die alten Daten vor und wundert sich.
#
# Deshalb ist der Projektname hier fest verdrahtet und wird NICHT aus dem
# Verzeichnis abgeleitet. Nach einem Umzug nach /tmp hiesse er sonst "tmp",
# und der Filter fände nichts.
HIER="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || echo /tmp)"
PROJEKT="update-server"
QUELL="${ARBEIT}/main/update-server"
[[ -f "${QUELL}/docker-compose.yml" ]] || QUELL="$HIER"
COMPOSE="${QUELL}/docker-compose.yml"

echo
echo "  ================================================================"
if $MASCHINE; then
    echo "   Update-Server zuruecksetzen - wie eine frische Maschine"
else
    echo "   Update-Server neu aufsetzen"
fi
echo "  ================================================================"
echo

# Erst zeigen, was da ist. Eine Warnung ueber Unsichtbares liest sich wie eine
# Formalie und wird entsprechend gelesen.
echo "  Volumes dieses Projekts:"
# Das abschliessende "true" ist nicht kosmetisch.
#
# Die Schleife endet mit "docker volume inspect", und das schlaegt beim
# letzten nicht vorhandenen Volume fehl. Der Block gaebe damit 1 zurueck,
# pipefail reichte das durch die ganze Pipeline, und set -e beendete das
# Skript - noch VOR dem Loeschen. Der Anwender saehe die Liste und daechte,
# es sei erledigt.
{
    docker volume ls -q --filter "label=com.docker.compose.project=${PROJEKT}" 2>/dev/null || true
    # Ueber den Namen nachfassen: Volumes aus aelteren Compose-Fassungen
    # tragen das Label nicht zwingend, der Name traegt es immer.
    for name in ausliefern caddy-daten caddy-konfig forgejo-daten runner-daten updater-daten; do
        if docker volume inspect "${PROJEKT}_${name}" >/dev/null 2>&1; then
            echo "${PROJEKT}_${name}"
        fi
    done
    true
} | sort -u | while read -r vol; do
    if [[ -n "$vol" ]]; then printf '    %s\n' "$vol"; fi
done || true

if ! docker volume ls -q 2>/dev/null | grep -q "^${PROJEKT}_"; then
    echo "    (keine gefunden - der Stack lief hier wohl nie)"
fi

echo
echo "  Unwiderruflich weg:"
echo
echo "    ausliefern      Tresor und Release-Manifeste"
echo "    forgejo-daten   alle veroeffentlichten Abbilder und das Konto"
echo "    updater-daten   alle Knoten, Freigaben, Zugriffsprotokoll"
echo "    runner-daten    die Anmeldung des CI-Runners"

if $MASCHINE; then
    echo
    echo "  Zusaetzlich am Host:"
    echo
    if [[ -d "$ARBEIT" ]]; then
        printf '    %-32s %s\n' "$ARBEIT" "$(du -sh "$ARBEIT" 2>/dev/null | cut -f1)"
    fi
    printf '    %-32s %s\n' "Abbilder des Stacks" "hoerjetzt/*, forgejo, caddy, postgres, redis"
    printf '    %-32s %s\n' "Registry-Anmeldung" "auths auf 127.0.0.1 in config.json"
    printf '    %-32s %s\n' "insecure-registries" "/etc/docker/daemon.json"
    # ls gibt 2 zurueck, wenn nichts passt - und pipefail reicht das
    # durch die Ersetzung nach aussen, wo set -e das Skript beendet.
    # Genau der Normalfall auf einer Maschine ohne diese Timer.
    zeitgeber="$(ls /etc/systemd/system/hj-*.timer \
                    /etc/systemd/system/hoerjetzt-*.timer 2>/dev/null | wc -l || true)"
fi

echo
echo "  Danach von Hand noetig:"
echo
echo "    bash tresor.sh fuellen voll"
echo "    bash tresor.sh fuellen lavalink"
echo "    bash veroeffentlichen.sh <version>     Abbilder neu bauen"
echo "    jeden Knoten unter \"Verwalten\" neu anlegen"
echo

if ! $OHNE_RUECKFRAGE; then
    printf '  Zum Fortfahren "loeschen" eintippen: '
    read -r antwort
    [[ "$antwort" == "loeschen" ]] || { echo "  Abgebrochen - nichts angefasst."; exit 1; }
fi

echo
echo "  Container und Volumes..."
if [[ -f "$COMPOSE" ]]; then
    # --remove-orphans faengt Container aus aelteren Fassungen der
    # Compose-Datei ab - etwa den alten "ai-radio", der nach der Umbenennung
    # sonst weiterlaeuft und seinen Port belegt haelt.
    docker compose -f "$COMPOSE" down --volumes --remove-orphans 2>/dev/null || true
else
    echo "    Keine Compose-Datei - Container und Volumes direkt entfernen."
fi

# Zweiter Durchgang, immer.
#
# Ohne Compose-Datei kommt "compose down" gar nicht erst zum Zug, und die
# Volumes blieben als Waisen stehen. Das ist der haeufige Fall: erst
# rm -rf /opt/hoerjetzt, dann neu installieren - und der frische Stack findet
# die alten Daten vor. Forgejo hat sein Konto dann schon, das Anlegen
# scheitert, und die Meldung deutet auf alles Moegliche ausser die Ursache.
#
# Auch nach einem erfolgreichen "compose down" schadet der Durchgang nicht:
# was weg ist, wird nicht noch einmal geloescht.
for c in $(docker ps -aq --filter "label=com.docker.compose.project=${PROJEKT}" 2>/dev/null); do
    docker rm -f "$c" >/dev/null 2>&1 || true
done

entfernt=0
schon=" "
weg_damit() {
    local v="$1"
    case "$schon" in *" ${v} "*) return 0 ;; esac
    if docker volume rm -f "$v" >/dev/null 2>&1; then
        echo "    ${v}"
        schon="${schon}${v} "
        entfernt=$((entfernt+1))
    fi
}

for v in $(docker volume ls -q --filter "label=com.docker.compose.project=${PROJEKT}" 2>/dev/null || true); do
    weg_damit "$v"
done

# Nachfassen ueber den Namen. Volumes aus aelteren Compose-Fassungen tragen
# das Projekt-Label nicht zwingend - der Name traegt es immer.
for name in ausliefern caddy-daten caddy-konfig forgejo-daten runner-daten updater-daten; do
    weg_damit "${PROJEKT}_${name}"
done

if [[ "$entfernt" -eq 0 ]]; then echo "    (keine gefunden)"; fi

rm -f "${QUELL}/.env" 2>/dev/null || true

if ! $MASCHINE; then
    echo "  Fertig."
    exec bash "${QUELL}/einrichten.sh"
fi

# ------------------------------------------------------------- nur --maschine

# JSON ersetzen, aber nur wenn das Ergebnis taugt.
#
# /etc/docker/daemon.json ist eine Datei, die Docker beim Start liest. Ist sie
# kaputt, faehrt Docker nicht mehr hoch - und dann steht die ganze Maschine,
# nicht nur dieser Stack. Ein Filter, der leer ausgibt oder halbes JSON
# hinterlaesst, waere also teurer als der Fehler, den er beheben soll.
#
# Deshalb: in eine Nebendatei schreiben, pruefen, erst dann uebernehmen.
# Diese Filter konnten nicht vorab ausgefuehrt werden - es gab kein jq zum
# Testen. Die Pruefung hier ersetzt das nicht, aber sie begrenzt den Schaden
# auf "nichts passiert" statt "Docker startet nicht mehr".
json_ersetzen() {
    local datei="$1" filter="$2" sicherung="$3"
    local neu="${datei}.neu.$$"

    if ! command -v jq >/dev/null 2>&1; then
        echo "    jq fehlt - ${datei} bitte selbst durchsehen."
        return 1
    fi
    if ! jq "$filter" "$sicherung" > "$neu" 2>/dev/null; then
        rm -f "$neu"
        echo "    Filter fehlgeschlagen - ${datei} unveraendert."
        return 1
    fi
    # Nicht leer, und wirklich JSON.
    if [[ ! -s "$neu" ]] || ! jq -e . "$neu" >/dev/null 2>&1; then
        rm -f "$neu"
        echo "    Ergebnis unbrauchbar - ${datei} unveraendert."
        return 1
    fi
    mv "$neu" "$datei"
    return 0
}

echo "  systemd-Timer..."
for unit in hj-agent hj-sicherung hoerjetzt-update; do
    if [[ -f "/etc/systemd/system/${unit}.timer" ]]; then
        systemctl disable --now "${unit}.timer" >/dev/null 2>&1 || true
        rm -f "/etc/systemd/system/${unit}.timer" "/etc/systemd/system/${unit}.service"
        echo "    ${unit} entfernt."
    fi
done
systemctl daemon-reload >/dev/null 2>&1 || true

echo "  Registry-Anmeldung..."
# Bleibt sie stehen, meldet sich Docker spaeter mit einem Passwort an, das es
# nicht mehr gibt. Die Meldung lautet dann "unauthorized" und sieht nach einem
# Rechteproblem aus, nicht nach einer alten Anmeldung.
#
# Nur die Eintraege auf localhost - in derselben Datei koennen Anmeldungen an
# fremden Registries liegen, die niemanden hier etwas angehen.
for pfad in /root/.docker/config.json "${HOME:-/root}/.docker/config.json"; do
    [[ -f "$pfad" ]] || continue
    cp "$pfad" "${pfad}.vorher"
    if json_ersetzen "$pfad" \
        'if .auths then .auths |= with_entries(select(.key | test("^(127\.0\.0\.1|localhost):") | not)) else . end' \
        "${pfad}.vorher"; then
        echo "    ${pfad} bereinigt (Sicherung: ${pfad}.vorher)."
    fi
done

echo "  /etc/docker/daemon.json..."
# Der insecure-registries-Eintrag zeigt auf den ALTEN Port. einrichten.sh legt
# die Datei nur an, wenn es sie noch nicht gibt - ist sie da, warnt es bloss
# und laesst den falschen Eintrag stehen. Der Registry-Zugriff scheitert dann
# mit "http: server gave HTTP response to HTTPS client", und die Ursache steht
# in einer Datei, an die niemand denkt.
DAEMON="/etc/docker/daemon.json"
if [[ -f "$DAEMON" ]]; then
    SICHERUNG="${DAEMON}.$(date '+%Y%m%d%H%M%S')"
    cp "$DAEMON" "$SICHERUNG"
    if json_ersetzen "$DAEMON" \
        'if ."insecure-registries" then ."insecure-registries" |= map(select(test("^(127\.0\.0\.1|localhost):") | not)) | if (."insecure-registries" | length) == 0 then del(."insecure-registries") else . end else . end' \
        "$SICHERUNG"; then
        echo "    bereinigt (Sicherung: ${SICHERUNG})."
        systemctl restart docker >/dev/null 2>&1 || true
        sleep 5
        if ! docker info >/dev/null 2>&1; then
            # Genau der Fall, vor dem die Pruefung schuetzen soll - falls er
            # doch eintritt, sofort zurueck. Ein Host ohne Docker ist schlimmer
            # als ein falscher Registry-Eintrag.
            echo "    Docker kam nicht hoch - daemon.json wird zurueckgesetzt."
            cp "$SICHERUNG" "$DAEMON"
            systemctl restart docker >/dev/null 2>&1 || true
        fi
    fi
fi

echo "  Abbilder..."
# Nur die des Stacks. Ein pauschales "image prune -a" naehme auch Abbilder
# fremder Dienste mit, die auf demselben Host laufen koennen.
for muster in hoerjetzt/ codeberg.org/forgejo caddy postgres redis; do
    ids="$(docker images --format '{{.Repository}} {{.ID}}' 2>/dev/null \
           | awk -v m="$muster" 'index($1,m)==1 {print $2}' | sort -u)"
    if [[ -n "$ids" ]]; then
        # shellcheck disable=SC2086
        docker rmi -f $ids >/dev/null 2>&1 || true
        echo "    ${muster}"
    fi
done
docker network rm "${PROJEKT}_hoerjetzt" >/dev/null 2>&1 || true

echo "  Quellen..."
rm -rf "$ARBEIT"

echo
echo "  ----------------------------------------------------------------"
echo "   Der Host ist zurueckgesetzt."
echo
echo "   Weiter wie auf einer frischen Maschine:"
echo
echo "     curl -fsSL https://raw.githubusercontent.com/MarcoEckerlin/hoer.jetzt/main/deploy/install-update-server.sh | bash"
echo "  ----------------------------------------------------------------"
echo
