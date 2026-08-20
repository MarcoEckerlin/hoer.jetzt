#!/usr/bin/env bash
#
# hoer.jetzt - Update-Server vollstaendig neu aufsetzen.
#
#   bash neu-aufsetzen.sh
#   bash neu-aufsetzen.sh --ja        # ohne Rueckfrage
#
# Loescht Container UND Volumes dieses Projekts und startet danach
# einrichten.sh. Alles, was der Server wusste, ist danach weg.
#
# ---------------------------------------------------------------------------
# Warum nicht einfach "docker volume prune"
#
# Weil das jedes ungenutzte Volume auf dem Host trifft - auch die von
# Diensten, die mit hoer.jetzt nichts zu tun haben. Auf einer Maschine, auf
# der nur dieser Stack laeuft, faellt das nicht auf; auf jeder anderen ist es
# ein stiller Datenverlust.
#
# "docker compose down --volumes" ist auf das Projekt begrenzt. Das genuegt.
# ---------------------------------------------------------------------------

set -euo pipefail

HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="${HIER}/docker-compose.yml"
OHNE_RUECKFRAGE=false

[[ "${1:-}" == "--ja" ]] && OHNE_RUECKFRAGE=true

[[ "$(id -u)" -eq 0 ]] || { echo "Bitte als root starten." >&2; exit 1; }
[[ -f "$COMPOSE" ]] || { echo "Nicht gefunden: ${COMPOSE}" >&2; exit 1; }

echo
echo "  ================================================================"
echo "   Update-Server vollstaendig neu aufsetzen"
echo "  ================================================================"
echo

# Erst zeigen, was da ist - dann fragen. Eine Warnung ueber Dinge, die man
# nicht sieht, liest sich wie eine Formalie.
echo "  Diese Volumes werden geloescht:"
echo
gefunden=0
while read -r vol; do
    [[ -z "$vol" ]] && continue
    groesse="$(docker system df -v --format '{{range .Volumes}}{{.Name}} {{.Size}}
{{end}}' 2>/dev/null | awk -v v="$vol" '$1==v{print $2}')"
    printf '    %-40s %s\n' "$vol" "${groesse:-?}"
    gefunden=1
done < <(docker volume ls -q --filter "label=com.docker.compose.project=$(basename "$HIER")" 2>/dev/null)

if [[ "$gefunden" -eq 0 ]]; then
    echo "    (keine gefunden - der Stack lief hier wohl noch nie)"
fi

cat <<'WARNUNG'

  Damit ist unwiderruflich weg:

    ausliefern      der Tresor und alle Release-Manifeste
    forgejo-daten   saemtliche veroeffentlichten Abbilder und das Konto
    updater-daten   alle Knoten, Freigaben und das Zugriffsprotokoll
    caddy-*         Zertifikatszwischenspeicher, unkritisch
    runner-daten    die Anmeldung des CI-Runners

  Es gibt keine Sicherung davon, die dieses Skript anlegt.

  Danach ist von Hand noetig:

    bash tresor.sh fuellen voll
    bash tresor.sh fuellen lavalink
    bash veroeffentlichen.sh <version>     Abbilder neu bauen
    jeden Knoten unter "Verwalten" neu anlegen und neu aufsetzen

WARNUNG

if ! $OHNE_RUECKFRAGE; then
    printf '  Zum Fortfahren "loeschen" eintippen: '
    read -r antwort
    if [[ "$antwort" != "loeschen" ]]; then
        echo "  Abgebrochen - nichts angefasst."
        exit 1
    fi
fi

echo
echo "  Container und Volumes entfernen..."
# --remove-orphans faengt Container ab, die aus einer aelteren Fassung der
# Compose-Datei stammen - etwa den alten "ai-radio", der nach der Umbenennung
# in "ki-radio" sonst weiterlaeuft und seinen Port belegt haelt.
docker compose -f "$COMPOSE" down --volumes --remove-orphans || true

# Die .env muss weg, sonst fragt einrichten.sh, ob es neu aufsetzen soll, und
# ein "nein" laesst einen halb abgeraeumten Stand zurueck.
if [[ -f "${HIER}/.env" ]]; then
    rm -f "${HIER}/.env"
    echo "  .env entfernt."
fi

echo "  Fertig. Weiter mit einrichten.sh."
echo
exec bash "${HIER}/einrichten.sh"
