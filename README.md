<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/d75b519d-4bab-4bc8-8772-7b38575f5977

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Run the app on an emulator or physical device (debug builds use the default Android debug keystore).

## Release build (AAB for Google Play)

Signing is read from environment variables, never from the repo:

```
KEYSTORE_PATH=/path/to/upload-key.jks STORE_PASSWORD=... KEY_PASSWORD=... ./gradlew bundleRelease
```

Never commit `*.jks` / `*.keystore` (already in `.gitignore`).
