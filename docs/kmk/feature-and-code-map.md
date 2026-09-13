# Feature and code map

This page helps contributors move from a visible feature to the code that owns it. It lists what each feature does, its main implementation area, and the screen states that must remain supported. For instructions, use the [user guide](user-guide.md); for behavior and diagrams, use the [feature guides](feature-guides/README.md).

| Feature family | User-visible behavior | Primary implementation owners | Expected states |
| --- | --- | --- | --- |
| [For You](feature-guides/recommendations.md) | Personalized source rows, exploration candidates, filters, exposure-aware ordering | `exh/recs/BrowsePersonalRecommendationsScreenModel.kt`, `exh/recs/BrowsePersonalRecommendationsTab.kt` | loading, loaded, partial, empty, recoverable error |
| [Top Picks and recommendation bundles](feature-guides/recommendations.md) | Review combined top matches; export or import a validated recommendation set | `exh/recs/TopPicksScreen.kt`, `exh/recs/share/` | loaded, partial, export chooser, import review, invalid, library-add result |
| [Group recommendations](feature-guides/recommendations.md) | Find related manga for a rated or linked group with progressive source rows | `exh/recs/group/`, `exh/recs/GroupPreviewLoadCoordinator.kt` | loading, partial, loaded, row timeout, row error, cancelled |
| [Ratings and preferences](feature-guides/ratings.md) | Love, Like, Dislike, Not Interested, collections, reversible transitions | `exh/recs/loved/`, `eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt` | neutral, loved, liked, disliked, not interested |
| [Local tracking](feature-guides/local-tracking.md) | Save status, chapter, score and dates without an external account; share progress across participating confirmed versions | `eu/kanade/domain/track/interactor/RecordLocalTrackedChapterProgress.kt`, `eu/kanade/domain/track/interactor/SyncLocalTrackingFromExternal.kt`, local tracker repository | untracked, planned, reading, on hold, completed, dropped, shared, opted out |
| [Source Evaluation](feature-guides/source-evaluation.md) | Evaluate source fit, continue or reassess work, show short explanations | `exh/recs/evaluation/` | ready, running, partial, completed, cancelled, failed |
| [Taste and source-quality diagnostics](feature-guides/source-evaluation.md) | Explain rating-derived tag suggestions, source observations, quality marks, and recovery actions | `exh/recs/settings/RecommendationDiagnosticsSettingsScreen.kt`, `exh/recs/settings/QualitySignalHistoryScreen.kt` | summary, history, limited evidence, marked, cleared |
| [Sources To Try](feature-guides/sources.md) | Suggest non-installed sources from taste and evaluation signals | `exh/recs/discovery/`, recommendation settings screen models | loading, suggestions, empty, install screen, unavailable |
| [Cross-source matching](feature-guides/versions.md) | Find related versions, create links and groups, choose a primary version | `exh/recs/matching/` | searching, candidates, selected, linked, no matches, partial failure |
| [Best Version](feature-guides/versions.md) | Compare linked versions, inspect a full chapter with View in Reader even when previews fail, and confirm migration separately | `exh/recs/bestversion/` | loading, comparable, preview failed, reader return, insufficient data, confirmed, cancelled, failed |
| [Source runtime](feature-guides/troubleshooting.md) | Report recoverable failures for the affected source and preserve cancellation; fatal crashes are not guaranteed to be contained | `eu/kanade/tachiyomi/source/SourceRuntime.kt` | success, skipped, recoverable failure, cancellation |
| [Alternate-source reading](feature-guides/reading.md) | Switch both ways within an active source pair, review matching chapters and translation labels, and correct a match; Add to library remains a separate explicit action | reader bridge and alternate-source dialogs | choosing source, choosing chapter, suggested match, corrected match, switched, cancelled, unavailable |
| [Reader controls](feature-guides/reading.md) | Timer, local schedule, completion prompt, linked-version ratings, jump to last read | `eu/kanade/tachiyomi/ui/reader/`, manga screen and toolbar | allowed, restricted, grace, completed, prompt after exit, no read position |
| [OCR search](feature-guides/ocr.md) | Build and search a local text index for downloaded chapter pages | `exh/ocr/`, `ocr_indexed_page.sq` | no index, indexing, searchable, partial, cancelled, failed |
| [Evaluation Mode](feature-guides/sharing.md) | Hide source and repository names without changing app behavior | `exh/util/EvaluationMode*.kt` | disabled, enabled, reversible action available, outside action cannot be undone |
| [Export and diagnostics](feature-guides/sharing.md) | Export through Android's document picker; diagnostic reports limit identifying details but still need review before sharing | settings/export coordinators and evaluation diagnostic policies | ready, chooser, success, cancelled, failed, created-file cleanup |
| [Backup and portability](feature-guides/backups.md) | Save supported ratings, settings, linked and preferred versions, source quality, evaluations and Local Tracking through separate backup choices | backup creators/restorers, taste and local tracker repositories | selected, file created, restored, partial, failed |
| [Extension operations](feature-guides/extensions.md) | Use the selected installer, manage saved safety restrictions, and export only selected extensions; installation and trust remain separate | extension manager, installers, `ExtensionApkExporter.kt` | ready, consent, success, cancelled, failed, created-file cleanup |
| [Security and integration](feature-guides/safety.md) | Check outside input, report failures without leaking raw details, preserve cancellation, and limit saved changes | deep-link, WebView, source runtime, export, and Action History code | accepted, rejected, isolated failure, cancelled, confirmed change |
| [Komikku FC change history](feature-guides/overview.md) | Present current and historical Komikku FC changes in grouped, readable release families | `exh/recs/KmkRecsReleaseNotes.kt`, `eu/kanade/tachiyomi/ui/more/KmkRecsWhatsNewScreen.kt` | current family, collapsed older family, expanded history, acknowledged |

## Settings

Recommendation settings use the app's normal list and section patterns. They are grouped into For You sources, Taste and filters, Source Evaluation, Sources to try, and Management and diagnostics. Ranking and exposure values that users can change are stored as settings and checked before use; they are not hidden in screen code.

## Failure behavior

Recoverable source failures can leave other rows available; not every fatal crash can be contained. Cancellation stops further work but can leave completed or partially saved changes. Short error categories are not a guarantee that every report or screenshot hides personal details. A screen that needs a missing extension, network response, tracker account, or Android document provider should explain the unavailable action rather than implying success. Check the feature's guide for its specific failure and recovery choices.

## Known limitation

Bulk preference changes made from For You can be reversed through Action History. The confirmation message does not yet include its own **Undo** button, so the reversal is available but less obvious than it should be.
