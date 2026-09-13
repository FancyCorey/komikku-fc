# Release channels

Komikku FC has public releases and separate development builds. Choose a public release for everyday reading.

## Public releases

Public releases use the `kmkPublicTest` build variant and package name `app.komikku.kmk`. The release workflow enables the updater and signs the APKs with the public release key. A local build of this variant uses a debug key instead and cannot replace an installed public release.

Create a public release from a tag matching the Komikku FC feature version, such as `v0.8.22`. The workflow checks that tag, runs formatting and unit tests, builds and signs the APKs, verifies their package, version code, and signing identity, then creates a draft with checksums and a release manifest. Publishing the reviewed draft makes it available to the updater. See [update compatibility](release-notes.md#app-updates) for older installations.

Android accepts an update only when the package name matches, the new version code is higher, and the APK is signed with the same release key. Keep the public keystore and its passwords outside the repository and preserve them for every future public release.

The public workflow needs these repository secrets:

- `SIGNING_KEY`: Base64-encoded public release keystore.
- `ALIAS`: key alias inside that keystore.
- `KEY_STORE_PASSWORD`: keystore password.
- `KEY_PASSWORD`: key password.

Google Drive sync is independent of the updater. Supplying `GOOGLE_CLIENT_SECRETS_JSON` includes the fork-owned Google Drive client in that build. Leaving it unset still creates a valid public release, but the Google Drive option is not included.

## Development builds

Development builds use `app.komikku.dev`. They install beside the public app, use the Android debug key, and do not enable the in-app updater. Development workflows create downloadable test APKs, not public updates.

For a genuinely private development history, keep the development branch in a separate private repository or private clone. Branches inside a public repository are public. A private GitHub release cannot be used as an anonymous in-app update feed, so private development builds are intentionally distributed through authenticated artifacts or direct testing rather than the public updater.

## Release checklist

1. Run the development build and test the intended changes on a device.
2. Merge or copy the approved code to the public release branch.
3. Increase Android's `versionCode` and update the Komikku FC feature version and release notes. Android's `versionName` is separate.
4. Run the public verification commands in [Build and verification](build-and-verify.md).
5. Create a new tag that matches the Komikku FC feature version, for example `v0.8.22`. Never move a published tag.
6. Review the workflow artifact, `SHA256SUMS.txt`, `CERTIFICATE_SHA256.txt`, and `RELEASE_MANIFEST.json`.
7. Install the signed APK over an earlier public build and confirm Android accepts the update.
8. Publish the reviewed draft release. A draft is not visible to the in-app updater.

The release workflow also supports a manual run for an existing `v*` tag. Use that only to rebuild a reviewed tag after the required signing secrets are in place; do not move an already published tag.
