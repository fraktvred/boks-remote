# Publishing a release

Pushing a tag `vX.Y.Z` that matches `versionName` in `app/build.gradle.kts` builds, signs and publishes the APK as a GitHub release. Bump `versionCode` along with it. Releases are signed with a fixed key so installs update in place; it lives in these repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the keystore, `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Create the keystore once with `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias boks-remote` and keep a backup: losing it means users have to uninstall before they can install a new build.
