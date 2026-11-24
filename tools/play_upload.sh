#!/usr/bin/env bash
# Uploads a signed .aab to a Google Play track through the Play Developer API (DESIGN §6). No third-party code sees the
# service-account key: it signs a token request here (OAuth 2.0 for service accounts) with openssl, curl and jq.
#
#   PLAY_SERVICE_ACCOUNT_JSON='{...}' tools/play_upload.sh <package> <file.aab> [track]
#
# The track defaults to "internal". The very first .aab of a new app is uploaded by hand in Play Console: the API can
# only publish to an app Play already knows (docs/play/README.md).
set -euo pipefail

pkg=${1:?package name}
aab=${2:?path to the .aab}
track=${3:-internal}
: "${PLAY_SERVICE_ACCOUNT_JSON:?the service-account key (JSON) from GitHub secrets}"
api=https://androidpublisher.googleapis.com

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# Short-lived access token from the key. The private key exists only in this temporary folder, removed on exit.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"
jq -r .private_key <<<"$PLAY_SERVICE_ACCOUNT_JSON" > "$tmp/key.pem"
email=$(jq -r .client_email <<<"$PLAY_SERVICE_ACCOUNT_JSON")
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
claims=$(jq -cn --arg iss "$email" --argjson iat "$now" '{
  iss: $iss, scope: "https://www.googleapis.com/auth/androidpublisher",
  aud: "https://oauth2.googleapis.com/token", iat: $iat, exp: ($iat + 600)
}' | b64url)
sig=$(printf '%s.%s' "$header" "$claims" | openssl dgst -sha256 -sign "$tmp/key.pem" -binary | b64url)
rm -f "$tmp/key.pem"

if ! reply=$(curl -sS --fail-with-body https://oauth2.googleapis.com/token \
  --data-urlencode grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer \
  --data-urlencode assertion="$header.$claims.$sig"); then
  echo "Google refused the service-account key: $(jq -r '.error_description // .error // .' <<<"$reply")" >&2
  exit 1
fi
token=$(jq -r .access_token <<<"$reply")
if [ "${GITHUB_ACTIONS:-}" = true ]; then echo "::add-mask::$token"; fi

# One Play API call; on failure, Google's own error message (it never contains the token or the key).
play() {
  local out
  if ! out=$(curl -sS --fail-with-body --max-time 900 -H "Authorization: Bearer $token" "$@"); then
    echo "Play API: $(jq -r '.error.message // .' <<<"$out" 2>/dev/null || printf '%s' "$out")" >&2
    return 1
  fi
  printf '%s' "$out"
}

app="$api/androidpublisher/v3/applications/$pkg"
edit=$(play -X POST "$app/edits" | jq -r .id)
code=$(play -X POST -H "Content-Type: application/octet-stream" --data-binary "@$aab" \
  "$api/upload/androidpublisher/v3/applications/$pkg/edits/$edit/bundles?uploadType=media" | jq -r .versionCode)
jq -n --arg track "$track" --arg code "$code" '{track: $track, releases: [{versionCodes: [$code], status: "completed"}]}' |
  play -X PUT -H "Content-Type: application/json" --data-binary @- "$app/edits/$edit/tracks/$track" > /dev/null
play -X POST "$app/edits/$edit:commit" > /dev/null
echo "Build $code of $pkg is on Play's $track track."
