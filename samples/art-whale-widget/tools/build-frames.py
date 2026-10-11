"""从 `source/` 那份动图抽成宿主槽要的逐帧图

出这样两组 (都落在 `plugin/art/` 下, 它们是**签名载荷的一部分**):

  dance-1/frame-00.png …      正常那一套 (32 帧, 10 fps)
  dance-1-slow/frame-00.png … 同一组帧, 慢放那一档 (5 fps; 点一下换的那一套)

命名是协议定死的形状: `<dir>/frame-<数字>.png` —— 宿主槽按这个形状**逐张**查、逐张取
(见 app 那一侧 `plugin/widget/WidgetFramesProvider.kt` 与 `PluginPaths.checkPackage`)

用法 (在本目录下):
  python tools/build-frames.py
"""

import os

from PIL import Image, ImageSequence

FRAMES = 32
TARGET = (300, 370)

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(HERE)
PLUGIN = os.path.join(MODULE, "plugin")
SOURCE = os.path.join(MODULE, "source", "dance-1.gif")


def frames():
    source = [frame.convert("RGB") for frame in ImageSequence.Iterator(Image.open(SOURCE))]
    step = max(1, len(source) // FRAMES)
    return [source[(position * step) % len(source)] for position in range(FRAMES)], len(source)


def write(directory: str, picks: list) -> int:
    os.makedirs(directory, exist_ok=True)
    total = 0
    for position, frame in enumerate(picks):
        path = os.path.join(directory, "frame-%02d.png" % position)
        frame.resize(TARGET, Image.LANCZOS).quantize(colors=255).save(path, optimize=True)
        total += os.path.getsize(path)
    return total


def main() -> None:
    picks, source_frames = frames()
    normal = write(os.path.join(PLUGIN, "art", "dance-1"), picks)
    slow = write(os.path.join(PLUGIN, "art", "dance-1-slow"), picks)
    print("dance-1       %2d 张 %6d KB" % (FRAMES, normal // 1024))
    print("dance-1-slow  %2d 张 %6d KB" % (FRAMES, slow // 1024))
    print("(源动图 %d 帧抽成 %d 帧: %s)" % (source_frames, FRAMES, SOURCE))


if __name__ == "__main__":
    main()
