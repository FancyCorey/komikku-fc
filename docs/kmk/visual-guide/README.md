# Visual guide to Komikku FC

This tour follows Komikku FC's main workflows across supported screen sizes. Each section explains what the screen is for, what its controls do, and where to find more detailed steps. Larger screens may show related navigation beside the current page; smaller screens may place the same controls behind a menu or on a separate page.

## For You

<p align="center"><img src="for-you-evaluation-mode.png" width="260" alt="For You page with personalized manga rows, matching tags, and anonymized source labels"></p>

At the top of For You, you can see topic shortcuts, personalized rows, matching tags, and manga cards. Name hiding replaces source names, while the covers and titles remain visible so the recommendations are still meaningful.

## Top Picks and preference actions

<p align="center"><img src="top-picks-preference-actions.png" width="260" alt="Top Picks with selected manga and the complete preference action set"></p>

Long-pressing a card starts selection mode. Love, Like, and Dislike remain directly available, while More contains Not Interested and Clear rating. This keeps the four preference choices in one workflow instead of presenting Not Interested as a separate feature.

## Manga preferences and collections

<p align="center"><img src="not-interested-other-versions.png" width="260" alt="Manga preference actions with Not Interested selected for another version"></p>

Love, Like, Dislike, and Not Interested are peer preferences. The selected state is shown on the manga action surface, and each preference has a matching collection or filter. Clearing Not Interested is an explicit reversible action; it is not treated as an absence of a rating. The Top Picks image above shows the bulk-action surface, while this detail image shows the state on a manga-related screen.

## Related manga

<p align="center"><img src="related-manga-results.png" width="260" alt="Related manga results grouped under neutral source labels"></p>

Related-manga results load in separate source rows. A short or unavailable row does not remove useful results returned by the other sources.

## Recommendations from a confirmed group

<p align="center"><img src="group-recommendations.png" width="260" alt="Group recommendations built from a confirmed multi-version manga group with neutral source labels"></p>

A confirmed linked group can seed its own recommendation search. Community suggestions and extension searches remain separated into source lanes, so a sparse or unavailable lane does not erase the useful results from other group members.

## Share recommendation bundles

<p align="center"><img src="recommendation-bundle-export.png" width="260" alt="For You with the Export Top Picks action open"></p>

The For You overflow menu exports the current Top Picks as a recommendation bundle. Individual source rows and rated collections provide the same kind of export from their own menus. Import opens a review step first; it does not add manga to the library until the reader confirms the reviewed selection.

## Browse compatibility

<p align="center"><img src="browse-evaluation-mode.png" width="260" alt="Browse page with neutral source labels while name hiding is enabled"></p>

Name hiding also hides source names in Browse. The rest of Komikku's navigation stays the same.

## Name hiding and sharing

Name hiding (**Hide technical names in screenshots** under **Settings > Advanced > Developer tools**) replaces source, extension, and selected taste labels with neutral names. It does not change saved manga, requests, identifiers, or actions. Manga titles, artwork, reading context, and some genre suggestions can remain visible. Check the whole screen before sharing it; see [Sharing screenshots and exported files safely](../feature-guides/sharing.md).

## Recommendation settings

<p align="center"><img src="v0.8.24/recommendation-settings.png" width="260" alt="Recommendation Settings first page with one entry per feature"></p>

The first page lists one entry per feature: Your taste, For You feed, Focus modes, Find new sources, Source evaluation, Speed and data, Linked versions and ratings, and Troubleshooting. Each row shows its current value, and the row for the page you are on keeps a filled icon. On a wider screen the list stays beside the open page.

## Your taste

<p align="center"><img src="v0.8.24/your-taste.png" width="260" alt="Your taste with known-manga choices, minimum chapter count and recent reading"></p>

Your taste controls which known or rated manga stay eligible, the minimum chapter count, recent reading, preferred and blocked tags, suggestions learned from your ratings, and recommendation languages. Name hiding shows preferred and blocked tags as placeholders.

## For You feed

<p align="center"><img src="v0.8.24/for-you-feed.png" width="260" alt="For You feed settings with Show looser matches"></p>

<p align="center"><img src="v0.8.24/for-you-feed-sources.png" width="260" alt="Source priority list with one-line status tags"></p>

For You feed holds the For You tab switch, **Show looser matches**, the saved preview, new-release exploration, repeat display, and the source order. Each source row shows one-line tags such as **Boosted**, **Great fit**, and **Top Picks**.

