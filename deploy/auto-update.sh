#!/usr/bin/env bash
#
# hoer.jetzt - naechtliches Update auf das jeweils neueste Release.
#
#   bash auto-update.sh              normal, wartet auf eine ruhige Minute
#   bash auto-update.sh --jetzt      sofort, ohne auf Zuhoerer zu ruecksichtigen
#   bash auto-update.sh --pruefen    nur nachsehen, nichts aendern
#   bash auto-update.sh --zurueck    auf das vorige Release zurueck
#
# Bezugsquelle ist der eigene Update-Server, nicht mehr GitHub. Er liefert
# fertige Abbilder statt Quellcode. Was das aendert:
#
#   - Auf diesem Host liegt kein Quellbaum mehr, kein Maven, kein JDK.
#   - Ein Update dauert Sekunden statt Minuten - es wird nichts gebaut,
#     nur geladen.
#   - Der Rueckweg ist ein Abbild-Tag, kein Rebuild. Deshalb gibt es
#     jetzt --zurueck.
#   - Der Zugang ist ein langes Zufallspasswort in der .env, kein
#     SSH-Deploy-Key mehr und kein Client-Zertifikat.
#     Wer es abgreift, kann die Abbilder ziehen, die dieser Host ohnehin
#     ausfuehrt. Zugangsdaten stehen darin nicht.
#
# Was gleich bleibt: es zaehlt nur ein veroeffentlichtes Release. Ein Push
# landet nicht in derselben Nacht auf einem Produktivsystem.

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
UMGEBUNG="${ARBEIT}/.env"
STAND="${ARBEIT}/.installiert"
VORHER="${ARBEIT}/.vorheriges"
PROTOKOLL="${PROTOKOLL:-/var/log/hoerjetzt-update.log}"
SPERRE="/var/lock/hoerjetzt-update.lock"

# Wartet auf eine Wiedergabepause: alle WARTE_TAKT Sekunden neu nachsehen,
# hoechstens WARTE_VERSUCHE mal. Danach bleibt es beim alten Stand und die
# naechste Nacht bekommt eine neue Gelegenheit.
WARTE_TAKT="${WARTE_TAKT:-900}"
WARTE_VERSUCHE="${WARTE_VERSUCHE:-8}"

NUR_PRUEFEN=0
SOFORT=0
ZURUECK=0
for argument in "$@"; do
    case "$argument" in
        --pruefen) NUR_PRUEFEN=1 ;;
        --jetzt)   SOFORT=1 ;;
        --zurueck) ZURUECK=1; SOFORT=1 ;;
        *) echo "Unbekannt: ${argument}" >&2; exit 2 ;;
    esac
done

sagen() {
    printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$PROTOKOLL"
}
ende()   { sagen "$*"; exit 0; }
fehler() { sagen "FEHLER: $*"; exit 1; }

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

wert() { grep "^$1=" "$UMGEBUNG" 2>/dev/null | cut -d= -f2- | head -n1 || true; }

mkdir -p "$(dirname "$PROTOKOLL")" "$(dirname "$SPERRE")" 2>/dev/null || true

# Zwei gleichzeitige Laeufe waeren ein halb gebautes System. Der zweite geht.
exec 9>"$SPERRE"
flock -n 9 || ende "Ein Update laeuft bereits - dieser Lauf entfaellt."

[[ -f "$UMGEBUNG" ]] || fehler "${UMGEBUNG} fehlt."

# ------------------------------------------------------------------ Zugang
#
# Der Knoten weist sich mit seiner EIGENEN Kennung aus, nicht mehr mit dem
# gemeinsamen Passwort.
#
# Hier standen drei Fehler uebereinander, und zusammen haben sie jedes
# Update verhindert:
#
#   1. Das Skript verlangte HJ_TOKEN_KNOTEN und brach ohne ihn ab. Gesetzt
#      hat den Wert nur der alte interaktive Weg (knoten-aufsetzen.sh) -
#      ein ueber bootstrap aufgesetzter Knoten hat ihn nie.
#   2. hole() und melden() meldeten sich als Benutzer "knoten" mit diesem
#      gemeinsamen Passwort an. Der Updater weist das ab, seit
#      hj.token.gemeinsam-erlauben auf false steht - also 401, selbst wenn
#      der Wert dagewesen waere.
#   3. HJ_KNOTEN_KENNUNG und HJ_KNOTEN_GEHEIMNIS wurden weiter unten fuer
#      "docker login" benutzt, aber nirgends aus der .env gelesen. Die .env
#      wird nicht in die Umgebung geladen; beide waren leer, und ":?" brach
#      ab mit "Knoten nicht angemeldet" - obwohl er angemeldet war.
#
# Das gemeinsame Passwort bleibt als Rueckfallweg, aber nur wenn es keine
# eigene Kennung gibt: Knoten aus der Zeit davor sollen nicht stehen
# bleiben, bloss weil sie noch nicht umgestellt sind.

