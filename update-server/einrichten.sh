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
# TLS macht der Nginx Proxy Manager. Dieser Dienst spricht einfaches HTTP
# und gehoert deshalb ins LAN - der Port darf nicht ins Internet.

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
    warn "${UMGEBUNG} existiert bereits."
    ja "Alles neu aufsetzen? Der alte Zugang gilt danach nicht mehr." n \
        || fail "Abgebrochen."
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
frage HJ_UPDATE_HOST "Oeffentlicher Name" "repo.updates.hoer.jetzt"

info ""
info "Und der Port, auf dem dieser Dienst im LAN lauscht. Dorthin zeigt"
info "spaeter der Proxy-Host im NPM. Nicht ins Internet weiterleiten -"
info "hier laeuft unverschluesseltes HTTP."
frage HJ_PORT_INTERN "Interner Port" "8086"
info ""
info "Auf welcher Adresse dieser Port liegen soll. Laeuft der NPM auf"
info "demselben Host, reicht 127.0.0.1. Sonst die LAN-Adresse."
frage HJ_CADDY_BIND "Lauschadresse" "127.0.0.1"

step "Port"
if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":${HJ_PORT_INTERN} "; then
    fail "Port ${HJ_PORT_INTERN} ist belegt. Freiraeumen oder einen anderen waehlen."
fi
info "Port ${HJ_PORT_INTERN} ist frei. 80 und 443 bleiben dem NPM."

step "Verwaltung"
info "Forgejo lauscht nur oertlich. Nach aussen geht allein /v2/ ueber Caddy."
frage HJ_GIT_BIND "Auf welcher Adresse lauschen" "127.0.0.1"
frage HJ_ADMIN    "Benutzername fuer die Verwaltung" "marco"
# Forgejo verlangt beim Anlegen eines Kontos eine Mailadresse. Sie wird nie
# benutzt - es geht kein Mailversand von hier aus.
frage HJ_ADMIN_MAIL "Mailadresse fuer das Forgejo-Konto" "system@hoer.jetzt"

info ""
info "Die Oberflaeche des Updaters - Freigaben, Knoten, Protokoll -"
info "liegt auf einem eigenen Port und gehoert ins private Netz."
frage HJ_PULT_BIND "Auf welcher Adresse soll die Oberflaeche lauschen" "127.0.0.1"
frage HJ_PULT_PORT "Auf welchem Port" "8090"

# ------------------------------------------------------------------ 2  Passwoerter

step "Passwoerter"

# Muss von Hand eingetippt werden koennen - deshalb Gruppen statt eines
# Zufallsbandes, und ein Alphabet ohne 0/O und 1/l/I. Vier Gruppen zu vier
# Zeichen aus 32 sind rund 80 Bit; das reicht fuer einen Zugang, der nur ein
# Installationsskript herausgibt und jederzeit gewechselt werden kann.
gruppe() { head -c 32 /dev/urandom | tr -dc 'ABCDEFGHJKMNPQRSTUVWXYZ23456789' | cut -c1-4; }
PW_AUFSETZEN="hj-$(gruppe)-$(gruppe)-$(gruppe)-$(gruppe)"

# 512 Byte = 4096 Bit. Wird nie abgetippt, sondern von Skripten weitergereicht
# und einmal in eine Zwischenablage kopiert - also darf es lang sein.
#
# Kein bcrypt darauf: der Updater vergleicht es unmittelbar. bcrypt schneidet
# nach 72 Byte ab, von 4096 Bit blieben also 576 uebrig - und der Hash
# enthielte Dollarzeichen, die Docker Compose in der .env als Variablen liest.
PW_KNOTEN="$(openssl rand -base64 512 | tr -d '\n')"

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

