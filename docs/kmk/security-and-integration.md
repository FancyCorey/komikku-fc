# Links, extensions, and recovery

Komikku FC opens manga through websites and extensions, connects to trackers you choose, and saves files through Android. These services and files are not automatically trustworthy because the app can open them. Use sources you trust, keep a backup, and check what you share.

## Opening links

If a manga link is rejected or does not open, check the address and find the manga through **Browse** instead. Do not try to bypass a rejected address. A working link does not prove that its website is safe. See [Links, Undo, and file safety](feature-guides/safety.md).

## Extensions and unavailable sources

An extension is installed code that can contact its own services. Source Evaluation checks whether a source works with the app; it is not a security certification. Supported source checks can report a failed request while other sources continue, but they cannot prevent every extension failure or make an unavailable chapter load.

Check the displayed result before retrying. Cancelling stops the pending task, but does not necessarily reverse work that already finished. See [Extensions](feature-guides/extensions.md), [Source Evaluation](feature-guides/source-evaluation.md), and [Troubleshooting](feature-guides/troubleshooting.md).

## Undo and backups

Open **Settings > Advanced > Action history** to review supported recorded changes. Use **Undo** when offered. If the affected value has changed since that action, Undo can report a conflict instead of replacing your current choice.

Undo is not a complete backup. Undoing a chapter's read or bookmark change does not undo reading history or tracker progress. A local reversal cannot guarantee reversal of a tracker update or extension installation. See [file safety](feature-guides/safety.md#undoing-supported-changes) and [Backups](feature-guides/backups.md).

## Incomplete files and shared diagnostics

Successful exports are kept. If an incomplete export can be identified, the app can offer **Keep** or **Remove** for that file. Remove targets that file only, not its folder; a failed removal leaves the file in place. If the app cannot identify the file, it cannot offer that cleanup safely.

Source Evaluation's copied diagnostics replace raw error text with a short category. They can still include source and extension names when Evaluation mode is off. Evaluation mode changes those names to neutral labels; it does not hide manga titles, account information, notifications, or everything in a screenshot or export.

Read any file, screenshot, or log before sharing it. See [Sharing](feature-guides/sharing.md) and [Privacy and data](privacy-and-data.md). Report security concerns through the [security policy](../../SECURITY.md).
