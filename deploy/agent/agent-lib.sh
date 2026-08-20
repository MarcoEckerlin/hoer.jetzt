#!/usr/bin/env bash
#
# hoer.jetzt - gemeinsame Grundlage fuer Agent und Installer.
#
# Wird mit "source" eingebunden, nicht ausgefuehrt.
#
# ---------------------------------------------------------------------------
# Warum das hier liegt und nicht im Agenten
#
# Agent und Installer machen dieselben Dinge - Umgebung lesen, Module
# erkennen, mit dem Update-Server sprechen, Docker anfassen. Zweimal
# geschrieben laufen sie auseinander, und zwar an der unangenehmsten Stelle:
# der Installer legt eine Modulliste an, die der Agent anders liest.
# ---------------------------------------------------------------------------

# Kein "set -e" hier. Diese Datei wird eingebunden; die Einstellungen der
# aufrufenden Datei sollen gelten, nicht die einer Bibliothek.

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
UMGEBUNG="${UMGEBUNG:-${ARBEIT}/.env}"
MODULDATEI="${MODULDATEI:-${ARBEIT}/module}"
SCHLUESSEL="${SCHLUESSEL:-${ARBEIT}/knoten.key}"
SPERRE="${SPERRE:-${ARBEIT}/.sperre}"

# Alle Module, die es gibt. Die Reihenfolge ist die Startreihenfolge:
# lavalink vor core, weil core beim Start einen Audio-Knoten sucht.
ALLE_MODULE="lavalink core ki-radio"