## Speed and data

<p align="center"><img src="v0.8.24/speed-and-data.png" width="260" alt="Speed and data with Use less data on recommendations"></p>

Speed and data holds **Use less data on recommendations** (Automatic, Always, or Never), result limits, extra details, extra pages, and the discovery history reset.

## Troubleshooting

<p align="center"><img src="v0.8.24/troubleshooting.png" width="260" alt="Troubleshooting listing sources with problems, then taste diagnostics"></p>

Troubleshooting lists sources that failed to load, sources the automatic check skips on For You, taste diagnostics, and source tag checks.

## Source-quality diagnostics

<p align="center"><img src="source-quality-diagnostics.png" width="260" alt="Expanded source-quality diagnostics with neutral source labels and aggregate observations"></p>

The expanded view shows sample counts, metadata coverage, match counts, blocked candidates, and confidence using neutral source labels. Each source can be expanded independently, so a weak or incomplete observation remains distinguishable from a successful evaluation.

## Source evaluation

<p align="center"><img src="v0.8.24/source-evaluation.png" width="260" alt="Source evaluation with run options and the reason Start is unavailable"></p>

Source evaluation shows its run options and **Start** first, with a line explaining why Start is unavailable when it is, followed by past results. **Check older results again** offers reassessment; **Diagnostics and recovery** and **Source management** come last.

<p align="center"><img src="v0.8.24/find-new-sources.png" width="260" alt="Find new sources with Source evaluation, Suggested for you and Automatic check"></p>

**Find new sources** links to Source evaluation and Sources to Try, and holds the automatic check, which is off until you turn it on.

## Sources to try

<p align="center"><img src="sources-to-try-evaluation-mode.png" width="260" alt="Sources to try with ranked suggestions and neutral source labels"></p>

The populated list shows ranking, sorting, installation, and selection controls. Name hiding replaces the source names with neutral labels.

## Linked versions

<p align="center"><img src="linked-versions-evaluation-mode.png" width="260" alt="Matching manga grouped under neutral source labels"></p>

This screen shows search results selected for a preference action, including sources with no results. Check each selected manga before applying the action: a search result is not proof that it is the same manga. Love, Like, Dislike, and Not Interested form one preference system; the Top Picks selection screen presents all four together.

## Compare versions before migrating

| Choose comparable chapters | Review page previews |
| --- | --- |
| ![Best Version comparison with chapter choices for each neutralized source](best-version-chapter-selection.png) | ![Best Version preview with comparable pages from neutralized sources](best-version-preview-comparison.png) |

Best Version starts from versions the reader has already confirmed as related. The first screen lets the reader choose comparable chapter samples. The second keeps each preview independent, so a missing or failed preview does not erase the other comparison results. Choosing a different version continues through Komikku's existing migration flow; the comparison itself does not silently change the library. See [Finding and comparing manga versions](../feature-guides/versions.md) for the complete flow.

## Reading schedule

<p align="center"><img src="reading-schedule.png" width="260" alt="Reading schedule dialog with recurring time windows"></p>

The schedule can block reading during selected windows or allow reading only inside them. Existing windows have explicit edit and remove controls, and changes are not applied until the reader saves the dialog.

Reader controls also include the optional timer and the completion handoff. The timer can stop or restrict reading without changing chapter progress. The reader's clock or schedule action opens the same schedule configuration described above, so the setting is discoverable from the place where it matters.

## Jump to last read

Long chapter lists have a Jump to last read action in the manga chapter toolbar. When a saved read position exists, it scrolls to that chapter without opening it or changing progress. When no position exists, the action explains that there is nothing to jump to. Chapter names and reading history are intentionally explained in text rather than shown in a public image.

## Completion preference

| Choose a preference | Continue to matching versions |
| --- | --- |
| ![Reader completion prompt offering Love, Like, Dislike, and Not Interested](reader-completion-preference.png) | ![Follow-up asking whether matching versions should receive the same preference](reader-linked-version-follow-up.png) |

After the final chapter, the reader may choose Love, Like, Dislike, or Not Interested, or close the prompt without changing the manga. A second, separate question controls whether the same preference should be considered for confirmed matching versions.

## Search downloaded pages with OCR

<p align="center"><img src="ocr-search-downloads.png" width="260" alt="OCR Search Downloads showing an indexed local library and its indexing controls"></p>

The index summary shows how many downloaded pages have been processed and how much space the local text index uses. Readers can index all downloads, force a fresh pass, or clear index records without deleting the downloaded pages themselves. Search results open the matching reading context.

