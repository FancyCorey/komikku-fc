# Feature and code map

This page helps contributors move from a visible feature to the code that owns it. It lists each feature's purpose, where to start in the code, and the states to preserve when changing it. These are review cases, not a claim that every case has passed a test. For instructions, use the [user guide](user-guide.md); for behavior and diagrams, use the [feature guides](feature-guides/README.md).

## [For You](feature-guides/recommendations.md)

Personalized source rows, recent-reading preferences, filters, and fewer repeated suggestions.

**Code:** [For You screen model](../../app/src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt), [For You tab](../../app/src/main/java/exh/recs/BrowsePersonalRecommendationsTab.kt).

**States to preserve:** loading, loaded, partial, empty, recoverable error.

## [Top Picks and recommendation bundles](feature-guides/recommendations.md)

Review combined top matches; export or import a validated recommendation set.

**Code:** [Top Picks screen](../../app/src/main/java/exh/recs/TopPicksScreen.kt), [Recommendation sharing](../../app/src/main/java/exh/recs/share/).

**States to preserve:** loaded, partial, export chooser, import review, invalid, library-add result.

## [Group recommendations](feature-guides/recommendations.md)

Find related manga for a rated or linked group with progressive source rows.

**Code:** [Group recommendations](../../app/src/main/java/exh/recs/group/), [Group preview loading](../../app/src/main/java/exh/recs/GroupPreviewLoadCoordinator.kt).

**States to preserve:** loading, partial, loaded, row timeout, row error, cancelled.

## [Ratings and preferences](feature-guides/ratings.md)

Love, Like, Dislike, Not Interested, collections, reversible transitions.

**Code:** [Rating collections](../../app/src/main/java/exh/recs/loved/), [Manga screen model](../../app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt).

**States to preserve:** neutral, loved, liked, disliked, not interested.

## [Local tracking](feature-guides/local-tracking.md)

Save status, chapter, score and dates without an external account; share progress across participating confirmed versions.

**Code:** [Record chapter progress](../../app/src/main/java/eu/kanade/domain/track/interactor/RecordLocalTrackedChapterProgress.kt), [Import tracker progress](../../app/src/main/java/eu/kanade/domain/track/interactor/SyncLocalTrackingFromExternal.kt), [Local tracker storage](../../data/src/main/java/tachiyomi/data/tracker/LocalTrackerRepositoryImpl.kt).

**States to preserve:** untracked, planned, reading, on hold, completed, dropped, shared, opted out.

## [Source evaluation](feature-guides/source-evaluation.md)

Evaluate source fit, continue or reassess work, re-check on a schedule, show short explanations.

**Code:** [Source evaluation](../../app/src/main/java/exh/recs/evaluation/).

**States to preserve:** ready, running, partial, completed, cancelled, failed.

## [Taste and source-quality diagnostics](feature-guides/source-evaluation.md)

Explain rating-derived tag suggestions, source observations, quality marks, and recovery actions.

**Code:** [Taste and source diagnostics](../../app/src/main/java/exh/recs/settings/RecommendationDiagnosticsSettingsScreen.kt), [Source-quality history](../../app/src/main/java/exh/recs/settings/QualitySignalHistoryScreen.kt).

**States to preserve:** summary, history, limited evidence, marked, cleared.

## [Sources To Try](feature-guides/sources.md)

Suggest non-installed sources from taste and evaluation signals.

**Code:** [Source suggestions](../../app/src/main/java/exh/recs/discovery/), [Recommendation settings](../../app/src/main/java/exh/recs/settings/).

**States to preserve:** loading, suggestions, empty, install screen, unavailable.

## [Cross-source matching](feature-guides/versions.md)

Find related versions, create links and groups, choose a primary version.

**Code:** [Same-manga matching](../../app/src/main/java/exh/recs/matching/).

**States to preserve:** searching, candidates, selected, linked, no matches, partial failure.

## [Best Version](feature-guides/versions.md)

Compare linked versions, inspect a full chapter with View in Reader even when previews fail, and confirm migration separately.

**Code:** [Best Version](../../app/src/main/java/exh/recs/bestversion/).

**States to preserve:** loading, comparable, preview failed, reader return, insufficient data, confirmed, cancelled, failed.

## [Source runtime](feature-guides/troubleshooting.md)

Report recoverable failures for the affected source and preserve cancellation; fatal crashes are not guaranteed to be contained.

**Code:** [Source failure handling](../../app/src/main/java/eu/kanade/tachiyomi/source/SourceRuntime.kt).

**States to preserve:** success, skipped, recoverable failure, cancellation.

