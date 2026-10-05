#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
VER="9.6.0"
BASE="$ROOT/.gradle-dist"
ZIP="$BASE/gradle-$VER-bin.zip"
HOME_DIR="$BASE/gradle-$VER"
mkdir -p "$BASE"
if [ ! -x "$HOME_DIR/bin/gradle" ]; then
  echo "Downloading Gradle $VER..."
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --connect-timeout 15 --max-time 180 "https://services.gradle.org/distributions/gradle-$VER-bin.zip" -o "$ZIP.part"
    mv "$ZIP.part" "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VER-bin.zip"
  else
    echo "Need curl or wget to bootstrap Gradle." >&2; exit 1
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ZIP" -d "$BASE"
  else
    echo "Need unzip to bootstrap Gradle." >&2; exit 1
  fi
fi
exec "$HOME_DIR/bin/gradle" "$@"
