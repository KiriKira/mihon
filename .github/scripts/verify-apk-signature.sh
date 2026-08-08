#!/usr/bin/env bash

set -euo pipefail

: "${KEYSTORE_FILE:?KEYSTORE_FILE is required}"
: "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is required}"
: "${KEY_ALIAS:?KEY_ALIAS is required}"
: "${EXPECTED_CERT_SHA256:?EXPECTED_CERT_SHA256 is required}"
: "${APKSIGNER:?APKSIGNER is required}"

normalize() {
  tr -d ':[:space:]\r\n' | tr '[:lower:]' '[:upper:]'
}

expected="$(printf '%s' "$EXPECTED_CERT_SHA256" | normalize)"
if [[ ! "$expected" =~ ^[0-9A-F]{64}$ ]]; then
  echo "EXPECTED_CERT_SHA256 must contain a SHA-256 certificate fingerprint." >&2
  exit 1
fi

keystore_fingerprint="$(keytool -list -v \
  -storetype PKCS12 \
  -keystore "$KEYSTORE_FILE" \
  -storepass "$KEYSTORE_PASSWORD" \
  -alias "$KEY_ALIAS" |
  sed -nE 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n 1 | normalize)"

if [[ "$keystore_fingerprint" != "$expected" ]]; then
  echo "Keystore certificate does not match EXPECTED_CERT_SHA256." >&2
  echo "Expected: $expected" >&2
  echo "Actual:   $keystore_fingerprint" >&2
  exit 1
fi

mapfile -t apks < <(find dist -maxdepth 1 -type f -name '*.apk' -print | sort)
if (( ${#apks[@]} == 0 )); then
  echo "No APKs were found under dist/." >&2
  exit 1
fi

for apk in "${apks[@]}"; do
  cert="$("$APKSIGNER" verify --verbose --print-certs "$apk" 2>&1 |
    grep -m 1 -Eo '[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){31}' | normalize || true)"
  if [[ "$cert" != "$expected" ]]; then
    echo "APK certificate mismatch: $apk" >&2
    echo "Expected: $expected" >&2
    echo "Actual:   ${cert:-<missing>}" >&2
    exit 1
  fi
done

printf '%s\n' "$expected" > dist/SIGNING_CERT_SHA256
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "sha256=$expected" >> "$GITHUB_OUTPUT"
fi

echo "Verified ${#apks[@]} APK(s) with certificate SHA-256 $expected."