HJ_UPDATE_HOST="$(wert HJ_UPDATE_HOST)"
HJ_KNOTEN_KENNUNG="$(wert HJ_KNOTEN_KENNUNG)"
HJ_KNOTEN_GEHEIMNIS="$(wert HJ_KNOTEN_GEHEIMNIS)"
HJ_TOKEN_KNOTEN="$(wert HJ_TOKEN_KNOTEN)"

if [[ -z "$HJ_UPDATE_HOST" ]]; then
    fehler "In ${UMGEBUNG} fehlt HJ_UPDATE_HOST.
       Dieser Host haengt noch am alten GitHub-Weg. Umstellen:
           curl -fsSLu knoten https://<update-server>/knoten/aufsetzen.sh -o a.sh && bash a.sh"
fi

if [[ -n "$HJ_KNOTEN_KENNUNG" && -n "$HJ_KNOTEN_GEHEIMNIS" ]]; then
    ANMELDE_BENUTZER="$HJ_KNOTEN_KENNUNG"
    ANMELDE_PASSWORT="$HJ_KNOTEN_GEHEIMNIS"
elif [[ -n "$HJ_TOKEN_KNOTEN" ]]; then
    # Uebergang. Der Updater nimmt das nur an, solange
    # HJ_GEMEINSAM_ERLAUBEN=true gesetzt ist - sonst antwortet er 401 und
    # die Meldung weiter unten sagt, was zu tun ist.
    ANMELDE_BENUTZER="knoten"
    ANMELDE_PASSWORT="$HJ_TOKEN_KNOTEN"
    sagen "WARNUNG: noch am gemeinsamen Passwort. Einmal install-node.sh mit"
    sagen "         frischem Aufsetz-Token gibt diesem Knoten eine eigene Kennung."
else
    fehler "Dieser Knoten hat keinen Zugang zum Update-Server.
       Weder HJ_KNOTEN_KENNUNG/HJ_KNOTEN_GEHEIMNIS noch HJ_TOKEN_KNOTEN
       stehen in ${UMGEBUNG}. Mit einem frischen Aufsetz-Token anmelden:
           bash ${ARBEIT}/main/deploy/install-node.sh \\
                --kennung <name> --token hj-... --modules <liste>"
fi

DOCKER="${ARBEIT}/main/deploy/docker"
[[ -d "$DOCKER" ]] || fehler "${DOCKER} fehlt."

# ------------------------------------------------------------------ Release

hole() {
    curl -fsS -m 30 \
        -u "${ANMELDE_BENUTZER}:${ANMELDE_PASSWORT}" \
        "https://${HJ_UPDATE_HOST}$1"
}

# ------------------------------------------------------------------ Melden
#
# Ein Herzschlag an den Update-Server, einmal je Lauf. Bewusst nicht mehr:
# der Agent (deploy/agent/hj-agent.sh) meldet ohnehin jede Minute Zustand
# und Version an den Controller. Was der Update-Server zusaetzlich wissen
# will, faellt nur hier an - naemlich ob das Update selbst durchgelaufen ist
# und auf welchem Stand dieser Host danach steht.
#
# Die Kennung ist dieselbe wie beim Agenten, damit sich beide Ansichten
# ueber einen Namen zusammenbringen lassen und nicht zwei Listen entstehen,
# die dasselbe meinen.
KENNUNG="$(wert HJ_NODE_NAME)"
KENNUNG="${KENNUNG:-$(hostname -s 2>/dev/null || echo unbekannt)}"
PROFIL="$(wert HJ_PROFIL)"

