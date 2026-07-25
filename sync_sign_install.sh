#!/bin/bash
set -e

KEYSTORE="/storage/emulated/0/A SOFTWARE DEVELOPMENT/arme.keystore"
ALIAS="arme"
OUTPUT_DIR="/sdcard/A SOFTWARE DEVELOPMENT/APPS/tasks/build_output"

mkdir -p "$OUTPUT_DIR"

echo "📥 Scaricamento dell'APK non firmato dalla release GitHub (ALE-ARME/tasks)..."
gh release download latest --repo ALE-ARME/tasks --pattern "*.apk" --dir "$OUTPUT_DIR" --clobber

DOWNLOADED_APK=$(ls -t "$OUTPUT_DIR"/*.apk | head -n 1)
SIGNED_APK="$OUTPUT_DIR/tasks-custom-signed.apk"

echo "🔑 Firma dell'APK in locale sul telefono usando $KEYSTORE..."
read -sp "Inserisci la password del Keystore: " KSPASS
echo ""

apksigner sign --ks "$KEYSTORE" --ks-pass pass:"$KSPASS" --ks-key-alias "$ALIAS" --out "$SIGNED_APK" "$DOWNLOADED_APK"

echo "📱 Installazione dell'APK firmato sul dispositivo via ADB..."
adb install -r "$SIGNED_APK"

echo "✅ Installazione completata con successo!"
