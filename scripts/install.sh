#!/usr/bin/env bash
set -euo pipefail

# Install arkhitekton CLI
#
# Download from GitHub Releases (default):
#   curl -fsSL https://raw.githubusercontent.com/nyulh/futprac-genai-spec-cli/main/scripts/install.sh | bash
#
# Build and install locally (requires sbt):
#   ./scripts/install.sh --local

REPO="nyulh/futprac-genai-spec-cli"
JAR_NAME="arkhitekton.jar"
BIN_NAME="arkhitekton"
LOCAL=false

for arg in "$@"; do
  case $arg in
    --local) LOCAL=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

if [ "$(id -u)" = "0" ]; then
  INSTALL_DIR="${PREFIX:-/usr/local}/bin"
else
  INSTALL_DIR="${PREFIX:-$HOME/.local}/bin"
fi

JAR_DIR="$HOME/.arkhitekton"
mkdir -p "$INSTALL_DIR" "$JAR_DIR"

if [ "$LOCAL" = true ]; then
  echo "🔨 Building arkhitekton from source..."
  sbt assembly
  cp target/scala-3.7.1/arkhitekton-assembly-*.jar "$JAR_DIR/$JAR_NAME"
else
  echo "⬇️  Downloading arkhitekton..."
  curl -fsSL \
    "https://github.com/$REPO/releases/latest/download/$JAR_NAME" \
    -o "$JAR_DIR/$JAR_NAME"
fi

echo "🔧 Installing wrapper to $INSTALL_DIR/$BIN_NAME..."
cat > "$INSTALL_DIR/$BIN_NAME" <<EOF
#!/usr/bin/env bash
exec java -jar "$JAR_DIR/$JAR_NAME" "\$@"
EOF
chmod +x "$INSTALL_DIR/$BIN_NAME"

echo ""
echo "arkhitekton installed!"
echo ""
echo "Make sure $INSTALL_DIR is on your PATH, then run:"
echo "  arkhitekton --help"
echo ""
echo "Set your API key before use:"
echo "  export ANTHROPIC_API_KEY=sk-ant-..."
