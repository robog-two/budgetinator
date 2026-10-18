#!/usr/bin/env bash
# Usage: packaging/make-dmg.sh <path/to/Budgetinator.app> <out.dmg>
#
# Builds a drag-to-Applications disk image: the app next to an "Applications" symlink.
# Done with plain hdiutil (instead of jpackage's DMG, which needs Finder scripting and silently
# omits the Applications link when run headless) so it works the same locally and on CI, and so
# the image can be built from an app that has already been notarized and stapled.
set -euo pipefail

APP=${1:?usage: make-dmg.sh <app> <out.dmg>}
OUT=${2:?usage: make-dmg.sh <app> <out.dmg>}
NAME=$(basename "$APP" .app)

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

ditto "$APP" "$STAGE/$NAME.app"
ln -s /Applications "$STAGE/Applications"

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
# ULMO (LZMA) is the smallest format and is readable on macOS 10.15+ (the app needs 12+).
hdiutil create -volname "$NAME" -srcfolder "$STAGE" -fs HFS+ -format ULMO -ov "$OUT" >/dev/null
echo "Created $OUT"
