#!/usr/bin/env bash
#
# hoer.jetzt - aus einer leeren Maschine einen Knoten machen.
#
#   curl -fsSLu knoten https://update.system.hoer.jetzt/knoten/aufsetzen.sh -o a.sh
#   bash a.sh
#
# Das "-u knoten" ohne Doppelpunkt ist Absicht: curl fragt das Passwort dann
# selbst ab, statt es in die Befehlszeile und damit in die Shell-Historie zu
# schreiben.
#
# Danach laeuft auf dieser Maschine entweder der vollstaendige Stack oder ein
# Audio-Knoten. Gefragt wird nur nach dem, was sich nicht herausfinden laesst:
# das Profil und die beiden Schluessel.
#
# Es wird nichts gebaut und kein Quellcode geholt. Alles kommt als fertiges
# Abbild - deshalb braucht diese Maschine kein Java, kein Maven und keine
# 2 GB fuer einen Quellbaum.

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
AUSWEIS="${ARBEIT}/ausweis"

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

[[ "$(id -u)" -eq 0 ]] || fail "Bitte als root starten."
command -v curl    >/dev/null 2>&1 || fail "curl fehlt."
command -v openssl >/dev/null 2>&1 || fail "openssl fehlt (Paket openssl)."

cat <<'KOPF'

  ------------------------------------------------------------------
   hoer.jetzt - Knoten aufsetzen
  ------------------------------------------------------------------

  Diese Maschine bekommt: Docker, einen Ausweis, die gemeinsamen
  Zugangsdaten und ein naechtliches Update. Kein Quellcode.

KOPF

# ------------------------------------------------------------------ 1  Profil

step "Profil"
info "voll      Bot, Weboberflaeche, AI-Radio und ein Audio-Knoten."
info "lavalink  Nur ein Audio-Knoten. Bekommt weder Datenbank noch Bot-Token -"
info "          er braucht beides nicht."
frage PROFIL "Was soll hier laufen" "lavalink"
case "$PROFIL" in
    voll|lavalink) ;;
    *) fail "Profil muss voll oder lavalink sein." ;;
esac

frage HJ_UPDATE_HOST "Update-Server" "update.system.hoer.jetzt"
geheim KNOTEN_PW "Knoten-Passwort (dasselbe wie eben)"

# ------------------------------------------------------------------ 2  Docker

step "Docker"
if ! command -v docker >/dev/null 2>&1; then
    info "Nicht vorhanden - wird installiert."
    curl -fsSL https://get.docker.com | sh >/dev/null 2>&1 \
        || fail "Docker-Installation fehlgeschlagen."
    systemctl enable --now docker >/dev/null 2>&1 || true
fi
docker compose version >/dev/null 2>&1 || fail "docker compose (v2) fehlt."
info "$(docker --version)"

# ------------------------------------------------------------------ 3  Schluessel
#
# Beide sind 4096 Bit und damit zu lang zum Abtippen. Entweder liegen sie
# schon auf der Maschine (scp), oder sie werden hier hineingeklebt.

einlesen_pem() {
    local ziel="$1" titel="$2" pfad=""
    echo
    info "${titel}"
    frage pfad "Pfad zur Datei (leer = einfuegen)" ""
    if [[ -n "$pfad" ]]; then
        [[ -f "$pfad" ]] || fail "${pfad} gibt es nicht."
        cp "$pfad" "$ziel"
    else
        info "Jetzt den kompletten Block einfuegen, mit BEGIN- und END-Zeile."
        info "Nach der END-Zeile ist Schluss - Enter genuegt."
        : > "$ziel"
        local zeile
        while IFS= read -r zeile; do
            printf '%s\n' "$zeile" >> "$ziel"
            [[ "$zeile" == *"-----END"* ]] && break
        done
    fi
    # Ein leerer oder halber Block faellt sonst erst beim ersten naechtlichen
    # Update auf - und dann ohne jemanden, der zusieht.
    grep -q -- "-----BEGIN" "$ziel" || fail "In ${ziel} steht kein PEM-Block."
    grep -q -- "-----END"   "$ziel" || fail "${ziel} hoert mittendrin auf."
}

mkdir -p "$AUSWEIS"
chmod 700 "$AUSWEIS"

step "Update-Ausweis"
einlesen_pem "${AUSWEIS}/update.crt" "Das Zertifikat (update-ausweis.crt)"
einlesen_pem "${AUSWEIS}/update.key" "Der zugehoerige Schluessel (update-ausweis.key)"
chmod 600 "${AUSWEIS}/update.key"
chmod 644 "${AUSWEIS}/update.crt"

# Gehoeren die beiden zusammen? Wenn nicht, endet das sonst in einem 403,
# dessen Ursache nirgends steht.
A="$(openssl x509 -noout -modulus -in "${AUSWEIS}/update.crt" 2>/dev/null | openssl sha256)"
B="$(openssl rsa  -noout -modulus -in "${AUSWEIS}/update.key" 2>/dev/null | openssl sha256)"
[[ -n "$A" && "$A" == "$B" ]] || fail "Zertifikat und Schluessel gehoeren nicht zusammen."
info "Zertifikat und Schluessel passen zueinander."

