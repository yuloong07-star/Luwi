"""生成这份样例外观的五张图 (每个状态一张, 240x240 png)

球才 48 dp, 所以画个简单的: 一圈深色描边 + 按状态换色的实心圆 + 右上角一块柔光。它只是"演示外观
插件长什么样", 不是给别人抄的设计稿

用法 (在本目录下):  python tools/build-art.py
"""

import os

from PIL import Image, ImageDraw

SIZE = 240

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(HERE)
ART = os.path.join(MODULE, "plugin", "art")

# 状态 → (圆面, 描边)
STATES = {
    "idle": ((26, 34, 54), (90, 104, 138)),
    "listening": ((20, 92, 76), (110, 243, 176)),
    "thinking": ((96, 66, 18), (255, 194, 77)),
    "speaking": ((66, 42, 110), (179, 136, 255)),
    "failed": ((104, 30, 34), (255, 82, 82)),
}


def draw(name: str, fill, ring) -> None:
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    pen = ImageDraw.Draw(image)
    pen.ellipse((18, 18, SIZE - 18, SIZE - 18), fill=fill, outline=ring, width=10)
    # 右上角一块柔光: 让它是"一颗球"而不是一个平圆
    pen.ellipse((SIZE - 96, 40, SIZE - 40, 96), fill=(255, 255, 255, 38))
    pen.ellipse((52, 150, 92, 190), fill=(255, 255, 255, 20))
    path = os.path.join(ART, name + ".png")
    image.save(path, optimize=True)
    print("%-10s %6d B" % (name, os.path.getsize(path)))


def main() -> None:
    os.makedirs(ART, exist_ok=True)
    for name, (fill, ring) in STATES.items():
        draw(name, fill, ring)


if __name__ == "__main__":
    main()
