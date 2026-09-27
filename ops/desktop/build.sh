#!/usr/bin/env bash
# Builds the Solid desktop installer for the machine it runs on (spec 060).
#
#   ops/desktop/build.sh            # the native installer: .deb on Linux, .dmg on macOS
#   ops/desktop/build.sh app-image  # just the unpacked application, useful for trying it out
#
# jpackage cannot cross-build: a Windows installer has to be built on Windows (ops/desktop/build.ps1), and a
# macOS one on macOS. .github/workflows/desktop.yml runs all three.
set -euo pipefail

TYPE="${1:-native}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "$ROOT/backend/pom.xml" | head -2 | tail -1)"
VERSION="${VERSION%-SNAPSHOT}"
OUT="$ROOT/backend/target/installer"

case "$(uname -s)" in
  Linux)  DESKTOP_OS=linux;  NATIVE_TYPE=deb ;;
  Darwin) NATIVE_TYPE=dmg
          if [ "$(uname -m)" = "arm64" ]; then DESKTOP_OS=macos-arm; else DESKTOP_OS=macos; fi ;;
  *) echo "Use ops/desktop/build.ps1 on Windows." >&2; exit 1 ;;
esac
[ "$TYPE" = "native" ] && TYPE="$NATIVE_TYPE"

echo "==> Building the web UI"
(cd "$ROOT/frontend" && npm ci && npm run build)

echo "==> Building the application (bundling PostgreSQL for $DESKTOP_OS)"
(cd "$ROOT/backend" && ./mvnw -B -DskipTests -Ddesktop.os="$DESKTOP_OS" package)

JAR="$(ls "$ROOT"/backend/target/solid-*.jar | grep -v original | head -1)"
STAGE="$ROOT/backend/target/jpackage-input"
rm -rf "$STAGE" "$OUT"; mkdir -p "$STAGE" "$OUT"
cp "$JAR" "$STAGE/solid.jar"

echo "==> jpackage ($TYPE)"
# --resource-dir carries THIRD-PARTY-NOTICES into every installed copy: the bundled OpenJDK and
# PostgreSQL binaries both require their license texts to travel with a binary distribution.
jpackage \
  --name Solid \
  --app-version "$VERSION" \
  --vendor "AE Software Solutions" \
  --description "Business and personal accounting with US tax" \
  --input "$STAGE" \
  --main-jar solid.jar \
  --main-class org.springframework.boot.loader.launch.JarLauncher \
  --java-options "-Dspring.profiles.active=desktop" \
  --java-options "-Xmx1g" \
  --resource-dir "$ROOT/ops/desktop/resources" \
  --dest "$OUT" \
  --type "$TYPE" \
  $( [ "$TYPE" = "deb" ] && echo "--linux-shortcut --linux-menu-group Office" ) \
  $( [ "$TYPE" = "dmg" ] && echo "--mac-package-name Solid" )

echo
echo "Built: $(ls "$OUT")"
echo "It carries its own Java and its own PostgreSQL; the person installing needs neither."