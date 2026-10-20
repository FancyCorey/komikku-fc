# Personalized recommendations and discovery

For You suggests manga from your chosen installed sources. It combines matches for your taste with newer discoveries and applies your filters. It can also move repeatedly shown manga lower when the app has no recorded interaction with them.

## Where you find it

Open **Browse**, then select **For You**. The page shows topic shortcuts and a **Focus** control. Below them are recommendation rows for the sources that are eligible under the current settings. The page menu also opens [Source evaluation](source-evaluation.md) and [Recommendation Settings](recommendation-settings.md).

## What For You is designed to do

For You is not a single popularity list. It gathers manga from your chosen sources, applies your filters, and combines matches for your taste with newer discoveries. Personalized results remain the majority when enough are available. Newer discoveries still use your blocked genres, languages, and source rules.

Your taste comes from your ratings and your preferred and blocked tags. With **Use recent reading** on, which is the default, it also comes from what you read lately: tags of manga read within the chosen window count more than your older ratings, which still contribute. Blocked and disliked tags are never boosted.

When repeat rotation is enabled, the page remembers manga shown in recent results. A manga shown more than once within your chosen time window can move lower instead of disappearing. That effect fades as the last showing gets older and ends when it falls outside the window. Manga saved in your library, rated, tracked, or with another recorded interaction are exempt. The app does not treat a lack of interaction as a dislike.

**Hide manga you already know** applies to the current refresh and to titles remembered from earlier refreshes. A title you have since rated, added to your library, or started reading does not reappear after Refresh.

## What you see on the page

- Topic shortcuts provide fast entry points into broad recommendation groups.
- Each source row shows its own results, loading status, or an explanation when results are unavailable.
- A row labelled **Looser matches: its tags don't match your taste** contains looser matches. See [When a source's manga don't match your taste](#when-a-sources-manga-dont-match-your-taste).
- Manga cards keep the normal Komikku interaction model: opening a result leads to its manga page; it does not save a preference without telling you.
- Evaluation Mode can hide source labels in screenshots. It does not change which manga are recommended or hide every personal detail. See [Sharing](sharing.md).

The order can change as new results arrive and previously shown manga move lower. Your filters still apply.

## How a refresh finds manga

For You searches your enabled sources in your chosen order, a few at a time. The next source starts as soon as a search slot is free. Once enough rows are ready, sources still searching are stopped, and they do not leave a loading row behind.

For each source, For You first searches for your taste. When none of those searches find anything, it uses the source's Popular page instead of leaving the row empty. An empty result is not reused from the cache, so the source is searched again on the next refresh.

### When a source's manga don't match your taste

A source can return manga that all fail your taste matching, often because the source tags its manga poorly or not at all. With **Show looser matches** on, which is the default, that source's row shows up to four of those manga, labelled as looser matches, instead of an empty row.

Looser matches still respect your blocked tags, dislikes, ratings, known-manga choices, and minimum chapter count; only the requirement to share a tag with your taste is relaxed. They are picked from what the source already returned in this refresh, so no extra request is made. They stay in their own source's row, never enter Top Picks, and are not a judgement of quality. The source's status still counts the refresh as filtered out. Turn them off under **Recommendation Settings > For You feed**.

### When a source is much slower than the others

A source that takes far longer than the other sources in the same refresh is stopped and shown as an error row with **Try again**. The time limit follows how long your other sources took, and it is always between 30 seconds and 3 minutes. Until the first source finishes, the limit is 3 minutes. On a slow connection every source takes longer, so the limit grows with them instead of cutting sources off at a fixed time.

### When a source fails

A failed row says what probably went wrong and what to try:

| Message | Likely cause |
| --- | --- |
| Check your connection, then try again. | The device could not reach the site, or the connection timed out. |
| The site wants a browser check. Open it once, then try again. | The site asked for a browser check, such as a Cloudflare page. **Open site** opens it in the in-app browser. |
| The site is having problems. Try again later. | The site was overloaded, returned a server error, or was too slow for this refresh. |
| This extension may need an update. | The extension did not work with this version of the app. |

The row offers these actions when they apply:

