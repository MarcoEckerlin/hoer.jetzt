#!/usr/bin/env bash
#
# hoer.jetzt - modularer Knoten-Installer.
#
#   bash install-node.sh --modules core
#   bash install-node.sh --modules lavalink
#   bash install-node.sh --modules core,lavalink,ki-radio
#
#   --kennung <name>    Kennung dieses Knotens (aus dem Updater)
#   --token <hj-...>    Aufsetz-Token (aus dem Updater, gilt zwei Stunden)
#   --update-host <fqdn>  Vorgabe: repository.hoer.jetzt
#   --pruefen           nur nachsehen, nichts aendern
#
# ---------------------------------------------------------------------------
# Mehrfach ausfuehren ist sicher
#
# Jeder Schritt fragt zuerst, ob er noetig ist. Ein zweiter Lauf ergaenzt, was
# fehlt, und laesst alles andere in Ruhe - insbesondere Schluessel, .env und
# Datenbank. Das ist keine Bequemlichkeit: bei einer abgebrochenen Installation
# ist "noch einmal laufen lassen" der erste Griff, und wenn der etwas kaputt
# macht, ist es der letzte.
#
# ---------------------------------------------------------------------------
# Die Reihenfolge ist nicht beliebig
#
#   1. Voraussetzungen (Docker, openssl, curl)
#   2. Schluesselpaar - der private Teil bleibt hier, immer
#   3. Anmelden: Token gegen dauerhaftes Geheimnis
#   4. Oeffentlichen Schluessel hinterlegen
#   5. Tresor holen - erst jetzt moeglich, vorher gibt es nichts zu oeffnen
#   6. Module eintragen, Dienste starten
#
# Schritt 2 vor Schritt 3, weil der Server beim Anmelden schon wissen soll,
# wohin er spaeter verschluesseln kann. Und Schritt 5 nach Schritt 4, weil der
# Tresor sonst an einen Schluessel ginge, den es hier noch nicht gibt.
# ---------------------------------------------------------------------------

set -euo pipefail

# Dieses Skript laedt Nachbardateien und braucht deshalb einen Dateinamen.
#
# Bei "curl ... | bash" gibt es den nicht - BASH_SOURCE ist leer, und "set -u"
# brach hier mit "unbound variable" ab. Das ist die richtige Entscheidung mit
# der falschen Meldung: der Weg funktioniert wirklich nicht, aber der Grund
# stand nirgends. Wer ihn sah, suchte den Fehler im Skript.
if [[ -z "${BASH_SOURCE[0]:-}" ]]; then
    echo "Dieses Skript laedt Dateien aus seinem eigenen Verzeichnis und" >&2
    echo "kann nicht ueber eine Pipe laufen. Erst herunterladen:" >&2
    echo >&2
    echo "  curl -fsSLu knoten https://<update-server>/knoten/aufsetzen.sh -o a.sh" >&2
    echo "  bash a.sh" >&2
    exit 1
fi
HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
UMGEBUNG="${ARBEIT}/.env"
MODULDATEI="${ARBEIT}/module"
SCHLUESSEL="${ARBEIT}/knoten.key"
SPERRE="${ARBEIT}/.sperre"

# shellcheck source=agent/agent-lib.sh
. "${HIER}/agent/agent-lib.sh"

MODULE_WUNSCH=""
KENNUNG=""
TOKEN=""
HJ_UPDATE_HOST="${HJ_UPDATE_HOST:-repository.hoer.jetzt}"
NUR_PRUEFEN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --modules|--module) MODULE_WUNSCH="${2:?Modulliste angeben}"; shift 2 ;;
        --kennung)          KENNUNG="${2:?Kennung angeben}"; shift 2 ;;
        --token)            TOKEN="${2:?Token angeben}"; shift 2 ;;
        --update-host)      HJ_UPDATE_HOST="${2:?Adresse angeben}"; shift 2 ;;
        --pruefen)          NUR_PRUEFEN=true; shift ;;
        -h|--help)          sed -n '2,20p' "$0"; exit 0 ;;
        *)                  fehler "Unbekannte Angabe: $1" ;;
    esac
done
export HJ_UPDATE_HOST

# ------------------------------------------------------- Module aufloesen

