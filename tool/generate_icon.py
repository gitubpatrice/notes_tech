"""Génère l'icône de Notes Tech aux différentes résolutions Android.

Reprend la charte Files Tech (identique à AI Tech) :
- 4 quadrants bleu/rouge alternés
- Texte "Notes" sur la première ligne, "Tech" sur la seconde, blanc gras
- Ombre portée légère pour détacher le texte du fond bicolore

Lancer une fois pour produire :
  - les `ic_launcher.png` dans `android/app/src/main/res/mipmap-*`
  - le master 1024 dans `assets/icon/app_icon.png`
  - le foreground adaptive dans `assets/icon/app_icon_fg.png` (texte sur fond
    transparent — utilisé par flutter_launcher_icons)
"""

from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

# Couleurs charte Files Tech (alignées sur AI Tech).
BLUE = (38, 96, 199)
RED = (203, 33, 41)
WHITE = (255, 255, 255)

# Tailles Android. flutter_launcher_icons écrit aussi mipmap-anydpi-v26
# (XML adaptive) et utilise le master pour générer ses propres tailles.
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

MASTER = 1024


def find_font(size: int) -> ImageFont.ImageFont:
    candidates = [
        r"C:\Windows\Fonts\segoeuib.ttf",
        r"C:\Windows\Fonts\arialbd.ttf",
        r"C:\Windows\Fonts\arial.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def render_master(transparent_bg: bool = False) -> Image.Image:
    """Produit l'icône master 1024×1024.

    Si `transparent_bg=True`, omet les quadrants pour ne garder que le
    texte sur fond transparent (foreground adaptive icon).
    """
    img = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if not transparent_bg:
        half = MASTER // 2
        # Quadrants : TL bleu, TR rouge, BL rouge, BR bleu.
        draw.rectangle([0, 0, half, half], fill=BLUE)
        draw.rectangle([half, 0, MASTER, half], fill=RED)
        draw.rectangle([0, half, half, MASTER], fill=RED)
        draw.rectangle([half, half, MASTER, MASTER], fill=BLUE)

    line_top = "Notes"
    line_bottom = "Tech"
    # "Notes" est plus long que "AI" : on réduit la taille de police pour
    # garder un écart latéral correct sans débordement.
    font_size = int(MASTER * 0.26)
    font = find_font(font_size)

    bbox_top = draw.textbbox((0, 0), line_top, font=font)
    tw_t, th_t = bbox_top[2] - bbox_top[0], bbox_top[3] - bbox_top[1]
    bbox_bot = draw.textbbox((0, 0), line_bottom, font=font)
    tw_b, th_b = bbox_bot[2] - bbox_bot[0], bbox_bot[3] - bbox_bot[1]

    line_gap = int(MASTER * 0.02)
    block_h = th_t + line_gap + th_b
    block_y = (MASTER - block_h) // 2

    tx_t = (MASTER - tw_t) / 2 - bbox_top[0]
    ty_t = block_y - bbox_top[1]
    tx_b = (MASTER - tw_b) / 2 - bbox_bot[0]
    ty_b = block_y + th_t + line_gap - bbox_bot[1]

    # Ombre portée douce.
    shadow_layer = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(shadow_layer)
    sdraw.text((tx_t + 4, ty_t + 8), line_top, font=font, fill=(0, 0, 0, 160))
    sdraw.text((tx_b + 4, ty_b + 8), line_bottom, font=font, fill=(0, 0, 0, 160))
    shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(radius=8))
    img.alpha_composite(shadow_layer)

    draw.text((tx_t, ty_t), line_top, font=font, fill=WHITE)
    draw.text((tx_b, ty_b), line_bottom, font=font, fill=WHITE)

    return img


def main() -> None:
    master = render_master(transparent_bg=False)

    # Tailles legacy mipmap.
    for folder, size in SIZES.items():
        out_dir = os.path.join(RES, folder)
        os.makedirs(out_dir, exist_ok=True)
        resized = master.resize((size, size), Image.LANCZOS)
        out_path = os.path.join(out_dir, "ic_launcher.png")
        resized.save(out_path, "PNG", optimize=True)
        print(f"écrit : {out_path}")

    # Master en racine assets pour cohérence Files Tech.
    assets_dir = os.path.join(ROOT, "assets", "icon")
    os.makedirs(assets_dir, exist_ok=True)
    master_path = os.path.join(assets_dir, "app_icon.png")
    master.save(master_path, "PNG", optimize=True)
    print(f"master 1024 : {master_path}")

    # Foreground adaptive (texte seul sur transparent) pour
    # `flutter_launcher_icons.adaptive_icon_foreground`.
    fg = render_master(transparent_bg=True)
    fg_path = os.path.join(assets_dir, "app_icon_fg.png")
    fg.save(fg_path, "PNG", optimize=True)
    print(f"foreground adaptive : {fg_path}")


if __name__ == "__main__":
    main()