# Schlaegt der Herzschlag fehl, ist das kein Grund abzubrechen: die Meldung
# ist Beiwerk, das Update ist die Aufgabe. Ein Update, das an einer
# Statusmeldung scheitert, waere die schlechtere Maschine.
melden() {
    local ergebnis="$1" zustand="${2:-}" antwort

    antwort="$(curl -fsS -m 15 -X POST \
        -u "${ANMELDE_BENUTZER}:${ANMELDE_PASSWORT}" \
        -H "Content-Type: application/json" \
        -d "$(printf '{"kennung":"%s","name":"%s","profil":"%s","version":"%s","vorher":"%s","zustand":"%s","ergebnis":"%s"}' \
            "$KENNUNG" "$KENNUNG" "$PROFIL" \
            "$(cat "$STAND" 2>/dev/null || echo '')" \
            "$(cat "$VORHER" 2>/dev/null || echo '')" \
            "$zustand" "$ergebnis")" \
        "https://${HJ_UPDATE_HOST}/melden" 2>/dev/null)" || {
        sagen "Herzschlag an ${HJ_UPDATE_HOST} nicht durchgekommen - weiter im Ablauf."
        printf ''
        return 0
    }
    printf '%s' "$antwort"
}

MANIFEST="$(hole /release/aktuell || true)"
[[ -n "$MANIFEST" ]] || fehler "${HJ_UPDATE_HOST} nicht erreichbar oder Passwort falsch."

manifestwert() { printf '%s\n' "$MANIFEST" | grep "^$1=" | cut -d= -f2- | head -n1 || true; }

NEUSTE="$(manifestwert version)"
REGISTRY="$(manifestwert registry)"
[[ -n "$NEUSTE" ]]   || ende "Noch nichts veroeffentlicht - nichts zu tun."
[[ -n "$REGISTRY" ]] || fehler "Das Manifest nennt keine Registry."

AKTUELL="$(cat "$STAND" 2>/dev/null || true)"

# Herzschlag zu Beginn. Er steht hier und nicht erst am Ende, damit auch ein
# Lauf, bei dem es nichts zu tun gibt, als Lebenszeichen zaehlt - sonst saehen
# in der Uebersicht ausgerechnet die Knoten stumm aus, die schlicht aktuell
# sind.
#
# Die Antwort traegt zurueck, ob in der Oberflaeche ein sofortiges Update
# vorgemerkt wurde. Das ist der einzige Weg in diese Richtung: es geht keine
# Verbindung vom Update-Server zu den Knoten, die stehen hinter fremdem NAT.
ANTWORT="$(melden geprueft)"
if printf '%s' "$ANTWORT" | grep -q '"update_angefordert":true'; then
    sagen "Sofortiges Update ist vorgemerkt - ohne Warten auf eine Wiedergabepause."
    SOFORT=1
fi

if [[ "$ZURUECK" -eq 1 ]]; then
    NEUSTE="$(cat "$VORHER" 2>/dev/null || true)"
    [[ -n "$NEUSTE" ]] || fehler "Kein voriges Release vermerkt - nichts, wohin zurueck."
    sagen "Zurueck auf ${NEUSTE} (laeuft: ${AKTUELL:-unbekannt})"
elif [[ "$NEUSTE" == "$AKTUELL" ]]; then
    ende "${NEUSTE} laeuft bereits."
else
    sagen "Neues Release: ${NEUSTE} (installiert: ${AKTUELL:-unbekannt})"
fi

[[ "$NUR_PRUEFEN" -eq 1 ]] && ende "Nur geprueft, nichts geaendert."

# ------------------------------------------------------------------ Compose

COMPOSE=(-f docker-compose.yml)

# Die Erweiterungsdatei muss mit, sonst macht jedes Update sie rueckgaengig.
#
# Vorher stand hier nur docker-compose.yml. Wer den Verbund mit Spock
# aufgesetzt hatte, bekam beim naechsten Update wieder das Standard-Abbild
# postgres:16-alpine untergeschoben - und auf dem gibt es die Erweiterung
# nicht. Die Daten im Volume ueberleben das, die Replikation nicht: danach
# meldet psql nur noch 'relation "spock.node" does not exist'.
if grep -q '^HJ_SPOCK=true' "$UMGEBUNG" 2>/dev/null; then
    COMPOSE+=(-f docker-compose.spock.yml)
