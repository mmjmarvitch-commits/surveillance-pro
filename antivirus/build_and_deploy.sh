#!/bin/bash

# ══════════════════════════════════════════════════════════════════════════════
# Security Pro - Script de build et déploiement
# ══════════════════════════════════════════════════════════════════════════════

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/../backend"
APK_NAME="SecurityPro.apk"

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║         Security Pro - Build & Deploy                     ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

# Vérifier si Gradle est disponible
if ! command -v ./gradlew &> /dev/null; then
    echo "❌ gradlew non trouvé. Assurez-vous d'être dans le dossier antivirus/"
    exit 1
fi

# Build Release
echo "📦 Construction de l'APK Release..."
cd "$SCRIPT_DIR"
./gradlew assembleRelease --no-daemon

# Trouver l'APK généré
APK_PATH=$(find app/build/outputs/apk/release -name "*.apk" | head -1)

if [ -z "$APK_PATH" ]; then
    echo "❌ APK non trouvé après le build"
    exit 1
fi

echo "✅ APK généré: $APK_PATH"

# Créer le dossier downloads si nécessaire
mkdir -p "$BACKEND_DIR/downloads"

# Copier l'APK vers le backend
echo "📤 Déploiement vers le serveur..."
cp "$APK_PATH" "$BACKEND_DIR/downloads/$APK_NAME"

echo ""
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║                    ✅ DÉPLOIEMENT RÉUSSI                  ║"
echo "╠═══════════════════════════════════════════════════════════╣"
echo "║  APK disponible sur: /download/securitypro                ║"
echo "║  Page de téléchargement: /securitypro.html                ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

# Afficher la taille de l'APK
APK_SIZE=$(du -h "$BACKEND_DIR/downloads/$APK_NAME" | cut -f1)
echo "📊 Taille de l'APK: $APK_SIZE"