sagen()  { printf '[agent] %s\n' "$*"; }
warnen() { printf '[agent] WARNUNG: %s\n' "$*" >&2; }
fehler() { printf '[agent] FEHLER: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- Umgebung

# Gelesen, nicht ausgefuehrt.
#
# "source" wuerde ein Dollarzeichen im Passwort als Variable lesen und das
# Skript unter "set -u" abbrechen. Genau das ist hier schon passiert - siehe
# denselben Kommentar in spock-einrichten.sh.
umgebung_lesen() {
    local datei="${1:-$UMGEBUNG}" zeile schluessel wert
    [[ -r "$datei" ]] || return 1
    while IFS= read -r zeile || [[ -n "$zeile" ]]; do
        [[ "$zeile" =~ ^[[:space:]]*# ]] && continue
        [[ "$zeile" == *=* ]] || continue
        schluessel="${zeile%%=*}"
        wert="${zeile#*=}"
        schluessel="${schluessel//[[:space:]]/}"
        [[ "$schluessel" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
        printf -v "$schluessel" '%s' "$wert"
        export "${schluessel?}"
    done < "$datei"
}

# Einen Wert in der .env setzen - nur wenn er sich unterscheidet.
#
# Ohne den Vergleich schriebe der Agent die Datei jede Minute neu, und jede
# Aenderung an der Datei sieht fuer Docker Compose nach einem Grund zum
# Neustarten aus.
umgebung_setzen() {
    local schluessel="$1" wert="$2" datei="${3:-$UMGEBUNG}"
    [[ -n "$wert" ]] || return 0
    if grep -q "^${schluessel}=" "$datei" 2>/dev/null; then
        local ist
        ist="$(grep "^${schluessel}=" "$datei" | head -1 | cut -d= -f2-)"
        [[ "$ist" == "$wert" ]] && return 1
        # Trennzeichen "|" statt "/": Werte enthalten Schraegstriche (URLs,
        # Pfade), und sed nimmt den ersten, den es findet.
        sed -i "s|^${schluessel}=.*|${schluessel}=${wert}|" "$datei"
    else
        printf '%s=%s\n' "$schluessel" "$wert" >> "$datei"
    fi
    return 0
}

# ------------------------------------------------------------------ Module

# Welche Module auf diesem Host laufen sollen.
#
# Steht in einer eigenen Datei und nicht in der .env: die .env wird vom
# Update-Server befuellt und vom Agenten fortgeschrieben, die Modulliste
# gehoert dem Installer. Zwei Schreiber auf derselben Datei sind eine
# Verabredung, die irgendwann jemand bricht.
module_lesen() {
    [[ -r "$MODULDATEI" ]] || { echo ""; return 0; }
    tr ',' '\n' < "$MODULDATEI" | tr -d ' \r' | grep -v '^$' | sort -u | paste -sd' ' -
}

module_schreiben() {
    printf '%s\n' "$(echo "$*" | tr ' ' '\n' | grep -v '^$' | sort -u | paste -sd, -)" \
        > "$MODULDATEI"
}

hat_modul() {
    local gesucht="$1"
    [[ " $(module_lesen) " == *" ${gesucht} "* ]]
}

# Welche Compose-Dienste zu einem Modul gehoeren.
#
# Ein Modul ist nicht dasselbe wie ein Container: "core" bringt die
# Weboberflaeche, die Datenbank und Redis mit. Der Agent muss ein Modul als
# Ganzes anfassen koennen, sonst startet er core neu und laesst web auf der
# alten Fassung stehen.
dienste_von() {
    case "$1" in
        core)     echo "core web postgres redis" ;;
        lavalink) echo "lavalink-free-1 yt-cipher" ;;
        ki-radio) echo "ki-radio" ;;
        *)        echo "" ;;
    esac
}

# ------------------------------------------------------------------ Sperre

# Verhindert, dass zwei Laeufe gleichzeitig an denselben Containern arbeiten.
#
# Der Fall ist real: der systemd-Timer feuert jede Minute, ein Update dauert
# laenger. Ohne Sperre laeuft der naechste Lauf in ein halb aktualisiertes
# Verzeichnis - und "docker compose up -d" zweimal parallel auf demselben
# Projekt endet in Containern, die keiner mehr zuordnen kann.
#
# Nicht warten, sondern aufgeben: der naechste Lauf kommt in einer Minute.
# Eine Warteschlange von Agenten waere schlimmer als ein ausgelassener Lauf.
#
# Zwei Verfahren, und der Grund fuer das zweite ist eine Falle:
#
#   flock loest die Sperre von selbst, wenn der Prozess stirbt - das ist genau
#   das gewuenschte Verhalten. Es gehoert zu util-linux und ist auf Debian da.
#
#   Fehlt es aber, gab die erste Fassung stillschweigend "1" zurueck und
#   meldete dabei "ein anderer Lauf arbeitet gerade". Der Agent haette dann
#   JEDEN Lauf ausgelassen und dafuer eine Meldung ausgegeben, die in die
#   voellig falsche Richtung zeigt. Ein Verzeichnis als Sperre ist weniger
#   elegant, aber es ist ueberall da - und ein lauter Rueckfall ist besser als
#   ein leiser Stillstand.
mit_sperre() {
    if command -v flock >/dev/null 2>&1; then
        exec 9>"$SPERRE"
        if ! flock -n 9; then
            sagen "Ein anderer Lauf arbeitet gerade - dieser wird ausgelassen."
            return 1
        fi
        return 0
    fi

    # Rueckfall: mkdir ist atomar, auch ueber NFS.
    local verzeichnis="${SPERRE}.d"

    # Erst das Naheliegende ausschliessen. Ohne diese Pruefung meldet der
    # Rueckfall bei einem fehlenden Elternverzeichnis "verwaiste Sperre" - und
    # schickt damit in die voellig falsche Richtung. Genau das ist beim
    # Erproben passiert.
    local eltern
    eltern="$(dirname "$verzeichnis")"
    if [[ ! -d "$eltern" ]]; then
        fehler "Arbeitsverzeichnis ${eltern} gibt es nicht - ARBEIT falsch gesetzt?"
    fi

    if mkdir "$verzeichnis" 2>/dev/null; then
        printf '%s\n' "$$" > "${verzeichnis}/pid"
        # Anders als bei flock raeumt hier niemand auf, wenn der Prozess
        # abstuerzt - deshalb die Falle.
        trap 'rm -rf "'"$verzeichnis"'"' EXIT
        return 0
    fi

    # Sperre liegt. Steht der Prozess dahinter noch?
    local alt
    alt="$(cat "${verzeichnis}/pid" 2>/dev/null || echo "")"
    if [[ -n "$alt" ]] && kill -0 "$alt" 2>/dev/null; then
        sagen "Ein anderer Lauf (PID ${alt}) arbeitet gerade - dieser wird ausgelassen."
        return 1
    fi

    # Verwaiste Sperre eines abgestuerzten Laufs. Wegzuraeumen ist hier
    # richtig: ohne das bliebe der Agent bis zum naechsten Neustart stehen,
    # und niemand bemerkte es.
    warnen "Verwaiste Sperre von PID ${alt:-?} entfernt."
    rm -rf "$verzeichnis"
    if mkdir "$verzeichnis" 2>/dev/null; then
        printf '%s\n' "$$" > "${verzeichnis}/pid"
        trap 'rm -rf "'"$verzeichnis"'"' EXIT
        return 0
    fi
    sagen "Sperre nicht zu bekommen - Lauf ausgelassen."
    return 1
}

# ------------------------------------------------------------ Update-Server

# Ruft den Update-Server mit der Kennung dieses Knotens auf.
#
# Benutzer ist die Kennung, Passwort das Geheimnis - genau die Aufteilung, die
# Basic-Auth ohnehin vorsieht und die "docker login" unveraendert benutzt.
us_holen() {
    local pfad="$1" ziel="${2:-}"
    : "${HJ_UPDATE_HOST:?HJ_UPDATE_HOST fehlt}"
    : "${HJ_KNOTEN_KENNUNG:?HJ_KNOTEN_KENNUNG fehlt - Knoten nicht angemeldet}"
    : "${HJ_KNOTEN_GEHEIMNIS:?HJ_KNOTEN_GEHEIMNIS fehlt - Knoten nicht angemeldet}"

    local ausgabe=(-fsS --max-time 60)
    [[ -n "$ziel" ]] && ausgabe+=(-o "$ziel")

    curl "${ausgabe[@]}" \
        -u "${HJ_KNOTEN_KENNUNG}:${HJ_KNOTEN_GEHEIMNIS}" \
        "https://${HJ_UPDATE_HOST}${pfad}"
}

us_senden() {
    local pfad="$1" koerper="$2"
    : "${HJ_UPDATE_HOST:?HJ_UPDATE_HOST fehlt}"
    curl -fsS --max-time 30 \
        -u "${HJ_KNOTEN_KENNUNG}:${HJ_KNOTEN_GEHEIMNIS}" \
        -H "Content-Type: application/json" \
        -d "$koerper" \
        "https://${HJ_UPDATE_HOST}${pfad}"
}

# ------------------------------------------------------------------ Tresor

# Holt ein Tresorprofil und legt es als .env-Teil ab.
#
# Der Umschlag ist an den Schluessel dieses Knotens gerichtet; kein anderer
# kann ihn oeffnen. Der Klartext landet nie auf der Platte - er geht durch
# eine Prozessersetzung direkt in die Zieldatei, und die wird vorher mit 0600
# angelegt.
tresor_holen() {
    local profil="$1" ziel="$2"
    local umschlag
    umschlag="$(mktemp)"
    # shellcheck disable=SC2064
    trap "rm -f '$umschlag'" RETURN

    if ! us_holen "/tresor/${profil}" "$umschlag"; then
        warnen "Tresor ${profil} nicht abrufbar."
        return 1
    fi

    # Zuerst die Rechte, dann der Inhalt. Andersherum laege der Klartext
    # einen Augenblick lang allgemein lesbar da.
    : > "$ziel"
    chmod 600 "$ziel"

    if ! bash "$(dirname "${BASH_SOURCE[0]}")/tresor-oeffnen.sh" \
            "$umschlag" "$SCHLUESSEL" > "$ziel"; then
        rm -f "$ziel"
        warnen "Tresor ${profil} liess sich nicht oeffnen."
        return 1
    fi
    sagen "Tresor ${profil} uebernommen."

    # Ist auch etwas drin?
    #
    # Ein leerer oder halb gefuellter Tresor faellt sonst erst beim Starten
    # auf - und dann als Wand aus Compose-Meldungen:
    #
    #   error while interpolating services.core.environment.HJ_BOT_TOKEN:
    #   required variable HJ_BOT_TOKEN is missing a value
    #
    # Die zeigen auf die Compose-Datei, obwohl dort nichts fehlt. Die Ursache
    # liegt auf dem Update-Server: dort ist "tresor.sh fuellen" nie gelaufen.
    # Zwischen der Meldung und der Ursache liegen zwei Maschinen.
    tresor_pruefen "$profil" "$ziel"
}

# Welche Werte ein Profil mitbringen muss, damit der Stack startet.
#
# Abgeleitet aus den ${VAR:?...}-Angaben der Compose-Dateien - dort steht,
# was ohne Wert zum Abbruch fuehrt.
tresor_pruefen() {
    local profil="$1" datei="$2" noetig="" fehlend=""

    case "$profil" in
        voll|core)
            noetig="HJ_BOT_TOKEN HJ_DB_PASSWORD HJ_DISCORD_CLIENT_ID
                    HJ_DISCORD_CLIENT_SECRET HJ_LAVALINK_PASSWORD HJ_WEB_BASE_URL" ;;
        lavalink)
            noetig="HJ_LAVALINK_PASSWORD" ;;
        controller)
            noetig="HJ_BOT_TOKEN HJ_DB_PASSWORD HJ_DISCORD_CLIENT_ID
                    HJ_DISCORD_CLIENT_SECRET HJ_LAVALINK_PASSWORD HJ_WEB_BASE_URL" ;;
        *)  return 0 ;;
    esac

    for schluessel in $noetig; do
        # Vorhanden UND nicht leer. "HJ_BOT_TOKEN=" ist so gut wie gar nichts.
        if ! grep -qE "^${schluessel}=.+" "$datei" 2>/dev/null; then
            fehlend="${fehlend} ${schluessel}"
        fi
    done

    if [[ -n "$fehlend" ]]; then
        warnen "Im Tresor-Profil '${profil}' fehlen Werte:"
        for f in $fehlend; do warnen "    ${f}"; done
        warnen ""
        warnen "Auf dem Update-Server nachholen:"
        warnen "    bash update-server/tresor.sh fuellen ${profil}"
        warnen ""
        warnen "Danach hier erneut:  bash install-node.sh --modules <liste>"
        return 1
    fi
    return 0
}

