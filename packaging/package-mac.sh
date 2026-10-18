#!/usr/bin/env bash
# Builds Budgetinator.app and signs it with a self-signed certificate that is generated on
# first run into ./.signing (gitignored). Output: dist/Budgetinator.app
cd "$(dirname "$0")/.."

# sdkman is not -u clean, so load it before enabling strict mode
source "$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null || true
sdk use java 25.0.1-tem >/dev/null 2>&1 || true
set -euo pipefail

SIGN_DIR=.signing
KEYCHAIN="$PWD/$SIGN_DIR/budgetinator.keychain-db"
KC_PASS=budgetinator   # protects only a throwaway, self-signed key
CN="Budgetinator Self-Signed"

if [[ ! -f "$KEYCHAIN" ]]; then
  echo "==> Generating self-signed code-signing certificate"
  mkdir -p "$SIGN_DIR"; chmod 700 "$SIGN_DIR"
  cat > "$SIGN_DIR/openssl.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $CN
O = Budgetinator
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
EOF
  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout "$SIGN_DIR/key.pem" -out "$SIGN_DIR/cert.pem" -config "$SIGN_DIR/openssl.cnf"
  openssl pkcs12 -export -inkey "$SIGN_DIR/key.pem" -in "$SIGN_DIR/cert.pem" \
    -out "$SIGN_DIR/identity.p12" -passout "pass:$KC_PASS" -name "$CN"
  security create-keychain -p "$KC_PASS" "$KEYCHAIN"
  security set-keychain-settings "$KEYCHAIN"            # no auto-lock
  security unlock-keychain -p "$KC_PASS" "$KEYCHAIN"
  security import "$SIGN_DIR/identity.p12" -k "$KEYCHAIN" -P "$KC_PASS" -T /usr/bin/codesign
  security set-key-partition-list -S apple-tool:,apple: -s -k "$KC_PASS" "$KEYCHAIN" >/dev/null
fi
security unlock-keychain -p "$KC_PASS" "$KEYCHAIN"

echo "==> Building app image"
./gradlew createDistributable --console=plain -q
APP_SRC=$(find build/compose/binaries -name 'Budgetinator.app' -maxdepth 4 | head -1)
[[ -n "$APP_SRC" ]] || { echo "Budgetinator.app not found"; exit 1; }

rm -rf dist; mkdir -p dist
ditto "$APP_SRC" dist/Budgetinator.app

echo "==> Signing"
HASH=$(security find-certificate -c "$CN" -Z "$KEYCHAIN" | awk '/SHA-1 hash:/ {print $3; exit}')
# Sign nested code first (dylibs, jspawnhelper, launcher), then the bundle.
find dist/Budgetinator.app -type f \( -name '*.dylib' -o -name '*.jnilib' -o -perm -111 \) -print0 |
  xargs -0 -n1 codesign --force --keychain "$KEYCHAIN" --sign "$HASH" --timestamp=none 2>/dev/null || true
codesign --force --deep --keychain "$KEYCHAIN" --sign "$HASH" --timestamp=none dist/Budgetinator.app
codesign --verify --deep --strict --verbose=2 dist/Budgetinator.app
echo "==> Done: dist/Budgetinator.app"
