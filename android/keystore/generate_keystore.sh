#!/bin/bash
# Script pour générer la clé de signature SurveillancePro
# Exécuter une seule fois: chmod +x generate_keystore.sh && ./generate_keystore.sh

KEYSTORE_FILE="surveillancepro.keystore"
ALIAS="surveillancepro"
PASSWORD="Surv3ill@nc3Pr0_2026!"
VALIDITY=10000  # ~27 ans

# Vérifier si le keystore existe déjà
if [ -f "$KEYSTORE_FILE" ]; then
    echo "⚠️  Le keystore existe déjà: $KEYSTORE_FILE"
    echo "    Supprimez-le d'abord si vous voulez en créer un nouveau."
    exit 1
fi

echo "🔐 Génération du keystore SurveillancePro..."

keytool -genkey -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity $VALIDITY \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=SurveillancePro, OU=Security, O=SurveillancePro Inc, L=Paris, ST=IDF, C=FR"

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Keystore créé avec succès: $KEYSTORE_FILE"
    echo ""
    echo "📋 Informations:"
    keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$PASSWORD" | head -20
    echo ""
    echo "⚠️  IMPORTANT: Sauvegardez ce fichier en lieu sûr!"
    echo "    Ne le perdez JAMAIS - vous ne pourrez plus mettre à jour l'app sans lui."
else
    echo "❌ Erreur lors de la création du keystore"
    exit 1
fi
