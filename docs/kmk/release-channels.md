# Release channels

Use a **public release** for everyday reading. **Development builds** are separate apps for testing changes; they are not updates to the public app.

## Public releases

Public Komikku FC uses the package ID `app.komikku.kmk`. Published releases are signed with the project's public release key and have the updater enabled. See [Update compatibility](release-notes.md#app-updates) if you have an older installation.

A new public release must keep that package ID and signing identity and use a higher Android `versionCode`. The feature version, such as **0.8.24**, is a separate label. For the Android versioning rules, see [Version your app](https://developer.android.com/studio/publish/versioning).

## Development builds

Debug builds use `app.komikku.dev` and the Android debug key. They install beside the public app, have their own data, and do not enable the public in-app updater. Installing one does not update the public app or copy its library.

The push CI and manual development workflows produce debug APKs for testing, not public releases. The inherited pull-request workflow builds preview APKs; it can use signing and an updater for pull requests from this repository, but those APKs are not the public `app.komikku.kmk` release. See [Build and verification](build-and-verify.md#repository-automation).

Keep unpublished development history locally or in a private repository if it must remain private. A branch in a public repository is public. Private release files require authenticated access and cannot serve as this app's anonymous public update feed.

## For release maintainers

The [Release build workflow](../../.github/workflows/build_release.yml) builds the `kmkPublicTest` variant, enables the updater, and signs the APKs with the public key. Despite its name, this is the variant used for public releases. A local build uses a debug key unless re-signed; it cannot replace the installed public app.

The workflow checks the tag against the feature version, runs formatting and unit tests, builds the APKs, verifies their package/version/signing identity, and creates a **draft release** with checksums and a release manifest. A draft is not visible to the updater; publishing the reviewed draft makes it available.

### Signing and optional Google Drive support

Keep the release keystore and its passwords outside the repository. Preserve the same signing identity for future public updates.

Set these GitHub repository secrets:

- `SIGNING_KEY`: the release keystore encoded as Base64.
- `ALIAS`: the key's name inside the keystore.
- `KEY_STORE_PASSWORD`: the keystore password.
- `KEY_PASSWORD`: the key password.

Google Drive sync is separate from updates. Supplying `GOOGLE_CLIENT_SECRETS_JSON` configures this fork's Google Drive sign-in. Without it, Drive sync is unavailable in that build, but the public updater can still work. Never put secret values in source files, issues, or documentation.

### Release checklist

1. Build a development APK and check the intended changes on a device.
2. Integrate the approved code into the public release branch.
3. Increase Android's `versionCode`, then update the Komikku FC feature version and release notes. Android's `versionName` is separate.
4. Run the verification commands in [Build and verification](build-and-verify.md).
5. Create a new, unused tag matching the feature version. For example, `v0.8.24` identifies feature version 0.8.24; do not reuse it for a later version or move a published tag.
6. Review the workflow APKs, `SHA256SUMS.txt`, `CERTIFICATE_SHA256.txt`, and `RELEASE_MANIFEST.json`.
7. Install the signed APK over an earlier public build and confirm that it updates successfully, preserving app data.
8. Publish the reviewed draft release.
9. Integrate the release commits into `master` and update its [release notes](release-notes.md) from candidate wording to published wording. The default branch should describe the latest public release.

The workflow can also be started manually for an existing `v*` tag. Use that to rebuild a reviewed tag only after the signing secrets are configured. Do not move a published tag or treat a rebuild as permission to replace published assets.
