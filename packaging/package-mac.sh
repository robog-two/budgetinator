#!/usr/bin/env bash
# Local release build: ProGuard-shrunk Budgetinator.app + drag-to-Applications DMG in ./dist.
#
#   packaging/package-mac.sh            # signs if a "Developer ID Application" identity is in
#                                       # your login keychain, otherwise builds unsigned
#   packaging/package-mac.sh --no-sign  # force an unsigned build
#
# Optional environment:
#   MAC_SIGNING_IDENTITY  which identity to use when you have several
#                         (e.g. "Developer ID Application: Samuel Knight (ABCDE12345)")
#   NOTARY_PROFILE        a notarytool keychain profile (created once with
#                         `xcrun notarytool store-credentials <name> ...`); when set, the app and
#                         DMG are notarized and stapled, like the CI release build.
#   APP_VERSION           overrides appVersion from gradle.properties
#
# Official releases are built by .github/workflows/release.yml; this is for local testing.
cd "$(dirname "$0")/.."

# sdkman is not -u clean, so load it before enabling strict mode
# shellcheck source=/dev/null
source "$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null || true
sdk use java 25.0.1-tem >/dev/null 2>&1 || true
set -euo pipefail

SIGN=auto
[[ "${1:-}" == "--no-sign" ]] && SIGN=no

VERSION=${APP_VERSION:-$(sed -n 's/^appVersion=//p' gradle.properties)}
case "$(uname -m)" in arm64) ARCH=arm64 ;; *) ARCH=x64 ;; esac

IDENTITY=""
if [[ "$SIGN" == auto ]]; then
  IDENTITY=${MAC_SIGNING_IDENTITY:-$(security find-identity -v -p codesigning |
    sed -n 's/.*"\(Developer ID Application: .*\)"$/\1/p' | head -1)}
fi

GRADLE_ARGS=(createReleaseDistributable "-PappVersion=$VERSION" --console=plain -q)
if [[ -n "$IDENTITY" ]]; then
  echo "==> Building $VERSION ($ARCH), signing as: $IDENTITY"
  GRADLE_ARGS+=(-Pmac.sign=true "-Pmac.signing.identity=$IDENTITY")
else
  echo "==> Building $VERSION ($ARCH), unsigned (no Developer ID Application identity found)"
fi
./gradlew "${GRADLE_ARGS[@]}"

APP=build/compose/binaries/main-release/app/Budgetinator.app
[[ -d "$APP" ]] || { echo "Budgetinator.app not found at $APP"; exit 1; }

rm -rf dist; mkdir -p dist
ditto "$APP" dist/Budgetinator.app
APP=dist/Budgetinator.app
DMG="dist/Budgetinator-$VERSION-$ARCH.dmg"
ZIP="dist/Budgetinator-$VERSION-$ARCH.zip"

notarize() {
  xcrun notarytool submit "$1" --keychain-profile "$NOTARY_PROFILE" --wait
}

if [[ -n "$IDENTITY" ]]; then
  codesign --verify --deep --strict --verbose=2 "$APP"
  if [[ -n "${NOTARY_PROFILE:-}" ]]; then
    echo "==> Notarizing app"
    ditto -c -k --keepParent "$APP" dist/notarize.zip
    notarize dist/notarize.zip
    rm dist/notarize.zip
    xcrun stapler staple "$APP"
  fi
fi

echo "==> Creating $DMG and $ZIP"
packaging/make-dmg.sh "$APP" "$DMG"
ditto -c -k --keepParent "$APP" "$ZIP"

if [[ -n "$IDENTITY" ]]; then
  codesign --force --timestamp --sign "$IDENTITY" "$DMG"
  if [[ -n "${NOTARY_PROFILE:-}" ]]; then
    echo "==> Notarizing DMG"
    notarize "$DMG"
    xcrun stapler staple "$DMG"
    spctl --assess --type execute --verbose=2 "$APP"
  fi
fi
echo "==> Done:"
ls -lh dist
