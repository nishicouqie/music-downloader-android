# -*- coding: utf-8 -*-
"""生成 Android 启动图标（传统 PNG + 自适应图标前景）"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

RES = Path(r"D:\我的\程序\deepseek harness work\android-repo\app\src\main\res")
ACCENT = (79, 70, 229, 255)
WHITE = (255, 255, 255, 255)


def note(size, pad_ratio=0.0, bg=None):
    """画白色双音符；(size,pad_ratio) 控制音符在画布里的占比"""
    S = size * 4
    img = Image.new("RGBA", (S, S), bg or (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = 1 - pad_ratio                      # 音符占比
    off = (S * (1 - k)) / 2

    def X(v):
        return off + v * S * k

    lx, ly, r = X(0.295), X(0.735), S * k * 0.128
    rx, ry = X(0.700), X(0.655)
    stem = S * k * 0.062
    ltop, rtop = X(0.300), X(0.220)
    d.ellipse([lx - r, ly - r * 0.86, lx + r, ly + r * 0.86], fill=WHITE)
    d.ellipse([rx - r, ry - r * 0.86, rx + r, ry + r * 0.86], fill=WHITE)
    d.rounded_rectangle([lx + r * 0.42, ltop, lx + r * 0.42 + stem, ly],
                        radius=stem / 2, fill=WHITE)
    d.rounded_rectangle([rx + r * 0.42, rtop, rx + r * 0.42 + stem, ry],
                        radius=stem / 2, fill=WHITE)
    d.polygon([(lx + r * 0.42, ltop), (rx + r * 0.42 + stem, rtop),
               (rx + r * 0.42 + stem, rtop + S * k * 0.105),
               (lx + r * 0.42, ltop + S * k * 0.115)], fill=WHITE)
    return img.resize((size, size), Image.LANCZOS)


def rounded(img, radius_ratio=0.235):
    S = img.size[0]
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1],
                                           radius=int(S * radius_ratio), fill=255)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

for name, px in DENS.items():
    d = RES / f"mipmap-{name}"
    d.mkdir(parents=True, exist_ok=True)
    # 传统图标：靛蓝圆角底 + 白音符
    base = Image.new("RGBA", (px, px), ACCENT)
    base.alpha_composite(note(px, 0.0))
    rounded(base).save(d / "ic_launcher.png")
    rounded(base).save(d / "ic_launcher_round.png")
    # 自适应图标前景：透明底 + 缩小到安全区的白音符
    fg = int(px * 108 / 48)
    note(fg, 0.30).save(d / "ic_launcher_foreground.png")
    print(f"  mipmap-{name}: {px}px (+前景 {fg}px)")

print("图标生成完成")
