# Technical reference

These XML files list features, related code, and existing screenshots. They are for documentation maintenance; use the public guides for instructions.

## Files

- [`feature-reference.xml`](feature-reference.xml) links feature areas to screens, behavior, privacy notes, guides, and source files. It is a maintenance map, not a complete list of current controls.
- [`screenshot-manifest-v0.8.23.xml`](screenshot-manifest-v0.8.23.xml) lists the v0.8.23 illustrations (What's New, Local Tracking decimal progress, Local Tracking refresh, and the source-switching chooser) with their SHA-256 hashes. The images were captured from the v0.8.23 public-test build using synthetic test content; the What's New image shows the beginning of scrollable release notes.
- [`screenshot-manifest-v0.8.24.xml`](screenshot-manifest-v0.8.24.xml) lists the v0.8.24 illustrations of Recommendation Settings, For You, Focus, Source evaluation, tracking, the reader and What's New, with their SHA-256 hashes. They were captured from the v0.8.24 public-test build with source, extension and tag names replaced by placeholders.
- [`screenshot-manifest.xml`](screenshot-manifest.xml) records the earlier illustrations, the states they show, their privacy checks, and their SHA-256 hashes.

The files contain written descriptions, repository-relative file paths, and screenshot hashes, not raw Android screen dumps or private device storage paths. The screenshots themselves can still show manga titles, covers, and reading context. Use the [user guide](../user-guide.md) for instructions, the [feature guides](../feature-guides/README.md) for current behavior, and the [visual guide](../visual-guide/README.md) for the images.

## Keeping the reference current

Neither a feature entry nor a screenshot label guarantees that a screen is anonymous or a source is safe. Check the actual image before sharing it. A matching SHA-256 hash only confirms that the file has not changed since it was recorded.

The manifests describe the builds used for those captures, not the latest release. Older Best Version images do not show **View in Reader**. The v0.8.23 Local Tracking and source-switching images use synthetic test content.

Update a feature entry when its route, visible states, privacy behavior, documentation page, or implementation owner changes. Update a screenshot entry when the image changes, then recompute its SHA-256 hash and repeat the privacy review. A source-only change does not require a new screenshot when the visible screen and behavior remain unchanged.