fi
for zusatz in $(wert HJ_COMPOSE_EXTRA); do
    [[ -f "${DOCKER}/${zusatz}" ]] || fehler "In HJ_COMPOSE_EXTRA steht ${zusatz}, die Datei gibt es nicht."
    COMPOSE+=(-f "$zusatz")
done
sagen "Compose: ${COMPOSE[*]}"

# ------------------------------------------------------------------ Zuhoerer

# Fragt den mitlaufenden Knoten, wie viele Wiedergaben gerade aktiv sind.
# Kommt keine Antwort, wird das als "keine" gewertet: ein kaputter Knoten ist
# kein Grund, ein Update ewig aufzuschieben.
spielende() {
    local passwort antwort
    passwort="$(wert HJ_LAVALINK_PASSWORD)"
    [[ -n "$passwort" ]] || { echo 0; return 0; }

    antwort="$(cd "$DOCKER" && docker compose "${COMPOSE[@]}" exec -T lavalink-free-1 \
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

# ------------------------------------------------------------------ Laden

cd "$DOCKER"

# Compose liest die Marken aus der Umgebung. Die Registry steht mit darin,
# damit ein Umzug des Update-Servers keine Aenderung an den Compose-Dateien
# braucht.
cp "$UMGEBUNG" .env
chmod 600 .env
{
    printf 'HJ_REGISTRY=%s\n' "$REGISTRY"
    for teil in core ai-radio lavalink web; do
        marke="$(manifestwert "$teil")"
        [[ -n "$marke" ]] || fehler "Das Manifest nennt keinen Stand fuer ${teil}."
        # core -> CORE_TAG, ai-radio -> AI_RADIO_TAG
        printf '%s_TAG=%s\n' "$(printf '%s' "$teil" | tr 'a-z-' 'A-Z_')" "$marke"
    done
} >> .env

# ------------------------------------------------------- Zentrale Vorgaben
#
# Werte, die auf allen Knoten gleich sind - Lavalink-Qualitaet, Plugin-
# Version, Schwellen. Gepflegt wird das auf der Oberflaeche des
# Update-Servers, nicht auf jeder Maschine einzeln.
#
# Sie kommen NACH der uebernommenen .env, also gewinnen sie: fuer diese
# Schluessel ist der Update-Server die Quelle. Was je Knoten verschieden
# sein muss, steht gar nicht erst darin.
#
# Fehlt die Datei, laeuft alles weiter. Ein Update darf nicht daran
# scheitern, dass noch niemand eine Vorgabe gesetzt hat.
VORGABEN="$(hole "/voreinstellungen/${PROFIL}.env" 2>/dev/null || true)"
if [[ -n "$VORGABEN" ]]; then
    # Die Datei kommt ueber das Netz. Der Katalog auf dem Server laesst
    # Geheimnisse und Knotenspezifisches nicht zu - aber darauf verlaesst
    # sich der Knoten nicht. Er hat seine eigene Sperrliste, und sie
    # entscheidet hier.
    #
    # Ohne sie liesse sich ueber diese Datei ein HJ_BOT_TOKEN oder eine
    # Shard-Grenze in die .env schreiben, und der Knoten uebernaehme es
    # wortlos.
    gesperrt="HJ_BOT_TOKEN HJ_DB_PASSWORD HJ_DISCORD_CLIENT_SECRET
              HJ_DISCORD_CLIENT_ID HJ_LAVALINK_PASSWORD YT_CIPHER_PASSWORD
              YOUTUBE_REFRESH_TOKEN HJ_HETZNER_TOKEN HJ_GEHEIMNIS_SCHLUESSEL
              HJ_NODE_TOKEN HJ_AGENT_TOKEN HJ_CONTROLLER_TOKEN HJ_TOKEN_KNOTEN
              HJ_SHARD_VON HJ_SHARD_BIS HJ_SHARDS_GESAMT HJ_NODE_NR HJ_NODE_NAME
              HJ_PRIVAT_IP HJ_ROLLE HJ_PROFIL HJ_UPDATE_HOST
              HJ_REGISTRY CORE_TAG WEB_TAG LAVALINK_TAG KI_RADIO_TAG AI_RADIO_TAG"

    uebernommen=0
    abgelehnt=0
    while IFS= read -r zeile; do
        [[ -z "$zeile" || "$zeile" == \#* ]] && continue
        schluessel="${zeile%%=*}"
        # Nur echte Zuweisungen. Alles andere ist kein Wert, sondern Unrat.
        [[ "$zeile" == *=* ]] || continue
        [[ "$schluessel" =~ ^[A-Z][A-Z0-9_]*$ ]] || { abgelehnt=$((abgelehnt+1)); continue; }
        if printf '%s' "$gesperrt" | tr -s ' \n' '\n\n' | grep -qx "$schluessel"; then
            sagen "WARNUNG: Vorgabe ${schluessel} abgelehnt - gehoert nicht in eine zentrale Datei."
            abgelehnt=$((abgelehnt+1))
            continue
        fi
        printf '%s\n' "$zeile" >> .env
        uebernommen=$((uebernommen+1))
    done <<< "$VORGABEN"

    sagen "Zentrale Vorgaben: ${uebernommen} uebernommen, ${abgelehnt} abgelehnt."
fi

# Anmeldung an der Registry - mit der EIGENEN Kennung.
#
# Frueher stand hier "-u knoten" mit dem gemeinsamen Knoten-Passwort. Seit
# hj.token.gemeinsam-erlauben abgeschaltet ist, wird das abgewiesen - und
# zwar mit einer Meldung, die nach einem Registry-Problem aussieht:
#
#   failed to fetch anonymous token: 401 Unauthorized
#
# Der Benutzername traegt die Kennung des Knotens; genau daran haengt, welche
# Abbilder er ziehen darf. Ein Audio-Knoten kommt so an lavalink und nicht an
# core - mit dem gemeinsamen Passwort kam er an alles.
#
# "docker login" schreibt die Anmeldung nach ~/.docker/config.json und haelt
# sie dort. Der Aufruf hier ist deshalb meist ein No-op, kostet aber nichts
# und heilt den Fall, dass jemand die Datei geleert oder das Geheimnis
# getauscht hat.
# Dieselbe Anmeldung wie oben - kein zweites Paar Werte, das auseinander-
# laufen kann. Die ":?"-Pruefungen, die hier standen, brachen ab, obwohl der
# Knoten angemeldet war: sie lasen Shell-Variablen, die niemand gefuellt
# hatte, weil die .env nicht in die Umgebung geladen wird.
if ! printf '%s' "$ANMELDE_PASSWORT" | docker login "$HJ_UPDATE_HOST" \
        -u "$ANMELDE_BENUTZER" --password-stdin >/dev/null 2>&1; then
    fehler "Anmeldung an der Registry ${HJ_UPDATE_HOST} als ${ANMELDE_BENUTZER} fehlgeschlagen.

       Gilt das Geheimnis noch? Wurde der Knoten im Updater neu angelegt,
       muss er sich mit einem frischen Aufsetz-Token neu anmelden:
           bash install-node.sh --kennung ${ANMELDE_BENUTZER} --token hj-... --modules <liste>"
fi

# Erst laden, dann umschalten. Bricht das Laden ab - Netz weg, Abbild fehlt,
# Platte voll - hat der laufende Stack davon nichts mitbekommen. Das war
# frueher der Build an dieser Stelle und dauerte Minuten statt Sekunden.
if ! docker compose "${COMPOSE[@]}" pull >>"$PROTOKOLL" 2>&1; then
    fehler "Abbilder liessen sich nicht laden - alter Stand laeuft weiter. Siehe ${PROTOKOLL}."
fi

# ------------------------------------------------------------------ Nachweis
#
# Abschnitt 37: ein beschaedigtes oder vertauschtes Abbild darf nicht laufen.
#
# Ein Tag ist eine Beschriftung. "core:2026.08.21.01" kann morgen auf ein
# anderes Abbild zeigen, ohne dass sich der Name aendert - versehentlich beim
# Ueberschreiben, oder absichtlich. Der Digest ist der Hash des Abbilds
# selbst; er kann nicht auf etwas anderes zeigen.
#
# Geprueft wird NACH dem Ziehen und VOR dem Starten. Stimmt etwas nicht,
# laeuft der alte Stand weiter - er wurde noch nicht angefasst.
#
# Nennt das Manifest keine Digests, wird nicht geprueft. Ein Update zu
# verweigern waere hier die schlechtere Wahl: der Knoten stuende auf einem
# alten Stand fest, und Stillstand ist auch keine Sicherheit. Es steht aber
# im Protokoll, damit es nicht unbemerkt bleibt.
abweichung=""
geprueft=0
for teil in core ai-radio lavalink web; do
    soll="$(manifestwert "${teil}_digest")"
    [[ "$soll" == sha256:* ]] || continue

    marke="$(manifestwert "$teil")"
    ist="$(docker image inspect --format '{{range .RepoDigests}}{{.}} {{end}}' \
           "${REGISTRY}/${teil}:${marke}" 2>/dev/null || true)"

    # RepoDigests kann mehrere Eintraege haben, wenn dasselbe Abbild unter
    # mehreren Registries bekannt ist. Es genuegt, wenn einer passt.
    if [[ "$ist" == *"@${soll}"* ]]; then
        geprueft=$((geprueft + 1))
    else
        abweichung="${abweichung}
       ${teil}: erwartet ${soll}
                 gefunden ${ist:-<keiner>}"
    fi
done

if [[ -n "$abweichung" ]]; then
    fehler "Ein Abbild stimmt nicht mit dem Manifest ueberein - nichts gestartet.${abweichung}

       Der alte Stand laeuft unveraendert weiter. Entweder wurde ein Tag im
       Verzeichnis ueberschrieben, oder das Manifest passt nicht zu den
       veroeffentlichten Abbildern. Auf dem Update-Server neu
       veroeffentlichen."
fi

if [[ "$geprueft" -gt 0 ]]; then
    sagen "Abbilder gegen das Manifest geprueft (${geprueft})."
else
    sagen "Das Manifest nennt keine Digests - Abbilder ungeprueft uebernommen."
fi

if ! docker compose "${COMPOSE[@]}" up -d >>"$PROTOKOLL" 2>&1; then
    fehler "Start fehlgeschlagen - siehe ${PROTOKOLL}."
fi

# Der bisherige Stand ist ab jetzt der Rueckweg. Beim Zurueckrollen nicht
# ueberschreiben, sonst schaukeln sich zwei kaputte Staende gegenseitig hoch.
if [[ "$ZURUECK" -eq 0 && -n "$AKTUELL" ]]; then
    printf '%s\n' "$AKTUELL" > "$VORHER"
fi
printf '%s\n' "$NEUSTE" > "$STAND"

# ------------------------------------------------------------------ Probe

sleep 30
KERN="$(kernbehaelter)"
zustand="$(docker inspect -f '{{.State.Status}}' "$KERN" 2>/dev/null || echo fehlt)"
if [[ "$zustand" == "running" ]]; then
    sagen "${NEUSTE} laeuft."
else
    sagen "WARNUNG: core steht auf '${zustand}' - docker logs ${KERN}"
    sagen "Rueckweg: bash ${BASH_SOURCE[0]} --zurueck"
fi

# Abschliessender Herzschlag - jetzt mit dem, was tatsaechlich herausgekommen
# ist. Erst hier, weil vorher weder der neue Stand noch der Zustand des
# Containers feststand.
if [[ "$zustand" == "running" ]]; then
    melden "aktualisiert auf ${NEUSTE}" "$zustand" >/dev/null
else
    melden "Start fehlgeschlagen - core steht auf '${zustand}'" "$zustand" >/dev/null
fi

# Aufgeraeumt wird erst nach dem erfolgreichen Start, und nur was aelter als
# eine Woche ist: das vorige Abbild ist der Rueckweg und muss so lange liegen
# bleiben. "docker image prune -f" haette es sofort mitgenommen.
docker image prune -f --filter "until=168h" >/dev/null 2>&1 || true
