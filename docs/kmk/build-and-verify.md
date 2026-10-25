# Build and verify Komikku FC

Use this guide to build the app on your computer and run its automated checks. The debug APK is for development: it uses a test signing key, not the key used for public releases.

## Requirements

- JDK 21, as recorded in [the Java version file](../../.github/.java-version). The inherited pull-request workflow still uses JDK 17; it is not the local setup described here.
- The Android SDK, with the platform and native tools required by [the app build](../../app/build.gradle.kts) and [image decoder build](../../core/image-decoder/build.gradle.kts). Install them through Android Studio's SDK Manager.
- Git and a checkout of this repository.
- Python and Meson for the native image decoder. CI installs Meson 1.12.0. Keep `meson` available on your command path.
- The Android SDK's CMake and NDK tools, including Ninja. NASM is needed for x86-family native decoder assembly; CI installs it separately.
- Network access for the first dependency download, or a complete compatible Gradle cache for offline builds.

## Build

Open a terminal in the repository folder—the folder containing `gradlew`—and run:

```shell
./gradlew :app:assembleDebug --max-workers=1 --no-parallel
```

On Windows PowerShell, use `./gradlew.bat` instead of `./gradlew`, with the same arguments. The APK for all supported processor types has `universal-debug.apk` at the end of its filename. Find it in `app/build/outputs/apk/debug/`.

The public app's package ID is `app.komikku.kmk`; the debug app uses `app.komikku.dev`. Android treats them as separate apps, with separate data. Installing the debug APK does not update your public app or copy its library. A debug-signed APK cannot replace a public release.

## Verify

Run the formatting and unit tests before sharing a code change:

```shell
./gradlew spotlessCheck :app:testDebugUnitTest :source-local:testDebugUnitTest :domain:testDebugUnitTest :data:testDebugUnitTest --max-workers=1 --no-parallel
```

For a small change, use the relevant feature tests while working, then run the checks above before sharing it. Rebuild with the Build command above if the code changed after your last APK. A successful build or unit test does not prove device navigation, Android's file picker, or extension compatibility; check affected device behavior separately. Remove private information from screenshots before sharing them.

## Check the APK

Before distributing an APK:

1. Record its SHA-256 hash.
2. Confirm its application ID, version, label, icon, and signing certificate.
3. Confirm that the update source refers to this fork.
4. Review bundled files for credentials and machine-specific paths.
5. Install only on an approved test target with an appropriate backup or data-preservation plan.

## Repository automation

The `CI` workflow runs on pushes to `master` and manual requests. It checks formatting, app and local-source unit tests, then assembles a debug APK without release signing keys. The manually started development workflow also produces a debug APK. These APKs use `app.komikku.dev` and do not create public releases or in-app updates.

The inherited `PR build check` workflow is different: it excludes Markdown-only changes, checks dependencies and formatting, assembles a preview build, and runs release unit tests. Same-repository pull requests can use configured Google service files, enable the updater, and sign preview APKs; pull requests from another repository do not take those conditional steps. A preview artifact is not the public `app.komikku.kmk` release. Check that workflow before relying on it for a particular change; it does not run the exact debug/local-source command above.

Stable release tags match the Komikku FC feature version in `KmkRecsReleaseNotes`, such as `v0.8.24` or the earlier numeric follow-up `v0.8.23.1`. The workflow also accepts `-fixNUMBER` and `-fc-bridge` tag formats. These feature versions are separate from Android's `versionName`, currently `1.14.1`. Android's `versionCode` must increase for each public update.

The release workflow builds `kmkPublicTest` with the updater enabled, then signs the APKs using `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, and `KEY_PASSWORD`. These GitHub secrets must use the same signing identity as the previous public release. The optional `GOOGLE_CLIENT_SECRETS_JSON` enables Google Drive support; it is not required for the updater. Do not paste secrets into source files or documentation.

The release workflow builds without telemetry service credentials, signs the APKs, verifies the package name, version, and shared signing certificate, writes SHA-256 checksums and a release manifest, and creates a draft GitHub release. A human must verify the certificate, hashes, generated notes, update behavior, and APK behavior before publishing that draft. Google Drive sign-in is an additional check only when that optional client configuration was supplied. See [Release channels](release-channels.md) for the full public-versus-development release model.

## Upstream and fork remotes

Keep the official Komikku repository as the upstream source and this repository as the fork. Never publish private test files, local build output, signing keys, device captures, or machine-specific configuration.