# --------------------------------------------------------------- Zustand

# Was dieser Host gerade tut. Bewusst knapp - der Server braucht kein Abbild
# der Maschine, sondern die Antwort auf "geht es dieser Node gut".
zustand_sammeln() {
    local laufend last speicher platte
    laufend="$(docker compose ps --status running --format '{{.Service}}' 2>/dev/null \
               | paste -sd, - || echo "")"
    last="$(cut -d' ' -f1-3 < /proc/loadavg)"
    speicher="$(free -m | awk '/^Mem:/ {print $3 "/" $2 " MB"}')"
    platte="$(df -h --output=pcent "${ARBEIT}" 2>/dev/null | tail -1 | tr -d ' %')"

    printf '{"dienste":"%s","module":"%s","last":"%s","speicher":"%s","platte_prozent":%s}' \
        "$laufend" "$(module_lesen | tr ' ' ',')" "$last" "$speicher" "${platte:-0}"
}

# ------------------------------------------------------------------ Docker

# Compose mit allen noetigen Dateien.
#
# Die Spock-Ueberlagerung MUSS mit, sobald sie eingerichtet ist. Ohne sie legt
# Compose Postgres aus der Grunddatei neu an - auf einem leeren Volume, mit
# einem anderen Abbild. Das ist genau einmal passiert und hat die Datenbank
# gekostet; deshalb steht es hier an einer Stelle statt in jedem Aufrufer.
compose() {
    local dateien=(-f docker-compose.yml)
    [[ "${HJ_SPOCK:-}" == "true" || -f "${ARBEIT}/.spock" ]] \
        && dateien+=(-f docker-compose.spock.yml)
    ( cd "${ARBEIT}/main/deploy/docker" && docker compose "${dateien[@]}" "$@" )
}
