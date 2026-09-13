# Build and verify Komikku FC

This guide produces a local development build and checks the behavior covered by automated tests. A local debug APK is not a signed public release.

## Requirements

- JDK 17.
- The Android SDK versions requested by the Gradle build.
- Git and a checkout of this repository.
- Network access for the first dependency download, or a complete compatible Gradle cache for offline builds.

## Build

From the repository root, run:

```shell
./gradlew :app:assembleDebug
```

On Windows PowerShell, use `./gradlew.bat :app:assembleDebug`. The universal debug APK is written below `app/build/outputs/apk/debug/`.

The public release package is `app.komikku.kmk`. The development build uses `app.komikku.dev`, so it installs separately. Public releases must use the project's release signing key. A debug-signed APK cannot update an installed public release.

## Verify

Run the formatting, unit, and local-source checks before sharing a change:

```shell
./gradlew spotlessCheck :app:testDebugUnitTest :source-local:testDebugUnitTest :domain:testDebugUnitTest :data:testDebugUnitTest --max-workers=1 --no-parallel
./gradlew :app:assembleDebug --max-workers=1 --no-parallel
```

When a change affects a specific feature, also run that feature's tests. Device behavior still needs to be checked on a supported Android device or emulator, with screenshots reviewed for private information. A successful computer build alone cannot prove navigation, Android document-picker behavior, or extension compatibility.

## Check the APK

Before distributing an APK:

1. Record its SHA-256 hash.
2. Confirm its application ID, version, label, icon, and signing certificate.
3. Confirm that the update source refers to this fork.
4. Review bundled files for credentials and machine-specific paths.
5. Install only on an approved test target with an appropriate backup or data-preservation plan.

## Repository automation

Pushes and pull requests run formatting, unit tests, local-source tests, and a debug build without release signing keys. Development APKs use `app.komikku.dev` and do not create public releases or in-app updates.

Stable release tags match the Komikku FC feature version in `KmkRecsReleaseNotes`, for example `v0.8.22`. This is separate from Android's `versionName`, currently `1.14.1`. Android's `versionCode` must increase for each public update. The release workflow builds `kmkPublicTest` with the updater enabled, then signs the APKs using `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, and `KEY_PASSWORD`. These secrets must preserve the signing identity of the previous public release. The optional `GOOGLE_CLIENT_SECRETS_JSON` includes Google Drive support; it is not required for the updater.

The release workflow builds without telemetry service credentials, signs the APKs, verifies the package name, version, and shared signing certificate, writes SHA-256 checksums and a release manifest, and creates a draft GitHub release. A human must verify the certificate, hashes, generated notes, update behavior, and APK behavior before publishing that draft. Google Drive sign-in is an additional check only when that optional client configuration was supplied. See [Release channels](release-channels.md) for the full public-versus-development release model.

## Upstream and fork remotes

Keep the official Komikku repository as the upstream source and this repository as the fork. Never publish private test files, local build output, signing keys, device captures, or machine-specific configuration.
