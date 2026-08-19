#!/usr/bin/env bash
#
# hoer.jetzt - Update-Server aufsetzen. Laeuft einmal, zuhause.
#
#   bash einrichten.sh
#
# Danach gibt es drei Zugaenge - einen zum Tippen, zwei zum Mitnehmen:
#
#   Knoten-Passwort    Kurz. Passt in eine Befehlszeile. Damit holt sich ein
#                      frischer Rechner das Installationsskript. Mehr gibt
#                      dieser Zugang nicht her.
#
#   Update-Ausweis     Client-Zertifikat, 4096 Bit. Bleibt dauerhaft auf dem
#                      Knoten und laesst ihn Abbilder ziehen. Docker legt ihn
#                      von sich aus vor.
#
#   Tresor-Schluessel  4096 Bit. Macht die gemeinsamen Zugangsdaten auf.
#                      Der private Teil liegt auf diesem Server nur, weil er
#                      hier erzeugt wurde - er gehoert in deine Ablage.
#
# Das Passwort wird genau einmal angezeigt. Danach steht in der .env nur noch
# der bcrypt-Hash, und daraus laesst es sich nicht zurueckrechnen.

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
frage HJ_UPDATE_NAME "Oeffentlicher Name" "update.system.hoer.jetzt"

info ""
info "Dieser Dienst geht bewusst an Cloudflare und am Nginx Proxy Manager"
info "vorbei: Client-Zertifikate ueberleben keinen Reverse-Proxy. Wer TLS"
info "terminiert, sieht das Zertifikat des Knotens - und was dahinter liegt,"
info "sieht nur noch das der Zwischenstelle. Deshalb ein eigener Port, den"
info "der Router direkt hierher weiterleitet, und Port 80 bleibt unberuehrt."
frage HJ_HTTPS_PORT "Auf welchem Port soll der Update-Server lauschen" "8443"

# Der Port gehoert in den Namen. Genau diese Schreibweise erwartet Docker als
# Verzeichnis unter /etc/docker/certs.d/, und dieselbe steht spaeter im
# Registry-Namen und in jeder URL. Eine zweite Variable dafuer waere eine
# Stelle mehr, an der die beiden auseinanderlaufen koennen.
HJ_UPDATE_HOST="${HJ_UPDATE_NAME}:${HJ_HTTPS_PORT}"


info ""
info ""
info "Das Serverzertifikat kommt aus der eigenen CA, nicht von Let's"
info "Encrypt. Die Gegenstellen sind ausschliesslich eigene Maschinen -"
info "die tragen die CA ohnehin, weil sie sich selbst damit ausweisen."
info "Damit braucht es weder Port 80 noch eine Stelle von aussen."

# Zeigt der Name auf Cloudflare statt hierher, laeuft der Verkehr durch deren
# Proxy - und dann kommt kein Client-Zertifikat mehr an. Das faellt sonst erst
# auf, wenn der erste Knoten sich nicht ausweisen kann, und sieht dort wie ein
# Fehler im Ausweis aus.
step "Namensaufloesung"
ZIEL="$(getent hosts "$HJ_UPDATE_NAME" 2>/dev/null | awk '{print $1}' | head -n1 || true)"
EIGEN="$(curl -fsS -m 8 https://api.ipify.org 2>/dev/null || true)"

hinter_cloudflare() {
    case "$1" in
        2606:4700:*|2803:f800:*|2405:b500:*|2405:8100:*|2a06:98c0:*|2c0f:f248:*) return 0 ;;
        104.1[6-9].*|104.2[0-7].*|172.6[4-9].*|172.7[01].*|173.245.*|188.114.*|190.93.*|197.234.*|198.41.*) return 0 ;;
    esac
    return 1
}

if [[ -z "$ZIEL" ]]; then
    warn "${HJ_UPDATE_NAME} laesst sich nicht aufloesen."
    warn "Ohne Eintrag bekommt Caddy zwar trotzdem ein Zertifikat (DNS-01),"
    warn "aber kein Knoten findet den Server."
    ja "Trotzdem weiter?" n || fail "Abgebrochen."