# Docker legt den Ausweis von sich aus vor, wenn er hier liegt - und zwar
# unter genau diesen Namen. "client.crt" statt "client.cert" wird stillschweigend
# ignoriert, der pull scheitert dann mit "unauthorized".
step "Ausweis fuer Docker hinterlegen"
DOCKERAUSWEIS="/etc/docker/certs.d/${HJ_UPDATE_HOST}"
mkdir -p "$DOCKERAUSWEIS"
cp "${AUSWEIS}/update.crt" "${DOCKERAUSWEIS}/client.cert"
cp "${AUSWEIS}/update.key" "${DOCKERAUSWEIS}/client.key"
chmod 600 "${DOCKERAUSWEIS}/client.key"
info "$DOCKERAUSWEIS"

step "Tresor-Schluessel"
TRESORKEY="${AUSWEIS}/tresor.key"
einlesen_pem "$TRESORKEY" "Der Tresor-Schluessel (tresor.key)"
chmod 600 "$TRESORKEY"

# ------------------------------------------------------------------ 4  Probe

step "Zugang pruefen"
# Holt etwas mit dem Ausweis - und unterscheidet dabei die beiden Gruende,
# aus denen es scheitern kann. Vorher lautete die Meldung in beiden Faellen
# "kein Tresor bereit", und das schickt einen an die falsche Stelle: der
# Ausweis ist in Ordnung, es fehlt die Freischaltung dieser Adresse.
mit_ausweis() {
    local ziel="$1" code
    code="$(curl -sS -m 30 -o /tmp/hj-antwort.$$ -w '%{http_code}' \
        --cert "${AUSWEIS}/update.crt" --key "${AUSWEIS}/update.key" \
        "https://${HJ_UPDATE_HOST}${ziel}" 2>/dev/null || echo 000)"

    if [[ "$code" == "403" ]]; then
        rm -f "/tmp/hj-antwort.$$"
        EIGENE_IP="$(curl -fsS -m 10 https://api.ipify.org 2>/dev/null || echo '')"
        fail "Diese Maschine ist auf dem Update-Server nicht freigeschaltet.

       Der Ausweis stimmt - die Adresse fehlt in der Freigabeliste.
       Im Updater unter Freigaben eintragen:

           ${EIGENE_IP:-<die oeffentliche Adresse dieser Maschine>}

       Die Oberflaeche liegt im privaten Netz, Vorgabe:
           http://<update-server>:8090/freigaben

       Danach dieses Skript erneut starten - es gilt sofort."
    fi

    if [[ "$code" != "200" ]]; then
        rm -f "/tmp/hj-antwort.$$"
        return 1
    fi

    cat "/tmp/hj-antwort.$$"
    rm -f "/tmp/hj-antwort.$$"
}
MANIFEST="$(mit_ausweis /release/aktuell || true)"
[[ -n "$MANIFEST" ]] || fail "Kein Zugang mit diesem Ausweis - stimmt der Update-Server?"
VERSION="$(printf '%s\n' "$MANIFEST" | grep '^version=' | cut -d= -f2- || true)"
info "Aktuelles Release: ${VERSION:-unbekannt}"

# ------------------------------------------------------------------ 5  Tresor

step "Zugangsdaten holen"
UMSCHLAG="$(mktemp)"
trap 'rm -f "$UMSCHLAG"' EXIT
mit_ausweis "/tresor/${PROFIL}.enc" > "$UMSCHLAG" \
    || fail "Fuer das Profil ${PROFIL} liegt kein Tresor bereit."

mkdir -p "$ARBEIT"
UMGEBUNG="${ARBEIT}/.env"

# -binary: ohne das haengt CMS an jede Zeile ein CRLF, und jeder Wert bekaeme
# ein unsichtbares Zeichen mehr. Das Datenbank-Passwort waere dann still
# falsch - ein Fehler, den man lange woanders sucht.
if ! openssl cms -decrypt -binary -inform PEM -in "$UMSCHLAG" \
        -inkey "$TRESORKEY" -out "${UMGEBUNG}.neu" 2>/dev/null; then
    fail "Der Tresor geht mit diesem Schluessel nicht auf."
fi
grep -q '^HJ_UPDATE_HOST=' "${UMGEBUNG}.neu" || fail "Der Tresor enthaelt nicht, was er soll."

