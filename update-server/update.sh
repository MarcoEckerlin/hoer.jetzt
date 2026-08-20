#!/usr/bin/env bash
#
# hoer.jetzt - den Update-Server selbst aktualisieren.
#
#   bash update.sh              holen, bauen, neu starten
#   bash update.sh --pruefen    nur nachsehen, ob es etwas Neues gibt
#   bash update.sh --zweig x    anderer Zweig
#
# ---------------------------------------------------------------------------
# Warum ausgerechnet dieser Server ein eigenes Skript braucht
#
# Jeder Knoten holt seine Aktualisierung von hier - auto-update.sh, per Timer,
# nachts um drei. Dieser Server kann das nicht: er IST die Bezugsquelle. Seine
# Quelle ist GitHub, und dorthin fuehrt sonst kein Weg mehr.
#
# Ohne dieses Skript blieb nur die Zeile, die einrichten.sh einmal am Ende
# anzeigt - und die hat man ein halbes Jahr spaeter nicht mehr.
#
# Was hier NICHT passiert: .env, Volumes und Passwoerter bleiben unangetastet.
# Aktualisiert wird der Quellstand und was daraus gebaut wird, sonst nichts.
# ---------------------------------------------------------------------------

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
NUR_PRUEFEN=false
ZWEIG=""

sagen()  { printf '[update] %s\n' "$*"; }
warnen() { printf '[update] WARNUNG: %s\n' "$*" >&2; }
fehler() { printf '[update] FEHLER: %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || fehler "Bitte als root starten."

# Sich selbst nicht unter den Fuessen wegziehen.
#
# Dieses Skript liegt in dem Verzeichnis, das es gleich per "git reset --hard"
# ueberschreibt. bash liest ein Skript waehrend der Ausfuehrung haeppchenweise
# nach; aendert sich die Datei mittendrin, fuehrt es Bruchstuecke aus. Also
# erst in eine Kopie ausserhalb umziehen.
#
# ${BASH_SOURCE[0]:-} und nicht ${BASH_SOURCE[0]}: bei "curl | bash" gibt es
# keinen Dateinamen, und "set -u" macht daraus einen Abbruch.
if [[ -z "${HJ_UMGEZOGEN:-}" ]]; then
    EIGEN="$(readlink -f "${BASH_SOURCE[0]:-}" 2>/dev/null || true)"
    if [[ -n "$EIGEN" && "$EIGEN" == "${ARBEIT}/"* ]]; then
        KOPIE="$(mktemp)"
        cp "$EIGEN" "$KOPIE"
        export HJ_UMGEZOGEN=1
        exec bash "$KOPIE" "$@"
    fi
fi

for arg in "$@"; do
    case "$arg" in
        --pruefen) NUR_PRUEFEN=true ;;
        --zweig)   : ;;   # Wert wird unten gelesen
        -h|--help) sed -n '2,7p' "${BASH_SOURCE[0]:-$0}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    esac
done
while [[ $# -gt 0 ]]; do
    case "$1" in
        --zweig) ZWEIG="${2:?Zweig angeben}"; shift 2 ;;
        *) shift ;;
    esac
done