step "Hash fuer die Oberflaeche"
docker pull -q caddy:2-alpine >/dev/null 2>&1 || fail "caddy:2-alpine nicht ladbar."
# Ueber die Standardeingabe, nicht als Argument.
#
# "--plaintext $PW_PULT" stellte das Passwort in "ps aux" - fuer jeden
# lokalen Benutzer lesbar, solange der Container laeuft. Das sind
# Millisekunden, aber es ist genau die Art Leck, die Abschnitt 10 der
# Spezifikation ausschliesst, und caddy liest ohne den Schalter von stdin.
HJ_VERWALTER_HASH="$(printf '%s' "$PW_PULT" | docker run --rm -i caddy:2-alpine caddy hash-password)"
[[ -n "$HJ_VERWALTER_HASH" ]] || fail "Hashen fehlgeschlagen."
info "bcrypt."

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
HJ_PORT_INTERN=${HJ_PORT_INTERN}
HJ_CADDY_BIND=${HJ_CADDY_BIND}
HJ_GIT_BIND=${HJ_GIT_BIND}
HJ_TOKEN_KNOTEN=${PW_KNOTEN}
HJ_TOKEN_AUFSETZEN=${PW_AUFSETZEN}
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
if ! ANLEGEN="$(docker compose exec -T -u git forgejo forgejo --config "$FJ_INI" admin user create \
        --admin --username "$HJ_ADMIN" --password "$PW_ADMIN" \
        --email "$HJ_ADMIN_MAIL" --must-change-password=false 2>&1)"; then
    warn "$ANLEGEN"
    fail "Verwaltungskonto liess sich nicht anlegen - siehe Meldung oben."
fi
info "$HJ_ADMIN"

# Kein zweites Konto fuer die Knoten: die Abbilder darf lesen, wer bis zur
# Registry kommt - und dorthin kommt nur, wer Caddy das Knoten-Passwort
# vorgelegt hat.
step "Organisation"
TOKEN="$(docker compose exec -T -u git forgejo forgejo --config "$FJ_INI" admin user generate-access-token \
    -u "$HJ_ADMIN" --scopes all --raw)" || fail "Kein Verwaltungstoken."
TOKEN="$(printf '%s' "$TOKEN" | tr -d '\r\n ')"

docker compose exec -T forgejo curl -fsS -X POST \
    -H "Authorization: token ${TOKEN}" -H "Content-Type: application/json" \
    -d '{"username":"hoerjetzt","visibility":"public"}' \
    http://127.0.0.1:3000/api/v1/orgs >/dev/null \
    || fail "Organisation hoerjetzt liess sich nicht anlegen."
info "hoerjetzt - hier liegen die Abbilder."

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

step "Caddy und Updater starten"
printf 'noch nichts veroeffentlicht\n' | aus_schreiben release/aktuell \
    || fail "Auslieferungsverzeichnis nicht beschreibbar."
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

if printf '%s' "$PW_KNOTEN" | docker login "$REGISTRY_LOKAL" \
        -u knoten --password-stdin >/dev/null 2>&1; then
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
cat <<ENDE

    Wird nicht wieder angezeigt.

      Aufsetz-Passwort  ${PW_AUFSETZEN}

          Neuen Knoten aufsetzen - das ist die ganze Zeile:

          curl -fsSLu knoten https://${HJ_UPDATE_HOST}/knoten/aufsetzen.sh -o a.sh && bash a.sh

      Knoten-Passwort   (4096 Bit, steht unten noch einmal einzeln)

      Updater           ${HJ_ADMIN} / $(if $PW_PULT_VORGEGEBEN; then
                            printf '%s' "<das beim Aufruf angegebene Passwort>"
                        else printf '%s' "$PW_PULT"; fi)
                        http://${HJ_PULT_BIND}:${HJ_PULT_PORT}/
                        Freigaben, Knoten, Verwalten, Zugriffsprotokoll.

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
      Weiterleiten an   http://${HJ_CADDY_BIND}:${HJ_PORT_INTERN}
      Zertifikat        wie ueblich ueber den NPM

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
info "Naechste Schritte:"
info "  1. Proxy-Host im NPM anlegen (siehe oben)."
info "  2. Tresor befuellen:  bash tresor.sh fuellen voll"
info "                        bash tresor.sh fuellen lavalink"
info "  3. Release bauen:     Tag v... auf main setzen"
echo
