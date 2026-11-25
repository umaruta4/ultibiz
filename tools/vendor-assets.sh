#!/usr/bin/env bash
# vendor-assets.sh — download pinned front-end dependencies (no CDN at runtime).
# Idempotent: re-running verifies checksums and re-downloads only on mismatch.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR_DIR="${ROOT}/ultibiz-web/src/main/resources/static/vendor"
SUMS_FILE="${VENDOR_DIR}/SHA256SUMS"

BOOTSTRAP_VERSION="5.3.3"
HTMX_VERSION="2.0.4"

mkdir -p "${VENDOR_DIR}/bootstrap/css" "${VENDOR_DIR}/bootstrap/js" "${VENDOR_DIR}/htmx"

download() {
  local url="$1" dest="$2"
  echo "Downloading ${url}"
  curl -fsSL --retry 3 --retry-delay 2 -o "${dest}" "${url}"
}

download \
  "https://cdn.jsdelivr.net/npm/bootstrap@${BOOTSTRAP_VERSION}/dist/css/bootstrap.min.css" \
  "${VENDOR_DIR}/bootstrap/css/bootstrap.min.css"
download \
  "https://cdn.jsdelivr.net/npm/bootstrap@${BOOTSTRAP_VERSION}/dist/js/bootstrap.bundle.min.js" \
  "${VENDOR_DIR}/bootstrap/js/bootstrap.bundle.min.js"
download \
  "https://unpkg.com/htmx.org@${HTMX_VERSION}/dist/htmx.min.js" \
  "${VENDOR_DIR}/htmx/htmx.min.js"

# Compute path-relative sha256 sums and persist them.
(
  cd "${VENDOR_DIR}"
  sha256sum \
    bootstrap/css/bootstrap.min.css \
    bootstrap/js/bootstrap.bundle.min.js \
    htmx/htmx.min.js > SHA256SUMS
)

echo
echo "Verifying checksums..."
(
  cd "${VENDOR_DIR}"
  sha256sum --check SHA256SUMS
)

echo
echo "Vendored assets (${SUMS_FILE}):"
cat "${SUMS_FILE}"