# Abhaengigkeiten. Heute genau eine, aber die Stelle dafuer gibt es jetzt:
# jedes Modul zieht den Agenten nach sich, und der ist kein Modul, sondern
# immer da. Waere er ein Modul, koennte man ihn abwaehlen - und haette einen
# Knoten, der sich nie wieder meldet.
module_aufloesen() {
    local roh="$1" ergebnis=""
    local m
    for m in ${roh//,/ }; do
        m="$(echo "$m" | tr '[:upper:]' '[:lower:]' | tr '_' '-')"
        [[ "$m" == "ai-radio" ]] && m="ki-radio"
        case "$m" in
            core|lavalink|ki-radio) ergebnis="${ergebnis} ${m}" ;;
            agent)
                # "Nur der Agent" - ein Host unter Verwaltung, der (noch)
                # nichts betreibt. Kein eigenes Modul in der Liste, sondern
                # die leere Liste: der Agent gehoert ohnehin immer dazu.
                #
                # Das ist der erste Schritt der Migration aus Abschnitt 69:
                # einen bestehenden Server aufnehmen, ohne an seinen
                # laufenden Diensten etwas zu aendern. Module lassen sich
                # danach im Updater zuschalten.
                ;;
            controller)
                # Der Controller ist der Core-Stapel in einer anderen Rolle -
                # kein eigenes Programm. Siehe Kollision K2.
                ergebnis="${ergebnis} core"
                umgebung_setzen HJ_ROLLE controller || true
                ;;
            *) fehler "Unbekanntes Modul: ${m} (moeglich: core, lavalink, ki-radio, controller, agent)" ;;
        esac
    done
    echo "$ergebnis" | tr ' ' '\n' | grep -v '^$' | sort -u | paste -sd' ' -
}

# ---------------------------------------------------------------- Pruefen

schritt() { printf '\n== %s\n' "$*"; }
gut()     { printf '   ok   %s\n' "$*"; }
fehlt()   { printf '   --   %s\n' "$*"; }

if $NUR_PRUEFEN; then
    schritt "Zustand"
    [[ -d "$ARBEIT" ]]        && gut "Arbeitsverzeichnis ${ARBEIT}"      || fehlt "Arbeitsverzeichnis fehlt"
    [[ -f "$UMGEBUNG" ]]      && gut ".env vorhanden"                    || fehlt ".env fehlt"
    [[ -f "$SCHLUESSEL" ]]    && gut "Schluesselpaar vorhanden"          || fehlt "Schluesselpaar fehlt"
    [[ -f "$MODULDATEI" ]]    && gut "Module: $(module_lesen)"           || fehlt "Modulliste fehlt"
    command -v docker >/dev/null && gut "Docker"                          || fehlt "Docker fehlt"
    umgebung_lesen 2>/dev/null || true
    [[ -n "${HJ_KNOTEN_KENNUNG:-}" ]] && gut "angemeldet als ${HJ_KNOTEN_KENNUNG}" \
                                      || fehlt "nicht angemeldet"
    exit 0
fi

[[ -n "$MODULE_WUNSCH" ]] || fehler "--modules angeben, z.B. --modules core,lavalink"
MODULE="$(module_aufloesen "$MODULE_WUNSCH")"

mkdir -p "$ARBEIT"
mit_sperre || fehler "Ein anderer Lauf arbeitet gerade."

sagen "Module: ${MODULE}"

# ------------------------------------------------- 1. Voraussetzungen

schritt "Voraussetzungen"

for werkzeug in curl openssl; do
    command -v "$werkzeug" >/dev/null || fehler "${werkzeug} fehlt - erst nachinstallieren."
    gut "$werkzeug"
done

if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
    gut "Docker mit Compose"
else
    sagen "Docker fehlt - wird installiert."
    curl -fsSL https://get.docker.com | sh
    docker compose version >/dev/null 2>&1 || fehler "Docker Compose fehlt weiterhin."
    gut "Docker eingerichtet"
fi

# ---------------------------------------------------- 2. Schluesselpaar

schritt "Schluesselpaar"

if [[ -f "$SCHLUESSEL" ]]; then
    # NIE ueberschreiben. Ein neuer Schluessel macht jeden Tresor unlesbar,
    # der an den alten gerichtet war - und der zweite Lauf eines Installers
    # ist genau der Moment, in dem das unbemerkt passieren wuerde.
    gut "vorhanden - bleibt unangetastet"
