# Outpost branding

The blue mark follows the owner-supplied artwork. Its opaque checkerboard background was removed by tracing the geometric shape into a clean vector. The source image in Downloads is unchanged.

- `outpost-mark.svg`: scalable blue mark with transparency.
- `outpost-mark-white.svg`: white mark for dark backgrounds.
- `outpost-mark.png`: transparent 1024px export.
- `outpost-icon.png`: 1024px icon on a dark rounded tile.

Android includes adaptive, themed monochrome and legacy launcher icons. Windows embeds a multi-resolution icon in its EXE and uses it for the window and tray. The Android header uses the same mark.

To regenerate the exports, install Pillow and run `python scripts/build-icons.py`. No image-generation API is needed.
