# Komikku FC public release notes

This page describes the latest published release. **v0.8.24** is the latest published version; the previous release was **v0.8.23.1**. The in-app **Komikku FC What's new** screen keeps the detailed history of changes.

## Release status

The latest published Komikku FC feature version is **v0.8.24**, titled **Komikku FC v0.8.24 — updated to the latest Komikku**. It replaces **v0.8.23.1**.

v0.8.24 includes every change on Komikku's main branch up to 12 October 2026: everything in Komikku v1.14.1, plus the later changes made before that date. The Komikku FC additions listed below are separate from those Komikku changes.

## App Updates

Download [Komikku FC v0.8.24](https://github.com/FancyCorey/komikku-fc/releases/tag/v0.8.24) from this fork's Releases page. The previous release, [v0.8.23.1](https://github.com/FancyCorey/komikku-fc/releases/tag/v0.8.23.1), remains available there. Choose the APK for your device and read its installation notes. Future updates are distributed through the same Releases page.

### Updated to the latest Komikku in v0.8.24

- **Komikku changes:** includes the latest Komikku changes, among them a new database engine with fixes for "database is locked" errors, cleanup of leftover rows in your database, screen transition and back gesture fixes, and newer libraries. Your library carries over when you update.

### Added in v0.8.24

- **Recent-reading recommendations:** For You can give extra weight to the tags of manga you read in the last 1 to 30 days. Older ratings still count. Blocked and disliked tags are never boosted, and titles rated Dislike or Not interested are ignored.
- **Use less data on recommendations:** Automatic, Always, and Never choices under **Speed and data**. Saving data has three effects:
  - it skips optional discovery pages;
  - it tries fewer searches per source;
  - it stops For You once about half the usual rows are ready.

  A manga's Recommendations and the library recommendation search look in at most 6 sources instead of 20. Your filters are unchanged.
- **Looser matches:** when a source returns manga but none of them match your taste, its row can show up to four of them, labelled as looser matches, instead of an empty row. Blocked tags, dislikes, and ratings still apply, and Top Picks stays strict. Turn it off under **For You feed**.
- **Help for failing sources:** a failing For You row says what probably went wrong and offers Try again, Open site, Check source, Manage extension, and Don't use in For You. Check source tells whether the source still works when browsing or is broken altogether.
- **Automatic source check:** Find new sources can re-check your sources on a schedule: every 7, 14, or 30 days, or any number of days from 1 to 90. It is off until you turn it on, and it can wait for Wi-Fi and for the battery not to be low. It never installs or removes a source, and it never changes your source order.
- **Alternate sources in Reader settings:** a new **Settings > Reader** section explains how reading from another source works and lets you choose when it is offered. The first time you use it, a short explanation shows what will happen and how to come back.

### Improved and fixed in v0.8.24

- **Clearer group rating for linked versions:** Track Other Versions now makes the confirmed group's current rating easier to see and change. Group rating already existed; this is an improvement, not a new rating system. The control shows the rating or Not rated and can change every locally tracked confirmed version at once. New locally tracked versions receive the group rating when automatic sync is on, without overwriting a rating you set yourself. See [Linked versions and ratings](feature-guides/recommendation-settings.md#linked-versions-and-ratings).

- Recommendation Settings is rebuilt with one entry per feature: Your taste, For You feed, Focus modes, Find new sources, Source evaluation, Speed and data, Linked versions and ratings, and Troubleshooting. Each row shows its current value, and the selected row keeps its filled icon. Every earlier setting is still available.
- Source evaluation can also be opened from the For You menu. It shows results right after its Start button and explains when Start is unavailable.
- Focus tags are now grouped chips. Tags that sources number differently, such as Adventure (157) and Adventure 27, are shown as one tag. When your sources use both a plural and a singular spelling, the plural joins the singular.
- The minimum chapter count now applies to titles you have never opened. After a refresh, For You checks up to 20 of the shown titles and remembers their counts for two weeks.
- A source that is far slower than the others in the same refresh now ends as an error row with Try again. The time limit follows how long your other sources took, between 30 seconds and 3 minutes.
- When a source's searches find nothing, For You now falls back to that source's Popular page. An empty earlier result is no longer reused from the cache.
- One failed image or page list no longer makes the rest of a large download from that source fail. Reading and downloading are held back only when the extension itself is broken or incompatible.
- Pulling down to refresh a tracked manga always uploads your newer reading progress. Opening a manga asks your trackers for progress at most once every 15 minutes, and not at all while offline.
- Versions tracked on the same tracker entry now share their tracker progress and their private or public setting. Versions confirmed as the same manga through another version (A = B and B = C) share one local-tracking record.
- Titles you rated, added, or read since an earlier For You refresh no longer reappear after Refresh.
- Closing the Focus picker without Apply keeps your previous focus, and Clear focus always clears it.
- The local-tracking versions list is titled Confirmed versions and hides source names when name hiding is on.
- The reading timer screen has larger text and three sections: how long, when to warn you, and what happens when time is up.

### Added in v0.8.23

- **OCR search:** search text in downloaded pages, follow indexing progress, recover interrupted indexing, and open results in the Reader.
- **Updater recovery:** resume interrupted app updates and extension operations, and clean up incomplete downloads or installs.

The v0.8.23 feature set also improves Best Version search, linked-version tracking, reader source switching, For You recommendations, extension recovery, accessibility, restore behavior, and app identity. See the in-app **What's new** screen and the complete release notes for all included changes.

### Fix one updates in v0.8.23.1

- Retry interrupted app-update work. A retry does not guarantee that a partial download resumes from its previous byte.
- Retry a failed alternate-source search or chapter lookup from the Reader.
- Apply the selected warning and chapter-end choices when starting the reading timer.
- Prevent a second extension-recovery attempt from interrupting an install already in progress.

Use **More > About > Check for updates** to check manually. Automatic discovery depends on the installed version's update checker, the published release, network access, and your update settings; notifications also require Android notification permission. Installing over an existing app requires a compatible package and signing certificate. Do not assume a development APK can replace your public installation.

Some older releases compare Komikku's version rather than the Komikku FC feature version and may miss a newer fork release. If no update appears, check the Releases page and read that release's installation instructions. Choose an APK compatible with your device and current installation. If Android reports an incompatible update, keep your existing installation and back up your data rather than uninstalling it to force the update.

## Komikku FC feature overview
The overview below covers capabilities across releases; it is not a list of features shipped only in v0.8.24. The v0.8.24 changes listed above are available from the published v0.8.24 release. Expand a topic for details.

<details>
<summary>For You, settings, and source checks</summary>

[Open the guide](feature-guides/recommendations.md).

- Personalized For You and Top Picks views with your manga filters, results from sources that remain available, optional recent-catalogue discoveries, repeat-display reordering, bulk rating actions, and status explanations for each source.
- For You can:
  - give extra weight to recently read manga;
  - show labelled looser matches in a source's own row;
  - suggest next steps for a failing source;
  - end a source that is far slower than the rest of the refresh;
  - fall back to a source's Popular page when its searches find nothing.
- A data-saving mode for recommendations can be set to Always or Never, or to Automatic, which saves data on metered or slow connections.
- Recommendations based on rated or linked manga groups, with results appearing source by source, a configurable initial result count, and separate explanations for slow or failed sources.
- Shareable recommendation bundles and an import review that identifies missing or uncertain matches before you choose what to add to your library.
- Searchable Recommendation Settings with one entry per feature: Your taste, For You feed, Focus modes, Find new sources, Source evaluation, Speed and data, Linked versions and ratings, and Troubleshooting. Together they cover taste and tags, languages, known or rated manga, minimum chapters, source priority, matching, result limits, discovery, data saving, cache, and maintenance.
- Focus modes list tags as grouped chips: format, demographic, and presentation and access first, then your own tags, then genres grouped by kind. Unrecognized tags are folded under More tags.
- Rating-derived tag suggestions, taste diagnostics, source-quality marks and history, Source evaluation continuation and reassessment, an optional scheduled source check, and searchable or sortable Sources to Try.
- Settings switches stay beside their labels while long labels and descriptions wrap within the available space.
- Tracking and recommendation settings explain their effect in everyday language, including which linked manga versions are affected and whether progress comes from the app or a connected tracker.
- Active For You tag filters continue searching eligible sources within the existing attempt limit when earlier results do not match, rather than stopping after enough unfiltered rows.
- After a refresh, For You's minimum-chapter filter checks the chapter lists of up to 20 shown titles, removes those below the minimum, and remembers their counts for two weeks. A title whose count is still unknown stays eligible; it is not treated as having zero chapters.
- For You source diagnostics distinguish the current refresh count from the rolling Top Picks contribution behind a “Great fit” label.

</details>

<details>
<summary>Ratings and linked-version groups</summary>

[Open the guide](feature-guides/ratings.md).

- Love, Like, Dislike, and Not Interested as equal preference choices, with searchable collections, bulk actions, linked-version groups, preferred versions, and Action History undo that protects later changes and shows accurate counts for group additions and moves.
- Rating can include unrated confirmed versions, and group management merges every selected group and exposes the same commands from More and the overflow menu.
- Rated Manga can choose a confirmed group's primary version from the version read most, then the one rated first. The behavior can be turned off, and a manual primary always wins.
- Best Version adds the selected manga to its confirmed link group only after identity confirmation succeeds and before migration begins. Link group management shows covers and titles and supports adding, moving, merging, removing, ungrouping, and undoing changes. Cancelling an ungroup keeps the existing group intact.

</details>

<details>
<summary>Local and external tracking</summary>

[Open the guide](feature-guides/local-tracking.md).

- New Local Tracking entries infer Plan to read, Reading, or Completed from actual reading state; On hold and Dropped remain manual choices.
- Sharing ratings, linked-version local tracking, automatic local status, importing tracker progress, tracking from ratings, and sharing progress between confirmed versions start enabled and can be changed independently.
- When a confirmed source has fewer chapters than the saved Local Tracking progress, the shared chapter number stays at the higher value while that source is marked read through its highest available chapter at or below it. A saved chapter URL is preferred when its row exists; fallback chapter numbers resolve duplicate scanlation rows consistently.
- The tracker's Local Tracking row has a shortcut to Tracking settings before local tracking exists; settings use the available width without an unrelated settings category pane beside them.
- Adding an external tracker prevents repeated submissions while linking; if follow-up synchronization fails after the link is saved, the app reports the partial result and keeps the link. If the tracker accepts the title but Komikku cannot save the local link, the app warns you to check the tracker before trying again.
- Local Tracking uses the same scrollable chapter picker as external trackers, while still allowing decimal or unusual chapter numbers to be entered manually.
- Local progress can be corrected from chapter zero onward, and the local tracker title opens its confirmed source versions without replacing the separate global search for new versions.
- Each confirmed local version can independently join or leave reading-progress sharing, and later tracking updates preserve that choice.
- Tracker refresh applies imported progress to local tracking and confirmed versions' chapter read marks. If a source is still loading its chapter list, the app waits for it; leaving the page cancels pending work.
- Whole-number tracker progress can follow a complete consecutive reading order when a source begins at a later chapter number, keeping read chapters and Resume aligned. Gapped and fractional sequences are left unchanged rather than guessed.
- Tracking settings can turn reading-order matching off for imported tracker progress and use chapter numbers only; exact chapter matches continue to sync normally.
- Turning off linked-version local tracking now prevents rating and tracker-refresh paths from attaching or consolidating other confirmed versions, while a rated manga can still start its own local tracking when that separate setting is enabled.
- Opening or refreshing another version that shares your Local Tracking entry marks chapters through your saved progress as read, including duplicate translations, independently of external-tracker sync. Versions opted out of sharing do not replay inherited progress, and saved chapter matches are not replaced with guesses.
- Opening the Tracking sheet also refreshes local read markers, so its saved chapter and the chapter list agree immediately when reconciliation succeeds.
- Starting a reading date or changing the local reading status no longer restores an older chapter number over the chapter just finished.
- Linked-version loading ignores stale results after the dialog closes and handles disappearing sources without escaping as an app crash.

</details>

<details>
<summary>Best Version and matching sources</summary>

[Open the guide](feature-guides/versions.md).

- Cross-source matching and Best Version comparison with chapter selection, independent preview retry, full-screen samples, a keep-current baseline, and a separate handoff to Komikku's migration flow.
- Find Best Version searches the sources and languages available to global search while respecting your Results per source limit. Previously confirmed matches start selected, your title-matching default applies to other results, and preview settings control automatic page samples.
- Every Best Version card offers **View in Reader**, including cards whose preview samples failed, so you can inspect the full chapter and return to the comparison screen.
- Best Version previews now show **Preview failed** with **Retry** when a sample image stays unresolved, instead of leaving a blank preview area.
- Best Version fullscreen previews can retry a page when its image download or decoding fails, while keeping pages that already loaded intact.
- Best Version lets you continue with matches already found while slower sources are still searching.
- Same-manga source search marks a slow source as timed out in Best Version, version matching, and alternate-source reading while results from other sources can still appear.
- Best Version fullscreen previews keep the title and source readable over light manga pages with a dark header scrim.
- Best Version empty results now clearly offer **Keep current version** when no alternate match is found.

</details>

<details>
<summary>Reader, timer, and alternate sources</summary>

[Open the guide](feature-guides/reading.md).

- Active-reading timer, recurring reading schedule, deferred completion preference and linked-version rating, group rating from Track Other Versions, and Jump to last read.
- Completion rating can be offered on leaving an eligible primary-source reader session after reaching the latest chapter's end. Already-rated manga are skipped; reading that chapter again can still qualify while unrated. Follow-up choices exclude already-rated versions.
- An Alternate sources section in Settings > Reader chooses when reading from another source is offered: when chapters are missing, when a source has no next chapter, or both.
- Alternate-source reading remains discoverable and switchable without adding a manga to the library or leaving selection shading, favorite icons, or workflow markers on ordinary cards. Adding it to the library remains an explicit action.
- Long-pressing search results while choosing alternate versions now selects them without changing whether they are in the library.
- Local Tracking includes a settings shortcut that returns to the tracking sheet, and active alternate-source reading keeps a persistent source-switch action.
- Alternate-source chapter selection starts at the closest matching chapter and shows scanlation groups beside duplicate chapter numbers. You can change the selected chapter before opening it.
- The alternate-source chooser identifies the source you are currently reading from before listing other matches, so switching sources is easier to understand.
- Switching back offers the primary source's chapters at your current reading position. Saved source links are available from either manga so you can switch again later.
- Reader errors show reader-specific messages for network, file, and extension failures, with a page-load message for other reader errors.

</details>

<details>
<summary>Backups, OCR, and extension recovery</summary>

[Open the guide](feature-guides/backups.md).

- Restoring Local Tracking reconciles saved progress with chapter rows so read markers and Resume return together.
- Cancelling a restore finishes applying its local reading progress before stopping. Library restore removes OCR records whose pages no longer exist, while keeping valid indexed pages.
- If an extension replacement is interrupted, the app restores the previous version before retrying. Cleanup problems with one extension do not block other extensions, and files the app cannot identify are left untouched.
- Installed extensions cannot replace the built-in Local source used by on-device manga.
- Local OCR indexing and search for downloaded pages can be cancelled; indexed pages can be removed by manga or chapter. Recognized text is not included in backups or sync.
- Komikku FC backup and restore support for ratings, recommendation settings, linked manga and preferred versions, source quality, and Local Tracking. Source evaluation results and run history are not included in exported backups and are not rebuilt by restore. Taste profile and Local tracking are separate backup choices; include both for linked reading progress.
- Android document-based recommendation and extension export with exact-created-file cleanup, plus supported package install and removal operations.

</details>

<details>
<summary>Privacy, undo, and change history</summary>

[Open the guide](feature-guides/sharing.md).

- Name hiding, diagnostics with limited identifying details, Action History entries that distinguish supported undo from events you cannot reverse locally, navigation warnings, and recovery information for extension failures. Fatal crashes are not guaranteed to be contained.
- A grouped in-app Komikku FC change history that remains separate from Komikku's own release notes.

</details>

## Guides

The [feature guides](feature-guides/README.md) cover recommendations, ratings, local tracking, source comparison, reading tools, backups, and extension management. The [local tracking guide](feature-guides/local-tracking.md) explains linked versions and automatic progress settings.

## Compatibility with Komikku

Komikku FC v0.8.24 is up to date with Komikku's main branch as of 12 October 2026, which covers Komikku v1.14.1 and the changes made after it. Komikku FC continues to use Komikku's existing Library, Browse, Reader, Settings, backup, tracking, extension, and migration flows. Recoverable source failures can leave other sources available; not every fatal crash can be contained. Cancelling stops further work, but changes already completed or partially saved can remain. Cancellation is not rollback, including for changes in Android or on an outside service.

## Fork identity

Komikku FC is an independent fork. It has its own app logo, uses the package name `app.komikku.kmk`, and uses this fork's releases for its own updates. Supported launchers can use the monochrome logo for themed icons. The repository is now `FancyCorey/komikku-fc`; links using the former `komikku-KMK` name redirect to it. Official Komikku releases and support channels remain separate.

## Known limitations

Name hiding covers supported labels, not every screen. The download queue can still show source names **even when name hiding is enabled**. Check the whole image before sharing a screenshot; manga artwork and reader pages are not hidden.

**Text and layout:** descriptions in **Confirmed versions** can show Markdown markers such as asterisks instead of formatting. The reading timer's **60 min** button can wrap onto two lines on narrow screens.

**Update checks still limited:** encrypted-database upgrades on the new database engine and external-tracker updates on a signed-in device were not confirmed before this release. Keep a current backup before updating, and check progress on your tracker after using it.

**Undo after bulk changes:** preference changes made to several manga at once in For You are recorded in Action History, but their completion message has no **Undo** button. Use Action History to reverse a supported change if a later edit has not replaced it.

**Local files:** Local Tracking cannot be started for titles from the built-in Local source. Choosing **Track locally** does not save an entry and shows a general error. Use a title from an installed source, or an external tracker, instead.

**Unknown chapter counts:** the minimum chapter count only removes titles whose chapter list has been checked. A title can stay visible while its count is unknown, and this extra check does not run while saving data.

**Looser matches:** these come from what a source returned for your search, not a quality assessment. Poor source tags can still lead to unrelated titles.

**Slow sources:** a source is stopped as too slow only after other sources in the same refresh have finished. If you use very few sources, it can take up to 3 minutes to show an error.

**MangaBaka:** the tracker is unavailable in this release because its sign-in is not configured.