else
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$SCHLUESSEL" 2>/dev/null
    chmod 600 "$SCHLUESSEL"
    gut "erzeugt (3072 Bit, bleibt auf diesem Host)"
fi
OEFFENTLICH="$(openssl rsa -in "$SCHLUESSEL" -pubout 2>/dev/null)"

# ---------------------------------------------------------- 3. Anmelden

schritt "Anmeldung beim Update-Server"

umgebung_lesen 2>/dev/null || true

if [[ -n "${HJ_KNOTEN_GEHEIMNIS:-}" ]]; then
    gut "bereits angemeldet als ${HJ_KNOTEN_KENNUNG}"
else
    [[ -n "$KENNUNG" ]] || fehler "--kennung fehlt. Im Updater unter Verwalten anlegen."
    [[ -n "$TOKEN" ]]   || fehler "--token fehlt. Im Updater unter Verwalten erzeugen."

    antwort="$(curl -fsS --max-time 30 \
        -H "Content-Type: application/json" \
        -d "$(printf '{"kennung":"%s","token":"%s","rechnername":"%s","privatIp":"%s","ipv4":"%s","agentVersion":"2"}' \
              "$KENNUNG" "$TOKEN" "$(hostname -f 2>/dev/null || hostname)" \
              "${HJ_PRIVAT_IP:-}" "$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo '')")" \
        "https://${HJ_UPDATE_HOST}/anmelden")" || fehler \
        "Anmeldung abgewiesen. Token abgelaufen (zwei Stunden) oder schon benutzt? Im Updater neu erzeugen."

    GEHEIMNIS="$(printf '%s' "$antwort" | sed -n 's/.*"geheimnis"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    [[ -n "$GEHEIMNIS" ]] || fehler "Antwort ohne Geheimnis: ${antwort}"

    touch "$UMGEBUNG" && chmod 600 "$UMGEBUNG"
    umgebung_setzen HJ_KNOTEN_KENNUNG   "$KENNUNG"   || true
    umgebung_setzen HJ_KNOTEN_GEHEIMNIS "$GEHEIMNIS" || true
    umgebung_setzen HJ_UPDATE_HOST      "$HJ_UPDATE_HOST" || true
    umgebung_lesen
    gut "angemeldet als ${KENNUNG}"
fi

# Woher die Abbilder kommen.
#
# Der Registry-Pfad ist der Update-Server plus Organisation - dieselbe
# Adresse, an der sich der Knoten gerade angemeldet hat.
#
# Ohne diese Zeile fiel der Hauptstack auf ghcr.io/marcoeckerlin/hoerjetzt
# zurueck. Das ist nicht unsere Registry: der Pull endete in "denied", und
# Compose versuchte daraufhin, die Abbilder aus dem Quellbaum zu BAUEN -
# den es auf einem Knoten nicht gibt:
#
#   unable to prepare context: path "/opt/hoerjetzt/core" not found
#
# Drei Meldungen, drei Irrwege, eine fehlende Variable. Die Compose-Dateien
# verlangen sie jetzt ausdruecklich, statt still auf etwas Fremdes zu zeigen.
umgebung_setzen HJ_REGISTRY "${HJ_UPDATE_HOST}/hoerjetzt" || true

# ------------------------------------------- 4. Schluessel hinterlegen

schritt "Oeffentlichen Schluessel hinterlegen"

# Jedes Mal, nicht nur beim ersten Lauf: hinterlegen ist billig, und ein
# Knoten, dessen Schluessel im Server fehlt, bekommt sonst beim Tresorabruf
# eine Meldung, die niemand erwartet.
if us_senden "/schluessel" "$(printf '{"zweck":"TRESOR","oeffentlich":%s}' \
        "$(printf '%s' "$OEFFENTLICH" | sed ':a;N;$!ba;s/\n/\\n/g;s/^/"/;s/$/"/')")" >/dev/null; then
    gut "hinterlegt"
else
    fehler "Schluessel liess sich nicht hinterlegen - ist die Adresse dieses Hosts freigeschaltet?"
fi

# ------------------------------------------------------------ 5. Tresor

schritt "Zugangsdaten"

