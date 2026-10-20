# Find and choose recommendation sources

A source is a website or service whose manga you can browse through an installed extension. **Sources to Try** suggests extensions you have not installed. The source list in **For You feed** controls which installed sources contribute recommendations and their priority.

Open **Recommendation Settings**, then choose **Find new sources > Suggested for you** for Sources to Try, or **For You feed** for the source list.

## Find a source to try

Suggestions use your recommendation languages, content filters, source preferences, and available information about the source. Already-installed and untrusted extensions are not suggested. Dismissed, disliked, and poor-quality-marked suggestions are also excluded.

<p align="center"><img src="../visual-guide/sources-to-try-evaluation-mode.png" width="260" alt="Sources to Try suggestions with neutral labels"></p>
<p align="center"><em>Sources to Try, sorted by best fit.</em></p>

A suggestion is not proof that a source works well or is safe. Some suggestions come from similarities to sources you already use and still need testing. Saved Source evaluation results can help order the list; missing results do not mean that the source passed an evaluation.

```mermaid
flowchart TD
    Settings["Choose recommendation languages and content filters"] --> List["Open Sources to Try"]
    List --> Search["Search or sort the suggestions"]
    Search --> Choice{"What do you want to do?"}
    Choice -->|Try it| Install["Choose Install"]
    Choice -->|Not now| Dismiss["Dismiss the suggestion"]
    Choice -->|Compare first| Evaluate["Open Source evaluation"]
```

Use the search field to find a name, language, or repository. Search filters the suggestions already available; it does not search every manga website. Sort by **Best fit**, **Name A-Z**, or **Language**. Best fit is a recommendation order, not a security rating.

When the list is shortened, use **Show more** to see the remaining suggestions. If a search finds nothing, clear the search. If there are no suggestions at all, check your languages, filters, repositories, and dismissed suggestions.

## Install deliberately

Use a suggestion's **Install** action, **Install visible**, or **Select** followed by **Install selected**. Bulk installation applies to the visible or selected suggestions, not every available extension.

Installation follows your configured extension installation method. Complete any permission or confirmation steps it requires. The app reports success only after the installation flow reports the extension installed.

```mermaid
flowchart LR
    Choose["Choose suggestions, start installation,<br/>and complete any prompts"] --> Result{"Result"}
    Result -->|Installed| Use["Check the source in Browse"]
    Result -->|Failed| Retry["Read the result and<br/>retry if offered"]
    Result -->|Cancelled| Stop["Installation stopped"]
    Result -->|Some installed| Partial["Check what installed<br/>before retrying failures"]
```

Cancellation does not uninstall extensions that already installed. A failed or cancelled attempt can offer Retry; check the result rather than assuming every selected extension was installed.

After installation, use [Source evaluation](source-evaluation.md) to check suitability and **For You feed** to check whether the source is enabled for recommendations. Installation alone does not guarantee useful matches. See [Extensions](extensions.md) for installation methods, trust, and removal.

## Set the priority of installed sources

In **For You feed**, use the source switch to include or exclude it from recommendations. **Don't use in For You** on a failing For You row turns the same switch off. Reorder rows by dragging or using the move actions. You can reset the order, or apply a fit-based suggested order when that action is available.

<p align="center"><img src="../visual-guide/v0.8.24/for-you-feed-sources.png" width="260" alt="Source priority with reorder handles and switches"></p>
<p align="center"><em>Drag a row to change its priority, or use its switch.</em></p>

```mermaid
flowchart TD
    Sources["Open For You feed"] --> Enabled["Enable the sources you want"]
    Enabled --> Order["Arrange their priority"]
    Order --> Refresh["Refresh For You"]
    Refresh --> Results["See manga that pass your filters"]
```

A higher-priority source is considered earlier. It does not bypass your manga filters or guarantee more results. A source can have a strong fit rating but show few manga after chapter, taste, or other filters. Fit and the number currently shown describe different things.

Each row shows one-line tags such as **Boosted**, **Great fit**, and **Top Picks**. Boosted is outlined because it is your own choice; the others describe results. The page also contains looser-match, exploration, and repeat-display controls. See [Recommendation settings](recommendation-settings.md) and [Recommendations](recommendations.md) for their effects. The For You preview shows a saved recommendation snapshot; it is not a live source check.

## Filter manga within a source

When For You opens a source, its recommendation restrictions continue to apply to that visit. If the carried restriction context is invalid, Browse withholds those results and asks you to reopen the source from For You.

Recommendation searches use a source's selector only when its label identifies a **genre**, **tag**, **category**, or **theme**. Format and status selectors are left unchanged. Unmatched labels are not guessed as genre filters; the requested terms remain in the source-search text.

## Keep preference and quality separate

**Like** and **Dislike** describe whether you want recommendations from a source. Quality marks describe a problem with that source's catalogue, such as poor quality or explicit content. They are separate choices; clearing one does not necessarily clear the other.

```mermaid
flowchart LR
    Row["Open a source's<br/>actions"] --> Choice{"What are you<br/>changing?"}
    Choice -->|Preference| Preference["Like, Dislike, or<br/>return to neutral"]
    Choice -->|Concern| Quality["Mark poor quality<br/>or explicit content"]
    Choice -->|Clear| Clear["Clear the quality mark"]
    Choice -->|Hide| Dismiss["Dismiss it from<br/>Sources to Try"]
```

In Sources to Try, **Clear dismissed suggestions** makes dismissed items eligible to appear again if their other conditions still allow them. It does not install them. **Clear all quality marks**, when offered, clears saved quality marks; it does not clear every dislike, dismissal, or disabled-source choice.

Changing a preference does not remove an extension or erase manga already in your library. Content marks and filters cannot guarantee that every explicit title will be detected.

For information you should check before sharing a screenshot or export, see [Sharing](sharing.md).