# Eine vorhandene .env nicht wegwerfen: darin koennen HJ_SPOCK, eigene Ports
# oder Tailscale-Angaben stehen, die dieser Host schon hatte.
if [[ -f "$UMGEBUNG" ]]; then
    warn "Es gibt schon eine ${UMGEBUNG}."
    if ja "Sichern und die Werte aus dem Tresor ergaenzen?"; then
        cp "$UMGEBUNG" "${UMGEBUNG}.$(date '+%Y%m%d%H%M%S')"
        while IFS= read -r zeile; do
            [[ "$zeile" =~ ^([A-Z_]+)= ]] || continue
            schluessel="${BASH_REMATCH[1]}"
            if grep -q "^${schluessel}=" "$UMGEBUNG"; then
                sed -i "s|^${schluessel}=.*|${zeile}|" "$UMGEBUNG"
            else
                printf '%s\n' "$zeile" >> "$UMGEBUNG"
            fi
        done < "${UMGEBUNG}.neu"
        rm -f "${UMGEBUNG}.neu"
    else
        fail "Abgebrochen - nichts geaendert."
    fi
else
    mv "${UMGEBUNG}.neu" "$UMGEBUNG"
fi
chmod 600 "$UMGEBUNG"

# Kennung und Profil festhalten. Der Herzschlag in auto-update.sh meldet
# beides an den Update-Server; die Kennung ist dieselbe, die der Agent
# benutzt, damit nicht zwei Listen entstehen, die dasselbe meinen.
setze_wert() {
    if grep -q "^$1=" "$UMGEBUNG"; then
        sed -i "s|^$1=.*|$1=$2|" "$UMGEBUNG"
    else
        printf '%s=%s\n' "$1" "$2" >> "$UMGEBUNG"
    fi
}
setze_wert HJ_PROFIL "$PROFIL"
grep -q '^HJ_NODE_NAME=' "$UMGEBUNG" || setze_wert HJ_NODE_NAME "$(hostname -s 2>/dev/null || echo knoten)"
info "$(printf '%s (%s Werte)' "$UMGEBUNG" "$(grep -c '^[A-Z_]*=' "$UMGEBUNG")")"

# ------------------------------------------------------------------ 6  Paket

step "Compose-Dateien holen"
PAKET="$(mktemp)"
curl -fsS -m 60 -u "knoten:${KNOTEN_PW}" \
    "https://${HJ_UPDATE_HOST}/knoten/${PROFIL}.tar.gz" -o "$PAKET" \
    || fail "Paket ${PROFIL}.tar.gz liess sich nicht holen - Passwort falsch?"

mkdir -p "${ARBEIT}/main/deploy"
tar -C "${ARBEIT}/main/deploy" -xzf "$PAKET" || fail "Paket liess sich nicht auspacken."
rm -f "$PAKET"
chmod +x "${ARBEIT}/main/deploy/auto-update.sh" 2>/dev/null || true
info "${ARBEIT}/main/deploy"

# ------------------------------------------------------------------ 7  Update

step "Naechtliches Update"
SYSTEMD="${ARBEIT}/main/deploy/systemd"
if [[ -f "${SYSTEMD}/hoerjetzt-update.timer" ]]; then
    install -m 0644 "${SYSTEMD}/hoerjetzt-update.service" /etc/systemd/system/
    install -m 0644 "${SYSTEMD}/hoerjetzt-update.timer"   /etc/systemd/system/
    systemctl daemon-reload
    systemctl enable --now hoerjetzt-update.timer >/dev/null 2>&1 || true
    info "Naechster Lauf: $(systemctl show -p NextElapseUSecRealtime --value \
          hoerjetzt-update.timer 2>/dev/null || echo '03:00')"
else
    warn "Keine Timer-Dateien im Paket - Auto-Update bleibt aus."
fi

# ------------------------------------------------------------------ 8  Start

step "Starten"
info "Jetzt laeuft dasselbe Skript, das auch nachts laeuft - kein Sonderweg"
info "fuer die Erstinstallation, der spaeter niemand mehr testet."
echo
if bash "${ARBEIT}/main/deploy/auto-update.sh" --jetzt; then
    step "Fertig"
    info "Release ${VERSION} laeuft."
else
    fail "Der erste Lauf ist gescheitert - siehe /var/log/hoerjetzt-update.log"
fi

echo
if [[ "$PROFIL" == "lavalink" ]]; then
    info "Diesen Knoten noch im Adminbereich eintragen:"
    info "    Adresse:  http://$(hostname -I 2>/dev/null | awk '{print $1}'):2333"
    info "    Passwort: steht in ${UMGEBUNG} unter HJ_LAVALINK_PASSWORD"
    echo
    warn "Erst dieser Eintrag entscheidet, welche Server auf dem Knoten landen."
else
    info "Noch zu tun:"
    info "  1. Weiterleitungsadresse im Discord Developer Portal eintragen"
    info "  2. Einmal /admin aufrufen"
fi
echo
info "Von Hand aktualisieren:  bash ${ARBEIT}/main/deploy/auto-update.sh --jetzt"
info "Zurueckrollen:           bash ${ARBEIT}/main/deploy/auto-update.sh --zurueck"
echo
