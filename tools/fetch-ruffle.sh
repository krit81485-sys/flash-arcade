#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEST="$ROOT_DIR/app/src/main/assets/ruffle"
ARCHIVE="${TMPDIR:-/tmp}/ruffle-0.7.1-web-selfhosted.zip"
URL="https://github.com/ruffle-rs/ruffle/releases/download/v0.7.1/ruffle-0.7.1-web-selfhosted.zip"
SHA256="ef12edbee135d581a9cd523837d746da30a07a64dbf1cf472d2e472c656f4b5e"

mkdir -p "$DEST"
curl --fail --location --retry 2 "$URL" --output "$ARCHIVE"
printf '%s  %s\n' "$SHA256" "$ARCHIVE" | sha256sum --check -
unzip -o -j "$ARCHIVE" \
  'ruffle.js' \
  '*.wasm' \
  'core.*.js' \
  'LICENSE_*' \
  -d "$DEST" >/dev/null

if [ ! -s "$DEST/ruffle.js" ] || ! find "$DEST" -maxdepth 1 -name '*.wasm' -print -quit | grep -q .; then
  echo "The downloaded Ruffle archive is missing required runtime files." >&2
  exit 1
fi

echo "Ruffle 0.7.1 runtime installed in app/src/main/assets/ruffle"
