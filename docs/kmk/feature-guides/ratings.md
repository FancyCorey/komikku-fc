# Rating manga and managing linked versions

Save how you feel about a manga with Love, Like, Dislike, or Not interested. You can revisit those choices, change them, and keep matching versions from different sources together.

## Where you find it

On a manga page, choose **Love**, **Like**, **Dislike**, or **Not interested**. The For You menu opens each collection. When versions are linked, you can choose which matching versions should receive the same preference.

<p align="center"><img src="../visual-guide/v0.8.24/manga-page.png" width="260" alt="Manga page with the Like preference selected"></p>
<p align="center"><em>The preference button shows the current choice, here Like.</em></p>

## Choose or clear a rating

Each manga has one current rating. Choosing Like after Love replaces Love; choosing Not interested replaces the previous rating too. **Clear rating** removes the choice and returns the manga to an unrated state. Not interested has its own collection and visible marker, just like the other choices.

<p align="center"><img src="../visual-guide/not-interested-other-versions.png" width="260" alt="Preference actions with Not interested selected"></p>
<p align="center"><em>Not interested is a preference like the others.</em></p>

Open the Loved, Liked, Disliked, or Not interested collection from For You to review your choices. A manga appears in the collection for its current rating. Linking versions does not merge their chapter lists or make them a single library entry.

## Settings that affect rating

| Setting | What it does |
| --- | --- |
| Rate linked versions together | Setting or clearing a rating can also apply to confirmed versions of the same manga from other sources that you track locally. Turn it off when you want to rate them separately. |
| Start tracking when you rate a manga | Starts local tracking when you save Love, Like, or Dislike. Not interested does not start tracking this way. This does not turn on updates to outside tracker accounts. |
| Track linked versions together | Lets other confirmed versions join local tracking when one version is tracked. This is separate from giving those versions the same rating. |
| Ask for a rating after finishing | Offers a rating after you finish the latest available chapter. You do not have to rate it. |
| Ask about other versions | After the reader rating prompt, offers a separate choice about giving that rating to other versions. Cancelling that follow-up does not undo the rating you already saved. |
| Use top-right rating actions | Changes whether rating actions are in the top-right menu or on each rated manga card. |
| Choose a primary version automatically | Chooses the entry shown for a linked group when you have not chosen one yourself; see below. |

The recommendation settings contain the linked-version, prompt, action-placement, and automatic-primary controls. **Start tracking when you rate a manga** is in Tracking settings. See [Recommendation settings](recommendation-settings.md) and [Local tracking](local-tracking.md) for the related controls.

These settings do different jobs:

- **Rating sharing:** setting or clearing a rating includes confirmed versions already tracked locally when **Rate linked versions together** is on.
- **New confirmations:** a locally tracked version you newly confirm, including through Best Version, can receive the group's rating with rating sharing on. An existing rating you gave that version is kept. A similar title alone is not confirmation.
- **Starting tracking:** saving Love, Like, or Dislike can create local tracking when **Start tracking when you rate a manga** is on.
- **Linking tracking:** **Track linked versions together** can attach other confirmed versions to that record. This attachment alone does not give them the rating you just saved. A later rating action can include them, or you can select them explicitly through **Find other versions**.

Turning off linked tracking does not stop the current version from starting local tracking under its separate setting. Turning off automatic creation does not stop an existing tracking record from being shared when linked tracking is on.

Clearing a rating removes the selected rating, including from the eligible tracked linked versions when rating sharing is on. It does not delete local tracking or erase chapter progress. Versions that have not been confirmed as the same manga still need your explicit selection.

## Rate a whole group at once

Open a manga's **Trackers**, then choose **Track other versions** in its **Local tracking** entry. The **Group rating** button above the Track button shows the confirmed group's current rating, or **Not rated**.

<p align="center"><img src="../visual-guide/v0.8.24/track-other-versions.png" width="260" alt="Track other versions with the Group rating button"></p>
<p align="center"><em>Track other versions, with Group rating above the Track button.</em></p>

<p align="center"><img src="../visual-guide/v0.8.24/group-rating-menu.png" width="260" alt="Group rating choices"></p>
<p align="center"><em>Choose a rating, or Not rated, for every locally tracked confirmed version.</em></p>