elif hinter_cloudflare "$ZIEL"; then
    warn "${HJ_UPDATE_NAME} zeigt auf ${ZIEL} - das ist Cloudflare."
    warn ""
    warn "Der Eintrag steht auf Proxy (orange Wolke). So kommt hier nie ein"
    warn "Client-Zertifikat an, und jeder Knoten wird abgewiesen."
    warn ""
    warn "In Cloudflare fuer genau diesen Namen auf 'DNS only' umstellen"
    warn "(graue Wolke) und auf ${EIGEN:-die Adresse dieses Anschlusses} zeigen lassen."
    warn "Cloudflare reicht Port ${HJ_HTTPS_PORT} im Proxy-Modus ohnehin nicht durch."
    ja "Trotzdem weiter?" n || fail "Abgebrochen."
elif [[ -n "$EIGEN" && "$ZIEL" != "$EIGEN" ]]; then
    warn "${HJ_UPDATE_NAME} zeigt auf ${ZIEL}, dieser Anschluss ist ${EIGEN}."
    warn "Stimmt das nicht, findet kein Knoten hierher."
    ja "Trotzdem weiter?" n || fail "Abgebrochen."
else
    info "${HJ_UPDATE_NAME} -> ${ZIEL}"
fi

# Nur der eigene Port. Port 80 und 443 werden nicht mehr angefasst - die
# duerfen dem Nginx Proxy Manager gehoeren.
step "Port"
if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":${HJ_HTTPS_PORT} "; then
    fail "Port ${HJ_HTTPS_PORT} ist belegt. Entweder freiraeumen oder einen anderen waehlen."
fi
info "Port ${HJ_HTTPS_PORT} ist frei. 80 und 443 bleiben unberuehrt."
info "Im Router weiterleiten: ${HJ_HTTPS_PORT} -> ${EIGEN:-dieser Host}:${HJ_HTTPS_PORT}"

step "Verwaltung"
info "Forgejo lauscht nur oertlich. Nach aussen geht allein /v2/ ueber Caddy."
info "Fuer die Oberflaeche vom Arbeitsplatz aus:"
info "    ssh -L 3000:127.0.0.1:3000 root@$(hostname -s 2>/dev/null || echo dieser-host)"
frage HJ_GIT_BIND "Auf welcher Adresse lauschen" "127.0.0.1"
frage HJ_ADMIN    "Benutzername fuer die Verwaltung" "marco"
# Forgejo verlangt beim Anlegen eines Kontos eine Mailadresse. Sie wird
# nie benutzt - es geht kein Mailversand von hier aus - steht aber im
# Konto, also lieber eine echte als etwas Ausgedachtes.
frage HJ_ADMIN_MAIL "Mailadresse fuer das Forgejo-Konto" "system@hoer.jetzt"

info "Die Oberflaeche des Updaters - Freigaben, Knoten, Protokoll -"
info "liegt auf einem eigenen Port und gehoert ins private Netz."
info "127.0.0.1 heisst: nur per SSH-Tunnel. Fuer Zugriff von unterwegs"
info "hier die Tailscale-Adresse dieses Hosts eintragen."
frage HJ_PULT_BIND "Auf welcher Adresse soll die Oberflaeche lauschen" "127.0.0.1"
frage HJ_PULT_PORT "Auf welchem Port" "8090"

# ------------------------------------------------------------------ 2  Schluessel

step "Schluessel erzeugen"
# Der Name geht mit: er landet als SAN im Serverzertifikat. Ohne
# passenden SAN lehnt jeder moderne Client ab - der CN allein zaehlt
# seit Jahren nicht mehr.
HJ_UPDATE_NAME="$HJ_UPDATE_NAME" bash "${HIER}/schluessel.sh" erzeugen \
    || fail "Die Schluessel liessen sich nicht erzeugen."

# ------------------------------------------------------------------ 3  Passwort

