# -*- coding: utf-8 -*-
"""核对 APK 里的 ic_launcher 是否就是我们这版图。

AAPT 会重新压缩 PNG，MD5 对不上是正常的，所以这里比像素：
按「尺寸 + 逐像素差」找 APK 内最相似的 res PNG，平均通道误差 < 2 即认为命中。
"""
import glob
import io
import os
import sys
import zipfile

from PIL import Image, ImageChops, ImageStat

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main(apk_path):
    z = zipfile.ZipFile(apk_path)
    pool = []
    for n in z.namelist():
        if n.startswith("res/") and n.endswith(".png"):
            try:
                im = Image.open(io.BytesIO(z.read(n))).convert("RGBA")
            except Exception:
                continue
            pool.append((n, im))
    pat = os.path.join(ROOT, "app", "src", "main", "res", "drawable*", "ic_launcher.png")
    ok = 0
    files = sorted(glob.glob(pat))
    for p in files:
        rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
        src = Image.open(p).convert("RGBA")
        best = None
        for n, im in pool:
            if im.size != src.size:
                continue
            diff = ImageChops.difference(im, src)
            stat = sum(ImageStat.Stat(diff).mean) / 4.0
            if best is None or stat < best[0]:
                best = (stat, n)
        if best and best[0] < 2.0:
            ok += 1
            print("OK   %-42s -> %-12s 平均误差 %.3f" % (rel, best[1], best[0]))
        else:
            print("MISS %-42s -> 最近 %s 误差 %s" % (
                rel, best[1] if best else "-", "%.3f" % best[0] if best else "-"))
    print("\n命中 %d / %d" % (ok, len(files)))
    return 0 if ok == len(files) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1
                  else "app/build/outputs/apk/release/app-release.apk"))
