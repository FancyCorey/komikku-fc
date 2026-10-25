# Komikku FC documentation

Komikku FC helps you find manga, compare sources, track reading, and manage your library.

## Start here

- **New to the app?** Follow the [User guide](user-guide.md) for step-by-step instructions.
- **Looking for one feature?** Choose a [Feature guide](feature-guides/README.md) for its controls, limits, and troubleshooting.
- **Want to see the screens?** Open the [Visual feature guide](visual-guide/README.md).
- **Want the bigger picture?** Read [How Komikku FC works](how-kmk-works.md) to see how recommendations, sources, reading, and saved data fit together.
- **Looking for changes?** See the [Release notes](release-notes.md).

## Privacy and help

- [Privacy and data](privacy-and-data.md): what is stored on your device, what uses the network, and what to check before sharing.
- [Links, extensions, and recovery](security-and-integration.md): trust choices, failed operations, cancellation, and recovery limits.
- [Security policy](../../SECURITY.md): how to report a vulnerability without posting sensitive details publicly.
- [Third-party components](third-party-components.md): additional libraries and their terms.

## For contributors

- [Feature and code map](feature-and-code-map.md): find the code behind a feature and the states to preserve when changing it.
- [Build and verification](build-and-verify.md): prepare your tools, build a development APK, and run automated checks.
- [Contributing](../../CONTRIBUTING.md): report issues, propose changes, and submit code.
- [Release channels](release-channels.md): development versus public builds, signing, updates, and release checks.
- [Diagram sources](diagrams/README.md): edit and render the guide diagrams.

<details>
<summary>Feature and screenshot reference files</summary>

The [Technical reference](technical-reference/README.md) explains these XML indexes:

- [Feature reference](technical-reference/feature-reference.xml): feature routes, states, privacy rules, and code locations.
- [v0.8.24 screenshot index](technical-reference/screenshot-manifest-v0.8.24.xml): illustrations and their SHA-256 file hashes.
- [v0.8.23 screenshot index](technical-reference/screenshot-manifest-v0.8.23.xml): the What's New illustration, not every workflow in that version.
- [Earlier screenshot index](technical-reference/screenshot-manifest.xml): older illustrations, their locations, and their limits.

</details>

## How the guides are organized

**Feature guides** explain individual tools, such as [Ratings](feature-guides/ratings.md), [Comparing versions](feature-guides/versions.md), and [Backups](feature-guides/backups.md). The **Visual guide** shows the main screens. Use the **Feature and code map** for implementation files; the **Technical reference** is for maintainers and automated checks.

## Important limits

Komikku FC keeps Komikku's familiar Library, Browse, Reader, Settings, backup, tracking, and extension screens. Its added tools use the app's existing saved data and settings.

A failed source does not necessarily stop every other source, but some extension crashes can still stop the app. Cancelling pending work does not undo files already written, extensions already installed, or tracker updates already sent.

**Name hiding** changes supported labels—not saved data or file contents—and it does not hide manga artwork or every name on every screen. Check screenshots and files before sharing. See [Privacy and data](privacy-and-data.md).
