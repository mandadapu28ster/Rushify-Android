#!/usr/bin/env bash
# One-off: copies the local signing key into GitHub Actions secrets so releases are signed with the same key every time.
# Needs the GitHub CLI logged in as someone who can administer the repo:  gh auth login
set -euo pipefail
REPO="${1:-mandadapu28ster/Rushify-Android}"
cd "$(dirname "$0")/.."
[ -f keystore.properties ] || { echo "keystore.properties not found in the project root" >&2; exit 1; }
prop() { grep "^$1=" keystore.properties | head -1 | cut -d= -f2-; }
JKS="$(prop storeFile)"
[ -f "$JKS" ] || { echo "Keystore file not found: $JKS" >&2; exit 1; }
base64 < "$JKS" | tr -d '\n' | gh secret set RUSHIFY_KEYSTORE_BASE64 -R "$REPO"
printf '%s' "$(prop storePassword)" | gh secret set RUSHIFY_KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$(prop keyAlias)"      | gh secret set RUSHIFY_KEY_ALIAS -R "$REPO"
printf '%s' "$(prop keyPassword)"   | gh secret set RUSHIFY_KEY_PASSWORD -R "$REPO"
echo "Done. Secrets set on $REPO:"; gh secret list -R "$REPO"
