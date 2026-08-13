#!/usr/bin/env bash
#
# hoer.jetzt - Lesezugang zu einem privaten Repository einrichten.
#
#   bash zugang-einrichten.sh
#
# Ist das Repository privat, kommt kein Host mehr anonym an den Code: weder
# install.sh beim Klonen noch auto-update.sh in der Nacht. Dieses Skript legt
# einen Deploy-Key an, stellt alle vorhandenen Arbeitsverzeichnisse auf SSH um
# und prueft, ob es klappt.
#
# Ein Deploy-Key gilt fuer genau ein Repository und laesst sich nur lesend
# einrichten. Pro Host ein eigener Schluessel - dann laesst sich ein einzelner
# Host abschalten, ohne alle anderen auszusperren.

set -euo pipefail

ARBEIT="${ARBEIT:-/opt/hoerjetzt}"
REPO_SSH="${REPO_SSH:-git@github.com:MarcoEckerlin/hoer.jetzt.git}"
SCHLUESSEL="${SCHLUESSEL:-/root/.ssh/hoerjetzt_deploy}"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[1;33m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || fail "Bitte als root starten."
command -v ssh-keygen >/dev/null 2>&1 || fail "ssh-keygen fehlt (Paket openssh-client)."

# ------------------------------------------------------------------ 1

step "Schluessel"
mkdir -p "$(dirname "$SCHLUESSEL")"
chmod 700 "$(dirname "$SCHLUESSEL")"

if [[ -f "$SCHLUESSEL" ]]; then
    info "Vorhanden: ${SCHLUESSEL}"
else
    # Ohne Passphrase, weil ihn ein Timer um 03:00 benutzt - eine Passphrase
    # muesste dann irgendwo im Klartext liegen und waere keine mehr.
    ssh-keygen -t ed25519 -N "" -C "hoerjetzt-$(hostname -s 2>/dev/null || echo host)" \
        -f "$SCHLUESSEL" >/dev/null
    info "Neu erzeugt: ${SCHLUESSEL}"
fi
chmod 600 "$SCHLUESSEL"

# ------------------------------------------------------------------ 2

step "Bei GitHub eintragen"
cat <<HINWEIS

  Diesen oeffentlichen Schluessel eintragen unter:

      https://github.com/MarcoEckerlin/hoer.jetzt/settings/keys
      -> "Add deploy key"
      -> Titel frei waehlen, "Allow write access" NICHT ankreuzen

HINWEIS
cat "${SCHLUESSEL}.pub"
echo
read -r -p "    Eingetragen? Dann Enter druecken. " _ || true

# ------------------------------------------------------------------ 3

step "SSH pruefen"
export GIT_SSH_COMMAND="ssh -i ${SCHLUESSEL} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"

if git ls-remote "$REPO_SSH" >/dev/null 2>&1; then
    info "Zugang steht."
else
    fail "Kein Zugang. Schluessel eingetragen? Richtiges Repository in REPO_SSH?"
fi

# ------------------------------------------------------------------ 4

step "Arbeitsverzeichnisse umstellen"
GEFUNDEN=0
for zweig in main core ai-radio lavalink; do
    ziel="${ARBEIT}/${zweig}"
    [[ -d "${ziel}/.git" ]] || continue
    GEFUNDEN=$((GEFUNDEN + 1))

    git config --global --add safe.directory "$ziel" 2>/dev/null || true
    git -C "$ziel" remote set-url origin "$REPO_SSH"
    # Am Repository hinterlegt, nicht global: so stoert es keinen anderen
    # Klon auf derselben Maschine.
    git -C "$ziel" config core.sshCommand "ssh -i ${SCHLUESSEL} -o IdentitiesOnly=yes"
    info "$(printf '%-9s %s' "$zweig" "auf SSH umgestellt")"
done

if [[ "$GEFUNDEN" -eq 0 ]]; then
    warn "Keine Arbeitsverzeichnisse unter ${ARBEIT} gefunden."
    warn "Beim ersten Klonen dann:"
    warn "    REPO=${REPO_SSH} bash install.sh"
fi

# ------------------------------------------------------------------ 5

step "Vorgabe fuer kuenftige Laeufe"
UMGEBUNG="${ARBEIT}/.env"
if [[ -f "$UMGEBUNG" ]]; then
    if grep -q '^REPO=' "$UMGEBUNG"; then
        sed -i "s|^REPO=.*|REPO=${REPO_SSH}|" "$UMGEBUNG"
    else
        printf 'REPO=%s\n' "$REPO_SSH" >> "$UMGEBUNG"
    fi
    info "REPO in ${UMGEBUNG} gesetzt."
fi

step "Fertig"
info "Probe: bash ${ARBEIT}/main/deploy/auto-update.sh --pruefen"
echo
warn "Der private Schluessel bleibt auf diesem Host. Nicht kopieren -"
warn "jeder weitere Host bekommt seinen eigenen, dann laesst sich einer"
warn "abschalten, ohne alle auszusperren."
echo
