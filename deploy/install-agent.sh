#!/usr/bin/env bash
#
# hoer.jetzt - nur den Agenten aufsetzen.
#
#   bash install-agent.sh --kennung <name> --token <hj-...>
#
# ---------------------------------------------------------------------------
# Wozu das gut ist
#
# Abschnitt 21 verlangt ihn als eigenen Installer. Fachlich ist er der
# Sonderfall "Knoten ohne Module": ein Host, der sich anmeldet, seinen
# Schluessel hinterlegt und sich meldet - aber nichts betreibt.
#
# Das ist kein akademischer Fall. Er ist der erste Schritt der Migration aus
# Abschnitt 69: einen bestehenden Server unter Verwaltung nehmen, ohne an
# seinen laufenden Diensten etwas zu aendern. Module lassen sich danach im
# Updater zuschalten, und beim naechsten Lauf des Agenten kommen sie hoch.
#
# Ohne Module holt der Knoten keinen Tresor und startet keinen Stack - er
# braucht also weder Zugangsdaten noch ein veroeffentlichtes Release.
# ---------------------------------------------------------------------------

set -euo pipefail
HIER="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
exec bash "${HIER}/install-node.sh" --modules agent "$@"
