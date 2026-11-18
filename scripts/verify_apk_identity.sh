#!/usr/bin/env bash
# Verify APK package identity, versionCode, cryptographic signature, and signer continuity.
# For release APKs, the caller MUST supply the expected SHA256 fingerprint of the
# user's original release certificate. No private keys are exposed by this script.
set -euo pipefail

if [[ "$#" -ne 4 ]]; then
  echo "Usage: bash scripts/verify_apk_identity.sh APK EXPECTED_PACKAGE EXPECTED_VERSION EXPECTED_CERT_SHA256" >&2
  exit 2
fi
apk="$1"
expected_package="$2"
expected_version="$3"
expected_cert="$(echo "$4" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
[[ -s "$apk" ]] || { echo "APK missing or empty: $apk" >&2; exit 1; }
[[ "$expected_cert" =~ ^[a-f0-9]{64}$ ]] || { echo "Expected SHA256 fingerprint is not 64 hex digits" >&2; exit 1; }

build_tools="$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n1)"
[[ -x "$build_tools/apksigner" && -x "$build_tools/aapt" ]] || {
  echo "Could not locate Android build tools apksigner/aapt" >&2
  exit 1
}

"$build_tools/apksigner" verify --verbose --print-certs "$apk" >/dev/null
identity="$("$build_tools/aapt" dump badging "$apk" | grep '^package: ' | head -n1)"
[[ "$identity" == *"name='$expected_package'"* ]] || {
  echo "Wrong package identity: $identity" >&2
  exit 1
}
[[ "$identity" == *"versionCode='$expected_version'"* ]] || {
  echo "Wrong APK version code: $identity" >&2
  exit 1
}
# Accept harmless leading whitespace or tool formatting differences, but never
# accept an absent or malformed signer fingerprint.
cert_output="$("$build_tools/apksigner" verify --print-certs "$apk")"
actual_cert="$(printf '%s\n' "$cert_output" |
  sed -nE 's/^.*Signer[[:space:]]+#[0-9]+[[:space:]]+certificate[[:space:]]+SHA-256[[:space:]]+digest:[[:space:]]*//p' |
  head -n1 | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
[[ "$actual_cert" =~ ^[a-f0-9]{64}$ ]] || {
  echo "Unable to extract a valid SHA-256 signing certificate from APK" >&2
  # The certificate is PUBLIC. Showing apksigner labels helps diagnose SDK
  # output changes without leaking the signing key or any password.
  printf '%s\n' "$cert_output" | grep -iE '(signer|certificate|sha.?256)' | head -n 12 >&2 || true
  exit 1
}
[[ "$actual_cert" == "$expected_cert" ]] || {
  echo "APK signing certificate mismatch; refusing release" >&2
  exit 1
}
echo "PASS: package=$expected_package versionCode=$expected_version signerSHA256=$actual_cert"
