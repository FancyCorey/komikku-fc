# Guide diagrams

Guides display SVG images so their labels keep the same layout across Markdown viewers. Editable Mermaid sources (`.mmd`) sit beside each SVG. Text descriptions in the guides explain the same steps without relying on the image.

## Updating a diagram

1. Edit its `.mmd` file.
2. Render the matching `.svg` with Mermaid CLI 11.4.2 and `mermaid-config.json`:

   ```sh
   mmdc -i docs/kmk/diagrams/source-evaluation/automatic-check.mmd -o docs/kmk/diagrams/source-evaluation/automatic-check.svg -c docs/kmk/diagrams/mermaid-config.json -b white
   ```

3. Inspect the SVG at normal reading size and in a narrow page. Check every label, arrow and branch for missing text, overlap and misleading flow. If anything changes, render and inspect it again.
4. Keep the source and SVG together in the same update.

Use short labels and line breaks instead of widening a diagram until the text becomes too small. Keep detailed instructions in the guide. The configuration uses SVG text rather than embedded HTML labels. Keep the white image background so dark text remains readable in both light and dark page themes.