| Action | When it appears | What it does |
| --- | --- | --- |
| Try again | Always | Searches that source again. |
| Open site | The site asked for a browser check | Opens the site so you can complete the check, then return and try again. |
| Check source | The source failed in most recent refreshes | Loads the source's Popular page once, outside For You's searches, and says whether it still works when browsing. |
| Manage extension | The extension has an update, is no longer offered by its repository, or Check source found it broken | Opens the extension so you can update or remove it. |
| Don't use in For You | The source failed in most recent refreshes, or Check source found it broken | Turns the source off for recommendations only. It stays installed and usable in Browse. Turn it back on under **For You feed**. |

**Check source** distinguishes two situations:

- **It works when browsing.** Only For You's searches fail, so you can stop using the source in For You and keep reading from it.
- **It doesn't work when browsing either.** Update or remove its extension, or stop using it in For You.

A note also appears when the extension has an update available or is no longer offered by its repository, which can mean the source stopped working.

```mermaid
flowchart TD
    Failed["A For You row failed"] --> Hint["Read the likely cause"]
    Hint --> Retry["Try again"]
    Retry --> Fixed{"Does it work now?"}
    Fixed -->|Yes| Done["Browse the row"]
    Fixed -->|No, and it keeps failing| Check["Check source"]
    Check --> Works{"Works when browsing?"}
    Works -->|Yes| Stop["Don't use in For You, keep it for reading"]
    Works -->|No| Manage["Manage extension: update or remove it"]
    Manage --> Stop2["Or don't use it in For You"]
```

## Minimum chapter count

**Minimum chapter count** under **Your taste** hides manga with fewer chapters than your choice. A newly found manga's chapter count is usually unknown until its chapter list has been loaded. After a refresh shows its rows, For You loads the chapter lists of up to 20 shown titles whose count is unknown, a few at a time. Titles below your minimum disappear from the page a few seconds later, and their counts are remembered for two weeks, so later refreshes filter them straight away.

Titles that could not be checked yet stay visible; an unknown count is not treated as zero chapters. The check does not run while For You is saving data.

## Saving data

**Use less data on recommendations** under **Speed and data** can be Automatic, Always, or Never. Automatic saves data on a metered or slow connection. While saving data, For You:

- skips the optional extra discovery page and new-release exploration;
- tries fewer search variations per source;
- does not run the minimum chapter check;
- stops once about half the usual number of rows are ready, so sources further down your list may not be searched.

A manga's Recommendations and the library recommendation search look in at most 6 of your sources instead of 20 and fetch fewer details. Your filters are unchanged.

## Focus on what you want now

**Focus** narrows For You to a few tags for the moment without changing your saved taste. Open it and choose tags:

- Tap a tag once to include it, a second time to exclude it, and a third time to clear it.
- **Match All** shows only manga that have every included tag. **Match Any** shows manga that have at least one of them.
- An excluded tag removes every manga that has it, whichever match choice you use.

Tags are grouped so the list stays short enough to scan:

1. **Format**, **Demographic**, and **Access** come first.
2. **Your tags** lists your strongest liked tags and every tag you have selected.
3. **Genres**, **Story and tropes**, **Settings and worlds**, **Characters**, and **Mature and relationships** follow. In each group, tags in what For You is showing now come first. Long groups show their first 12 tags with **Show all**.
4. **More tags**, folded, holds tags the app does not recognize.

Searching shows every matching tag unfolded. The same tag from different sources appears once: numbered copies such as Adventure (157) and Adventure 27 are one Adventure tag. A plural spelling such as Monsters joins Monster when your sources use both.

While a focus is active, **Show broader results** temporarily shows everything again without clearing your choice. **Save as…** stores the selection as a saved Focus mode, which you can rename, reorder, or delete under **Recommendation Settings > Focus modes**.

## Top Picks, selection, and sharing

Top Picks combines the strongest currently available matches into a focused list. The per-source rows stay, so you can still see where broader discovery came from. Looser matches never appear in Top Picks.

Use a card's selection action to enter selection mode; on a touch screen, this may be a press-and-hold gesture. The selection bar can apply a preference or clear one across the chosen manga. Single-item actions can open the manga or continue into [version comparison](versions.md).