# Welches Profil ein Modul braucht. Ein Audio-Knoten bekommt nur das
# Lavalink-Profil - er soll nichts weiter kennen.
for m in $MODULE; do
    case "$m" in
        core)     profil="core" ;;
        lavalink) profil="lavalink" ;;
        ki-radio) profil="ki-radio" ;;
        *)        continue ;;
    esac
    teil="${ARBEIT}/tresor-${profil}.env"
    if tresor_holen "$profil" "$teil"; then
        # In die .env uebernehmen, ohne Vorhandenes zu ueberschreiben:
        # HJ_SPOCK, eigene Ports und Tailscale-Angaben gehoeren dem Host.
        while IFS= read -r zeile; do
            [[ "$zeile" =~ ^[[:space:]]*# ]] && continue
            [[ "$zeile" == *=* ]] || continue
            umgebung_setzen "${zeile%%=*}" "${zeile#*=}" >/dev/null || true
        done < "$teil"
        rm -f "$teil"
        gut "Profil ${profil}"
    else
        # Zwei Ursachen, und die Meldung darf sie nicht vermengen:
        #
        #   - der Abruf scheiterte (Modul nicht zugeteilt, Adresse gesperrt)
        #   - er klappte, aber im Tresor stehen keine Werte
        #
        # Im zweiten Fall hat tresor_pruefen die fehlenden Schluessel bereits
        # einzeln genannt. Hier steht nur noch, dass es daran lag.
        fehler "Tresor-Profil '${profil}' unbrauchbar.

       Entweder ist es leer - dann auf dem Update-Server:
           bash update-server/tresor.sh fuellen ${profil}

       Oder der Abruf wurde abgewiesen. Dann im Updater nachsehen:
           ist dem Knoten das Modul zugeteilt, und ist seine Adresse frei?"
    fi
done

# ------------------------------------------------------------ 6. Starten

schritt "Module eintragen und starten"

module_schreiben "$MODULE"
gut "Modulliste: $(module_lesen)"

# Der Agent gehoert immer dazu - siehe module_aufloesen.
if [[ -d /etc/systemd/system ]]; then
    install -m644 "${HIER}/systemd/hj-agent.service" /etc/systemd/system/ 2>/dev/null || true
    install -m644 "${HIER}/systemd/hj-agent.timer"   /etc/systemd/system/ 2>/dev/null || true
    systemctl daemon-reload 2>/dev/null || true
    systemctl enable --now hj-agent.timer 2>/dev/null || true
    gut "Agent laeuft im Minutentakt"
fi

# Gibt es ueberhaupt ein Release?
#
# Ohne veroeffentlichte Abbilder gibt es nichts zu ziehen. Der Versuch endet
# in einer Kette irrefuehrender Meldungen - "denied", dann "unable to prepare
# context" -, weil Compose nach dem gescheiterten Pull versucht, aus dem
# Quellbaum zu bauen. Den gibt es auf einem Knoten nicht, und das ist auch
# richtig so: er soll Abbilder bekommen, keinen Quellcode.
#
# Bei "nur Agent" (leere Modulliste) faellt das weg: dieser Host betreibt
# nichts und braucht folglich kein Release.
if [[ -n "$MODULE" ]]; then
    MANIFEST="$(us_holen "/release/aktuell" 2>/dev/null || true)"
    if [[ -z "$MANIFEST" || "$MANIFEST" == *"noch nichts veroeffentlicht"* ]]; then
        fehler "Auf dem Update-Server ist noch kein Release veroeffentlicht.

       Ohne Abbilder kann dieser Knoten nichts starten. Auf dem
       Update-Server nachholen:

           bash update-server/veroeffentlichen.sh <version>

       Der Knoten ist bereits angemeldet und eingetragen - nach dem
       Veroeffentlichen genuegt hier:

           bash install-node.sh --modules ${MODULE// /,}"
    fi
fi

if [[ -z "$MODULE" ]]; then
    gut "Kein Modul - dieser Host ist unter Verwaltung, betreibt aber nichts."
    gut "Module lassen sich im Updater zuschalten; der Agent holt sie ab."
    echo
    exit 0
fi

dienste=""
for m in $MODULE; do
    dienste="${dienste} $(dienste_von "$m")"
done
sagen "Dienste: ${dienste# }"

if [[ -n "${dienste// /}" ]]; then
    # shellcheck disable=SC2086
    compose up -d $dienste
fi

schritt "Fertig"
sagen "Knoten ${HJ_KNOTEN_KENNUNG} mit [${MODULE}] eingerichtet."
sagen "Zustand ansehen: bash ${HIER}/agent/hj-agent.sh --zustand"
