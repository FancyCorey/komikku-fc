# Komikku FC user guide

Use this guide to find and use Komikku FC features. Each section starts with the actions to take, then explains the result and any important limitation. The [visual guide](visual-guide/README.md) shows the main screens, while the linked feature guides cover workflows that depend on manga, chapter, source, account, or storage details.

## For You

1. Open **Browse**.
2. Select the **For You** tab.
3. Wait for the recommendations to finish loading. Each row comes from a source that passed your settings and shows its strongest matching tags.
4. Select a manga card to open its details, or select the arrow at the end of a row to see more results from that source.
5. Use refresh after changing ratings, filters, source order, or discovery settings.

![For You with personalized manga rows, matching tags, and hidden source names](visual-guide/for-you-evaluation-mode.png)

*For You in Evaluation Mode. The recommendations stay visible while source names are hidden.*

Before displaying a manga, For You checks your language, blocked genres and tags, source settings, and exclusions. The minimum chapter count hides manga with a known count below your choice; manga with an unknown count can still appear. Personalized matches remain the majority when enough are available. A smaller selection from each source's recent catalogue can add variety, but it must pass the same checks. Repeatedly shown, untouched cards can move lower without being removed. Library manga and rated or tracked manga are not lowered by that repeat-display rule. See [Recommendation settings](feature-guides/recommendation-settings.md) for the display-history window and filters.

If one source fails, results from other sources remain available. When the page is empty or incomplete, read the message on the affected row; the whole app has not necessarily failed.

### Top Picks and bulk actions

1. Open **Top Picks** from For You to review the strongest combined matches.
2. Use a manga card's selection action to enter selection mode, then select additional cards to include them. On a touch screen, the selection action may be a press-and-hold gesture.
3. Apply Love, Like, or Dislike from the selection bar. Open **More** for Not Interested or Clear Rating. With one card selected, version comparison and Open are also available.
4. Read the completion message before leaving selection mode. Supported local changes also appear in Action History.

### Recommendation bundles

1. From the For You overflow menu, choose **Export Top Picks** to create a versioned recommendation bundle through Android's document picker. Source-row and rated-collection export actions create the same bundle format for their current list.
2. To import one, open **Settings > Data and storage > Import recommendation bundle** and choose the JSON file.
3. Review resolved, missing-source, ambiguous, unsupported, and already-in-library entries on the import screen.
4. Select the ready entries you want, then confirm the separate library-add action.

Opening a bundle never adds manga automatically. Invalid structure and unresolved sources remain visible instead of being guessed.

### Group recommendations

1. Open a Loved, Liked, Disliked, or linked-version collection.
2. Open an item's menu and choose its recommendation action, or select a confirmed group and choose **See group recommendations**.
3. Review the progressive source rows. A row can time out or fail without removing results from another source.
4. Open a source row to continue beyond its configured initial preview.

## Recommendation settings

1. From For You, select the settings icon.
2. Choose one of the five sections: **For You sources**, **Taste and filters**, **Source Evaluation**, **Sources to try**, or **Management and diagnostics**.
3. Change a setting with the switches, choices, and dialogs used elsewhere in Komikku.
4. Return to For You and refresh when asked to reload recommendations.

![Recommendation settings divided into five sections](visual-guide/recommendation-settings.png)

*The main settings page keeps related controls together instead of placing every option in one long list.*

You can configure:

- Source order and languages.
- Blocked genres and tags.
- Minimum chapters and result limits.
- The share of recent-catalogue results.
- How long visible cards keep their position.
- Evaluation, matching, quality checks, cache, and diagnostics.
- Search across individual settings and use the quick-access row or For You side panel to move between sections.
- Review taste diagnostics, rating-derived tag suggestions, source-quality history, and explicit recovery actions.

Number settings are checked before use so an invalid saved value does not create an unlimited search or result list.

![Management and diagnostics settings with grouped controls](visual-guide/management-diagnostics.png)

*Management and diagnostics keeps maintenance tools, saved-data controls, and short status summaries in one place.*

## Manga preferences

For chapter progress and reading status, see [Local tracking](feature-guides/local-tracking.md).

See [Ratings and linked versions](feature-guides/ratings.md) to manage your choices and undo supported changes.

1. Open a manga.
2. Use the preference action to choose **Love**, **Like**, **Dislike**, or **Not interested**.
3. The main preference button changes to show your choice. Choosing Love, Like, or Dislike while Not Interested is active replaces Not Interested in one saved change.
4. Use **Clear rating** or **Undo not interested** to return to neutral.
5. Open the corresponding Loved, Liked, Disliked, or Not Interested collection from the For You menu to review those entries.

When Action History is available in Evaluation Mode, supported changes include the value that existed before the change. You can reverse bulk For You actions from Action History. The short confirmation message shown after a bulk action does not currently include its own **Undo** button.

## Local tracking

