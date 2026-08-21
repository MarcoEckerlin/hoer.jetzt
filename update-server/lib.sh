# hoer.jetzt - gemeinsame Hilfen der Update-Server-Skripte.
#
#   . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
#
# Kein eigenstaendiges Skript. Wird von einrichten.sh, tresor.sh und
# veroeffentlichen.sh eingebunden.

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

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

frage_leer() {
    local __v="$1" __t="$2" __e=""
    read -r -p "    ${__t}: " __e || true
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

# Kein "tr ... | head -c" - head schliesst die Pipe, tr faengt SIGPIPE und
# unter "set -o pipefail" bricht das Skript wortlos ab.
zufall() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32; }

# ------------------------------------------------------------------ Volume
#
# Das Auslieferungsverzeichnis ist ein benanntes Docker-Volume, kein Pfad auf
# dem Host. Grund: die Bauschritte des CI-Runners laufen in eigenen Containern
# und kennen keine Hostpfade - ein Volume koennen sie ueber seinen Namen
# einhaengen. Dieselben vier Funktionen laufen deshalb auf dem Host und in
# der CI unveraendert.
#
# Compose stellt dem Volumennamen den Projektnamen voran.

AUS_VOLUME="${AUS_VOLUME:-hj-update_ausliefern}"
AUS_HELFER="${AUS_HELFER:-alpine:3}"

# Wem "release/" im Auslieferungsverzeichnis gehoert.
#
# Alles dort schreiben sonst nur veroeffentlichen.sh und tresor.sh, beide
# als root. Seit es die Release-Seite gibt, muss auch der Updater ran: ein
# Zurueckrollen schaltet "release/aktuell" um, und Caddy liefert genau diese
# Datei an die Knoten aus. Ueber einen Umweg ginge das nicht.
#
# Statt ihm das ganze Verzeichnis zu oeffnen, gehoeren ihm nur "release/"
# und "voreinstellungen/" - beide schreibt die Oberflaeche.
# "knoten/" und "tresor/" bleiben root - der Mount ist zwar schreibbar, die
# Rechte sind aber die eigentliche Grenze. Die Kennung steht im Dockerfile
# des Updaters fest; ohne das vergaebe useradd bei jedem Neubau eine andere
# und die Rechte passten nach einem Update nicht mehr.
AUS_BESITZER="${AUS_BESITZER:-1500}"

# aus_uebergeben <pfad>   Pfad dem Updater ueberschreiben.
aus_uebergeben() {
    aus_docker sh -c "chown -R ${AUS_BESITZER}:${AUS_BESITZER} '$1'" >/dev/null 2>&1 \
        || warn "Rechte an '$1' liessen sich nicht setzen - Zurueckrollen ueber die Oberflaeche geht dann nicht."
}

aus_docker() {
    docker run --rm -i -v "${AUS_VOLUME}:/aus" -w /aus "$AUS_HELFER" "$@"
}

# aus_schreiben <pfad>       Inhalt kommt von der Standardeingabe.
#
# Erst daneben, dann umbenennen. Bricht es mittendrin ab, holt sich kein
# Knoten eine halbe Datei - der alte Stand bleibt gueltig, bis der neue
# vollstaendig ist. Genau dieser Fall trifft sonst immer den einen Host,
# der zufaellig gerade aktualisiert.
aus_schreiben() {
    local pfad="$1"
    aus_docker sh -c "mkdir -p \"\$(dirname '${pfad}')\" \
        && cat > '${pfad}.neu' \
        && chmod 644 '${pfad}.neu' \
        && mv '${pfad}.neu' '${pfad}'"
}

# aus_lesen <pfad>
aus_lesen() {
    aus_docker sh -c "cat '$1'"
}

# aus_gibt_es <pfad>
aus_gibt_es() {
    aus_docker sh -c "test -f '$1'" >/dev/null 2>&1
}

# aus_liste [pfad]
aus_liste() {
    aus_docker sh -c "ls -la '${1:-.}' 2>/dev/null || true"
}