## [Alternate-source reading](feature-guides/reading.md)

Switch both ways within an active source pair, review matching chapters and translation labels, and correct a match; Add to library remains a separate explicit action.

**Code:** [Reader bridge](../../app/src/main/java/eu/kanade/tachiyomi/ui/reader/bridge/), [alternate-source dialog](../../app/src/main/java/eu/kanade/presentation/reader/AlternateSourceReaderDialog.kt).

**States to preserve:** choosing source, choosing chapter, suggested match, corrected match, switched, cancelled, unavailable.

## [Reader controls](feature-guides/reading.md)

Timer, local schedule, completion prompt, linked-version ratings, jump to last read.

**Code:** [Reader](../../app/src/main/java/eu/kanade/tachiyomi/ui/reader/), [Manga screen model](../../app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt).

**States to preserve:** allowed, restricted, grace, completed, prompt after exit, no read position.

## [OCR search](feature-guides/ocr.md)

Build and search a local text index for downloaded chapter pages.

**Code:** [OCR](../../app/src/main/java/exh/ocr/), [OCR storage](../../data/src/main/sqldelight/tachiyomi/data/ocr_indexed_page.sq).

**States to preserve:** no index, indexing, searchable, partial, cancelled, failed.

## [Name hiding](feature-guides/sharing.md)

Hide supported source and repository labels; exports and other screens can still reveal names.

**Code:** [Name-hiding helpers](../../app/src/main/java/exh/util/).

**States to preserve:** disabled, enabled, reversible action available, outside action cannot be undone.

## [Export and diagnostics](feature-guides/sharing.md)

Export through Android's document picker; diagnostic reports limit identifying details but still need review before sharing.

**Code:** [Settings exports](../../app/src/main/java/exh/recs/settings/), [evaluation diagnostics](../../app/src/main/java/exh/recs/evaluation/).

**States to preserve:** ready, chooser, success, cancelled, failed, created-file cleanup.

## [Backup and portability](feature-guides/backups.md)

Save supported ratings, settings, linked and preferred versions, source quality and Local Tracking through separate backup choices; evaluation results and run history are excluded.

**Code:** [Backup creation](../../app/src/main/java/eu/kanade/tachiyomi/data/backup/create/), [backup restore](../../app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/), [taste data](../../data/src/main/java/tachiyomi/data/taste/).

**States to preserve:** selected, file created, restored, partial, failed.

## [Extension operations](feature-guides/extensions.md)

Use the selected installer, manage saved safety restrictions, and export only selected extensions; installation and trust remain separate.

**Code:** [Extension manager and installers](../../app/src/main/java/eu/kanade/tachiyomi/extension/), [APK export](../../app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionApkExporter.kt).

**States to preserve:** ready, consent, success, cancelled, failed, created-file cleanup.

## [Security and integration](feature-guides/safety.md)

Check outside input, report failures without leaking raw details, preserve cancellation, and limit saved changes.

**Code:** [Deep links and WebView](security-and-integration.md), [source runtime](../../app/src/main/java/eu/kanade/tachiyomi/source/SourceRuntime.kt), [Action History](../../app/src/main/java/exh/util/EvaluationModeActionHistoryScreen.kt).

**States to preserve:** accepted, rejected, isolated failure, cancelled, confirmed change.

## [Komikku FC change history](feature-guides/overview.md)

Present current and historical Komikku FC changes in grouped, readable release families.

**Code:** [Release-note content](../../app/src/main/java/exh/recs/KmkRecsReleaseNotes.kt), [What's New screen](../../app/src/main/java/eu/kanade/tachiyomi/ui/more/KmkRecsWhatsNewScreen.kt).

**States to preserve:** current family, collapsed older family, expanded history, acknowledged.

## Settings

Recommendation settings use the app's normal list and section patterns. The first page lists one entry per feature: Your taste, For You feed, Focus modes, Find new sources, Source evaluation, Speed and data, Linked versions and ratings, and Troubleshooting. Recommendation and repeat-display choices are saved as settings and checked before use.

## Failure behavior

Recoverable source failures can leave other rows available; not every fatal crash can be contained. Cancellation stops further work but can leave completed or partially saved changes. Short error categories are not a guarantee that every report or screenshot hides personal details. A screen that needs a missing extension, network response, tracker account, or Android document provider should explain the unavailable action rather than implying success. Check the feature's guide for its specific failure and recovery choices.

## Known limitation

Bulk preference changes made from For You can be reversed through Action History. The confirmation message does not yet include its own **Undo** button, so the reversal is available but less obvious than it should be.
