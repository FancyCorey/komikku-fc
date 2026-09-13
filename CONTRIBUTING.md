Looking to report a bug or request a feature? Start with the fork's [issue forms](https://github.com/FancyCorey/komikku-fc/issues/new/choose). The forms explain what information helps and which source or extension problems are outside the app's control.

---

Thanks for your interest in contributing to Komikku FC. This fork builds on Komikku, TachiyomiSY, and Mihon. Keep their existing behavior intact unless a proposed change explicitly addresses it.


# Code contributions

Pull requests are welcome!

If you're interested in taking on [an open Komikku FC issue](https://github.com/FancyCorey/komikku-fc/issues), please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

For a large change, open or join an issue first so the expected behavior, compatibility boundary, and validation can be agreed before substantial work begins.

## Prerequisites

Code contributions use Android and Kotlin. Familiarity with the following will help you get started:

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled to test changes.

## Getting help

- Use a Komikku FC issue for questions about this fork's behavior or contribution scope.
- Use the [upstream Komikku contribution documentation](https://komikku-app.github.io/docs/contribute) for shared project structure and Android development guidance. Report fork-specific problems here rather than asking upstream maintainers to support them.

# Translations

Translations inherited from Komikku are managed through upstream [Weblate](https://hosted.weblate.org/engage/komikku-app/). See the [upstream translation guide](https://komikku-app.github.io/docs/contribute#translation) for those strings. Changes to Komikku FC-specific text should update the default string resource and follow the existing localization structure. Leave generated upstream translations unchanged.


# Downstream forks

Further forks are allowed so long as they abide by [the project's LICENSE](LICENSE) and the licenses of included components.

When creating a fork, remember to:

- To avoid confusion with the main app:
    - Change the app name
    - Change the app icon
    - Change or disable the [app update checker](https://github.com/komikku-app/komikku/blob/master/app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt)
- To avoid installation conflicts:
    - Change the `applicationId` in [`build.gradle.kts`](https://github.com/komikku-app/komikku/blob/master/app/build.gradle.kts)
- To avoid having your data polluting the main app's analytics and crash report services:
    - If you want to use Firebase analytics, replace [`google-services.json`](https://github.com/komikku-app/komikku/blob/master/app/src/standard/google-services.json) with your own
    - If you want to use ACRA crash reporting, replace the `ACRA_URI` endpoint in [`build.gradle.kts`](https://github.com/komikku-app/komikku/blob/master/app/build.gradle.kts) with your own


### Supporting Cloud Sync - Google Drive Implementation

Google Drive support is optional. A distributing fork must configure its own client for its package and signing identity rather than reuse another project's credentials. See [Build and verification](docs/kmk/build-and-verify.md) and [Release channels](docs/kmk/release-channels.md) for the build configuration. Keep client configuration and signing secrets out of commits.
