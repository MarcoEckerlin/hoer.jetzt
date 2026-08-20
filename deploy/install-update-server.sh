#!/usr/bin/env bash
#
# hoer.jetzt - Update-Server aufsetzen.
#
#   bash install-update-server.sh
#   bash install-update-server.sh --passwort-stdin < geheim.txt
#   bash install-update-server.sh --passwort-datei /root/pw.txt
#   bash install-update-server.sh --passwort 'GEHEIM'          # siehe unten
#
#   --zweig <name>     Vorgabe: main
#   --quelle <url>     Vorgabe: das GitHub-Repository
#   --nur-holen        Quellen holen, nicht einrichten
#
# ---------------------------------------------------------------------------
# Warum dieses Skript von GitHub kommt und nicht vom Update-Server
#
# Weil es ihn gerade erst anlegt. Jeder andere Installer holt sich seinen
# Stand von repo.updates.hoer.jetzt - der hier kann das nicht, er ist das
# Ziel. Das ist kein Schoenheitsfehler, sondern der Grund, warum GitHub als
# Bezugsquelle erhalten bleibt, obwohl der Update-Server sie sonst ersetzt.
#
# Damit ist auch klar, was hier NICHT hingehoert: Zugangsdaten. Das
# Repository ist die eine Quelle, die diese Kette nicht schuetzt.
#
# ---------------------------------------------------------------------------
# Das Initialpasswort
#
# Abschnitt 10 der Spezifikation verlangt, dass der Aufruf eines entgegennimmt.
# Er verlangt zugleich, dass es nicht in der Shell-Historie, im Abbild, im
# Repository oder in Logs landet. Beides zusammen geht nur so:
#
#   --passwort-stdin   die saubere Form. Nichts steht in der Historie,
#                      nichts in "ps aux".
#   --passwort-datei   fast so gut. Die Datei wird nach dem Lesen
#                      ueberschrieben und geloescht.
#   --passwort         funktioniert, WARNT aber - der Wert steht danach in
#                      ~/.bash_history und war waehrend des Laufs in
#                      "ps aux" fuer jeden lokalen Benutzer sichtbar. Es gibt
#                      keine Moeglichkeit, das nachtraeglich zu heilen; nur
#                      der Hinweis, das Passwort zu wechseln.
#
# Ohne Angabe erzeugt einrichten.sh eines und zeigt es einmal an - das ist
# die beste der vier Varianten und deshalb die Vorgabe.
# ---------------------------------------------------------------------------

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
ZWEIG="main"
QUELLE="${HJ_QUELLE:-https://github.com/MarcoEckerlin/hoer.jetzt.git}"
NUR_HOLEN=false
PASSWORT=""
PASSWORT_UNSICHER=false

sagen()  { printf '[update-server] %s\n' "$*"; }
warnen() { printf '[update-server] WARNUNG: %s\n' "$*" >&2; }
fehler() { printf '[update-server] FEHLER: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Sich selbst nicht unter den Fuessen wegziehen
#
# Ab dem zweiten Lauf liegt dieses Skript in ${ARBEIT}/${ZWEIG}/deploy/ - also
# GENAU in dem Verzeichnis, das es weiter unten per "git reset --hard"
# ueberschreibt.
#
# bash liest ein Skript haeppchenweise waehrend der Ausfuehrung. Aendert sich
# die Datei mittendrin, liest bash ab dort an einer verschobenen Stelle weiter
# und fuehrt Bruchstuecke aus - mit Fehlermeldungen, die zu nichts im Skript
# passen. Beim ersten Lauf faellt das nicht auf, weil es das Verzeichnis noch
# nicht gibt.
#
# Deshalb: in eine Kopie ausserhalb des Baums umziehen und von dort
# weitermachen.
#
# Das steht VOR der Argumentauswertung, und das ist kein Zufall:
#   - danach waere "$@" bereits leergeshiftet, der zweite Lauf bekaeme also
#     keine Angaben mehr,
#   - und --passwort-stdin haette die Standardeingabe schon gelesen. exec
#     erhaelt die Dateideskriptoren, ein zweiter Leseversuch faende nichts.
# ---------------------------------------------------------------------------
if [[ -z "${HJ_UMGEZOGEN:-}" ]]; then
    EIGEN="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || true)"
    if [[ -n "$EIGEN" && "$EIGEN" == "${ARBEIT}/"* ]]; then
        KOPIE="$(mktemp)"
        cp "$EIGEN" "$KOPIE"
        export HJ_UMGEZOGEN=1
        # Kein trap zum Aufraeumen: exec ersetzt diesen Prozess, er kaeme nie
        # zum Zug. Die Kopie liegt in /tmp, enthaelt nur dieses Skript und
        # verschwindet beim naechsten Neustart.
        exec bash "$KOPIE" "$@"
    fi
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --zweig)  ZWEIG="${2:?Zweig angeben}"; shift 2 ;;
        --quelle) QUELLE="${2:?URL angeben}"; shift 2 ;;
        --nur-holen) NUR_HOLEN=true; shift ;;
        --passwort-stdin)
            IFS= read -r PASSWORT || true
            [[ -n "$PASSWORT" ]] || fehler "Nichts auf der Standardeingabe."
            shift
            ;;
        --passwort-datei)
            datei="${2:?Datei angeben}"
            [[ -r "$datei" ]] || fehler "Nicht lesbar: ${datei}"
            IFS= read -r PASSWORT < "$datei" || true
            [[ -n "$PASSWORT" ]] || fehler "Datei ist leer: ${datei}"
            # Ueberschreiben, nicht nur loeschen: ein blosses rm laesst den
            # Inhalt auf der Platte stehen, bis der Block neu vergeben wird.
            shred -u "$datei" 2>/dev/null || { : > "$datei"; rm -f "$datei"; }
            sagen "Passwortdatei gelesen und entfernt."
            shift 2
            ;;
        --passwort)
            PASSWORT="${2:?Passwort angeben}"
            PASSWORT_UNSICHER=true
            shift 2
            ;;
        -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fehler "Unbekannte Angabe: $1" ;;
    esac
