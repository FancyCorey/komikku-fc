# Privacy and data

Komikku FC keeps your recommendation preferences and reading data on your device. That does not mean the app is always offline: manga sources, connected trackers, and some optional services still use the network.

## What is saved on your device

- Your ratings, including **Not interested**, and the manga cards For You has shown.
- Source evaluations, source-quality choices, linked versions, and feature settings.
- Reading progress and local tracking information.
- Text recognized from downloaded chapter pages by OCR.
- The automatic source check's schedule, network and battery choices, and last-run time.
- Small **Action History** records used to undo supported changes.

For You also remembers checked chapter counts for up to two weeks, with a limit of 3,000 titles. This avoids loading the same chapter lists again just to apply your minimum chapter count.

## What uses the network

Browsing, For You, source checks, finding other versions, loading manga details, checking chapter counts, installing extensions, and external tracking can contact their sources or services.

**Use less data on recommendations** reduces optional searching; it does not make recommendations work offline or stop every network request. See [Speed and data](feature-guides/recommendation-settings.md#speed-and-data).

### OCR and Google ML Kit

OCR reads downloaded page images on your device. Google says ML Kit does not send the images or recognized text to its servers. It can still contact Google for updates, compatibility information, and technical usage metrics. See Google's [ML Kit privacy terms](https://developers.google.com/ml-kit/terms#privacy).

<details>
<summary>What technical information may ML Kit send?</summary>

Google's [Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) lists device and app information, per-installation identifiers, performance measurements, API configuration, input and output sizes, feature versions, event types, and error codes. Sizes are not the image or text contents. Google says the listed data is encrypted during transfer and not shared with third parties.

That disclosure covers Google's latest SDKs. This app uses text recognition version 16.0.1; its exact network events have not been independently measured.

</details>

### Analytics, Google Drive, and updates

The inherited analytics and crash-reporting code is not enabled for the public Komikku FC app, `app.komikku.kmk`. This is separate from ML Kit's technical metrics and requests made by extensions or trackers.

Google Drive sync needs a build configured with this fork's own Google sign-in details. The build configuration identifies the app to Google; your authorization token is stored in the app's local storage. It does not enable the inherited analytics or crash reporting.

The updater is available only in builds that enable it. When enabled, it checks this fork's repository, not the original app's repository.

## Backups and exports

Selected backup options can save ratings, recommendation preferences, links, source-quality choices, and other supported app data. Source evaluation results, evaluation run history, and OCR text are excluded. OCR text can be rebuilt from your downloaded pages.

Restoring a backup does not undo tracker updates already sent, uninstall extensions, restore the clipboard, or replace a full device backup.

Exports use Android's file chooser. If an export fails, cleanup targets only the file that operation created—not other files in the folder.

## Before sharing a screenshot or file

**Name hiding is not a full privacy filter.** It replaces supported source and repository labels, but some screens—including the download queue—can still show source names **even when name hiding is enabled**.

It does not automatically hide manga titles, artwork, account information, reader pages, notifications, or Android controls. Check the whole screenshot before sending it. If you also share a screen-layout file (XML), check that separately for names, identifiers, paths, and links.

Source evaluation's **Copy Diagnostics** uses short error categories instead of raw error messages. If name hiding is off, that text can include source and extension names. Other logs and exported files can reveal more. Read them before sharing; turning on name hiding does not redact file contents.

See [Sharing](feature-guides/sharing.md), [Backups](feature-guides/backups.md), and [File safety](feature-guides/safety.md).

## Clearing data and undo

OCR text can be cleared for one chapter, one manga, a status group, or the whole index without deleting the downloaded pages. **Action History** can undo only supported changes; it cannot guarantee reversal of an external tracker update or an Android installation.
