#!/usr/bin/env bash
# Makes sure the Firebase Android app exists and knows our signing fingerprints,
# then writes app/google-services.json. Needs FIREBASE_TOKEN; NOTED_SHA1 / NOTED_SHA256 optional.
set -euo pipefail
P=noted-ce451
PKG=com.ohav.noted
fb() { npx --yes firebase-tools@15 "$@" --project "$P" --non-interactive; }

app_id() { fb apps:list ANDROID --json | jq -r --arg p "$PKG" '[.result[] | select((.namespace // .packageName // "") == $p)][0].appId // empty'; }

ID=$(app_id)
if [ -z "$ID" ]; then
  echo "Creating Firebase Android app $PKG"
  fb apps:create ANDROID noted --package-name "$PKG" >/dev/null
  ID=$(app_id)
fi
[ -n "$ID" ] || { echo "::error::Firebase Android app not found / not created"; exit 1; }
echo "Firebase app: $ID"

have=$(fb apps:android:sha:list "$ID" --json | jq -r '.result[]?.shaHash // empty' | tr 'A-F' 'a-f' | tr -d ':')
for h in "${NOTED_SHA1:-}" "${NOTED_SHA256:-}"; do
  [ -n "$h" ] || continue
  h=$(echo "$h" | tr -d ':' | tr 'A-F' 'a-f')
  if ! grep -qx "$h" <<<"$have"; then echo "Adding SHA ${h:0:8}…"; fb apps:android:sha:create "$ID" "$h" >/dev/null; fi
done

fb apps:sdkconfig ANDROID "$ID" -o app/google-services.json >/dev/null
jq -e . app/google-services.json >/dev/null
if ! jq -e '[.client[].oauth_client[]? | select(.client_type == 3)] | length > 0' app/google-services.json >/dev/null; then
  echo "::warning::No web OAuth client in google-services.json — enable Google sign-in in Firebase Authentication."
fi

# Storage rules follow as soon as the owner has set up Storage in the console.
(cd "$(dirname "$0")/.." && fb deploy --only storage >/dev/null 2>&1 && echo "storage rules deployed") || echo "storage not set up yet"
