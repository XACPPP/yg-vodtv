# -*- coding: utf-8 -*-
"""把一张方形设计稿切成 Android 各密度 ic_launcher.png。

用法:
    python tools/gen_icons.py <源图.png>

源图要求: 正方形、内容自带圆角（iOS 风格卡片）。脚本会按源图自身的圆角半径
生成带透明圆角的 alpha 蒙版（4x 超采样抗锯齿），再缩放到各密度。

输出（覆盖）:
    app/src/main/res/drawable/ic_launcher.png        192
    app/src/main/res/drawable-mdpi/ic_launcher.png    48
    app/src/main/res/drawable-hdpi/ic_launcher.png    72
    app/src/main/res/drawable-xhdpi/ic_launcher.png   96
    app/src/main/res/drawable-xxhdpi/ic_launcher.png 144
    app/src/main/res/drawable-xxxhdpi/ic_launcher.png 192
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGETS = [
    ("drawable", 192),
    ("drawable-mdpi", 48),
    ("drawable-hdpi", 72),
    ("drawable-xhdpi", 96),
    ("drawable-xxhdpi", 144),
    ("drawable-xxxhdpi", 192),
]
# 源图 1254px 画布实测圆角约 300px（y=20 处首个非黑像素 x=191，符合 R=300 的圆弧）
DEFAULT_RADIUS_RATIO = 300.0 / 1254.0


def rounded_mask(size, radius_ratio, scale=4):
    """生成 size×size 的圆角矩形 alpha 蒙版，超采样后缩小做抗锯齿。"""
    big = size * scale
    r = int(round(big * radius_ratio))
    m = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=r, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    src = sys.argv[1]
    im = Image.open(src).convert("RGB")
    w, h = im.size
    if w != h:
        print("源图不是正方形: %dx%d" % (w, h))
        return 2
    rgba = im.convert("RGBA")
    mask = rounded_mask(w, DEFAULT_RADIUS_RATIO)
    rgba.putalpha(mask)
    print("源图 %d x %d，圆角半径比例 %.4f" % (w, h, DEFAULT_RADIUS_RATIO))
    for bucket, px in TARGETS:
        out_dir = os.path.join(ROOT, "app", "src", "main", "res", bucket)
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, "ic_launcher.png")
        rgba.resize((px, px), Image.LANCZOS).save(out, "PNG", optimize=True)
        print("  %-20s %3dpx -> %s" % (bucket, px, out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
