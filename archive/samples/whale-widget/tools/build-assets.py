"""把 `art/` 下那份动图收进伴侣 APK 的资源里

出这样两样:
  1. `res/drawable-nodpi/whale_frame_<n>_<xx>.png` —— 逐帧那一份 (桌面组件靠它动: 由 `ViewFlipper`
     一张一张翻, 见 `WhaleFrames`)
  2. `res/drawable-nodpi/whale_preview.png` —— 一号那套的第一帧, 给"选组件"那一页当缩略图

**源动图不进 res**: 它留在 `art/` (那是源), 由这一份脚本抽帧。原样那份动态 GIF 已经量过"交给宿主
不解" (`setImageResource` 那条路解不出 `AnimatedImageDrawable`), 所以包里不留它 —— 见
`docs/LW-鲸鱼娘小组件与应用技能-施工单.md` 第 2.1 节的三条读数

用法 (在模块根下):
  python tools/build-assets.py --dance 1 art/dance-1.gif
"""
import argparse
import os
import re

from PIL import Image, ImageSequence

FRAMES = 32
TARGET = (300, 370)

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(HERE)


def build(res: str, index: int, source: str) -> None:
    nodpi = os.path.join(res, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)

    source_frames = [frame.convert("RGB") for frame in ImageSequence.Iterator(Image.open(source))]
    step = max(1, len(source_frames) // FRAMES)
    picks = [source_frames[(position * step) % len(source_frames)] for position in range(FRAMES)]

    total = 0
    for position, frame in enumerate(picks):
        path = os.path.join(nodpi, "whale_frame_%d_%02d.png" % (index, position))
        frame.resize(TARGET, Image.LANCZOS).quantize(colors=255).save(path, optimize=True)
        total += os.path.getsize(path)
    print("frames %2d 张 whale_frame_%d_*  %6d KB (%s 帧的源抽成 %d 帧)"
          % (FRAMES, index, total // 1024, len(source_frames), FRAMES))

    if index == 1:
        preview = os.path.join(nodpi, "whale_preview.png")
        picks[0].resize(TARGET, Image.LANCZOS).quantize(colors=255).save(preview, optimize=True)
        print("preview whale_preview.png  %6d KB" % (os.path.getsize(preview) // 1024))


def prune(res: str, wanted: list) -> None:
    """这一轮没点的帧一律删掉, 免得它既进 APK 又骗人 ("桌面上只有一套, 包里却有三套的帧")"""
    nodpi = os.path.join(res, "drawable-nodpi")
    if not os.path.isdir(nodpi):
        return
    for name in os.listdir(nodpi):
        match = re.fullmatch(r"whale_frame_(\d+)_\d+\.png", name)
        stale = name.startswith("whale_dance_") or (
            match is not None and int(match.group(1)) not in wanted
        )
        if not stale:
            continue
        os.remove(os.path.join(nodpi, name))
        print("pruned", name)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--res", default=os.path.join(MODULE, "src", "main", "res"))
    parser.add_argument("--dance", nargs=2, action="append", metavar=("INDEX", "GIF"), required=True)
    args = parser.parse_args()
    wanted = [int(index) for index, _ in args.dance]
    for index, source in args.dance:
        build(args.res, int(index), source)
    prune(args.res, wanted)


if __name__ == "__main__":
    main()