Select it to choose Love, Like, Dislike, or Not interested, or **Not rated** to clear the rating, for every locally tracked confirmed version at once. This includes eligible versions you have not opened yet. If existing versions have different ratings, the button uses the most recently updated rating. Loading or saving errors appear on the screen so you can try again.

Confirming a version through **Best Version** also gives the chosen version the confirmed group's rating when it is locally tracked and **Rate linked versions together** is on, without replacing a rating you set yourself.

## What Action History can restore

After a supported rating change, open **Action history** and use **Undo** if it is offered. The app checks for later changes before restoring the earlier choice. If you changed the rating again, Undo may report a conflict rather than overwrite your newer rating.

<p align="center"><img src="../visual-guide/action-history-undo.png" width="360" alt="Action history with an Undo action"></p>
<p align="center"><em>A supported change listed in Action history with Undo.</em></p>

Undoing a local rating is not a promise to undo changes on a tracker website, reinstall extensions, or restore every related reading-progress change. Check the action shown in the history screen.

## Linked-version behavior

Linked versions are entries for the same manga from different sources, not different releases of the app. Use **Linked versions** to inspect them and **Manage group** to change the group. Check that the entries really are the same manga before combining them.

<p align="center"><img src="../visual-guide/linked-versions-evaluation-mode.png" width="260" alt="Rated collection with linked versions"></p>
<p align="center"><em>Linked versions in a rated collection.</em></p>

To combine entries, select two or more matching versions in a rated collection, choose the merge-into-group action, and review the confirmation. Cancel if the selection is wrong. You can also select all entries in a group, remove selected entries from a group, or ungroup it. Removing a version from a group does not clear its rating or delete its manga data.

**Find other versions** searches for matching entries. **Favorite other versions** is a separate library action, not the setting that chooses the displayed primary. You can also request recommendations from one version or from the linked group.

## Which version represents a group?

The primary version is the manga entry whose title and cover represent a linked group in a rated collection. It is not a different app version, does not add other versions to your library, and does not move chapters between sources.

With **Choose a primary version automatically** enabled in the recommendation settings, the app prefers the linked version with the most chapters marked read. If several versions have the same count, it prefers the one rated first. This is a useful starting point, not a judgment about which translation is best.

Choosing a primary version yourself takes precedence over that automatic choice while your chosen version is available in the collection. Turn the setting off if you do not want reading counts to decide the default. Removing a version or making its source unavailable can require the group to display another available member.

To manage chapter progress separately, see [Local tracking](local-tracking.md). Rating and selecting a primary version do not, by themselves, turn on updates to outside tracker accounts.

## Preference states

Every rating replaces the previous choice in one action and moves the manga to the corresponding collection. **Clear rating** returns it to unrated; it does not put the manga in another rating collection.

## Change a rating and revisit it

1. Choose or clear a rating and check its saved value and collection.
2. To reverse a supported change, open **Settings > Advanced > Action history** and select **Undo** if offered.
3. Read the result. If Undo reports a conflict or is unavailable, check the current rating and change it directly if needed.

Only supported saved changes can have an Undo action. Read any failure or conflict message; a message about one action does not mean every related setting or progress change was reversed.

## Create or merge a group

1. In a rated collection, select at least two versions of the same manga.
2. Choose the merge-into-group action and check the confirmation. Cancel to correct a wrong selection.
3. Confirm, then review the linked versions. If saving fails, read the message and check the selection before retrying.

The confirmation also applies when combining existing groups.

## Maintain a group

Changing the primary version or merging groups keeps a group together. **Ungroup** separates its members. Removing one version affects the grouped display as follows:

<img src="../diagrams/ratings/remove-member.svg" width="440" alt="After removing a version, keep a multi-version group if at least two members remain; otherwise show a separate entry. Ratings are kept.">

With fewer than two versions, the rated collection no longer shows a multi-version group. Removing a version or ungrouping does not clear its rating.

## Grouped display

The displayed version is chosen in this order:

1. Your chosen primary, if available in the collection.
2. The most-read eligible version if automatic selection is on, with the earliest rating breaking ties.
3. Otherwise, the collection's default group choice.

Your collection and sort settings still apply.

Collections come from the saved preferences and linked versions, so they stay consistent across screens.

Not interested is a full preference with its own collection and marker. It can be changed or cleared like the other choices, and supported local changes can be undone from Action History when nothing newer has replaced them.