1. Open a manga and select **Trackers**.
2. In **Local tracking**, select the status, chapter, score, or date to change it. The chapter picker supports scrolling and direct entry, including zero and decimal chapter numbers.
3. Open or refresh a manga to apply its saved local progress to its available chapters.
4. To keep confirmed copies from other sources caught up, enable **Share progress across linked versions** in **Settings > Tracking**. Select the local tracker title to review participating versions and turn **Keep reading progress in sync** off for a version you want to keep separate.

For example, progress at chapter 15 can mark available chapters through the matching point read on another participating version. A confirmed chapter match takes priority; otherwise the app uses the highest available number at or below 15. This does not download pages, make later chapters unread, or send an outside tracker update. If you mark an earlier chapter unread but leave saved progress farther ahead, loading or refreshing can mark it read again. See [Local tracking](feature-guides/local-tracking.md) for matching limits and separate external-tracker settings.

## Source Evaluation

1. Open **Recommendation Settings** and select **Source Evaluation**.
2. Choose how many sources to evaluate at once and review any warning.
3. Select **Start evaluation**.
4. You can leave the screen while evaluation continues.
5. Review completed, skipped, failed, or partial outcomes. A source with too little information may need manual review; that does not prove its manga are a poor match.
6. Use reassessment when installed extensions change or when the app reports stale evaluation data.

![Source Evaluation showing progress, warnings, and reassessment actions](visual-guide/source-evaluation.png)

*Source Evaluation can show useful progress and partial results without displaying raw source errors.*

Source Evaluation samples how well a source fits your preferences and separately checks recommendation searches. It is not a complete catalogue inspection or a security guarantee. Copied diagnostics show short error categories and counts, but can include source and extension names when Evaluation Mode is off. Review the text before sharing it. See [Source Evaluation](feature-guides/source-evaluation.md) for sorting, resets, and recovery actions.

## Sources to try

See [Sources to try and source priority](feature-guides/sources.md) for suggestion, filtering, and installation handoff states.

![Sources to try with ranked suggestions and hidden source names](visual-guide/sources-to-try-evaluation-mode.png)

*Sort source suggestions by fit, name, or language before choosing an installation action.*

1. Open **Recommendation Settings** and select **Sources to try**.
2. Review compatible non-installed source suggestions based on your taste and source evaluations.
3. Choose a suggestion to install its extension using your selected installation method. Follow any prompts and check whether installation succeeded.
4. Return to evaluation or For You after installation if the new source needs assessment.

If the installed-source list or a network response is unavailable, the app shows an unavailable or failed state. The screen does not invent results when real source information is missing.

## Find other versions and Best Version

See [Finding and comparing manga versions](feature-guides/versions.md) for search, linking, comparison, and migration handoff.

![Matching manga versions grouped under hidden source names](visual-guide/linked-versions-evaluation-mode.png)

*Review the matches found across sources and keep only the versions that belong together.*

1. Open a manga and choose **Find other versions** from its actions.
2. Review versions found through other sources and deselect incorrect matches.
3. Confirm the versions that should be linked or grouped.
4. Open **Best Version** to compare available versions.
5. Choose comparable chapter samples when automatic matching needs help.
6. Review page previews. A missing preview affects only that version. **View in Reader** is available even when a preview fails; press Back to return to the comparison page.
7. Keep the current version or continue to Komikku's migration confirmation.

| Choose chapter samples | Compare previews |
| --- | --- |
| ![Best Version chapter selection with hidden source names](visual-guide/best-version-chapter-selection.png) | ![Best Version page previews with hidden source names](visual-guide/best-version-preview-comparison.png) |

Comparison does not alter the library. A library change begins only after you select another version and confirm the established migration flow.

The images above are older captures and do not show **View in Reader**. Opening the full reader can update reading progress through ordinary reading; returning to the comparison does not undo that progress.

If you cancel a search, or one source fails, versions you already accepted remain selected. Migration shows which steps succeeded and which failed. It does not claim to reverse changes that already finished or happened outside the app.

## Reader controls

See [Reading schedule, completion, and chapter navigation](feature-guides/reading.md) for schedule checks, completion prompts, linked-version ratings, and Jump to last read.

### Switch between sources

Open the reader's alternate-source chooser and select another source offering the same manga. Review the suggested chapter and the translation-group name when available, then open your choice. The chooser starts with the first chapter sharing your current chapter number; without an exact match, it suggests the closest available number, favoring the lower number in a tie.

