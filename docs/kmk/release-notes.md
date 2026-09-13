# Komikku FC public release notes

This page summarizes the features included in the current version. The in-app **Komikku FC What's new** screen keeps the detailed history of changes.

## Current Komikku FC feature version

The current in-app feature version is **Komikku FC v0.8.22**.

## App Updates

Komikku FC updates are distributed through this fork's [Releases page](https://github.com/FancyCorey/komikku-fc/releases). A release must be published with a compatible APK before you can download and install it. This page is not an announcement that a new public APK is already available.

Use **More > About > Check for updates** to check manually. Automatic discovery depends on the installed version's update checker, the published release, network access, and your update settings; notifications also require Android notification permission. Installing over an existing app requires a compatible package and signing certificate. Do not assume a development APK can replace your public installation.

Some older releases compare Komikku's version rather than the Komikku FC feature version and may miss a newer fork release. If no update appears, check the Releases page and read that release's installation instructions. Choose an APK compatible with your device and current installation. If Android reports an incompatible update, keep your existing installation and back up your data rather than uninstalling it to force the update.

## Included feature families

- Personalized For You and Top Picks views with your manga filters, results from sources that remain available, optional recent-catalogue discoveries, repeat-display reordering, bulk rating actions, and status explanations for each source.
- Recommendations based on rated or linked manga groups, with results appearing source by source, a configurable initial result count, and separate explanations for slow or failed sources.
- Shareable recommendation bundles and an import review that identifies missing or uncertain matches before you choose what to add to your library.
- Searchable, sectioned Recommendation Settings covering taste and tags, known or rated manga, minimum chapters, source priority, matching, result limits, discovery, cache, and maintenance.
- Rating-derived tag suggestions, taste diagnostics, source-quality marks and history, Source Evaluation continuation and reassessment, and searchable or sortable Sources to Try.
- Love, Like, Dislike, and Not Interested as equal preference choices, with searchable collections, bulk actions, linked-version groups, preferred versions, and Action History undo that protects later changes.
- Rating can include unrated confirmed versions, and group management merges every selected group and exposes the same commands from More and the overflow menu.
- New Local Tracking entries infer Plan to read, Reading, or Completed from actual reading state; On hold and Dropped remain manual choices.
- Rated Manga can choose a confirmed group's primary version from the version read most, then the one rated first. The behavior can be turned off, and a manual primary always wins.
- Rating propagation, linked-version local tracking, automatic local status, tracker-to-local updates, tracking from ratings, and confirmed-version progress sharing start enabled and can be changed independently.
- Cross-source matching and Best Version comparison with chapter selection, independent preview retry, full-screen samples, a keep-current baseline, and a separate handoff to Komikku's migration flow.
- Active-reading timer, recurring reading schedule, deferred completion preference and linked-version rating, and Jump to last read.
- Settings switches stay beside their labels while long labels and descriptions wrap within the available space.
- Tracking settings opened from the tracker use the available width without an unrelated settings category pane beside them.
- Tracking and recommendation settings explain their effect in everyday language, including which linked manga versions are affected and whether progress comes from the app or a connected tracker.
- Local Tracking uses the same scrollable chapter picker as external trackers, while still allowing decimal or unusual chapter numbers to be entered manually.
- Local progress can be corrected from chapter zero onward, and the local tracker title opens its confirmed source versions without replacing the separate global search for new versions.
- Each confirmed local version can independently join or leave reading-progress sharing, and later tracking updates preserve that choice.
- Tracker refresh immediately replays imported progress into local and confirmed-source chapters, then waits for chapter rows when a linked source is still loading and cancels cleanly when navigation changes.
- Whole-number tracker progress can follow a complete consecutive reading order when a source begins at a later chapter number, keeping read chapters and Resume aligned. Gapped and fractional sequences are left unchanged rather than guessed.
- Tracking settings can turn reading-order matching off for imported tracker progress and use chapter numbers only; exact chapter matches continue to sync normally.
- Turning off linked-version local tracking now prevents rating and tracker-refresh paths from attaching or consolidating other confirmed versions, while a rated manga can still start its own local tracking when that separate setting is enabled.
- Restoring Local Tracking reconciles saved progress with chapter rows so read markers and Resume return together.
- Opening or refreshing another version that shares your Local Tracking entry marks chapters through your saved progress as read, including duplicate translations, independently of external-tracker sync. Versions opted out of sharing do not replay inherited progress, and saved chapter matches are not replaced with guesses.
- Opening the Tracking sheet also refreshes local read markers, so its saved chapter and the chapter list agree immediately when reconciliation succeeds.
- Starting a reading date or changing the local reading status no longer restores an older chapter number over the chapter just finished.
- Linked-version loading ignores stale results after the dialog closes and handles disappearing sources without escaping as an app crash.
- For You's minimum-chapter filter now leaves fresh candidates eligible until their source chapter list is known locally, instead of treating unloaded candidates as zero-chapter results and reducing a source row to one or two cards.
- For You source diagnostics distinguish the current refresh count from the rolling Top Picks contribution behind a “Great fit” label.
- Completion rating appears only for a newly completed final chapter, skips rereads and already-rated manga, and excludes already-rated versions from follow-up choices.
- Alternate-source reading remains discoverable and switchable without adding a manga to the library or leaving selection shading, favorite icons, or workflow markers on ordinary cards. Adding it to the library remains an explicit action.
- Long-pressing search results while choosing alternate versions now selects them without changing whether they are in the library.
- Local Tracking includes a settings shortcut that returns to the tracking sheet, and active alternate-source reading keeps a persistent source-switch action.
- Alternate-source chapter selection starts at the closest matching chapter and shows scanlation groups beside duplicate chapter numbers. You can change the selected chapter before opening it.
- The alternate-source chooser identifies the source you are currently reading from before listing other matches, so switching sources is easier to understand.
- Switching back offers the primary source's chapters at your current reading position. Saved source links are available from either manga so you can switch again later.
- Every Best Version card offers **View in Reader**, including cards whose preview samples failed, so you can inspect the full chapter and return to the comparison screen.
- Best Version previews now show **Preview failed** with **Retry** when a sample image stays unresolved, instead of leaving a blank preview area.
- Best Version fullscreen previews keep the title and source readable over light manga pages with a dark header scrim.
- Best Version empty results now clearly offer **Keep current version** when no alternate match is found.
- Installed extensions cannot replace the built-in Local source used by on-device manga.
- Local, cancellable OCR indexing and search for downloaded pages, with scoped cleanup and no recognized text in backup or sync.
- Komikku FC backup and restore support for ratings, recommendation settings, linked manga and preferred versions, source quality, evaluation results, and Local Tracking. Taste profile and Local tracking are separate backup choices; include both for linked reading progress.
- Android document-based recommendation and extension export with exact-created-file cleanup, plus supported package install and removal operations.
- Evaluation Mode, diagnostics with limited identifying details, Action History entries that distinguish supported undo from events you cannot reverse locally, navigation warnings, and recovery information for extension failures. Fatal crashes are not guaranteed to be contained.
- A grouped in-app Komikku FC change history that remains separate from Komikku's own release notes.

## Guides

The [feature guides](feature-guides/README.md) cover recommendations, ratings, local tracking, source comparison, reading tools, backups, and extension management. The [local tracking guide](feature-guides/local-tracking.md) explains linked versions and automatic progress settings.

## Compatibility with Komikku

Komikku FC continues to use Komikku's existing Library, Browse, Reader, Settings, backup, tracking, extension, and migration flows. Recoverable source failures can leave other sources available; not every fatal crash can be contained. Cancelling stops further work, but changes already completed or partially saved can remain. Cancellation is not rollback, including for changes in Android or on an outside service.

## Fork identity

Komikku FC is an independent fork. It keeps the original Komikku artwork, uses the package name `app.komikku.kmk`, and uses this fork's releases for its own updates. The repository is now `FancyCorey/komikku-fc`; links using the former `komikku-KMK` name redirect to it. Official Komikku releases and support channels remain separate.

## Known limitation

Bulk preference actions made directly from For You are recorded in Action History, but the immediate completion message does not provide an inline **Undo** action. Reversal remains available through Action History when its conflict checks allow it.
