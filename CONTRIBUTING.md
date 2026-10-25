# Contributing to Komikku FC

To report a bug or request a feature, use the fork's [issue forms](https://github.com/FancyCorey/komikku-fc/issues/new/choose). They explain what information to include and which source problems are outside the app's control.

---

Thanks for your interest in contributing to Komikku FC. This fork builds on Komikku, TachiyomiSY, and Mihon. Keep their existing behavior intact unless a proposed change explicitly addresses it.


## Code contributions

Pull requests are welcome!

If you're interested in taking on [an open Komikku FC issue](https://github.com/FancyCorey/komikku-fc/issues), please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

For a large change, discuss the expected behavior, compatibility, and checks in an issue before starting.

### Prerequisites

Code contributions use Android and Kotlin. Familiarity with the following will help you get started:

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled to test changes.

### Getting help

- Use a Komikku FC issue for questions about this fork's behavior or contribution scope.
- Use the [upstream Komikku contribution documentation](https://komikku-app.github.io/docs/contribute) for shared project structure and Android development guidance. Report fork-specific problems here rather than asking upstream maintainers to support them.

See [Build and verification](docs/kmk/build-and-verify.md) for this fork's build setup and focused checks. For documentation changes, reread the result and inspect diagrams at desktop and phone widths; see the [diagram instructions](docs/kmk/diagrams/README.md).

## Translations

Translations inherited from Komikku are managed through upstream [Weblate](https://hosted.weblate.org/engage/komikku-app/). See the [upstream translation guide](https://komikku-app.github.io/docs/contribute#translation) for those strings. Changes to Komikku FC-specific text should update the default string resource and follow the existing localization structure. Leave generated upstream translations unchanged.


## Downstream forks

Further forks are allowed so long as they abide by [the project's LICENSE](LICENSE) and the licenses of included components.

When creating a fork:

- Change the app name and icon so users can distinguish it.
- Configure your own update repository, or disable update checking. Start with the [update checker](app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt).
- Set a distinct `applicationId` and review build-type suffixes in [the app build configuration](app/build.gradle.kts) to avoid installation conflicts.
- If you enable analytics or crash reporting, configure your own service rather than sending reports to another project's account. Telemetry plugins are conditional in this fork; do not assume every build includes them.


### Optional Google Drive sync

Google Drive support is optional. A distributing fork must configure its own client for its package and signing identity rather than reuse another project's credentials. See [Build and verification](docs/kmk/build-and-verify.md) and [Release channels](docs/kmk/release-channels.md) for the build configuration. Keep client configuration and signing secrets out of commits.
