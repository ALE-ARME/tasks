#!/bin/bash
set -e

OUTPUT_DIR="/sdcard/A SOFTWARE DEVELOPMENT/APPS/tasks/build_output"
mkdir -p "$OUTPUT_DIR"

echo "📥 Scaricamento dell'APK dalla release GitHub (ALE-ARME/tasks)..."
gh release download latest --repo ALE-ARME/tasks --pattern "*.apk" --dir "$OUTPUT_DIR" --clobber

DOWNLOADED_APK=$(ls -t "$OUTPUT_DIR"/*.apk | head -n 1)

echo "🗑️ Disinstallazione della vecchia versione di org.tasks..."
adb uninstall org.tasks 2>/dev/null || true

echo "📱 Installazione dell'APK firmato sul dispositivo via ADB..."
adb install -r "$DOWNLOADED_APK"

echo "✅ Installazione completata con successo!"