While that reading pair is active, **Switch source** lets you choose a chapter from the other source in either direction. Returning to the source you started from does not end the pair. If the pair is unavailable after the session ends, open the alternate-source chooser again. Switching does not add manga to your library or bypass reading-time restrictions. See [Switch between sources](feature-guides/reading.md#switch-between-sources) for the complete steps.

| Reading schedule | Completion preference |
| --- | --- |
| ![Reading schedule with recurring time windows](visual-guide/reading-schedule.png) | ![Reader completion prompt with all four manga preferences](visual-guide/reader-completion-preference.png) |

### Jump to last read

1. Open a manga with chapter progress.
2. Select **Jump to last read** in the manga toolbar.
3. The chapter list scrolls to the resolved last-read position without changing read state.

The action is unavailable when no valid read position exists.

### Reading schedule

1. Open **Settings**, then **Reader**.
2. Configure the optional reading schedule, mode, weekdays, and time windows.
3. The reader evaluates the local schedule when it opens and when it returns to the foreground.

The schedule is off by default. If you open the reader during a restricted time, reading is blocked immediately. If the restriction begins while you are reading, the visible grace message explains whether you may finish the current chapter. Switching chapters cannot bypass the restriction.

### Completion rating

Enable **Ask for a rating after finishing** under **Recommendation settings > Versions and quality** if you want the offer after leaving a newly completed final chapter. Rereads and already-rated manga do not trigger it. Choose a rating or skip the prompt. **Ask about other versions** controls the later offer to rate other versions; dismissing that offer keeps the rating you already saved.

![Reader follow-up for applying the preference to matching versions](visual-guide/reader-linked-version-follow-up.png)

## Evaluation Mode and Action History

See [Evaluation Mode and exports](feature-guides/sharing.md) for presentation privacy and [Ratings and linked versions](feature-guides/ratings.md) for reversible actions.

Evaluation Mode replaces source names and other identifying labels with neutral text for review and screenshots. It does not change saved data, actions, source requests, or network behavior.

![Browse with source names hidden by Evaluation Mode](visual-guide/browse-evaluation-mode.png)

*Browse keeps its normal layout and navigation while Evaluation Mode hides source names.*

Open **Action History** to review supported reversible actions. If the same value changed again after the original action, the app refuses to undo it instead of overwriting newer data. Some outside actions, such as updates sent to a tracking service, can be listed but cannot be reversed locally.

![Action History with a completed local preference change and its Undo action](visual-guide/action-history-undo.png)

## Export and cleanup

See [Evaluation Mode and exports](feature-guides/sharing.md) for chooser, cancellation, success, and exact-file cleanup states.

Extension, recommendation, and library exports use Android's document picker. Choose the destination through the system UI. Public builds keep successful exports. If cleanup is offered for an incomplete, failed, or cancelled export, Keep or Remove applies only to the document that export created. A removal failure can leave it in place. Cancellation does not mean that an already-created file was removed, and cleanup does not scan unrelated storage.

![For You with the Export Top Picks action open](visual-guide/recommendation-bundle-export.png)

## OCR search for downloads

See [Searching downloaded pages with OCR](feature-guides/ocr.md) for indexing, cancellation, search, and cleanup.

1. Open **OCR Search Downloads** from the app's search tools.
2. Choose the current manga or all downloaded manga, then start indexing.
3. Keep the app available while the background notification reports progress, or cancel the job from the provided action.
4. Search the recognized text and select a result to return to its manga, chapter, and page context.
5. Use the result menu or index controls to clear one chapter, one manga, failed rows, old-version rows, or the entire index.

Recognition runs on the device. The extracted text stays on the device, is excluded from backup and sync, and can be removed without deleting downloaded pages. It works best with Latin-script text; stylized or non-Latin pages may produce incomplete results.

![OCR Search Downloads with a real indexed summary and indexing controls](visual-guide/ocr-search-downloads.png)

## Backup and restore

See [Backup and restore](feature-guides/backups.md) for choosing what to save, protecting your file, and checking partial restore results.

Backups can include your ratings, recommendation choices, source evaluations, linked manga versions, preferred versions, source-quality information, and Local Tracking. **Taste profile** and **Local tracking** are separate choices: include both to save linked reading progress. Select **App settings** for recommendation controls stored as preferences. Sensitive settings, which can include tracker sign-in details, are optional and off by default. A partial restore reports what could not be restored instead of treating the entire operation as successful.

OCR text is excluded from backup because it can be regenerated from local downloads. Action History also cannot roll back changes made by outside services or installed packages.

![Data and storage settings with backup, restore, scheduling, and privacy guidance](visual-guide/backup-and-restore.png)

## Extension operations

See [Extension management](feature-guides/extensions.md) for isolation, consent, installation, removal, export, and cleanup.

Choose the ordinary installer under **Settings > Advanced > Extensions > Extension installer**. Available methods depend on your build; private installation is not offered in public builds. Android prompts depend on the method and permissions. Source Evaluation has a separate installer choice for its checks. A failing extension does not necessarily affect unrelated sources, but a source failure is not a guarantee that every crash will be contained. Extension export uses Android's document picker and acts only on extensions you select. Cleanup is limited to the document created by that export.

![Extension export confirmation explaining the executable package boundary](visual-guide/extension-export-confirmation.png)

## Privacy and troubleshooting

- Turn on Evaluation Mode before sharing screenshots that would otherwise show source names.
- Review screenshots for title preferences, account state, reader pages, notifications, status bars, and document paths.
- Review a diagnostic summary before publishing it; do not publish raw errors or logs.
- A source-specific error should be retried or reassessed independently; it should not require clearing app data.
- Backup and restore protect supported local app data, but they are not a substitute for reversing tracker writes, extension installation, or other external effects.
