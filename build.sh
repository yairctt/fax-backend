#!/bin/bash
# ═══════════════════════════════════════════════════
#  build.sh — Compila el servidor FAX
# ═══════════════════════════════════════════════════
set -e

echo ""
echo "╔══════════════════════════════════════╗"
echo "  FAX Compiler Backend — Build Script"
echo "╚══════════════════════════════════════╝"
echo ""

cd "$(dirname "$0")"

# ── 1. Compilar el servidor Java ────────────────────
echo "▶ Compilando FaxServer.java..."
mkdir -p build
javac -source 11 -target 11 -d build src/FaxServer.java

# ── 2. Empaquetar en JAR ────────────────────────────
echo "▶ Creando fax-backend.jar..."
cat > build/MANIFEST.MF <<EOF
Manifest-Version: 1.0
Main-Class: FaxServer
EOF

jar cfm fax-backend.jar build/MANIFEST.MF -C build .

echo ""
echo "✓ Build exitoso: fax-backend.jar"
echo ""
echo "Coloca Fax.jar (compilador JavaCC) en este directorio."
echo "Luego ejecuta:  java -jar fax-backend.jar"
echo ""
