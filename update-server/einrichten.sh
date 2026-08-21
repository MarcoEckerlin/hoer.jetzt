#!/usr/bin/env bash
#
# hoer.jetzt - Update-Server aufsetzen. Laeuft einmal, zuhause.
#
#   bash einrichten.sh
#
# Danach gibt es zwei Passwoerter:
#
#   Aufsetz-Passwort   Kurz und tippbar. Damit holt sich ein frischer Rechner
#                      das Installationsskript. Mehr gibt es nicht her.
#
#   Knoten-Passwort    4096 Bit Zufall. Oeffnet Abbilder, Release, Tresor und
#                      die Meldestelle. Bleibt dauerhaft auf dem Knoten.
#
# Zusaetzlich muss jede Adresse im Updater freigeschaltet sein. Passwort
# allein reicht nicht.
#
# TLS macht der Nginx Proxy Manager oder Cloudflare. Dieser Dienst spricht
# einfaches HTTP. Werden seine Ports oeffentlich gebunden - Vorgabe ist
# 0.0.0.0 -, gehoert zwingend TLS davor, sonst gehen Verwalter-Passwort und
# Knoten-Passwort im Klartext ueber die Leitung.
#
# Die Adressfreigabe wirkt in beiden Faellen: Vorfeld.java glaubt den
# Weiterleitungs-Koepfen nur, wenn die Verbindung von einem bekannten Proxy
# kommt. Wer direkt anklopft, wird mit seiner echten Adresse gemessen.

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "${HIER}/lib.sh"
UMGEBUNG="${HIER}/.env"

[[ "$(id -u)" -eq 0 ]] || fail "Bitte als root starten."
command -v docker  >/dev/null 2>&1 || fail "Docker fehlt."
command -v curl    >/dev/null 2>&1 || fail "curl fehlt."
command -v openssl >/dev/null 2>&1 || fail "openssl fehlt (Paket openssl)."
docker compose version >/dev/null 2>&1 || fail "docker compose (v2) fehlt."

if [[ -f "$UMGEBUNG" ]]; then
    # ----------------------------------------------------------------------
    # Vorhandene Werte uebernehmen, statt alles neu zu erfragen.
    #
    # Frueher stand hier nur "Alles neu aufsetzen? j/n" - und ein Ja hiess:
    # jede Frage noch einmal beantworten, alle vier Passwoerter neu, jeder
    # Knoten ausgesperrt. Das ist bei einem abgebrochenen Lauf genau die
    # falsche Antwort. Man will ihn zu Ende bringen, nicht von vorn beginnen.
    #
    # Jetzt werden die Werte aus der .env gelesen und als Vorgabe angeboten:
    # Enter uebernimmt sie. Die Passwoerter bleiben, wie sie sind - erzeugt
    # wird nur, was fehlt.
    #
    # Wer wirklich alles neu will, nimmt neu-aufsetzen.sh.
    # ----------------------------------------------------------------------
    alt() { grep "^$1=" "$UMGEBUNG" 2>/dev/null | head -1 | cut -d= -f2- || true; }

    HJ_UPDATE_HOST="$(alt HJ_UPDATE_HOST)"
    HJ_PORT_INTERN="$(alt HJ_PORT_INTERN)"
    HJ_CADDY_BIND="$(alt HJ_CADDY_BIND)"
    HJ_GIT_BIND="$(alt HJ_GIT_BIND)"
    HJ_PULT_BIND="$(alt HJ_PULT_BIND)"
    HJ_PULT_PORT="$(alt HJ_PULT_PORT)"
    HJ_ADMIN="$(alt HJ_VERWALTER_NAME)"
    ALT_TOKEN_KNOTEN="$(alt HJ_TOKEN_KNOTEN)"
    ALT_TOKEN_AUFSETZEN="$(alt HJ_TOKEN_AUFSETZEN)"
    ALT_TOKEN_VEROEFFENTLICHEN="$(alt HJ_TOKEN_VEROEFFENTLICHEN)"
    ALT_VERWALTER_HASH="$(alt HJ_VERWALTER_HASH)"

    warn "${UMGEBUNG} gibt es bereits."
    info "Die vorhandenen Werte werden angeboten - Enter uebernimmt sie."
    info "Passwoerter bleiben unveraendert; erzeugt wird nur, was fehlt."
    info ""
    info "Alles wirklich von vorn:  bash neu-aufsetzen.sh --maschine"
    echo
fi

cat <<'KOPF'

  ------------------------------------------------------------------
   hoer.jetzt - Update-Server
  ------------------------------------------------------------------

  Dieser Host loest GitHub ab. Er liefert Abbilder statt Quellcode -
  auf den Knoten faellt damit Maven, das JDK und der Quellbaum weg.

KOPF

# ------------------------------------------------------------------ 1  Lage

step "Adresse"
info "Der oeffentliche Name, unter dem der Nginx Proxy Manager diesen"
info "Dienst veroeffentlicht. Ohne Port und ohne https:// davor."
frage HJ_UPDATE_HOST "Oeffentlicher Name" "${HJ_UPDATE_HOST:-repository.hoer.jetzt}"

info ""
info "Port der Repo-Seite - Abbilder, Release, Tresor, Meldestelle."
info "Hier laeuft unverschluesseltes HTTP. Wird der Port oeffentlich"
info "erreichbar gemacht, gehoert TLS davor (NPM oder Cloudflare)."
frage HJ_PORT_INTERN "Port der Repo-Seite" "${HJ_PORT_INTERN:-8091}"
info ""
info "Auf welcher Adresse. 0.0.0.0 heisst: von ueberall erreichbar."
info "127.0.0.1 heisst: nur ueber einen Proxy auf demselben Host."
frage HJ_CADDY_BIND "Lauschadresse" "${HJ_CADDY_BIND:-0.0.0.0}"

