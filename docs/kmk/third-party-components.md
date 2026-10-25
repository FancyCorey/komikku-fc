# Additional third-party components

Komikku FC's open-source license screen lists the libraries included in the app. This page gives more detail about selected components, what they do, and which ones are used only for tests.

## Inherited runtime components

- `com.github.chrisbanes:PhotoView:2.3.0` supports reader image zooming. Its [published library metadata](https://github.com/Baseflow/PhotoView/blob/master/photoview/build.gradle) identifies the Apache License 2.0.
- `com.github.komikku-app:UniFile:084a54140a` supports file and storage access. The [fork's license](https://github.com/komikku-app/UniFile/blob/084a54140a48231ecd7f2336fe83b79bb7bb6558/LICENSE) is Apache License 2.0; its repository also includes a [notice](https://github.com/komikku-app/UniFile/blob/084a54140a48231ecd7f2336fe83b79bb7bb6558/NOTICE).
- `com.github.tachiyomiorg:DirectionalViewPager:1.0.0` supports reader paging. Its [license](https://github.com/tachiyomiorg/DirectionalViewPager/blob/main/LICENSE) is Apache License 2.0.
- `net.zetetic:sqlcipher-android:4.19.1` supplies encrypted database support. Its [license](https://github.com/sqlcipher/sqlcipher-android/blob/master/LICENSE) contains the Zetetic copyright notice and BSD-style redistribution conditions.

The `core:image-decoder` module has its own [Mozilla Public License 2.0](../../core/image-decoder/LICENSE) terms; the repository's top-level Apache License does not replace them.

## ML Kit text recognition

`com.google.mlkit:text-recognition:16.0.1` recognizes text for OCR Search Downloads. Images and recognized text are processed on the device, not sent to Google for recognition.

The SDK may contact Google for updates or compatibility information and send technical usage and performance information. See [Privacy and data](privacy-and-data.md#ocr-and-google-ml-kit) for the distinction between local text recognition and SDK traffic. Use is subject to the [ML Kit terms and privacy notice](https://developers.google.com/ml-kit/terms) and the incorporated Google API terms.

## SQLDelight SQLite driver

`app.cash.sqldelight:sqlite-driver` lets migration tests use SQLite on a development computer. SQLDelight is distributed under the [Apache License 2.0](https://github.com/sqldelight/sqldelight/blob/main/LICENSE.txt). This test dependency is not included in the production APK.

## Android and host test components

AndroidX test extensions, the AndroidX test runner, UI Automator, and Compose UI tests support app-interface checks. Some tests run on Android; others run on a development computer using Robolectric's simulated Android environment. Roborazzi supports screenshot comparisons.

These are test dependencies, not production app dependencies. A test or screenshot comparison covers only the states it exercises, not every screen or animation.

## Google Drive sync

Google Drive sync uses Google's sign-in permission flow and app-data storage. It needs a Google client configuration supplied when the app is built; that configuration is not stored in this repository.

Release maintainers must supply the fork's configuration through the protected release workflow, check Google's permission-screen and sign-in return-address settings, and test both sign-in and revoking access before publishing.

The app stores authorization tokens to keep you signed in. Do not include them in issues, screenshots, backups shared for support, or public build logs.

Before publishing a release, regenerate or inspect the app's open-source license list so all included libraries and notices match that exact build.
