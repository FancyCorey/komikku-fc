# Additional third-party components

Komikku FC includes an open-source license screen. This page also identifies inherited runtime components and direct dependencies used by its features and tests.

## Inherited runtime components

- `com.github.chrisbanes:PhotoView:2.3.0` supports reader image zooming. Its [published library metadata](https://github.com/Baseflow/PhotoView/blob/master/photoview/build.gradle) identifies the Apache License 2.0.
- `com.github.komikku-app:UniFile:084a54140a` supports file and storage access. The [fork's license](https://github.com/komikku-app/UniFile/blob/084a54140a48231ecd7f2336fe83b79bb7bb6558/LICENSE) is Apache License 2.0; its repository also includes a [notice](https://github.com/komikku-app/UniFile/blob/084a54140a48231ecd7f2336fe83b79bb7bb6558/NOTICE).
- `com.github.tachiyomiorg:DirectionalViewPager:1.0.0` supports reader paging. Its [license](https://github.com/tachiyomiorg/DirectionalViewPager/blob/main/LICENSE) is Apache License 2.0.
- `net.zetetic:sqlcipher-android:4.13.0` supplies encrypted database support. Its [license](https://github.com/sqlcipher/sqlcipher-android/blob/master/LICENSE) contains the Zetetic copyright notice and BSD-style redistribution conditions.

The `core:image-decoder` module has its own [Mozilla Public License 2.0](../../core/image-decoder/LICENSE) terms; the repository's top-level Apache License does not replace them.

## ML Kit text recognition

`com.google.mlkit:text-recognition:16.0.1` provides the on-device text recognizer used by OCR Search Downloads. Input images and recognized text are processed on the device and are not sent to Google. The SDK may contact Google for updates or compatibility information and may send SDK performance and utilization metrics. Use is subject to the [ML Kit terms and privacy notice](https://developers.google.com/ml-kit/terms) and the incorporated Google API terms.

## SQLDelight SQLite driver

`app.cash.sqldelight:sqlite-driver` is used only by JVM migration tests. SQLDelight is distributed under the [Apache License 2.0](https://github.com/sqldelight/sqldelight/blob/master/LICENSE.txt). It is not added to the production APK by this test dependency.

## Android and host test components

AndroidX test extensions, the AndroidX test runner, UI Automator, and Compose UI tests support Android UI checks. AndroidX test extensions and Compose UI tests are also used by host tests. Robolectric supplies an Android environment for host tests, and Roborazzi supports screenshot checks. These are test dependencies, not production app dependencies. A test or screenshot check covers only the states it actually exercises; it does not prove every screen or animation works.

## Google Drive sync

The inherited Google Drive sync provider uses Google's installed-app OAuth flow and app-data storage. Its client configuration is not stored in this repository. A release maintainer must provide a fork-owned configuration through the protected release workflow, verify its consent-screen and redirect settings, and test sign-in and revocation before publishing. User authorization tokens are stored by the app and should never be included in issues, screenshots, backups shared for support, or public build logs.

Before publishing a release, regenerate or inspect the app's open-source license list so all included libraries and notices match that exact build.