## Undo a supported local action

<p align="center"><img src="action-history-undo.png" width="360" alt="Action History showing a completed manga preference change with an Undo action"></p>

Action History records a supported local change only after it succeeds. The Undo button restores the previous value when the current state still matches the recorded action. It cannot undo a later conflicting change or an update sent to an external tracker.

## Extension export

<p align="center"><img src="extension-export-confirmation.png" width="260" alt="Extension export confirmation explaining what the file contains"></p>

The confirmation explains that an extension package is executable code and that ratings, history, and other app data are not included. Android's document flow chooses the destination after the reader confirms.

## Extension operations and failure isolation

Extension operations cover loading, install consent, cancellation, removal, export, and cleanup confirmation. Android shows the confirmation screens for installing extensions and choosing export files; Komikku FC does not install an extension silently or delete an unrelated file. If one extension fails or is cancelled, the remaining source work keeps its own result and can be retried. The complete operational flow is in [Managing and isolating extensions](../feature-guides/extensions.md) and [Handling source and extension failures](../feature-guides/troubleshooting.md).

## Back up and restore app data

<p align="center"><img src="backup-and-restore.png" width="260" alt="Data and storage settings with backup, restore, scheduling, and privacy guidance"></p>

The Data and storage page keeps manual backup, restore, automatic frequency, last-backup status, and restore progress controls together. It reports only that a storage location is configured in this view, then gives a separate warning that backup files may contain sensitive data.

Komikku FC's saved ratings, recommendation settings, linked-version groups, primary-version choices, and source-quality information can travel through Komikku's normal backup flow when selected. Source Evaluation results and run history are not included in exported backups and are not rebuilt by restore. OCR text, temporary review state, and unsupported external account data remain outside the backup. Restore reports partial or failed records instead of presenting an incomplete restore as successful. See [Preserving Komikku FC data in Komikku backups](../feature-guides/backups.md).

## Local Tracking progress and refresh

<p align="center"><img src="v0.8.23/local-tracking-decimal-progress.png" width="260" alt="Tracking sheet showing Local Tracking with the Reading status and decimal chapter progress 2.5"></p>

The Tracking sheet keeps Local Tracking beside any external tracker rows. Choose a status, set the chapter progress, and add a score or dates. The progress dialog accepts a decimal chapter number as well as a whole chapter, so a fractional chapter such as 2.5 can be saved.

<p align="center"><img src="v0.8.23/local-tracking-refresh-read-markers.png" width="260" alt="Chapter list where chapters 1 and 2 are marked read and chapter 3 is unread after saved progress of 2.5"></p>

Opening the Tracking sheet refreshes the local read markers so the saved chapter and the chapter list agree. After progress 2.5 was saved and the sheet was opened again, chapters 1 and 2 are marked read, chapter 3 stays unread, and Resume is offered. Both images use synthetic test content on a test source, not a real series. Local Tracking is not available for titles from the built-in Local source; use a source-backed title or an external tracker for those.

## Switch to another source while reading

<p align="center"><img src="v0.8.24/alternate-source-chooser.png" width="260" alt="Choose an alternate source dialog showing the current source and two matches"></p>

In the Reader, **Choose an alternate source** opens the chooser. It shows the source you are reading from, then matches from other sources. **Search another title** looks for the manga under a different name, and **Search more sources** looks in more of your sources. Cancel closes the chooser without changing anything.

<p align="center"><img src="v0.8.24/reader-alternate-sources.png" width="260" alt="Reader settings Alternate sources section"></p>

**Settings > Reader > Alternate sources** chooses when **Continue from another source** is offered: when chapters are missing, when a source has no next chapter, or both.

## Reading timer

<p align="center"><img src="v0.8.24/reading-timer.png" width="260" alt="Reading timer with How long and warnings"></p>

The reading timer has three sections: how long, when to warn you, and what happens when time is up.

## Review Komikku FC changes in the app

<p align="center"><img src="v0.8.24/whats-new.png" width="260" alt="What's New showing Komikku FC v0.8.24"></p>

Komikku FC What's New groups the current and earlier fork releases, summarizes each release, and separates new behavior from fixes. The installed version is shown at the top. Scroll in the app to read the remaining changes.

## When a For You source fails

<p align="center"><img src="v0.8.24/for-you-failed-source.png" width="260" alt="Failed For You rows with hints and actions"></p>

