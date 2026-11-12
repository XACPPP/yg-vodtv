# -*- coding: utf-8 -*-
"""
YG影视TV 应用图标 —— 终版（Q 版机器人 / 动漫风）· 第 2 稿。

第 1 稿的两个问题：
  1. 眼睛是圆角矩形，两只并排读成「两块黄色屏幕」，不像眼睛
  2. 底部那条白横条（肩）像显示器底座，整个机器人像一台摆在桌上的旧显示器

这一稿：
  · 眼睛改椭圆 + 黑瞳 + 双高光，读成「有神的大眼睛」
  · 去掉肩部横条，只留一截短脖子，让它是一个「漂浮的机器人脑袋」
  · 头略放大、上移，把画面吃满
  · 天线杆缩短，小球不贴边
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFilter

SS = 4
BASE = 512
S = BASE * SS
RADIUS_RATIO = 0.225

GRAD = [(0x4A, 0x2B, 0x8C), (0x7B, 0x3D, 0xC9), (0xE0, 0x4E, 0xA8)]

SHELL_TOP = (0xF6, 0xF8, 0xFF)
SHELL_BOT = (0xC5, 0xD0, 0xE6)
SHELL_EDGE = (0x9F, 0xAD, 0xC8)
FACE = (0x12, 0x16, 0x1F)
FACE_EDGE = (0x26, 0x2E, 0x3D)
EYE = (0xFF, 0xC4, 0x1E)
EYE_GLOW = (0xFF, 0xD6, 0x4A)
PUPIL = (0x1A, 0x12, 0x08)
ACCENT_PINK = (0xFF, 0x7A, 0xB8)
ACCENT_PINK_D = (0xD9, 0x4A, 0x8E)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def vgrad_diag(size, stops):
    img = Image.new("RGB", (size, size))
    px = img.load()
    n = len(stops) - 1
    for y in range(size):
        for x in range(size):
            t = (x / (size - 1.0) * 0.40 + y / (size - 1.0) * 0.60) * n
            i = min(n - 1, int(t))
            f = t - i
            a, b = stops[i], stops[i + 1]
            px[x, y] = (round(a[0] + (b[0] - a[0]) * f),
                        round(a[1] + (b[1] - a[1]) * f),
                        round(a[2] + (b[2] - a[2]) * f))
    return img


def rmask(size, r):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return m


def soft(size, cx, cy, rx, ry, color, alpha, blur=0.03):
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=color + (alpha,))
    return layer.filter(ImageFilter.GaussianBlur(size * blur))


def rr(d, box, r, fill, outline=None, width=0):
    d.rounded_rectangle(box, radius=int(r), fill=fill, outline=outline, width=width)


def make_robot(size):
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    cx = size * 0.500
    head_cy = size * 0.545
    hw = size * 0.330
    hh = size * 0.300

    # ---- 天线（短杆 + 粉球）----
    ant_top = head_cy - hh - size * 0.115
    for w, col in ((int(size * 0.030), SHELL_EDGE + (255,)), (int(size * 0.016), SHELL_TOP + (255,))):
        d.line([(cx, head_cy - hh + size * 0.004), (cx, ant_top + size * 0.024)], fill=col, width=w)
    layer = Image.alpha_composite(layer, soft(size, cx, ant_top, size * 0.060, size * 0.060,
                                              ACCENT_PINK, 155, 0.016))
    d = ImageDraw.Draw(layer)
    d.ellipse([cx - size * 0.038, ant_top - size * 0.038,
               cx + size * 0.038, ant_top + size * 0.038], fill=ACCENT_PINK + (255,))
    d.ellipse([cx - size * 0.023, ant_top - size * 0.027,
               cx + size * 0.004, ant_top - size * 0.004], fill=(255, 226, 242, 210))

    # ---- 耳机（先画，藏在头两侧）----
    for sx in (-1, 1):
        ex = cx + sx * (hw + size * 0.026)
        rr(d, [ex - size * 0.050, head_cy - size * 0.110,
               ex + size * 0.050, head_cy + size * 0.110],
           size * 0.038, ACCENT_PINK_D + (255,))
        rr(d, [ex - size * 0.028, head_cy - size * 0.064,
               ex + size * 0.028, head_cy + size * 0.064],
           size * 0.026, ACCENT_PINK + (255,))

    # ---- 短脖子（只露一截，不要肩）----
    rr(d, [cx - size * 0.072, head_cy + hh - size * 0.008,
           cx + size * 0.072, head_cy + hh + size * 0.062],
       size * 0.026, SHELL_BOT + (255,))
    rr(d, [cx - size * 0.072, head_cy + hh - size * 0.008,
           cx + size * 0.072, head_cy + hh + size * 0.020],
       size * 0.020, SHELL_EDGE + (255,))

    # ---- 头壳 ----
    box = [cx - hw, head_cy - hh, cx + hw, head_cy + hh]
    rr(d, box, size * 0.112, SHELL_BOT + (255,))
    rr(d, [box[0], box[1], box[2], box[1] + (box[3] - box[1]) * 0.60],
       size * 0.112, SHELL_TOP + (255,))
    rr(d, box, size * 0.112, None, SHELL_EDGE + (255,), max(2, int(size * 0.008)))

    # ---- 面部屏（深色，带一点内发光边）----
    fx0, fy0 = cx - hw * 0.775, head_cy - hh * 0.605
    fx1, fy1 = cx + hw * 0.775, head_cy + hh * 0.545
    rr(d, [fx0, fy0, fx1, fy1], size * 0.078, FACE + (255,))
    rr(d, [fx0, fy0, fx1, fy0 + (fy1 - fy0) * 0.32], size * 0.078, FACE_EDGE + (255,))
    rr(d, [fx0, fy0, fx1, fy1], size * 0.078, None, (0x3A, 0x46, 0x5C, 210), max(2, int(size * 0.005)))

    # ---- 眼睛：椭圆 + 黑瞳 + 双高光 ----
    for sx in (-1, 1):
        ex = cx + sx * hw * 0.395
        ey = (fy0 + fy1) / 2 + size * 0.006
        erx, ery = size * 0.070, size * 0.078

        layer = Image.alpha_composite(layer, soft(size, ex, ey, erx * 2.0, ery * 1.7,
                                                  EYE_GLOW, 120, 0.013))
        d = ImageDraw.Draw(layer)
        d.ellipse([ex - erx, ey - ery, ex + erx, ey + ery], fill=EYE + (255,))
        # 瞳孔（略偏内下，读成「看向你」）
        d.ellipse([ex - erx * 0.40 + (-size * 0.004 * sx), ey - ery * 0.10,
                   ex + erx * 0.40 + (-size * 0.004 * sx), ey + ery * 0.62],
                  fill=PUPIL + (255,))
        # 主高光 + 副高光
        d.ellipse([ex - erx * 0.62, ey - ery * 0.66,
                   ex - erx * 0.04, ey - ery * 0.16], fill=(255, 255, 255, 235))
        d.ellipse([ex + erx * 0.22, ey + ery * 0.16,
                   ex + erx * 0.54, ey + ery * 0.44], fill=(255, 255, 255, 150))

    # ---- 嘴：小圆点 ----
    my = fy1 - size * 0.036
    d = ImageDraw.Draw(layer)
    d.ellipse([cx - size * 0.018, my - size * 0.014,
               cx + size * 0.018, my + size * 0.014], fill=EYE + (215,))

    # ---- 脸颊指示灯 ----
    for sx in (-1, 1):
        bx = cx + sx * (hw * 0.585)
        by = fy1 + size * 0.028
        d.ellipse([bx - size * 0.013, by - size * 0.013,
                   bx + size * 0.013, by + size * 0.013], fill=ACCENT_PINK + (235,))

    return layer


def build(size, star=True):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    img.paste(vgrad_diag(size, GRAD).convert("RGBA"), (0, 0))
    img = Image.alpha_composite(img, soft(size, size * 0.26, size * 0.16,
                                          size * 0.44, size * 0.36, (255, 255, 255), 58, 0.055))
    img = Image.alpha_composite(img, soft(size, size * 0.86, size * 0.86,
                                          size * 0.32, size * 0.28, (0x2B, 0x0F, 0x66), 70, 0.05))
    img = Image.alpha_composite(img, soft(size, size * 0.50, size * 0.885,
                                          size * 0.26, size * 0.045, EYE, 55, 0.026))

    img = Image.alpha_composite(img, make_robot(size))

    if star:
        st = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        d = ImageDraw.Draw(st)
        sx, sy, r = size * 0.148, size * 0.168, size * 0.052
        w = r * 0.24
        d.polygon([(sx - r, sy), (sx, sy - w), (sx + r, sy), (sx, sy + w)], fill=EYE_GLOW + (255,))
        d.polygon([(sx, sy - r), (sx + w, sy), (sx, sy + r), (sx - w, sy)], fill=EYE_GLOW + (255,))
        img = Image.alpha_composite(img, st.filter(ImageFilter.GaussianBlur(size * 0.0015)))

    img.putalpha(rmask(size, int(size * RADIUS_RATIO)))
    return img


def build_round(size):
    img = build(size)
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    img.putalpha(m)
    return img


def save_png(img, path, px):
    """按目标尺寸重采样后落盘。

    量化到 200 色调色板再转回 RGBA：这个图标一共就 6 个色系 + 渐变，
    肉眼看不出差别，体积却只有真彩的三分之一（192px 是 13KB vs 30KB）。
    """
    small = img.resize((px, px), Image.LANCZOS)
    q = small.quantize(colors=200, method=Image.FASTOCTREE, dither=Image.NONE)
    q.convert("RGBA").save(path, "PNG", optimize=True)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    proj = os.path.dirname(here)
    res = os.path.join(proj, "app", "res")
    cand = os.path.join(here, "icon_candidates")
    os.makedirs(cand, exist_ok=True)

    master = build(S)
    save_png(master, os.path.join(cand, "robot_512.png"), 512)
    save_png(build_round(S), os.path.join(cand, "robot_round.png"), 512)

    sizes = [32, 40, 48, 72, 96, 144, 192]
    pad = 22
    cell = max(sizes) + pad
    sheet = Image.new("RGBA", (cell * len(sizes), cell), (0x14, 0x18, 0x20, 255))
    for i, px in enumerate(sizes):
        sheet.alpha_composite(master.resize((px, px), Image.LANCZOS),
                              (i * cell + (cell - px) // 2, (cell - px) // 2))
    sheet.save(os.path.join(cand, "robot_ladder.png"))

    for name, px in DENSITIES.items():
        d = os.path.join(res, "drawable-" + name)
        os.makedirs(d, exist_ok=True)
        save_png(master, os.path.join(d, "ic_launcher.png"), px)
    plain = os.path.join(res, "drawable")
    os.makedirs(plain, exist_ok=True)
    save_png(master, os.path.join(plain, "ic_launcher.png"), 192)
    print("ok: res 已更新为机器人图标")


if __name__ == "__main__":
    main()