step "Knoten-Passwort"
# Muss von Hand eingetippt werden koennen - deshalb Gruppen statt eines
# Zufallsbandes, und ein Alphabet ohne 0/O und 1/l/I. Vier Gruppen zu vier
# Zeichen aus 32 sind rund 80 Bit; das reicht fuer einen Zugang, der nur ein
# Installationsskript herausgibt und jederzeit gewechselt werden kann.
gruppe() { head -c 32 /dev/urandom | tr -dc 'ABCDEFGHJKMNPQRSTUVWXYZ23456789' | cut -c1-4; }
PW_KNOTEN="hj-$(gruppe)-$(gruppe)-$(gruppe)-$(gruppe)"
PW_ADMIN="$(zufall)"
# Fuer die Updater-Oberflaeche. Kein tippbares Kurzpasswort wie beim
# Knoten-Zugang: dieses hier wird in einen Browser eingefuegt, nicht in
# eine Befehlszeile abgetippt - also darf es lang sein.
PW_PULT="$(zufall)"
info "${#PW_KNOTEN} Zeichen, tippbar."

step "Hash bilden"
docker pull -q caddy:2-alpine >/dev/null 2>&1 || fail "caddy:2-alpine nicht ladbar."
HJ_HASH_KNOTEN="$(docker run --rm caddy:2-alpine caddy hash-password --plaintext "$PW_KNOTEN")"
[[ -n "$HJ_HASH_KNOTEN" ]] || fail "Hashen fehlgeschlagen."
# Caddys hash-password liefert bcrypt im Format $2a$ - genau das, was
# Springs BCryptPasswordEncoder im Updater liest. Ein zweites Werkzeug
# nur zum Hashen waere hier ueberfluessig.
HJ_VERWALTER_HASH="$(docker run --rm caddy:2-alpine caddy hash-password --plaintext "$PW_PULT")"
[[ -n "$HJ_VERWALTER_HASH" ]] || fail "Hashen des Verwalter-Passworts fehlgeschlagen."
info "bcrypt."

step "Umgebungsdatei"
cat > "$UMGEBUNG" <<ENV
# hoer.jetzt Update-Server. Erzeugt von einrichten.sh am $(date '+%Y-%m-%d %H:%M').
#
# Hier steht nur ein Hash. Das Klartext-Passwort wurde einmal angezeigt und
# ist auf diesem Host nirgends gespeichert - auch nicht hier.
# Traegt den Port mit - dieselbe Schreibweise wie in der URL, im
# Registry-Namen und unter /etc/docker/certs.d/.
HJ_UPDATE_HOST=${HJ_UPDATE_HOST}
HJ_UPDATE_NAME=${HJ_UPDATE_NAME}
HJ_HTTPS_PORT=${HJ_HTTPS_PORT}
HJ_GIT_BIND=${HJ_GIT_BIND}
HJ_HASH_KNOTEN=${HJ_HASH_KNOTEN}
HJ_VERWALTER_NAME=${HJ_ADMIN}
HJ_VERWALTER_HASH=${HJ_VERWALTER_HASH}
HJ_PULT_BIND=${HJ_PULT_BIND}
HJ_PULT_PORT=${HJ_PULT_PORT}
ENV
chmod 600 "$UMGEBUNG"
info "${UMGEBUNG} (0600)"

# ------------------------------------------------------------------ 4  Start

step "Forgejo starten"
cd "$HIER"
docker compose up -d forgejo || fail "Forgejo startet nicht."

info "Warte auf die Ersteinrichtung..."
for versuch in $(seq 1 60); do
    if docker compose exec -T forgejo curl -fsS -m 3 \
            http://127.0.0.1:3000/api/healthz >/dev/null 2>&1; then
        break
    fi
    [[ "$versuch" -eq 60 ]] && fail "Forgejo antwortet nicht - docker compose logs forgejo"
    sleep 2
done
info "Forgejo antwortet."

step "Verwaltungskonto"
docker compose exec -T -u git forgejo forgejo admin user create \
    --admin --username "$HJ_ADMIN" --password "$PW_ADMIN" \
    --email "$HJ_ADMIN_MAIL" --must-change-password=false >/dev/null \
    || fail "Verwaltungskonto liess sich nicht anlegen."
info "$HJ_ADMIN"

# Kein zweites Konto fuer die Knoten: die Abbilder darf lesen, wer bis zur
# Registry kommt - und dorthin kommt nur, wer Caddy einen gueltigen Ausweis
# vorgelegt hat. Zwei Zugangsdaten fuer dieselbe Sache waeren eine mehr, als
# es zu schuetzen gibt.
step "Organisation"
TOKEN="$(docker compose exec -T -u git forgejo forgejo admin user generate-access-token \
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