A failed row says what probably went wrong and offers the actions that apply: **Try again**, **Check source**, **Manage extension**, and **Don't use in For You**. A note appears when the extension has an update or is no longer offered by its repository.

## Focus

<p align="center"><img src="v0.8.24/focus-picker.png" width="260" alt="Focus picker with one included and one excluded tag"></p>

Focus lists tags as chips in sections. Tap a tag once to include it, a second time to exclude it, and a third time to clear it. Closing the picker without **Apply** keeps your previous focus.

## Group rating and confirmed versions

<p align="center"><img src="v0.8.24/track-other-versions.png" width="260" alt="Track other versions with the Group rating button"></p>

**Track other versions** shows the confirmed group's rating and changes it for every confirmed version at once.

<p align="center"><img src="v0.8.24/confirmed-versions.png" width="260" alt="Confirmed versions with Keep reading progress in sync"></p>

Selecting the title in the Local tracking entry opens **Confirmed versions**, where each version can join or leave reading-progress sharing.

## When sources or links fail

Source and link handling keeps supported extension failures, cancellation, and retry separate from successful results. Check unfamiliar links and extension packages before opening or installing them. Evaluation diagnostics summarize failures, but copied diagnostics can still include names when name hiding is off. Check logs and exported files before sharing them. See [Validating links, actions, and file cleanup](../feature-guides/safety.md) for the checks and their limits.

## Complete feature-family coverage

The visual guide follows the full Komikku FC feature contract. A family is listed here even when its safest public representation is a written flow rather than a screenshot.