done

[[ "$(id -u)" == "0" ]] || fehler "Als root ausfuehren."

if $PASSWORT_UNSICHER; then
    warnen "--passwort steht jetzt in der Shell-Historie und stand waehrend"
    warnen "des Laufs in 'ps aux'. Das laesst sich nicht nachtraeglich heilen."
    warnen "Beim naechsten Mal: --passwort-stdin oder --passwort-datei."
    warnen "Historie loeschen:  history -d \$((HISTCMD-1))"
fi

# ------------------------------------------------------- Voraussetzungen

sagen "Voraussetzungen"

if command -v apt-get >/dev/null; then
    fehlend=()
    for w in git curl openssl ca-certificates util-linux; do
        dpkg -s "$w" >/dev/null 2>&1 || fehlend+=("$w")
    done
    if [[ ${#fehlend[@]} -gt 0 ]]; then
        sagen "Nachinstallieren: ${fehlend[*]}"
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq && apt-get install -y -qq "${fehlend[@]}"
    fi
else
    for w in git curl openssl; do
        command -v "$w" >/dev/null || fehler "${w} fehlt und apt-get gibt es nicht."
    done
fi

if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
    sagen "Docker einrichten"
    curl -fsSL https://get.docker.com | sh
    docker compose version >/dev/null 2>&1 || fehler "Docker Compose fehlt weiterhin."
fi
sagen "Docker $(docker --version | cut -d' ' -f3 | tr -d ,)"

# -------------------------------------------------------- Quellen holen

ZIEL="${ARBEIT}/${ZWEIG}"
sagen "Quellen nach ${ZIEL}"

mkdir -p "$ARBEIT"

if [[ -d "${ZIEL}/.git" ]]; then
    # Mehrfach ausfuehren ist sicher: vorhandene Arbeitskopie wird
    # aktualisiert statt neu geklont. Ein "rm -rf" waere hier der bequeme und
    # falsche Weg - im selben Verzeichnis liegt die .env des Servers.
    sagen "Vorhandene Arbeitskopie wird aktualisiert."
    git -C "$ZIEL" fetch -q --depth 1 origin "$ZWEIG"
    git -C "$ZIEL" reset -q --hard "origin/${ZWEIG}"
else
    git clone -q --branch "$ZWEIG" --single-branch --depth 1 "$QUELLE" "$ZIEL"
fi
sagen "Stand: $(git -C "$ZIEL" rev-parse --short HEAD)"

# Zeilenenden.
#
# Wird das Repository unter Windows ausgecheckt und weitergereicht, tragen
# die Skripte CRLF - und ein "#!/usr/bin/env bash" mit angehaengtem
# Wagenruecklauf endet in "set: Illegal option -". Genau das hat hier schon
# einmal Lavalink in eine Neustartschleife geschickt. .gitattributes verhindert
# es beim Auschecken; diese Zeile faengt den Rest ab.
find "$ZIEL" -name "*.sh" -exec sed -i 's/\r$//' {} + 2>/dev/null || true

if $NUR_HOLEN; then
    sagen "Nur geholt - einrichten.sh nicht gestartet."
    sagen "Weiter mit: bash ${ZIEL}/update-server/einrichten.sh"
    exit 0
fi

# ------------------------------------------------------------ Einrichten

sagen "einrichten.sh starten"

EINRICHTEN="${ZIEL}/update-server/einrichten.sh"
[[ -f "$EINRICHTEN" ]] || fehler "${EINRICHTEN} fehlt - falscher Zweig?"

# Das Passwort geht ueber die Umgebung, nicht als Argument.
#
# Als Argument stuende es in "ps aux" und waere fuer jeden lokalen Benutzer
# lesbar, solange der Prozess laeuft. Die Umgebung eines Prozesses liest unter
# Linux nur sein Eigentuemer und root - das ist nicht perfekt, aber deutlich
# besser, und mehr gibt die Schnittstelle zu einem Kindprozess nicht her.
if [[ -n "$PASSWORT" ]]; then
    export HJ_VERWALTER_PASSWORT="$PASSWORT"
    unset PASSWORT
    sagen "Initialpasswort wird uebernommen."
fi

exec bash "$EINRICHTEN"
