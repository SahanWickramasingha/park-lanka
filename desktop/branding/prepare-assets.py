"""Resize the supplied artwork deterministically; never generate or redraw a logo.

Requires Python 3 and Pillow. Originals in source/ are retained byte for byte.
Run from any directory: python desktop/branding/prepare-assets.py
"""
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
RESOURCES = HERE.parent / "src/main/resources/lk/sahan/parking/desktop/branding"
SIZES = (16, 24, 32, 48, 64, 128, 256)


def trimmed(path):
    image = Image.open(path).convert("RGBA")
    return image.crop(image.getchannel("A").getbbox())


def main():
    RESOURCES.mkdir(parents=True, exist_ok=True)
    logo = trimmed(HERE / "source/park-lanka-logo.png")
    logo.save(RESOURCES / "park-lanka-logo.png")
    icon = trimmed(HERE / "source/park-lanka-icon.png")
    # Equal padding, square canvas, no aspect distortion or corner clipping.
    side = max(icon.size)
    padding = max(1, round(side * 0.02))
    square = Image.new("RGBA", (side + 2 * padding,) * 2)
    square.alpha_composite(icon, ((square.width - icon.width) // 2, (square.height - icon.height) // 2))
    master = square.resize((1024, 1024), Image.Resampling.LANCZOS)
    master.save(RESOURCES / "park-lanka-icon-1024.png")
    for size in SIZES:
        master.resize((size, size), Image.Resampling.LANCZOS).save(RESOURCES / f"park-lanka-icon-{size}.png")
    master.save(HERE / "park-lanka.ico", format="ICO", sizes=[(size, size) for size in SIZES])
    with Image.open(HERE / "park-lanka.ico") as ico:
        assert ico.ico.sizes() == {(size, size) for size in SIZES}
    # Inspection sheet only; never used as application artwork.
    preview = Image.new("RGB", (680, 300), "#eef2f8")
    draw = ImageDraw.Draw(preview)
    x = 14
    for size in SIZES:
        tile = Image.open(RESOURCES / f"park-lanka-icon-{size}.png").convert("RGBA")
        preview.paste(tile, (x, 30), tile)
        draw.text((x, 12), str(size), fill="#17243c")
        x += size + 10
    preview.save(HERE / "icon-sizes-preview.png")
    print("Prepared transparent logo, 1024px master, seven PNG sizes, and seven-entry ICO.")


if __name__ == "__main__":
    main()
