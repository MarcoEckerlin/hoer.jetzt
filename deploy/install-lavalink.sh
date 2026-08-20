#!/usr/bin/env bash
#
# hoer.jetzt - Einzelinstaller fuer lavalink.
#
# Duenne Huelle um install-node.sh. Die Logik liegt dort und nur dort:
# zwei Installer, die dasselbe tun, laufen frueher oder spaeter auseinander -
# und zwar an der Stelle, an der es niemand nachprueft.
#
#   bash install-lavalink.sh --kennung <name> --token <hj-...>
#
# Alle weiteren Angaben werden unveraendert durchgereicht.

set -euo pipefail
HIER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${HIER}/install-node.sh" --modules lavalink "$@"
