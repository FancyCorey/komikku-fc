# How Komikku FC features work together

Komikku FC is an independent Android manga reader based on Komikku. It keeps the familiar library, reader, downloads, and backups, and adds tools for recommendations, linked manga versions, local tracking, page-text search, and optional reading limits.

## Choose the guide you need

- [Recommendations](recommendations.md): find manga based on your preferences.
- [Recommendation settings](recommendation-settings.md): change filters and recommendation choices.
- [Ratings](ratings.md): rate manga and manage linked versions.
- [Local tracking](local-tracking.md): remember chapter progress without a tracker account.
- [Versions](versions.md): compare the same manga from different sources.
- [Reading](reading.md): use reader controls and switch between sources.
- [Source Evaluation](source-evaluation.md): check source fit and search compatibility.
- [Sources](sources.md) and [Extensions](extensions.md): find and manage places to read.
- [Page-text search](ocr.md): search text extracted from manga pages.
- [Backups](backups.md): save and restore supported app data.
- [Sharing](sharing.md), [Safety](safety.md), and [Troubleshooting](troubleshooting.md): review privacy, permissions, and problems.

## Start with your manga

An extension connects the app to a manga source. That source supplies its own titles, chapter lists, and pages. What is available can differ between sources, and a source can be temporarily unavailable.

```mermaid
flowchart TD
    Browse["Browse or search a source"] --> Manga["Open a manga"]
    Manga --> Library["Add it to your library if you want to keep it"]
    Manga --> Reader["Choose a chapter and read"]
    Reader --> Progress["Keep track of what you have read"]
```

## Ratings and progress are different

A rating tells the app what you think of a manga. Tracking remembers your chapter progress. Settings can start local tracking when you rate a manga or share progress across confirmed linked versions, but these are separate choices. Linking versions does not mean copying all library data or changing external tracker accounts.

```mermaid
flowchart TD
    Manga["Open a manga"] --> Rating["Rate it to express your preference"]
    Manga --> Track["Use tracking to remember your progress"]
    Rating --> Recommendations["Ratings can influence recommendations"]
    Track --> Reading["Progress helps you resume reading"]
    Link["Link confirmed versions of the same manga"] --> Settings["Choose whether linked versions share ratings or local progress"]
```

## Compare before changing versions

Different sources can offer different translations, chapter numbering, or image quality. Comparing previews or opening a candidate in the reader does not automatically migrate your library entry. Read the confirmation choices before copying or migrating data.

```mermaid
flowchart TD
    Current["Open your current manga"] --> Compare["Compare available versions"]
    Compare --> Preview["Inspect previews or View in Reader"]
    Preview --> Decide{"Do you want a different version?"}
    Decide -->|No| Keep["Keep your current version"]
    Decide -->|Yes| Confirm["Review the Copy or Migrate confirmation"]
    Confirm --> Choice["Apply only the choice you confirm"]
```

## Save data before making large changes

A backup contains the data selected in the backup options, not every file or account detail on your device. Downloaded chapter images need separate handling. Restoring is not an undo button for everything you have done, including changes on external services.

```mermaid
flowchart TD
    Backup["Open backup settings"] --> Options["Choose which supported data to include"]
    Options --> File["Save the backup file"]
    File --> Safe["Keep it somewhere you can access later"]
    Safe --> Restore["Review restore choices when you need the data"]
```

See [Backups](backups.md) for sensitive-data options, merge behavior, and restore limits. Before installing an update, read its release notes and use the correct build for your installed app. This guide does not promise that every older installation can automatically discover a new fork release.

## Know what is shared

Recommendations do not require a separate Komikku FC recommendation account. That does not make all app activity offline: installed sources receive requests for manga and pages, connected trackers can receive updates, and exports or backups can contain personal reading information.

```mermaid
flowchart TD
    App["Komikku FC"] --> Local["Stores supported settings, ratings, history, and local progress"]
    App --> Sources["Contacts sources to load manga and pages"]
    App --> Trackers["Can update connected tracker accounts"]
    App --> Files["Creates exports or backups you choose to save"]
    Files --> Check["Check file contents before sharing"]
```

**Evaluation mode** hides supported identifying labels on screen only. It does not hide manga titles or artwork, sanitize exported files, or change the source an action uses. Check screenshots and files yourself before sharing them.

## Read What's New

The in-app **What's New** history explains Komikku FC feature changes separately from Komikku's upstream release information. Open older sections when you want to review previous changes. Reading or acknowledging the history is not the same as downloading or installing an update.
