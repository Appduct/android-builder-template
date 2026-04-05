# Appduct Android Builder

This repository is used by the Appduct platform to automatically build Android APK/AAB files from WebView-based app configurations.

## How It Works

1. The Appduct platform dispatches a GitHub Actions workflow with build parameters
2. The workflow generates the Android project from templates
3. Gradle builds the APK and AAB
4. The artifacts are uploaded back to Appduct's storage
5. A webhook notifies the platform that the build is complete

## Setup

### Repository Secrets Required

Add these secrets in your GitHub repo → Settings → Secrets and variables → Actions:

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded Android keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias in the keystore |
| `KEY_PASSWORD` | Key password |
| `SUPABASE_URL` | Your Supabase project URL |
| `SUPABASE_SERVICE_ROLE_KEY` | Supabase service role key |
| `BUILD_WEBHOOK_SECRET` | Webhook authentication secret |

### Generate a Keystore

```bash
keytool -genkey -v -keystore release.keystore -alias appduct -keyalg RSA -keysize 2048 -validity 10000
base64 -i release.keystore -o keystore_base64.txt
```

Copy the contents of `keystore_base64.txt` into the `KEYSTORE_BASE64` secret.

## Manual Testing

```bash
cd template
./gradlew assembleDebug
```