| Contract family | Coverage in this guide |
| --- | --- |
| For You | [For You](#for-you) |
| Top Picks and recommendation bundles | [Top Picks and preference actions](#top-picks-and-preference-actions) and [Share recommendation bundles](#share-recommendation-bundles) |
| Group recommendations | [Recommendations from a confirmed group](#recommendations-from-a-confirmed-group) |
| Recommendation settings | [Recommendation settings](#recommendation-settings) |
| Source Evaluation | [Source evaluation](#source-evaluation) |
| Taste and source-quality diagnostics | [Your taste](#your-taste), [Troubleshooting](#troubleshooting), and [Source-quality diagnostics](#source-quality-diagnostics) |
| Sources to try | [Sources to try](#sources-to-try) |
| Manga preferences | [Manga preferences and collections](#manga-preferences-and-collections) and [Top Picks and preference actions](#top-picks-and-preference-actions) |
| Cross-source matching | [Linked versions](#linked-versions) |
| Best Version | [Compare versions before migrating](#compare-versions-before-migrating) |
| Reader controls | [Reading schedule](#reading-schedule) and [Completion preference](#completion-preference) |
| Jump to last read | [Jump to last read](#jump-to-last-read), written flow only because chapter history is private |
| Name hiding | [Name hiding and sharing](#name-hiding-and-sharing) |
| Action History | [Undo a supported local action](#undo-a-supported-local-action) |
| Extension export | [Extension export](#extension-export) |
| OCR search | [Search downloaded pages with OCR](#search-downloaded-pages-with-ocr) |
| Backup portability | [Back up and restore app data](#back-up-and-restore-app-data) |
| Extension operations | [Extension operations and failure isolation](#extension-operations-and-failure-isolation) |
| Security and integration | [When sources or links fail](#when-sources-or-links-fail), written flow only |
| Komikku FC change history | [Review Komikku FC changes in the app](#review-komikku-fc-changes-in-the-app) |

Name hiding replaces source identities with neutral labels, but it is not a way to hide everything about your reading. Manga artwork, titles, page text, reading context, genre suggestions, and aggregate counts may remain visible.

## Screenshot coverage

Use this table to find a screen or the written steps for each feature area.

| Feature area | Where to look | What it shows |
| --- | --- | --- |
| For You | [Screenshot](for-you-evaluation-mode.png) | Name hiding replaces source names while keeping the recommendations visible. |
| Top Picks and bulk preference selection | [Screenshot](top-picks-preference-actions.png) | Selection mode shows the complete preference action surface. |
| Recommendation bundle sharing | [Screenshot](recommendation-bundle-export.png) | The Top Picks export entry point. The feature guide separately explains import review and the explicit library-add step. |
| Related and group-seeded recommendations | [Related-manga screenshot](related-manga-results.png) and [group-seeded screenshot](group-recommendations.png) | The difference between ordinary related results and recommendations seeded by a confirmed multi-version group. |
| Recommendation settings | [Screenshot](v0.8.24/recommendation-settings.png) | The first page contains no account, manga, source, or storage details. |
| Speed and data | [Screenshot](v0.8.24/speed-and-data.png) | Only settings and their current values are shown. |
| Your taste | [Screenshot](v0.8.24/your-taste.png) | Settings and their current values; tag names appear as placeholders further down the page. |
| Source-quality diagnostics | [Screenshot](source-quality-diagnostics.png) | The expanded view shows aggregate evidence and per-source detail with neutral labels. |
| Source evaluation | [Screenshot](v0.8.24/source-evaluation.png) | Run options and readiness only. |
| Browse with name hiding on | [Screenshot](browse-evaluation-mode.png) | Source labels are neutralized without changing the normal Browse layout. |
| Love, Like, Dislike, and Not Interested | [Top Picks selection](top-picks-preference-actions.png) | The shared action surface shows Love, Like, Dislike, Not Interested, and Clear rating together. |
| Sources to try | [Screenshot](sources-to-try-evaluation-mode.png) and [feature explanation](../feature-guides/sources.md) | Name hiding replaces the populated list's source identities with neutral labels. |
| Find other versions | [Screenshot](linked-versions-evaluation-mode.png) and [feature explanation](../feature-guides/versions.md) | Manga matches with source names replaced by neutral labels. |
| Best Version comparison | [Chapter selection](best-version-chapter-selection.png), [preview comparison](best-version-preview-comparison.png), and [feature guide](../feature-guides/versions.md) | Chapter and page comparisons with neutral source labels. |
| Reading schedule | [Screenshot](reading-schedule.png) | The configuration dialog shows recurrence, editing, deletion, cancellation, and save controls. |
| Reader completion rating and linked-version follow-up | [Preference prompt](reader-completion-preference.png) and [matching-version follow-up](reader-linked-version-follow-up.png) | The two prompts keep the local preference decision separate from the cross-source continuation. |
| Reader timer and Jump to last read | [Reader guide](../feature-guides/reading.md) | The Reader tools family is represented visually by schedule and completion screens; the guide gives the timer and chapter-jump steps. |
| Action History | [Screenshot](action-history-undo.png) | A completed local preference change and its Undo action. |
| Extension export | [Screenshot](extension-export-confirmation.png) | The executable-file boundary and the app data excluded from the export. |
| Exact-file cleanup | [Export guide](../feature-guides/sharing.md) | The export family is represented visually by its consent screen; the guide explains the exact-document cleanup result and its limits. |
| OCR Search Downloads | [Screenshot](ocr-search-downloads.png) and [feature guide](../feature-guides/ocr.md) | Indexed-page totals, storage use, indexing options, and cleanup controls. |
| Backup and restore | [Screenshot](backup-and-restore.png) | Backup and restore controls, scheduling, status, warnings, and progress settings. |
| Local Tracking progress and refresh | [Decimal progress](v0.8.23/local-tracking-decimal-progress.png) and [refreshed chapter list](v0.8.23/local-tracking-refresh-read-markers.png) | A saved decimal chapter, then read markers that agree with it after the sheet is reopened; synthetic test content. |
| Switch source in the Reader | [Screenshot](v0.8.24/alternate-source-chooser.png) | Source names are placeholders; the manga title is visible. |
| Komikku FC What's New | [Screenshot](v0.8.24/whats-new.png) | The v0.8.24 notes; no personal content. |
| Extension operations | [Export confirmation](extension-export-confirmation.png) | The package boundary and the app data excluded from an export. |
| When sources or links fail | [Feature explanation](../feature-guides/safety.md) | Written explanations of checking, rejection, cancellation, and cleanup behavior. |

Screenshots illustrate the workflows described above, including partial results and unavailable previews. Some controls have changed since these images were captured. The Best Version images show chapter selection and preview comparison but do not show the newer **View in Reader** action; see the [versions guide](../feature-guides/versions.md) for its current controls.

## Technical reference

The XML files in [`technical-reference/`](../technical-reference/) list each feature's screens, possible states, related code, privacy rules, and screenshot hashes. They are written references, not raw Android screen dumps, and they do not contain coordinates, device identifiers, manga titles, source names, URLs, account data, or local paths.

## Maintenance rule

Update the matching XML entry when a screen, visible control, privacy rule, or screenshot changes. A code change does not need a new screenshot when the screen still looks and behaves the same, but its links to the code must remain accurate.