step "Port"
if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":${HJ_PORT_INTERN} "; then
    fail "Port ${HJ_PORT_INTERN} ist belegt. Freiraeumen oder einen anderen waehlen."
fi
info "Port ${HJ_PORT_INTERN} ist frei. 80 und 443 bleiben dem NPM."

step "Verwaltung"
info "Forgejo lauscht nur oertlich. Nach aussen geht allein /v2/ ueber Caddy."
frage HJ_GIT_BIND "Auf welcher Adresse lauschen" "${HJ_GIT_BIND:-127.0.0.1}"
frage HJ_ADMIN    "Benutzername fuer die Verwaltung" "${HJ_ADMIN:-marco}"
# Forgejo verlangt beim Anlegen eines Kontos eine Mailadresse. Sie wird nie
# benutzt - es geht kein Mailversand von hier aus.
frage HJ_ADMIN_MAIL "Mailadresse fuer das Forgejo-Konto" "system@hoer.jetzt"

info ""
info "Die Oberflaeche des Updaters - Freigaben, Knoten, Verwalten,"
info "Protokoll. Dahinter liegen Tresor- und Release-Steuerung."
info ""
info "0.0.0.0 macht sie von ueberall erreichbar. Dann ist das"
info "Verwalter-Passwort die einzige Huerde - TLS davorschalten und"
info "ein langes Passwort waehlen."
frage HJ_PULT_BIND "Auf welcher Adresse soll die Oberflaeche lauschen" "${HJ_PULT_BIND:-0.0.0.0}"
frage HJ_PULT_PORT "Auf welchem Port" "${HJ_PULT_PORT:-8090}"

# ------------------------------------------------------------------ 2  Passwoerter

step "Passwoerter"