step "Caddy starten"
printf 'noch nichts veroeffentlicht\n' | aus_schreiben release/aktuell \
    || fail "Auslieferungsverzeichnis nicht beschreibbar."
docker compose up -d || fail "Start fehlgeschlagen."
info "Warte auf den Start..."
sleep 10

# ------------------------------------------------------------------ 4b  Eigener Zugang
#
# Der Runner baut hier und schiebt die Abbilder in die eigene Registry. Er
# geht dabei denselben Weg wie jeder Knoten - durch Caddy, mit Ausweis. Also
# braucht auch der Docker-Dienst dieses Hosts den Ausweis.

step "Eigenen Docker-Zugang einrichten"
DOCKERAUSWEIS="/etc/docker/certs.d/${HJ_UPDATE_HOST}"
mkdir -p "$DOCKERAUSWEIS"
# "client.cert", nicht "client.crt" - unter dem falschen Namen wird die Datei
# stillschweigend ignoriert und der Push scheitert mit "unauthorized".
cp "${HIER}/schluessel/update-ausweis.crt" "${DOCKERAUSWEIS}/client.cert"
cp "${HIER}/schluessel/update-ausweis.key" "${DOCKERAUSWEIS}/client.key"
chmod 600 "${DOCKERAUSWEIS}/client.key"
# Und die CA. Ohne sie lehnt Docker den Server ab - das Zertifikat kommt
# nicht mehr von Let's Encrypt, also steht es in keinem Systemspeicher.
# Die Meldung lautete sonst "x509: certificate signed by unknown authority".
cp "${HIER}/schluessel/ca.crt" "${DOCKERAUSWEIS}/ca.crt"

info "$DOCKERAUSWEIS"

# Der Name muss auf diesem Host nach innen zeigen. Sonst liefe der Push zum
# Router hinaus und wieder herein - und NAT-Hairpin koennen viele Anschluesse
# nicht. Erst hier, nach der Namenspruefung weiter oben: vorher haette der
# Eintrag genau die Kontrolle unbrauchbar gemacht, die er stoeren wuerde.
# Ohne Port - /etc/hosts kennt nur Namen. Der Port steckt in
# HJ_UPDATE_HOST, hier ist deshalb HJ_UPDATE_NAME richtig.
if ! grep -q "[[:space:]]${HJ_UPDATE_NAME}$" /etc/hosts 2>/dev/null; then
    printf '127.0.0.1 %s\n' "$HJ_UPDATE_NAME" >> /etc/hosts
    info "/etc/hosts: ${HJ_UPDATE_NAME} -> 127.0.0.1"
else
    info "/etc/hosts hat den Eintrag bereits."
fi

# ------------------------------------------------------------------ 5  Probe

step "Selbstprobe"
GRUEN=1
probe() {
    local text="$1"; shift
    if "$@" >/dev/null 2>&1; then
        info "$(printf '%-38s %s' "$text" "ok")"
    else
        warn "$(printf '%-38s %s' "$text" "FEHLGESCHLAGEN")"
        GRUEN=0
    fi
}
S="${HIER}/schluessel"

# Die CA gehoert in den Knoten-Bereich. Sie ist kein Geheimnis - ein
# CA-Zertifikat ist der oeffentliche Teil - aber ohne sie kann ein
# frischer Knoten den Server nicht pruefen. Sie liegt hinter dem
# Knoten-Passwort, weil dort ohnehin alles liegt, was ein neuer Rechner
# als erstes braucht.
aus_schreiben knoten/ca.crt < "${S}/ca.crt" \
    || fail "ca.crt liess sich nicht ausliefern."
info "/knoten/ca.crt bereitgestellt."

# --cacert bei jedem Aufruf: das Serverzertifikat kommt aus der eigenen
# CA und steht in keinem Systemspeicher. Ohne die Angabe scheiterte jede
# Probe an der Vertrauenskette statt an dem, was sie pruefen soll - und
# die Meldung wiese in die falsche Richtung.
PRUEF=(--cacert "${S}/ca.crt")
AUSWEIS=(--cert "${S}/update-ausweis.crt" --key "${S}/update-ausweis.key")