Top Picks, an individual source row, or a rated collection can also be exported as a recommendation bundle to share. Import opens a review screen first. Check the manga and source matches before adding anything to your library. Entries that cannot be matched or read are identified rather than silently added as another manga. See [Sharing](sharing.md).

## Recommendations from a group

Rated and linked-version groups can start a recommendation search using the whole group rather than one manga alone. Results appear source by source, and the preview-size setting controls how many appear at first. Leaving the page stops unfinished work; a slow or failing source affects its own row rather than the whole result.

## Feature overview

```mermaid
flowchart LR
    Screen["Open For You"] --> Retrieval["Find manga from your chosen sources"]
    Retrieval --> Eligibility["Apply your filters"]
    Eligibility --> Ranking["Order taste matches and newer discoveries"]
    Ranking --> Rows["Show results and each source's status"]
    Rows --> Check["Check chapter counts of shown titles"]
```

One unavailable source does not prevent other sources from showing results.

## Retrieval and display

```mermaid
sequenceDiagram
    actor Reader
    participant Screen as For You
    Reader->>Screen: Open or refresh
    Screen-->>Reader: Results and status for each source
    Screen-->>Reader: Remove shown titles below the minimum chapter count
    Reader->>Screen: Open a manga card
    Screen-->>Reader: Manga page
    Reader->>Screen: Return to For You
    Screen-->>Reader: Continue browsing recommendations
```

Each source is handled independently. One failing source can produce a row-level explanation without discarding successful rows.

## One source's row

```mermaid
flowchart TD
    Start["Search the source for your taste"] --> Finished{"Finished within the time limit?"}
    Finished -->|No| Slow["Error row with Try again"]
    Finished -->|Failed| Error["Error row with the likely cause and actions"]
    Finished -->|Yes| Found{"Did the searches find anything?"}
    Found -->|No| Popular["Use the source's Popular page"]
    Found -->|Yes| Match{"Any manga match your taste?"}
    Popular --> Match
    Match -->|Yes| Row["Show matching manga"]
    Match -->|No| Looser{"Show looser matches on?"}
    Looser -->|Yes| LooserRow["Show up to four, labelled as looser matches"]
    Looser -->|No| Empty["Explain that nothing matched"]
```

Every path still applies your blocked tags, dislikes, ratings, and known-manga choices first.

## Candidate eligibility

```mermaid
flowchart TD
    Candidate["Manga found for For You"] --> Rules{"Allowed by your other filters?"}
    Rules -->|No| Exclude["Hide this manga"]
    Rules -->|Yes| Minimum{"Minimum chapter count enabled?"}
    Minimum -->|No| Rank["Consider for the results"]
    Minimum -->|Yes| Known{"Chapter count known or checked?"}
    Known -->|No| Rank
    Known -->|Yes| Enough{"At least your chosen number of chapters?"}
    Enough -->|No| Exclude
    Enough -->|Yes| Rank
    Rank --> Order["Order taste matches and newer discoveries"]
    Order --> Visible["Show the selected results"]
    Visible --> CheckShown["Check unknown counts of up to 20 shown titles"]
    CheckShown -->|Below the minimum| Exclude
```

This diagram describes **For You**, not every recommendation search. A count that is still unknown does not hide a manga. Being considered does not guarantee a visible place, because only a limited number of results are shown. Newer discoveries use the same For You filters.

## Exposure-aware refresh

```mermaid
flowchart TD
    Visible["Manga shown in For You"] --> Enabled{"Repeat rotation enabled?"}
    Enabled -->|No| Keep["Keep normal rank"]
    Enabled -->|Yes| Window{"Shown more than once, with last showing inside your chosen window?"}
    Window -->|No| Keep["Keep normal rank"]
    Window -->|Yes| Exempt{"Saved, rated, tracked, or another recorded interaction?"}
    Exempt -->|Yes| Keep
    Exempt -->|No| Lower["Can move lower; effect fades over time"]
    Lower --> Refresh["Give other matching manga a higher place"]
```

Moving a manga lower changes its position only. It does not delete the manga or remove your library entry, rating, or tracking.

The page keeps personalized results, looser matches, recent discoveries, filters, and source status separate so the result remains understandable and predictable.
