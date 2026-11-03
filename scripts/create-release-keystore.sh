#!/usr/bin/env bash
# Erzeugt den Release-Signaturschlüssel für SmbBackup und trägt ihn als GitHub-Secrets ein.
#
# Aufruf:  ./scripts/create-release-keystore.sh [Zielordner]
#   Zielordner: wo die .jks-Datei landet (Standard: ~/smbbackup-keys, liegt bewusst außerhalb des Repos)
#
# Ist die GitHub-CLI (gh) installiert und angemeldet, werden die vier Secrets direkt gesetzt.
# Sonst schreibt das Script den Base64-Text in eine Datei und zeigt, was von Hand einzutragen ist.

set -euo pipefail

ALIAS="smbbackup"
DEFAULT_REPO="astral1977/SmbBackup"
TARGET_DIR="${1:-$HOME/smbbackup-keys}"
KEYSTORE="$TARGET_DIR/smbbackup-release.jks"

fail() { echo "Fehler: $*" >&2; exit 1; }

# --- Voraussetzungen ---------------------------------------------------------
if ! command -v keytool >/dev/null 2>&1; then
    # Android Studio bringt ein eigenes Java mit
    for candidate in "$HOME"/android-studio/jbr/bin/keytool /opt/android-studio/jbr/bin/keytool \
                     /snap/android-studio/current/jbr/bin/keytool; do
        if [ -x "$candidate" ]; then PATH="$(dirname "$candidate"):$PATH"; break; fi
    done
fi
command -v keytool >/dev/null 2>&1 || fail "keytool nicht gefunden. Java installieren, z. B.: sudo apt install openjdk-17-jdk-headless"
command -v base64 >/dev/null 2>&1 || fail "base64 nicht gefunden."

[ -e "$KEYSTORE" ] && fail "$KEYSTORE existiert schon. Nicht überschrieben – ein vorhandener Schlüssel darf nicht verloren gehen."

# --- Passwort ----------------------------------------------------------------
while true; do
    read -rsp "Passwort für den Schlüssel (mind. 6 Zeichen): " PW1; echo
    read -rsp "Passwort wiederholen: " PW2; echo
    if [ "$PW1" != "$PW2" ]; then echo "Passwörter stimmen nicht überein."; continue; fi
    if [ "${#PW1}" -lt 6 ]; then echo "Zu kurz."; continue; fi
    break
done
unset PW2

# --- Schlüssel erzeugen ------------------------------------------------------
mkdir -p "$TARGET_DIR"
chmod 700 "$TARGET_DIR"

# Passwort über Umgebungsvariable statt Kommandozeile, damit es nicht in der Prozessliste steht
export SMBBACKUP_PW="$PW1"
keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=SmbBackup" \
    -storepass:env SMBBACKUP_PW -keypass:env SMBBACKUP_PW >/dev/null
chmod 600 "$KEYSTORE"
echo "Schlüssel erzeugt: $KEYSTORE"
keytool -list -v -keystore "$KEYSTORE" -storepass:env SMBBACKUP_PW -alias "$ALIAS" \
    | grep -E "SHA256:|Owner:|Eigentümer:" || true
unset SMBBACKUP_PW

KEYSTORE_B64="$(base64 -w0 "$KEYSTORE")"

# --- GitHub-Secrets ----------------------------------------------------------
REPO="$DEFAULT_REPO"
if git -C "$(dirname "$0")" remote get-url origin >/dev/null 2>&1; then
    url="$(git -C "$(dirname "$0")" remote get-url origin)"
    parsed="$(echo "$url" | sed -E 's#^(https://github.com/|git@github.com:)##; s#\.git$##')"
    [[ "$parsed" == */* ]] && REPO="$parsed"
fi

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    read -rp "Secrets jetzt in $REPO eintragen? [J/n] " answer
    if [[ ! "$answer" =~ ^[nN] ]]; then
        printf '%s' "$KEYSTORE_B64" | gh secret set SMBBACKUP_KEYSTORE_BASE64 --repo "$REPO"
        printf '%s' "$PW1"          | gh secret set SMBBACKUP_KEYSTORE_PASSWORD --repo "$REPO"
        printf '%s' "$ALIAS"        | gh secret set SMBBACKUP_KEY_ALIAS --repo "$REPO"
        printf '%s' "$PW1"          | gh secret set SMBBACKUP_KEY_PASSWORD --repo "$REPO"
        unset PW1
        echo
        echo "Fertig. Secrets in $REPO gesetzt."
        echo "Jetzt den Build neu starten (Actions → Build → Run workflow) oder etwas pushen."
        echo "Im Schritt „Signatur anzeigen“ muss dann CN=SmbBackup stehen."
        echo
        echo "WICHTIG: $KEYSTORE und das Passwort sicher aufbewahren (Backup + Passwort-Manager)."
        exit 0
    fi
fi
unset PW1

B64_FILE="$TARGET_DIR/smbbackup-release.jks.base64.txt"
printf '%s' "$KEYSTORE_B64" > "$B64_FILE"
chmod 600 "$B64_FILE"

cat <<EOF

GitHub-CLI nicht verfügbar oder nicht angemeldet – bitte von Hand eintragen:
https://github.com/$REPO/settings/secrets/actions  →  New repository secret

  SMBBACKUP_KEYSTORE_BASE64    Inhalt von $B64_FILE
  SMBBACKUP_KEYSTORE_PASSWORD  dein Passwort
  SMBBACKUP_KEY_ALIAS          $ALIAS
  SMBBACKUP_KEY_PASSWORD       dasselbe Passwort

Base64-Datei in die Zwischenablage (falls xclip installiert):
  xclip -selection clipboard < "$B64_FILE"

Nach dem Eintragen die Base64-Datei löschen:  rm "$B64_FILE"
Die .jks-Datei und das Passwort sicher aufbewahren (Backup + Passwort-Manager).
EOF
