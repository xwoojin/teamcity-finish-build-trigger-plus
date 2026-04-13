#!/bin/bash
# Build script with auto-versioning (YYMMDD.N format)
# Usage: ./build.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ROOT_POM="pom.xml"
SERVER_POM="server/pom.xml"
PLUGIN_XML="teamcity-plugin.xml"

# Get current version from root pom.xml (first <version> tag)
CURRENT_VERSION=$(sed -n 's/.*<version>\([0-9]*\.[0-9]*\)<\/version>.*/\1/p' "$ROOT_POM" | head -1)
CURRENT_DATE="${CURRENT_VERSION%%.*}"
CURRENT_N="${CURRENT_VERSION##*.}"

# Today's date in YYMMDD format
TODAY=$(date +%y%m%d)

if [ "$CURRENT_DATE" = "$TODAY" ]; then
    NEW_N=$((CURRENT_N + 1))
else
    NEW_N=1
fi

NEW_VERSION="${TODAY}.${NEW_N}"

echo "Version: ${CURRENT_VERSION} -> ${NEW_VERSION}"

# Update version in all 3 files
sed -i "s|<version>${CURRENT_VERSION}</version>|<version>${NEW_VERSION}</version>|" "$ROOT_POM"
sed -i "s|<version>${CURRENT_VERSION}</version>|<version>${NEW_VERSION}</version>|" "$SERVER_POM"
sed -i "s|<version>${CURRENT_VERSION}</version>|<version>${NEW_VERSION}</version>|" "$PLUGIN_XML"

# Clean dist and build
rm -rf dist
mvn clean verify -q

echo "Build complete: dist/finish-build-trigger-plus.zip (v${NEW_VERSION})"
