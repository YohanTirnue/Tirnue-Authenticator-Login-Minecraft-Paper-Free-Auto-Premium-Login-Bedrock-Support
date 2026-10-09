#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTANCE="/opt/mcsmanager/production-code/daemon/data/InstanceData/9bf0a2566ecb4ab49e0134764b855f41"

CP_JARS=$(find "$INSTANCE/libraries" -name "*.jar" 2>/dev/null | tr '\n' ':')
PAPER_API=$(find "$INSTANCE/libraries" -name "*paper-api*.jar" 2>/dev/null | head -n 1)
PACKETEVENTS_JAR="$INSTANCE/plugins/packetevents-spigot-2.13.0 (1).jar"
FLOODGATE_JAR="$INSTANCE/plugins/floodgate-spigot.jar"
PAPI_JAR="$INSTANCE/plugins/PlaceholderAPI-2.12.3.jar"
SERVER_JAR="$INSTANCE/versions/26.2/paper-26.2.jar"

SRC_DIR="$SCRIPT_DIR/src"
BIN_DIR="$SCRIPT_DIR/bin"
RES_DIR="$SCRIPT_DIR/resources"
OUT_JAR="$SCRIPT_DIR/TirnueAuth.jar"
DEPLOY_JAR="$INSTANCE/plugins/TirnueAuth.jar"

echo "=== TirnueAuth Build ==="

rm -rf "$BIN_DIR"
mkdir -p "$BIN_DIR"

echo "[1/4] Compiling..."
find "$SRC_DIR" -name "*.java" | xargs javac --release 21 \
  -cp "${CP_JARS}${PAPER_API}:${PACKETEVENTS_JAR}:${FLOODGATE_JAR}:${PAPI_JAR}:${SERVER_JAR}" \
  -d "$BIN_DIR"
echo "      Compiled successfully."

echo "[2/4] Copying resources..."
cp "$RES_DIR/plugin.yml" "$BIN_DIR/"
cp "$RES_DIR/config.yml" "$BIN_DIR/"
cp "$RES_DIR/messages_en.yml" "$BIN_DIR/" 2>/dev/null || true
cp "$RES_DIR/englishlanguage.config" "$BIN_DIR/" 2>/dev/null || true

echo "[3/4] Packaging JAR..."
jar -cf "$OUT_JAR" -C "$BIN_DIR" .
echo "      Created: $OUT_JAR"

echo "[4/4] Deploying to server plugins..."
cp "$OUT_JAR" "$DEPLOY_JAR"
echo "      Deployed to: $DEPLOY_JAR"

echo ""
echo "=== Build complete! ==="