# Muss von Hand eingetippt werden koennen - deshalb Gruppen statt eines
# Zufallsbandes, und ein Alphabet ohne 0/O und 1/l/I.
#
# ---------------------------------------------------------------------------
# Warum hier eine Schleife steht und kein einzelnes "head -c"
#
# Die erste Fassung war:
#
#   head -c 32 /dev/urandom | tr -dc '<31 Zeichen>' | cut -c1-4
#
# Das sieht richtig aus und ist es nicht. Von 256 moeglichen Bytewerten
# ueberleben nur 31 das tr - jedes Byte also mit rund 12 Prozent. Aus 32 Byte
# werden im Mittel 3,9 Zeichen, mit erheblicher Streuung: gemessen lieferten
# nur 54 Prozent der Aufrufe die vollen vier, 1,5 Prozent gar keins.
#
# Das Gesamtpasswort schwankte dadurch zwischen 13 und 22 Zeichen, und die im
# Kommentar behaupteten 80 Bit stimmten in den wenigsten Faellen. Ein Lauf mit
# 14 Zeichen hatte acht Zufallszeichen - 40 Bit, die Haelfte.
#
# Jetzt wird nachgefuellt, bis vier Zeichen beisammen sind. Vier Gruppen zu
# vier Zeichen aus 31 sind rund 79 Bit, und zwar immer.
# ---------------------------------------------------------------------------
VORRAT='ABCDEFGHJKMNPQRSTUVWXYZ23456789'
gruppe() {
    local aus=""
    while [[ ${#aus} -lt 4 ]]; do
        aus="${aus}$(head -c 64 /dev/urandom | tr -dc "$VORRAT" || true)"
    done
    printf '%s' "${aus:0:4}"
}
# Vorhandenes behalten. Ein neues Aufsetz-Passwort machte jede offene
# Anleitung ungueltig; ein neues Knoten-Passwort spektte JEDEN Knoten aus.
# Beim ersten Lauf sind die Variablen leer, dann wird erzeugt.
if [[ -n "${ALT_TOKEN_AUFSETZEN:-}" ]]; then
    PW_AUFSETZEN="$ALT_TOKEN_AUFSETZEN"
    PW_AUFSETZEN_ALT=true
else
    PW_AUFSETZEN="hj-$(gruppe)-$(gruppe)-$(gruppe)-$(gruppe)"
    PW_AUFSETZEN_ALT=false
fi

# Sicherheitsnetz: waere an der Erzeugung noch etwas falsch, faellt es hier
# auf und nicht erst, wenn jemand ein zu kurzes Passwort im Einsatz hat.
[[ ${#PW_AUFSETZEN} -eq 22 ]] \
    || fail "Aufsetz-Passwort hat ${#PW_AUFSETZEN} statt 22 Zeichen - Erzeugung pruefen."

# 512 Byte = 4096 Bit. Wird nie abgetippt, sondern von Skripten weitergereicht
# und einmal in eine Zwischenablage kopiert - also darf es lang sein.
#
# Kein bcrypt darauf: der Updater vergleicht es unmittelbar. bcrypt schneidet
# nach 72 Byte ab, von 4096 Bit blieben also 576 uebrig - und der Hash
# enthielte Dollarzeichen, die Docker Compose in der .env als Variablen liest.
# Auch hier: vorhandenes behalten.
#
# Ein neues Knoten-Passwort sperrt JEDEN Knoten aus, bis er es bekommen hat -
# und es gibt keine Verbindung von hier zu ihm. Das darf nicht die Nebenwirkung
# davon sein, dass jemand einen abgebrochenen Lauf zu Ende bringt.
if [[ -n "${ALT_TOKEN_KNOTEN:-}" ]]; then
    PW_KNOTEN="$ALT_TOKEN_KNOTEN"
    PW_KNOTEN_ALT=true
else
    PW_KNOTEN="$(openssl rand -base64 512 | tr -d '\n')"
    PW_KNOTEN_ALT=false
fi

info "Aufsetzen: ${#PW_AUFSETZEN} Zeichen, tippbar."
info "Knoten:    ${#PW_KNOTEN} Zeichen, 4096 Bit."

# Das einzige, was noch gehasht wird: die Anmeldung an der Oberflaeche. Ein
# Mensch tippt es, also darf es nicht im Klartext herumliegen.
#
# HJ_VERWALTER_PASSWORT kommt aus install-update-server.sh, wenn dort ein
# Initialpasswort angegeben wurde (Abschnitt 10 der Spezifikation). Ueber die
# Umgebung und nicht als Argument: ein Argument stuende in "ps aux" und waere
# fuer jeden lokalen Benutzer lesbar, solange der Prozess laeuft.
#
# Sofort nach dem Lesen aus der Umgebung entfernt - sonst erbte es jeder
# Kindprozess dieses Skripts, und davon gibt es hier etliche, unter anderem
# mehrere "docker run".
if [[ -n "${HJ_VERWALTER_PASSWORT:-}" ]]; then
    PW_PULT="$HJ_VERWALTER_PASSWORT"
    unset HJ_VERWALTER_PASSWORT
    PW_PULT_VORGEGEBEN=true
    if [[ ${#PW_PULT} -lt 12 ]]; then
        warn "Das Initialpasswort ist kuerzer als zwoelf Zeichen."
        warn "Diese Oberflaeche steuert Freigaben, Tresor und Releases."
    fi
else
    PW_PULT="$(zufall)"
    PW_PULT_VORGEGEBEN=false
fi
PW_ADMIN="$(zufall)"

# Die eigene oeffentliche Adresse ermitteln.
#
# Sie kommt in die Freigaben, weil der Token-Umweg beim Veroeffentlichen von
# aussen zurueckkommt - siehe EigeneFreigabe.java. Ohne sie scheitert das
# Veroeffentlichen mit "Adresse nicht freigeschaltet", und zwar auf genau dem
# Rechner, auf dem man gerade sitzt.
#
# Schlaegt die Abfrage fehl, bleibt der Wert leer: das ist kein Grund, das
# Einrichten abzubrechen. Der Updater weist dann beim Start darauf hin.
HJ_EIGENE_IP="$(curl -fsS --max-time 8 https://api.ipify.org 2>/dev/null || true)"
if [[ -n "$HJ_EIGENE_IP" ]]; then
    info "Eigene Adresse: ${HJ_EIGENE_IP} (kommt in die Freigaben)"
else
    warn "Eigene oeffentliche Adresse nicht ermittelbar."
    warn "Falls das Veroeffentlichen spaeter an der Adresspruefung scheitert,"
    warn "die genannte Adresse unter 'Freigaben' eintragen."
fi

# Das Passwort, mit dem dieser Server in seine eigene Registry schiebt.
#
# Er ist kein Knoten: keine Kennung, keine Module, kein Geheimnis aus einer
# Anmeldung. Bis hierher benutzte er dafuer den Benutzer "knoten" mit dem
# gemeinsamen Knoten-Passwort - seit das abgeschaltet ist, geht das nicht
# mehr, und das Veroeffentlichen scheiterte mit "no basic auth credentials".
#
# Bleibt auf diesem Host. Wird nirgends ausgeliefert und steht in keinem
# Tresorprofil.
if [[ -n "${ALT_TOKEN_VEROEFFENTLICHEN:-}" ]]; then
    PW_VEROEFFENTLICHEN="$ALT_TOKEN_VEROEFFENTLICHEN"
else
    PW_VEROEFFENTLICHEN="$(zufall)"
fi

step "Hash fuer die Oberflaeche"

# Vorhandenen Hash behalten.
#
# Der Klartext steht nirgends - gespeichert ist nur der bcrypt-Hash. Waere
# hier neu gehasht worden, gaelte ab sofort ein neues, frisch erzeugtes
# Passwort, und das alte waere ohne Vorwarnung ungueltig. Bei einem Lauf, den
# man nur zu Ende bringen wollte, ist das die letzte Ueberraschung, die man
# brauchen kann.
#
# Ein ausdruecklich uebergebenes Initialpasswort schlaegt das: wer
# --passwort mitgibt, will es aendern.
if [[ -n "${ALT_VERWALTER_HASH:-}" && "$PW_PULT_VORGEGEBEN" == "false" ]]; then
    # Aus der .env kommt der maskierte Hash ($$ statt $) - zurueckdrehen,
    # sonst wird er beim Schreiben ein zweites Mal maskiert.
    HJ_VERWALTER_HASH="$(printf '%s' "$ALT_VERWALTER_HASH" | sed 's/\$\$/$/g')"
    PW_PULT_BEHALTEN=true
    info "Vorhandenes Passwort bleibt gueltig."
else
    PW_PULT_BEHALTEN=false
# Ueber die Standardeingabe, nicht als Argument.
#
# "--plaintext $PW_PULT" stellte das Passwort in "ps aux" - fuer jeden
# lokalen Benutzer lesbar, solange der Container laeuft. Das sind
# Millisekunden, aber es ist genau die Art Leck, die Abschnitt 10 der
# Spezifikation ausschliesst.
#
# ---------------------------------------------------------------------------
# Der Zeilenumbruch ist nicht optional.
#
# caddy liest ohne --plaintext bis zum ersten \n und schneidet es ab. Ohne
# Umbruch kommt es nie und der Aufruf endet mit "Error: EOF" - genau da stand
# die erste Fassung dieses Blocks, und die Installation brach an dieser Stelle
# ab. printf '%s\n' statt printf '%s'.
# ---------------------------------------------------------------------------
docker pull -q caddy:2-alpine >/dev/null 2>&1 || fail "caddy:2-alpine nicht ladbar."

hash_ueber_stdin() {
    printf '%s\n' "$PW_PULT" | docker run --rm -i caddy:2-alpine caddy hash-password 2>/dev/null
}

HJ_VERWALTER_HASH="$(hash_ueber_stdin || true)"

# Rueckfall auf das Argument.
#
# Aeltere caddy-Fassungen lesen stdin anders oder gar nicht. Ein Installer,
# der daran haengenbleibt, ist schlimmer als eine kurze Sichtbarkeit in
# "ps aux" - deshalb wird hier weitergemacht und gesagt, was passiert ist.
if [[ ! "$HJ_VERWALTER_HASH" =~ ^\$2[aby]\$ ]]; then
    warn "caddy nimmt das Passwort nicht ueber die Standardeingabe."
    warn "Rueckfall auf --plaintext: das Passwort ist waehrend des Hashens"
    warn "kurz in 'ps aux' sichtbar. Auf diesem Host vertretbar, aber vermerkt."
    HJ_VERWALTER_HASH="$(docker run --rm caddy:2-alpine caddy hash-password --plaintext "$PW_PULT")"
fi

[[ "$HJ_VERWALTER_HASH" =~ ^\$2[aby]\$ ]] \
    || fail "Hashen fehlgeschlagen - caddy lieferte: ${HJ_VERWALTER_HASH:-<nichts>}"
info "bcrypt."
fi

# ------------------------------------------------------------------ 3  Umgebung

step "Umgebungsdatei"

# Docker Compose ersetzt in der .env Variablen - aus einem bcrypt-Hash wie
# "$2a$14$..." wuerden damit Bruchstuecke plus Leerstrings, und Caddy bekaeme
# einen kaputten Hash. Ein verdoppeltes Dollarzeichen ist die Fluchtform;
# Compose macht daraus beim Einlesen wieder eines.
#
# Das hat genau einmal drei Warnungen pro Aufruf und ein nicht anlegbares
# Verwaltungskonto gekostet.
schuetzen() { printf '%s' "$1" | sed 's/\$/$$/g'; }

cat > "$UMGEBUNG" <<ENV
# hoer.jetzt Update-Server. Erzeugt von einrichten.sh am $(date '+%Y-%m-%d %H:%M').
#
# Die beiden Knoten-Passwoerter stehen hier im Klartext: sie werden im
# Klartext verglichen. Wer diese Datei lesen kann, steht ohnehin auf dem
# Server. Das Passwort der Oberflaeche steht dagegen nur als Hash da.
HJ_UPDATE_HOST=${HJ_UPDATE_HOST}
HJ_EIGENE_IP=${HJ_EIGENE_IP}
HJ_PORT_INTERN=${HJ_PORT_INTERN}
HJ_CADDY_BIND=${HJ_CADDY_BIND}
HJ_GIT_BIND=${HJ_GIT_BIND}
HJ_TOKEN_KNOTEN=${PW_KNOTEN}
HJ_TOKEN_AUFSETZEN=${PW_AUFSETZEN}
HJ_TOKEN_VEROEFFENTLICHEN=${PW_VEROEFFENTLICHEN}
HJ_VERWALTER_NAME=${HJ_ADMIN}
HJ_VERWALTER_HASH=$(schuetzen "$HJ_VERWALTER_HASH")
HJ_PULT_BIND=${HJ_PULT_BIND}
HJ_PULT_PORT=${HJ_PULT_PORT}
ENV
chmod 600 "$UMGEBUNG"
info "${UMGEBUNG} (0600)"

# Gegenprobe: liest Compose die Datei so, wie sie gemeint ist? Ein falsch
# maskierter Hash faellt sonst erst auf, wenn niemand sich anmelden kann.
cd "$HIER"
# "docker compose config" maskiert Dollarzeichen in seiner Ausgabe wieder -
# die Ausgabe soll sich erneut einlesen lassen. Ein Hash, der richtig
# ankommt, erscheint dort deshalb als "$$2a$$14$$...". Ohne das
# Zuruecknehmen verglichen man die maskierte Form mit der unmaskierten und
# haelt genau den Fall fuer kaputt, der stimmt.
GELESEN="$(docker compose config 2>/dev/null | grep -m1 'HJ_VERWALTER_HASH:' \
    | sed 's/.*HJ_VERWALTER_HASH: *//' | tr -d '"' | sed 's/\$\$/$/g' || true)"
if [[ "$GELESEN" == "$HJ_VERWALTER_HASH" ]]; then
    info "Compose liest den Hash unveraendert."
else
    warn "Compose liest den Hash anders als geschrieben:"
    warn "  geschrieben: ${HJ_VERWALTER_HASH}"
    warn "  gelesen:     ${GELESEN:-<leer>}"
    fail "Die Anmeldung an der Oberflaeche wuerde nicht funktionieren."
fi

# ------------------------------------------------------------------ 4  Start

step "Forgejo starten"
docker compose up -d forgejo || fail "Forgejo startet nicht."

# Zwei Bedingungen, und die zweite ist die eigentliche. Der Health-Check
# antwortet, sobald der Webserver steht - auch wenn Forgejo sich noch fuer
# nicht installiert haelt. Erst mit der app.ini ist es wirklich soweit, und
# genau die fehlte, als "forgejo admin" hier abbrach.
FJ_INI="/data/gitea/conf/app.ini"
info "Warte auf die Ersteinrichtung..."
for versuch in $(seq 1 60); do
    if docker compose exec -T forgejo curl -fsS -m 3 \
            http://127.0.0.1:3000/api/healthz >/dev/null 2>&1 \
       && docker compose exec -T forgejo test -f "$FJ_INI" >/dev/null 2>&1; then
        break
    fi
    if [[ "$versuch" -eq 60 ]]; then
        warn "Forgejo ist nach zwei Minuten nicht fertig eingerichtet."
        warn "Nachsehen: docker compose logs forgejo"
        warn "Fehlt ${FJ_INI}, ist INSTALL_LOCK nicht angekommen."
        fail "Abgebrochen."
    fi
    sleep 2
done
info "Forgejo ist eingerichtet."

step "Verwaltungskonto"
# Die Ausgabe nicht wegwerfen: schlaegt es fehl, steht der Grund darin, und
# ohne ihn sucht man an der falschen Stelle.
if ANLEGEN="$(docker compose exec -T -u git forgejo forgejo --config "$FJ_INI" admin user create \
        --admin --username "$HJ_ADMIN" --password "$PW_ADMIN" \
        --email "$HJ_ADMIN_MAIL" --must-change-password=false 2>&1)"; then
    info "$HJ_ADMIN angelegt."

# Das Konto gibt es schon.
#
# Genau der Fall, wenn jemand /opt/hoerjetzt loescht und neu installiert: die
# Quellen sind weg, das Forgejo-Volume nicht. Frueher brach das Einrichten
# hier ab - nach dem Anlegen der .env, nach dem Start von Forgejo, also mitten
# in einem halb fertigen Aufbau, und mit einer Meldung, die wie ein Defekt
# aussieht statt wie ein Zustand.
#
# Das Passwort des alten Kontos ist unbekannt - in der neuen .env steht ein
# frisch erzeugtes. Also wird es gesetzt, nicht geraten.
elif printf '%s' "$ANLEGEN" | grep -qi "already exists"; then
    warn "Das Konto ${HJ_ADMIN} gibt es bereits - aus einer frueheren Installation."
    warn "Das Forgejo-Volume hat den Neuaufbau ueberlebt."
    if ! WECHSEL="$(docker compose exec -T -u git forgejo forgejo --config "$FJ_INI" \
            admin user change-password --username "$HJ_ADMIN" \
            --password "$PW_ADMIN" --must-change-password=false 2>&1)"; then
        warn "$WECHSEL"
        fail "Passwort des vorhandenen Kontos liess sich nicht setzen.
       Entweder das alte Volume entfernen und neu beginnen:
         bash neu-aufsetzen.sh --maschine
       oder einen anderen Benutzernamen waehlen."
    fi
    info "Passwort von ${HJ_ADMIN} auf den neuen Wert gesetzt."
    warn "Achtung: die alten Abbilder und Repositories liegen noch in diesem"
    warn "Volume. Fuer einen wirklich leeren Stand: bash neu-aufsetzen.sh --maschine"

else
    warn "$ANLEGEN"
    fail "Verwaltungskonto liess sich nicht anlegen - siehe Meldung oben."
fi

# Kein zweites Konto fuer die Knoten: die Abbilder darf lesen, wer bis zur
# Registry kommt - und dorthin kommt nur, wer Caddy das Knoten-Passwort
# vorgelegt hat.
step "Organisation"

# Der Token bekommt einen eindeutigen Namen.
#
# Forgejo lehnt einen zweiten Token mit demselben Namen ab ("access token name
# has been used already"). Ohne Zeitstempel scheiterte damit jeder zweite Lauf
# von einrichten.sh - und zwar an einer Stelle, die mit dem eigentlichen
# Vorgang nichts zu tun hat.
TOKEN_NAME="einrichten-$(date '+%Y%m%d%H%M%S')"
TOKEN="$(docker compose exec -T -u git forgejo forgejo --config "$FJ_INI" \
    admin user generate-access-token -u "$HJ_ADMIN" --token-name "$TOKEN_NAME" \
    --scopes all --raw 2>&1)" || fail "Kein Verwaltungstoken: ${TOKEN}"
TOKEN="$(printf '%s' "$TOKEN" | tr -d '\r\n ')"

# Organisation anlegen - oder feststellen, dass es sie schon gibt.
#
# POST /orgs antwortet mit 422, wenn der Name vergeben ist, und "curl -fsS"
# macht daraus einen Fehler. Beim zweiten Lauf brach das Einrichten hier ab,
# obwohl alles in Ordnung war. Deshalb zuerst nachsehen.
if docker compose exec -T forgejo curl -fsS \
        -H "Authorization: token ${TOKEN}" \
        http://127.0.0.1:3000/api/v1/orgs/hoerjetzt >/dev/null 2>&1; then
    info "hoerjetzt - gibt es bereits."
else
    docker compose exec -T forgejo curl -fsS -X POST \
        -H "Authorization: token ${TOKEN}" -H "Content-Type: application/json" \
        -d '{"username":"hoerjetzt","visibility":"public"}' \
        http://127.0.0.1:3000/api/v1/orgs >/dev/null \
        || fail "Organisation hoerjetzt liess sich nicht anlegen."
    info "hoerjetzt - hier liegen die Abbilder."
fi

step "Runner anmelden"
RTOKEN="$(docker compose exec -T forgejo curl -fsS \
    -H "Authorization: token ${TOKEN}" \
    http://127.0.0.1:3000/api/v1/admin/runners/registration-token 2>/dev/null \
    | tr ',' '\n' | grep -m1 token | cut -d'"' -f4 || true)"
if [[ -n "$RTOKEN" ]]; then
    if docker compose run --rm -T runner forgejo-runner register --no-interactive \
            --instance http://forgejo:3000 --token "$RTOKEN" \
            --name "$(hostname -s)" --labels docker >/dev/null 2>&1; then
        info "Runner angemeldet."
    else
        warn "Runner-Anmeldung fehlgeschlagen - spaeter von Hand nachholen."
    fi
else
    warn "Kein Runner-Token erhalten - Actions bleiben vorerst aus."
fi

step "Aufsetz-Werkzeug bereitstellen"

# Das Aufsetzen darf nicht auf ein Release warten.
#
# bootstrap.sh, aufsetzen.sh und der Deploy-Stand haengen an keinem Release -
# sie liegen bereits im Quellbaum. Bis hierher legte sie aber allein
# veroeffentlichen.sh ab, und das laeuft erst, wenn Abbilder gebaut werden.
#
# Folge auf einem frisch eingerichteten Server: die Knotenseite zeigt einen
# fertigen Einzeiler, und der endet in
#
#   curl: (22) The requested URL returned error: 404
#
# Der Befehl ist richtig, die Datei fehlt nur. Wer das sieht, sucht am
# falschen Ende - bei DNS, beim Proxy, beim Token.
QUELLBAUM_HIER="$(cd "${HIER}/.." && pwd)"

aus_schreiben "knoten/bootstrap.sh" < "${QUELLBAUM_HIER}/deploy/bootstrap.sh" \
    || fail "bootstrap.sh liess sich nicht ablegen."
aus_schreiben "knoten/aufsetzen.sh" < "${QUELLBAUM_HIER}/deploy/knoten-aufsetzen.sh" \
    || fail "aufsetzen.sh liess sich nicht ablegen."
info "bootstrap.sh und aufsetzen.sh"

# Das Bundle, das bootstrap.sh danach holt. Struktur "<zweig>/deploy/..." -
# bootstrap.sh entpackt es nach ARBEIT und startet ARBEIT/<zweig>/deploy/...
ZWEIG_HIER="$(git -C "$QUELLBAUM_HIER" rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
BUENDEL="$(mktemp -d)"
mkdir -p "${BUENDEL}/${ZWEIG_HIER}"
cp -r "${QUELLBAUM_HIER}/deploy" "${BUENDEL}/${ZWEIG_HIER}/deploy"
find "$BUENDEL" \( -name "*.env" -o -name "*.key" -o -name ".env" \) -delete 2>/dev/null || true
tar -C "$BUENDEL" -czf "${BUENDEL}.tar.gz" .
aus_schreiben "knoten/${ZWEIG_HIER}.tar.gz" < "${BUENDEL}.tar.gz" \
    || fail "Deploy-Buendel liess sich nicht ablegen."
info "$(printf '%s.tar.gz (%s Dateien)' "$ZWEIG_HIER" "$(find "$BUENDEL" -type f | wc -l)")"
rm -rf "$BUENDEL" "${BUENDEL}.tar.gz"

step "Caddy und Updater starten"
printf 'noch nichts veroeffentlicht\n' | aus_schreiben release/aktuell \
    || fail "Auslieferungsverzeichnis nicht beschreibbar."
# "release/" gehoert dem Updater - er schaltet es beim Zurueckrollen um.
aus_uebergeben release
info "Der Updater wird beim ersten Mal gebaut - das dauert ein paar Minuten."
docker compose up -d --build || fail "Start fehlgeschlagen."

info "Warte auf den Updater..."
for versuch in $(seq 1 60); do
    # 401 ohne Passwort ist die erwartete Antwort und heisst: er laeuft.
    if docker compose exec -T updater curl -s -m 3 -o /dev/null \
            -w '%{http_code}' http://127.0.0.1:8080/intern/pruefen 2>/dev/null \
            | grep -qE '^(401|403|204)$'; then break; fi
    [[ "$versuch" -eq 60 ]] && fail "Updater antwortet nicht - docker compose logs updater"
    sleep 3
done
info "Updater antwortet."

# ------------------------------------------------------------------ 5  Docker
#
# Der Runner baut hier und schiebt die Abbilder in die eigene Registry. Er
# geht denselben Weg wie jeder Knoten - durch Caddy, mit dem Knoten-Passwort.
# Also muss auch der Docker-Dienst dieses Hosts angemeldet sein.
#
# Von hier aus geht es nicht ueber den NPM, sondern direkt auf den internen
# Port. Der spricht HTTP, deshalb muss Docker diese Registry ausdruecklich
# zugelassen bekommen - sonst bricht der Push mit "server gave HTTP response
# to HTTPS client" ab.

step "Eigenen Docker-Zugang einrichten"
REGISTRY_LOKAL="127.0.0.1:${HJ_PORT_INTERN}"

DAEMON="/etc/docker/daemon.json"
if ! grep -q "$REGISTRY_LOKAL" "$DAEMON" 2>/dev/null; then
    if [[ -f "$DAEMON" ]]; then
        cp "$DAEMON" "${DAEMON}.$(date '+%Y%m%d%H%M%S')"
        warn "${DAEMON} gibt es schon - gesichert, aber nicht veraendert."
        warn "Bitte selbst eintragen und Docker neu starten:"
        warn "  \"insecure-registries\": [\"${REGISTRY_LOKAL}\"]"
    else
        printf '{\n  "insecure-registries": ["%s"]\n}\n' "$REGISTRY_LOKAL" > "$DAEMON"
        info "${DAEMON} angelegt."
        systemctl restart docker 2>/dev/null || true
        sleep 8
        docker compose up -d >/dev/null 2>&1 || true
    fi
fi

# Anmeldung an der eigenen Registry.
#
# Als "veroeffentlichen", nicht als "knoten". Der Update-Server ist kein
# Knoten - er hat keine Kennung, keine Module und kein Geheimnis aus einer
# Anmeldung. Frueher benutzte er dafuer das gemeinsame Knoten-Passwort; seit
# das abgeschaltet ist (hj.token.gemeinsam-erlauben=false), wird es
# abgewiesen, und das Veroeffentlichen scheiterte mit
# "no basic auth credentials".
if printf '%s' "$PW_VEROEFFENTLICHEN" | docker login "$REGISTRY_LOKAL" \
        -u veroeffentlichen --password-stdin >/dev/null 2>&1; then
    info "Angemeldet an ${REGISTRY_LOKAL}."
else
    warn "docker login fehlgeschlagen - die Registry-Probe wird scheitern."
fi

# ------------------------------------------------------------------ 6  Probe

step "Selbstprobe"
GRUEN=1
probe() {
    local text="$1"; shift
    if "$@" >/dev/null 2>&1; then
        info "$(printf '%-42s %s' "$text" "ok")"
    else
        warn "$(printf '%-42s %s' "$text" "FEHLGESCHLAGEN")"
        GRUEN=0
    fi
}

BASIS="http://127.0.0.1:${HJ_PORT_INTERN}"
code() { curl -s -o /dev/null -w '%{http_code}' -m 15 "$@" || echo 0; }

probe "Knoten-Passwort oeffnet das Release" \
    curl -fsS -m 15 -u "knoten:${PW_KNOTEN}" "${BASIS}/release/aktuell"
probe "ohne Passwort bleibt es zu" \
    test 401 = "$(code "${BASIS}/release/aktuell")"
probe "falsches Passwort bleibt draussen" \
    test 401 = "$(code -u 'knoten:falsch' "${BASIS}/release/aktuell")"
probe "Knoten-Passwort oeffnet die Registry" \
    curl -fsS -m 15 -u "knoten:${PW_KNOTEN}" "${BASIS}/v2/"
probe "Aufsetz-Passwort oeffnet /knoten/" \
    curl -fsS -m 15 -u "knoten:${PW_AUFSETZEN}" "${BASIS}/knoten/"
# Die beiden Stufen muessen wirklich zwei sein - sonst waere das kurze
# Passwort in Wahrheit der ganze Zugang.
probe "Aufsetz-Passwort oeffnet das Release NICHT" \
    test 401 = "$(code -u "knoten:${PW_AUFSETZEN}" "${BASIS}/release/aktuell")"
probe "Knoten-Passwort oeffnet /knoten/ NICHT" \
    test 401 = "$(code -u "knoten:${PW_KNOTEN}" "${BASIS}/knoten/")"

# Die Adresspruefung ist die zweite Huerde und die leiseste Fehlerquelle:
# laesst sie zu viel durch, faellt es nie auf. Deshalb beide Richtungen.
#
# Die Rueckschleife kommt durch, weil der Updater beim ersten Start
# Grundfreigaben anlegt (siehe Erstbelegung.java).
im_tor() {
    docker compose exec -T updater curl -s -o /dev/null -m 10 -w '%{http_code}' \
        "$@" http://127.0.0.1:8080/intern/pruefen 2>/dev/null || echo 0
}
probe "Tor laesst eine freigeschaltete Adresse durch" \
    test 204 = "$(im_tor -u "knoten:${PW_KNOTEN}" -H 'CF-Connecting-IP: 127.0.0.1')"
probe "Tor sperrt eine fremde Adresse" \
    test 403 = "$(im_tor -u "knoten:${PW_KNOTEN}" -H 'CF-Connecting-IP: 203.0.113.7')"
# Ohne Passwort darf nicht einmal die Adresse geprueft werden - sonst waere
# die Antwort eine Auskunft ueber die Freigabeliste an jeden, der anklopft.
probe "ohne Passwort keine Auskunft ueber Freigaben" \
    test 401 = "$(im_tor -H 'CF-Connecting-IP: 203.0.113.7')"
# Der Torwaechter-Port steht absichtlich nicht unter "ports". Kaeme hier eine
# Antwort, haenge die Zugangskontrolle offen im Netz.
probe "Tor-Port liegt NICHT auf dem Host" \
    test 000 = "$(curl -s -o /dev/null -m 5 -w '%{http_code}' \
                  http://127.0.0.1:8080/intern/pruefen 2>/dev/null || echo 000)"
probe "Oberflaeche verlangt Anmeldung" \
    test 302 = "$(code "http://${HJ_PULT_BIND}:${HJ_PULT_PORT}/")"

# Die entscheidende Probe: einmal wirklich hoch und wieder herunter. Sie
# faellt auf alles herein, was die Proben oben nicht sehen - eine fehlende
# Anmeldung, eine nicht zugelassene HTTP-Registry, ein Leserecht, das
# Forgejo doch verlangt.
step "Registry - einmal hin und zurueck"
MARKE="${REGISTRY_LOKAL}/hoerjetzt/probe:1"
if docker pull -q alpine:3 >/dev/null 2>&1 \
        && docker tag alpine:3 "$MARKE" \
        && docker push -q "$MARKE" >/dev/null 2>&1; then
    info "Hochladen: ok"
    docker rmi "$MARKE" >/dev/null 2>&1 || true
    if docker pull -q "$MARKE" >/dev/null 2>&1; then
        info "Herunterladen: ok"
    else
        warn "Herunterladen: FEHLGESCHLAGEN"
        GRUEN=0
    fi
    docker rmi "$MARKE" >/dev/null 2>&1 || true
else
    warn "Hochladen: FEHLGESCHLAGEN - docker compose logs caddy"
    GRUEN=0
fi

# ------------------------------------------------------------------ 7  Ende

step "Jetzt notieren"
# Welche Adresse der NPM anwaehlen muss.
#
# HJ_CADDY_BIND ist die LAUSCH-Adresse. Steht dort 0.0.0.0, heisst das "alle
# Schnittstellen" - als Ziel eingetragen ist es unbrauchbar, und genau das
# stand hier vorher: "Weiterleiten an http://0.0.0.0:8082". Wer das liest,
# raet sich eine Adresse zusammen, und wenn dabei auch noch der Port aus der
# Anleitung statt der tatsaechliche genommen wird, endet es in 502.
#
# Deshalb: bei 0.0.0.0 die erste LAN-Adresse des Hosts anbieten, sonst die
# Lauschadresse selbst - die ist dann ja eine echte.
if [[ "$HJ_CADDY_BIND" == "0.0.0.0" ]]; then
    NPM_ZIEL="$(hostname -I 2>/dev/null | awk '{print $1}')"
    NPM_ZIEL="${NPM_ZIEL:-<IP dieses Hosts>}"
else
    NPM_ZIEL="$HJ_CADDY_BIND"
fi

cat <<ENDE

    Wird nicht wieder angezeigt.

      Aufsetz-Passwort  ${PW_AUFSETZEN}$(if $PW_AUFSETZEN_ALT; then printf "%s" "   (unveraendert)"; fi)

          Neuen Knoten aufsetzen - das ist die ganze Zeile:

          curl -fsSLu knoten https://${HJ_UPDATE_HOST}/knoten/aufsetzen.sh -o a.sh && bash a.sh

      Knoten-Passwort   (4096 Bit, steht unten noch einmal einzeln)$(if $PW_KNOTEN_ALT; then printf "%s" " - unveraendert"; fi)

      Updater           ${HJ_ADMIN} / $(if $PW_PULT_BEHALTEN; then
                            printf '%s' "<unveraendert - das bisherige gilt weiter>"
                        elif $PW_PULT_VORGEGEBEN; then
                            printf '%s' "<das beim Aufruf angegebene Passwort>"
                        else printf '%s' "$PW_PULT"; fi)

                        http://$(hostname -I 2>/dev/null | awk '{print $1}'):${HJ_PULT_PORT}/

                        Dieser Port ist auf ${HJ_PULT_BIND} gebunden. Steht dort
                        0.0.0.0, ist die Oberflaeche aus dem ganzen Netz
                        erreichbar - und das Verwalter-Passwort ist die
                        einzige Huerde davor. Es laeuft unverschluesseltes
                        HTTP: ohne TLS davor geht das Passwort im Klartext
                        ueber die Leitung.

                        Vorgegebene Passwoerter werden hier nicht wiederholt -
                        sie stehen schon dort, wo sie hergekommen sind, und
                        eine zweite Kopie im Terminalpuffer macht es nicht
                        besser.

                        Neue Knoten werden unter "Verwalten" angelegt; dabei
                        entsteht ein Aufsetz-Token, der zwei Stunden gilt.
                        Ihre Adresse muss unter "Freigaben" eingetragen sein,
                        bevor sie an Tresor und Abbilder kommen.

      Forgejo           ${HJ_ADMIN} / ${PW_ADMIN}
                        ssh -L 3000:127.0.0.1:3000 und dann
                        http://127.0.0.1:3000

    Im Nginx Proxy Manager anlegen:

      Domain            ${HJ_UPDATE_HOST}
      Weiterleiten an   http://${NPM_ZIEL}:${HJ_PORT_INTERN}
      Zertifikat        wie ueblich ueber den NPM

      Der Port ist ${HJ_PORT_INTERN} - nicht die Vorgabe 8091, falls du
      beim Einrichten einen anderen genommen hast. Stimmen NPM und
      Caddy hier nicht ueberein, meldet der Browser 502 Bad Gateway,
      und Cloudflare wie NPM zeigen dabei gruen: der letzte Sprung
      geht ins Leere.

      Wichtig: keine Groessenbegrenzung fuer Uploads (client_max_body_size 0).
      Abbild-Schichten sind gross; NPM bricht sonst mittendrin ab.

    Cloudflare darf auf Proxy stehen (orange Wolke) - der Updater liest
    die echte Adresse aus CF-Connecting-IP.

ENDE

# Getrennt und ohne Einrueckung: das Passwort ist 684 Zeichen lang und wird
# markiert und kopiert, nicht gelesen. Fuehrende Leerzeichen waeren beim
# Einfuegen mit dabei.
cat <<ENDE
    Knoten-Passwort - in einem Stueck, zum Kopieren:

ENDE
printf '%s\n\n' "$PW_KNOTEN"

read -r -p "    Notiert? Dann Enter. " _ || true
clear 2>/dev/null || true

step "Fertig"
if [[ "$GRUEN" -eq 1 ]]; then
    info "Alle Proben bestanden."
else
    warn "Mindestens eine Probe ist fehlgeschlagen - siehe oben."
    warn "Protokoll: docker compose logs caddy updater"
fi
echo
# Woher dieser Server seinen Stand holt - fuer die Anzeige unten.
#
# QUELLBAUM ist das Verzeichnis mit der Arbeitskopie: HIER ist
# .../main/update-server, eine Ebene hoeher liegt der Zweig.
QUELLBAUM="$(cd "${HIER}/.." && pwd)"
HJ_ZWEIG_ANZEIGE="$(git -C "$QUELLBAUM" rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
HJ_QUELLE_ANZEIGE="$(git -C "$QUELLBAUM" remote get-url origin 2>/dev/null \
                     || echo 'https://github.com/MarcoEckerlin/hoer.jetzt.git')"

info "Naechste Schritte:"
info "  1. Proxy-Host im NPM anlegen (siehe oben)."
info "  2. Tresor befuellen:  bash tresor.sh fuellen voll"
info "                        bash tresor.sh fuellen lavalink"
info "  3. Release bauen:     Tag v... auf main setzen"
echo

# Der Aktualisierungsbefehl, fertig zum Kopieren.
#
# Dieser Server ist der einzige, der seinen Stand von GitHub holt statt von
# sich selbst - er IST die Bezugsquelle. Der Weg steht deshalb hier, samt
# Quelle, damit niemand ihn zusammensuchen muss.
#
# Zwei Befehle in einer Zeile: erst den neuen Stand holen, dann die Dienste
# damit neu starten. "--build" ist noetig, weil der Updater aus dem Quellbaum
# gebaut wird - ohne ihn liefe der alte Container mit neuem Code daneben.
cat <<ENDE
  ----------------------------------------------------------------
   Diesen Server aktualisieren

   Quelle: ${HJ_QUELLE_ANZEIGE}
   Stand:  $(git -C "${QUELLBAUM}" rev-parse --short HEAD 2>/dev/null || echo unbekannt)

   git -C ${QUELLBAUM} fetch --depth 1 origin ${HJ_ZWEIG_ANZEIGE} && git -C ${QUELLBAUM} reset --hard origin/${HJ_ZWEIG_ANZEIGE} && docker compose -f ${HIER}/docker-compose.yml up -d --build

   Aendert nichts an der .env, an den Volumes oder an den Passwoertern.
  ----------------------------------------------------------------
ENDE
echo
echo
