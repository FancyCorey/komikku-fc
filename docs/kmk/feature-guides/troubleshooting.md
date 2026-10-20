# Troubleshooting sources and reading

Start with the action that failed and the message on that page. A source is the website or service used by an extension. One source may fail while another still works; this does not mean your library is lost.

## A source cannot load

Check your connection, then check whether the source's website is available. If an extension update is available, read its details before updating. Retry the failed action when the page offers it.

```mermaid
flowchart TD
    Failed["A source cannot load"] --> Connection["Check your connection"]
    Connection --> Website["Check whether the source website is available"]
    Website --> Update["Check for an extension update"]
    Update --> Retry["Retry the failed action if offered"]
    Retry --> Works{"Does it work now?"}
    Works -->|Yes| Continue["Continue browsing or reading"]
    Works -->|No| Other["Try another source or report the problem"]
```

The app temporarily avoids repeated calls to a recently failed source. This does not uninstall or permanently disable the extension. A retry can still fail if the underlying problem has not changed.

Source evaluation has a separate restriction for extensions associated with a detected crash during evaluation. A normal connection failure and an evaluation crash restriction are not the same thing. Follow the message on the evaluation page rather than repeatedly forcing the source to run.

## For You shows few or no manga

Open **Recommendation Settings > For You feed** and check which sources are enabled. Then open **Your taste** and check the recommendation languages and filters, including the minimum chapter count.

A strong source-fit rating does not guarantee many visible recommendations. Fit describes the source's usefulness; shown results also depend on your manga filters and what the source returned.

```mermaid
flowchart TD
    Empty["For You has few or no results"] --> Enabled["Check enabled sources in For You feed"]
    Enabled --> Languages["Check languages in Your taste"]
    Languages --> Filters["Check taste and chapter filters in Your taste"]
    Filters --> Status["Read the source row's message"]
    Status --> Refresh["Refresh For You"]
    Refresh --> Result{"Results available?"}
    Result -->|Yes| Read["Browse the matches"]
    Result -->|No| Evaluate["Use Check source on the row, or open Source evaluation"]
```

Do not assume that an empty list proves the source has no manga. When none of a source's searches find anything, For You already tries that source's Popular page. When a source returns manga but none share a tag with your taste, **Show looser matches** under **For You feed** can show a few of them instead of an empty row.

**Recommendation Settings > Troubleshooting** lists sources that failed to load, and any sources the automatic check is skipping on For You. See [Recommendations](recommendations.md), [Recommendation settings](recommendation-settings.md), and [Sources](sources.md) for the controls that affect this list.

In Sources to Try, clear a search that finds nothing before changing your settings. Also check dismissed suggestions, dislikes, content filters, languages, and repository availability.

## A For You source keeps failing

Read the message on the failed row first. It names the likely cause: your connection, a browser check the site wants, a problem on the site, or an extension that may need an update.

```mermaid
flowchart TD
    Row["A For You row failed"] --> Cause{"What does the row say?"}
    Cause -->|Connection| Connection["Check your connection, then Try again"]
    Cause -->|Browser check| Site["Open site, complete the check, then Try again"]
    Cause -->|Site problem| Later["Try again later"]
    Cause -->|Extension| Update["Manage extension and update it"]
    Connection --> Again{"Still failing in most refreshes?"}
    Site --> Again
    Later --> Again
    Update --> Again
    Again -->|Yes| Check["Check source"]
    Check --> Works{"Works when browsing?"}
    Works -->|Yes| Stop["Don't use in For You; keep reading from it in Browse"]
    Works -->|No| Remove["Update or remove the extension, or don't use it in For You"]
```

A source that is far slower than the others in the same refresh ends as an error row with **Try again**. The time limit follows how long your other sources took, between 30 seconds and 3 minutes, so a slow connection does not cut sources off. **Don't use in For You** affects recommendations only; it does not uninstall the extension. Turn the source back on under **For You feed**.

## A large download stops partway

One failed image or page list no longer makes the rest of a large download from that source fail. If chapters still fail, check the failed chapter's message in the download queue and retry it. When the extension itself is broken or incompatible, reading and downloading from it are held back until you update or replace the extension. See [Extensions](extensions.md).

## Preview or reader pages fail

In **Find Best Version**, use **Retry** when a preview fails. **View in Reader** provides another way to open the selected chapter even if preview images failed; it still needs the chapter to be available. Press Back to return to the comparison page.

```mermaid
flowchart TD
    Preview["A comparison preview failed"] --> Retry["Try Retry"]
    Retry --> Reader["Use View in Reader to check the chapter"]
    Reader --> Loaded{"Pages load?"}
    Loaded -->|Yes| Back["Press Back to return to comparison"]
    Loaded -->|No| Source["Check the source or another linked version"]
```

A working preview does not guarantee that every chapter will load, and View in Reader cannot repair a missing chapter or unavailable source. Reading there can update normal reading progress.

If alternate-source switching cannot find the matching chapter, check the chapter list and its scanlator or release-group labels. Choose the appropriate chapter instead of assuming different sources number every chapter identically. See [Reading](reading.md) and [Compare versions](versions.md).

For downloaded-page text searches, check that the pages are still downloaded and process those pages again if old results no longer open. See [Search downloaded pages](ocr.md).

## The app closes or an action stops partway through

A recoverable source error may leave other results available, but not every crash can be caught. If the app closes, reopen it and inspect the current state before repeating a restore, migration, or bulk installation. Some work may already have completed.

```mermaid
flowchart TD
    Stop["The app closes or an action is interrupted"] --> Open["Reopen the app"]
    Open --> State["Check what already changed"]
    State --> Choice{"What needs attention?"}
    Choice -->|Reading| Chapter["Check the current chapter and tracker"]
    Choice -->|Restore or migration| Backup["Check restored items before retrying"]
    Choice -->|Bulk installation| Installed["Check installed extensions before retrying"]
    Choice -->|Repeated closure| Report["Record the steps and app version"]
```

For a repeatable problem, include the app version from **More > About > Version**, the steps taken, the expected result, and what happened. A screenshot or crash log can help, but a crash report may not be available for every closure.

Check screenshots and logs before sharing: logs can contain source names and technical details, and screenshots can expose manga titles or account information. Evaluation mode does not anonymize everything. See [Sharing](sharing.md) and the repository's [issue tracker](https://github.com/FancyCorey/komikku-fc/issues).

For interrupted restores and supported Undo actions, see [Backups](backups.md) and [Links, Undo, and file safety](safety.md). Cancellation is not a promise that all completed work was reversed.
