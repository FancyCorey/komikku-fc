# Privacy and data

Komikku FC is an Android app, not an online recommendation service. Recommendations, ratings, recently shown cards, source evaluations, linked versions, and OCR data are stored locally through the app's database and settings. Installed extensions and configured trackers can still contact their own services when you use them.

## Local data

- Ratings, Not Interested, recently shown recommendation cards, source evaluations, source quality, linked versions, and feature settings are stored locally.
- OCR recognizes downloaded page images on the device and stores recognized text in the local database. ML Kit does not send the input images or recognized text to Google.
- OCR text is excluded from backup and sync payloads and can be cleared by chapter, manga, status group, or entire index.
- For You remembers the chapter counts it checked for recommended titles, for up to two weeks and at most 3,000 titles, so the minimum chapter count can be applied again without reloading their chapter lists.
- The automatic source check stores its schedule, its network and battery choices, and when it last ran.
- Action History stores small local undo records for supported actions. It cannot guarantee that a tracker update or Android package action can be reversed.

## Network boundaries

For You, source evaluation, the automatic source check, matching, extra manga details, chapter-count checks, extension installation, and tracking can make requests through installed extensions or configured services. **Use less data on recommendations** reduces how many requests For You and recommendation searches make; see [Recommendation settings](feature-guides/recommendation-settings.md#speed-and-data). These are separate from the manga information stored on your device. Error messages and copied diagnostics are not a guarantee that every screen or log hides private details; check them before sharing.

OCR processes downloaded page images on the device. The app does not send those images or the recognized text to Google. The bundled ML Kit SDK may still communicate with Google for model or compatibility updates and diagnostics. Google's [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) says its latest SDK versions may collect device and app information, per-installation identifiers, performance metrics, API configuration, input and output sizes, feature versions, event types, and error codes. Google says this data is encrypted in transit and not shared with third parties. The disclosure describes the latest SDK versions; the exact network events sent by this app's pinned ML Kit version have not been independently measured.

The inherited analytics and crash-reporting code accepts only official packages signed with the official certificate, so it does not start for the public Komikku FC package, `app.komikku.kmk`. This is separate from the ML Kit metrics described above and requests made by extensions or trackers.

A release maintainer must provide this fork's own Google Drive OAuth configuration while building a release before Google Drive sync can be offered. That configuration identifies the app to Google; each user's authorization token remains in app storage. Analytics and crash reporting remain excluded. The updater is off unless a build enables it, and an enabled updater checks only this fork's repository.

## Backups and exports

Backups include supported Komikku FC state that is difficult to recreate, such as ratings, recommendation preferences, links, and source-quality choices. Source evaluation results and run history are not included. Backups do not erase remote side effects, uninstall extensions, restore the clipboard, or replace a device backup.

Exports use Android's document APIs. Cleanup is limited to the exact document created by the operation and never scans an arbitrary folder for similarly named files.

## Screenshots and diagnostics

Name hiding replaces supported source and repository labels with neutral labels. Some screens, including the download queue, can still show source names. It does not hide manga artwork, titles, account information, reader pages, notifications, or every part of Android's interface. Before sharing a screenshot, check the whole image and any accompanying XML for private content, identifiers, paths, URLs, and status-bar information.

Source evaluation's copied diagnostics use a short category instead of raw error text. When name hiding is off, source and extension names can remain in that text. Other logs or exported files may contain additional information. See [Sharing](feature-guides/sharing.md) and [file safety](feature-guides/safety.md) before sending them to someone else.