# Kurzform, damit die Proben lesbar bleiben. --cacert steckt in PRUEF: das
# Serverzertifikat kommt aus der eigenen CA und steht in keinem Systemspeicher.
# Ohne die Angabe scheiterte jede Probe an der Vertrauenskette statt an dem,
# was sie pruefen soll - und die Meldung wiese in die falsche Richtung.
code() {
    curl -s -o /dev/null -w '%{http_code}' -m 15 "${PRUEF[@]}" "$@" || echo 0
}

probe "Ausweis oeffnet den Release-Bereich" \
    curl -fsS -m 15 "${PRUEF[@]}" "${AUSWEIS[@]}" "https://${HJ_UPDATE_HOST}/release/aktuell"
probe "ohne Ausweis bleibt er zu" \
    test 403 = "$(code "https://${HJ_UPDATE_HOST}/release/aktuell")"
probe "Ausweis oeffnet die Registry" \
    curl -fsS -m 15 "${PRUEF[@]}" "${AUSWEIS[@]}" "https://${HJ_UPDATE_HOST}/v2/"
probe "ohne Ausweis bleibt sie zu" \
    test 403 = "$(code "https://${HJ_UPDATE_HOST}/v2/")"
probe "Knoten-Bereich nimmt das Passwort" \
    curl -fsS -m 15 "${PRUEF[@]}" -u "knoten:${PW_KNOTEN}" "https://${HJ_UPDATE_HOST}/knoten/"
probe "Knoten-Bereich weist Falsches ab" \
    test 401 = "$(code -u 'knoten:falsch' "https://${HJ_UPDATE_HOST}/knoten/")"
# Der Ausweis darf gerade nicht ins Installationsverzeichnis - sonst waeren
# die beiden Stufen in Wahrheit eine.
probe "Ausweis oeffnet /knoten/ NICHT" \
    test 401 = "$(code "${AUSWEIS[@]}" "https://${HJ_UPDATE_HOST}/knoten/")"
# Das Serverzertifikat muss auf den Namen mit Port passen. Ein fehlender SAN
# faellt sonst erst auf dem ersten Knoten auf, und dort sieht es nach einem
# Fehler im Ausweis aus.
probe "Serverzertifikat passt zum Namen" \
    curl -fsS -m 15 --cacert "${S}/ca.crt" "${AUSWEIS[@]}" \
        "https://${HJ_UPDATE_HOST}/release/aktuell"
probe "ohne die CA wird der Server abgelehnt" \
    test 0 = "$(curl -s -o /dev/null -w '%{http_code}' -m 15 \
                "${AUSWEIS[@]}" "https://${HJ_UPDATE_HOST}/release/aktuell" || echo 0)"


# --------------------------------------------------------------- Torwaechter
#
# Die Adresspruefung ist die zweite Stufe und die neue Fehlerquelle: laesst
# sie zu viel durch, faellt es nie auf. Deshalb beide Richtungen pruefen.
#
# Die Rueckschleife kommt durch, weil der Updater beim ersten Start
# Grundfreigaben anlegt (siehe Erstbelegung.java). Genau darauf beruht auch
# die Registry-Probe weiter unten - sie geht ueber /etc/hosts nach 127.0.0.1.
probe "Updater antwortet am Tor" \
    docker compose exec -T updater curl -fsS -m 10 -o /dev/null \
        -w '%{http_code}' http://127.0.0.1:8080/intern/pruefen
probe "Tor weist ohne Adresse ab" \
    test 403 = "$(docker compose exec -T updater curl -s -o /dev/null -m 10 \
                  -w '%{http_code}' http://127.0.0.1:8080/intern/pruefen 2>/dev/null || echo 0)"
probe "Tor laesst die Rueckschleife durch" \
    test 204 = "$(docker compose exec -T updater curl -s -o /dev/null -m 10 \
                  -H 'X-Echte-Ip: 127.0.0.1' -w '%{http_code}' \
                  http://127.0.0.1:8080/intern/pruefen 2>/dev/null || echo 0)"
