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

## Tests

Game rules live in `GameEngine` (pure Kotlin, no Android deps) and are unit-tested:

```
./gradlew testDebugUnitTest
```

CI (`.gitlab-ci.yml`) runs unit tests + lint on every pipeline, builds a debug APK on branches/MRs and a signed AAB on the default branch and tags.

## Releasing (signed APK + AAB via GitHub Actions)

1. Create an upload key once, locally, and keep it OUT of the repo:
   ```
   keytool -genkeypair -v -keystore upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Base64 it (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload-key.jks")) | Set-Clipboard`
3. Repo -> Settings -> Secrets and variables -> Actions -> add: `KEYSTORE_BASE64` (the clipboard text), `STORE_PASSWORD`, `KEY_PASSWORD` (alias must be `upload`).
4. Publish: `git tag v1.0.0 && git push origin v1.0.0`. The **Release** workflow builds, verifies the signature, and attaches `RanSanMoi-<version>.apk` (sideload) and `.aab` (Google Play) to a GitHub Release.

Back up `upload-key.jks` and both passwords somewhere safe: losing them means you cannot update the app under the same signature.

The **Emulator smoke tests** workflow launches the app on Android 7 (API 24), 11 (API 30) and 14 (API 34), runs the UI smoke tests, then installs the R8-minified release build and checks it starts without crashing.