QUELLBAUM=""
for kandidat in "${ARBEIT}/main" "${ARBEIT}"/*; do
    if [[ -d "${kandidat}/.git" && -f "${kandidat}/update-server/docker-compose.yml" ]]; then
        QUELLBAUM="$kandidat"
        break
    fi
done
[[ -n "$QUELLBAUM" ]] || fehler "Keine Arbeitskopie unter ${ARBEIT} gefunden."

COMPOSE="${QUELLBAUM}/update-server/docker-compose.yml"
[[ -f "${QUELLBAUM}/update-server/.env" ]] \
    || fehler "${QUELLBAUM}/update-server/.env fehlt - erst einrichten.sh laufen lassen."

[[ -n "$ZWEIG" ]] || ZWEIG="$(git -C "$QUELLBAUM" rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
QUELLE="$(git -C "$QUELLBAUM" remote get-url origin 2>/dev/null || echo unbekannt)"
VORHER="$(git -C "$QUELLBAUM" rev-parse --short HEAD 2>/dev/null || echo unbekannt)"

sagen "Quelle:  ${QUELLE}"
sagen "Zweig:   ${ZWEIG}"
sagen "Stand:   ${VORHER}"

# ------------------------------------------------------------------ nachsehen

sagen "Nachsehen, ob es etwas Neues gibt"
git -C "$QUELLBAUM" fetch -q --depth 1 origin "$ZWEIG" \
    || fehler "GitHub nicht erreichbar. Ohne Netz geht hier nichts - dieser
       Server bezieht seinen Stand als einziger von aussen."

NACHHER="$(git -C "$QUELLBAUM" rev-parse --short "origin/${ZWEIG}")"

if [[ "$VORHER" == "$NACHHER" ]]; then
    sagen "Schon aktuell (${VORHER})."
    $NUR_PRUEFEN && exit 0
    sagen "Trotzdem neu bauen und starten? Nichts holt sich sonst etwas."
else
    sagen "Neu: ${VORHER} -> ${NACHHER}"
    # Was sich geaendert hat, in Stichworten. Wer aktualisiert, will wissen,
    # was auf ihn zukommt - und nicht erst hinterher im Log suchen.
    git -C "$QUELLBAUM" log --oneline --no-decorate "HEAD..origin/${ZWEIG}" 2>/dev/null \
        | head -15 | sed 's/^/           /' || true
fi

if $NUR_PRUEFEN; then
    sagen "Nur nachgesehen - nichts geaendert."
    exit 0
fi

# ------------------------------------------------------------------ holen

# Oertliche Aenderungen gehen dabei verloren. Das ist Absicht: der Quellbaum
# auf diesem Server ist eine Arbeitskopie und keine Werkstatt. Wer hier etwas
# von Hand geaendert hat, soll es merken - deshalb wird es genannt.
if ! git -C "$QUELLBAUM" diff --quiet 2>/dev/null; then
    warnen "Im Quellbaum liegen oertliche Aenderungen. Sie gehen verloren:"
    git -C "$QUELLBAUM" diff --stat 2>/dev/null | sed 's/^/           /' || true
fi

sagen "Holen"
git -C "$QUELLBAUM" reset -q --hard "origin/${ZWEIG}" \
    || fehler "Zuruecksetzen auf origin/${ZWEIG} fehlgeschlagen."

# Zeilenenden. Wird das Repository unter Windows ausgecheckt und
# weitergereicht, tragen die Skripte CRLF - und ein Shebang mit angehaengtem
# Wagenruecklauf endet in "set: Illegal option -".
find "$QUELLBAUM" -name "*.sh" -exec sed -i 's/\r$//' {} + 2>/dev/null || true

# ------------------------------------------------------------------ bauen

sagen "Bauen und starten"
# --build ist noetig: der Updater wird aus dem Quellbaum gebaut. Ohne ihn
# liefe der alte Container mit neuem Code daneben, und die Aenderung waere
# unsichtbar - man haette aktualisiert und nichts davon.
if ! docker compose -f "$COMPOSE" up -d --build; then
    fehler "Start fehlgeschlagen. Der alte Stand liegt noch im Abbild:
       docker compose -f ${COMPOSE} logs --tail 40"
fi

# ------------------------------------------------------------ Werkzeug

# bootstrap.sh und das Deploy-Buendel mitziehen.
#
# Sie haengen an keinem Release - es sind die Skripte aus dem Quellbaum, der
# gerade aktualisiert wurde. Wuerden sie hier nicht mitgehen, holte sich ein
# frisch aufgesetzter Knoten weiterhin den alten Stand, und niemand kaeme
# darauf: der Server meldet die neue Version, der Knoten bekommt die alte.
sagen "Aufsetz-Werkzeug nachziehen"
AUS_VOLUME="${AUS_VOLUME:-hj-update_ausliefern}"
schreiben() {
    docker run --rm -i -v "${AUS_VOLUME}:/aus" -w /aus alpine:3 \
        sh -c "mkdir -p \"\$(dirname '$1')\" && cat > '$1.neu' && chmod 644 '$1.neu' && mv '$1.neu' '$1'"
}
if schreiben "knoten/bootstrap.sh" < "${QUELLBAUM}/deploy/bootstrap.sh" \
   && schreiben "knoten/aufsetzen.sh" < "${QUELLBAUM}/deploy/knoten-aufsetzen.sh"; then
    B="$(mktemp -d)"
    mkdir -p "${B}/${ZWEIG}"
    cp -r "${QUELLBAUM}/deploy" "${B}/${ZWEIG}/deploy"
    find "$B" \( -name "*.env" -o -name "*.key" -o -name ".env" \) -delete 2>/dev/null || true
    tar -C "$B" -czf "${B}.tar.gz" .
    if schreiben "knoten/${ZWEIG}.tar.gz" < "${B}.tar.gz"; then
        sagen "  bootstrap.sh, aufsetzen.sh und ${ZWEIG}.tar.gz aktualisiert."
    else
        warnen "Deploy-Buendel liess sich nicht ablegen."
    fi
    rm -rf "$B" "${B}.tar.gz"
else
    warnen "Aufsetz-Werkzeug liess sich nicht ablegen - laeuft Docker?"
fi

# ------------------------------------------------------------------ pruefen

sagen "Nachsehen, ob alles laeuft"
sleep 5
FEHLT=""
for dienst in caddy updater forgejo; do
    zustand="$(docker compose -f "$COMPOSE" ps --format '{{.Service}} {{.State}}' 2>/dev/null \
               | awk -v d="$dienst" '$1==d{print $2}' | head -1)"
    case "$zustand" in
        running) printf '[update]   %-10s laeuft\n' "$dienst" ;;
        "")      printf '[update]   %-10s FEHLT\n' "$dienst"; FEHLT="yes" ;;
        *)       printf '[update]   %-10s %s\n' "$dienst" "$zustand"; FEHLT="yes" ;;
    esac
done

echo
if [[ -n "$FEHLT" ]]; then
    warnen "Nicht alles laeuft. Das Protokoll sagt, warum:"
    warnen "  docker compose -f ${COMPOSE} logs --tail 40"
    exit 1
fi

sagen "Fertig: ${VORHER} -> $(git -C "$QUELLBAUM" rev-parse --short HEAD)"
sagen ".env, Volumes und Passwoerter sind unveraendert."