probe "Tor sperrt eine fremde Adresse" \
    test 403 = "$(docker compose exec -T updater curl -s -o /dev/null -m 10 \
                  -H 'X-Echte-Ip: 203.0.113.7' -w '%{http_code}' \
                  http://127.0.0.1:8080/intern/pruefen 2>/dev/null || echo 0)"
# Der Torwaechter-Port darf vom Host aus NICHT erreichbar sein - er steht
# absichtlich nicht unter "ports". Kaeme hier eine Antwort, haenge die
# Zugangskontrolle des Servers offen im Netz.
probe "Tor-Port liegt NICHT auf dem Host" \
    test 000 = "$(curl -s -o /dev/null -m 5 -w '%{http_code}' \
                  http://127.0.0.1:8080/intern/pruefen 2>/dev/null || echo 000)"
probe "Oberflaeche verlangt Anmeldung" \
    test 302 = "$(curl -s -o /dev/null -m 10 -w '%{http_code}' \
                  "http://${HJ_PULT_BIND}:${HJ_PULT_PORT}/" 2>/dev/null || echo 0)"
# Die entscheidende Probe: einmal wirklich hoch und wieder herunter. Sie
# faellt auf alles herein, was die Proben oben nicht sehen - den falschen
# Dateinamen im certs.d-Verzeichnis, einen fehlenden /etc/hosts-Eintrag, ein
# Leserecht, das Forgejo doch verlangt. Ohne sie faellt das erst beim ersten
# echten Release auf.
step "Registry - einmal hin und zurueck"
MARKE="${HJ_UPDATE_HOST}/hoerjetzt/probe:1"
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

# ------------------------------------------------------------------ 6  Ende

# Der Fingerabdruck der CA. Beim Aufsetzen eines Knotens muss derselbe
# erscheinen - das ist die einzige Pruefung, die der erste, noch
# ungepruefte Abruf der CA zulaesst.
CA_FINGER="$(openssl x509 -in "${HIER}/schluessel/ca.crt" -noout \
    -fingerprint -sha256 | cut -d= -f2- | tr -d ':')"

step "Jetzt notieren"
cat <<ENDE

    Wird nicht wieder angezeigt.

      Knoten-Passwort   ${PW_KNOTEN}

          Neuen Knoten aufsetzen - das ist die ganze Zeile:

          curl -fsSLku knoten https://${HJ_UPDATE_HOST}/knoten/aufsetzen.sh -o a.sh && bash a.sh

          Das k in -fsSLku: der Server weist sich mit der eigenen CA aus,
          und die kennt ein frischer Rechner noch nicht. Dieser eine
          Abruf laeuft deshalb ungeprueft. Das Skript holt danach die CA
          und zeigt ihren Fingerabdruck - er muss dem hier entsprechen:

      CA-Fingerabdruck  ${CA_FINGER}

      Updater           ${HJ_ADMIN} / ${PW_PULT}
                        http://${HJ_PULT_BIND}:${HJ_PULT_PORT}/
                        Freigaben, Knoten, Zugriffsprotokoll.

                        Neue Knoten muessen dort freigeschaltet werden,
                        bevor sie an Tresor und Abbilder kommen.

      Forgejo           ${HJ_ADMIN} / ${PW_ADMIN}
                        ssh -L 3000:127.0.0.1:3000 und dann
                        http://127.0.0.1:3000

    Die beiden Schluessel liegen in ${S} und gehoeren in deine Ablage:

      update-ausweis.crt + .key    kommt auf jeden Knoten
      tresor.key                   macht die Zugangsdaten auf
      ca.key                       bleibt HIER - damit liessen sich
                                   beliebige weitere Ausweise ausstellen

ENDE
read -r -p "    Notiert? Dann Enter. " _ || true
clear 2>/dev/null || true

step "Fertig"
if [[ "$GRUEN" -eq 1 ]]; then
    info "Alle Proben bestanden."
else
    warn "Mindestens eine Probe ist fehlgeschlagen - siehe oben."
    warn "Protokoll: docker compose logs caddy"
fi
echo
info "Naechste Schritte:"
info "  1. Tresor befuellen:  bash tresor.sh fuellen voll"
info "                        bash tresor.sh fuellen lavalink"
info "  2. Release bauen:     Tag v... auf main setzen"
echo
