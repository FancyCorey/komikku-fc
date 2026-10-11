# Technical reference

This directory contains XML references that help documentation tools and release checks inspect the same feature and screenshot information described in the public guides.

## Files

- [`feature-reference.xml`](feature-reference.xml) maps each Komikku FC feature to its route, purpose, visible states, privacy rule, documentation page, and primary implementation owner.
- [`screenshot-manifest-v0.8.23.xml`](screenshot-manifest-v0.8.23.xml) lists the v0.8.23 illustrations (What's New, Local Tracking decimal progress, Local Tracking refresh, and the source-switching chooser) with their SHA-256 hashes. The images were captured from the v0.8.23 public-test build using synthetic test content; the What's New image shows the beginning of scrollable release notes.
- [`screenshot-manifest.xml`](screenshot-manifest.xml) records the earlier illustrations, the states they show, their privacy checks, and their SHA-256 hashes.

These files are not Android UI dumps and do not contain screen coordinates, device identifiers, account details, manga titles, source names, URLs, or local storage paths. Use the [user guide](../user-guide.md) for instructions, the [feature guides](../feature-guides/README.md) for behavior, and the [visual feature guide](../visual-guide/README.md) for the images themselves.

## Keeping the reference current

The feature reference describes app behavior, not a guarantee that a screen is anonymous or a source is safe. Its `screenshot` labels refer to the existing illustration set, not approval of every current state. Check each image and its manifest entry separately; older Best Version illustrations do not show the newer View in Reader action. The v0.8.23 manifest has illustrations for Local Tracking progress and refresh and for source switching; they show synthetic test content only.

Update a feature entry when its route, visible states, privacy behavior, documentation page, or implementation owner changes. Update a screenshot entry when the image changes, then recompute its SHA-256 hash and repeat the privacy review. A source-only change does not require a new screenshot when the visible screen and behavior remain unchanged.
