"""把 Luwi 自己的标记做成 Android 要的那几档位图

为什么需要这个脚本: 手机上真正画出来的那一个图标是自适应图标那两份
(`mipmap-anydpi/ic_launcher.xml` = `drawable/ic_launcher_background.xml` 那条渐变 +
`drawable/ic_launcher_foreground.xml` 那个标记), 而只要有一处回退到密度位图 (旧启动器、图标
选择器、任何直接翻 `mipmap-*dpi` 的地方) 拿到的就是下面这几张 —— 两者必须长得一样, 所以位图
不能靠手画, 于是把它做成可复现的一步

源图是 `tools/brand/luwi-mark.png` (主人 2026-10-10 给的品牌图: 一圈水花里一只鲸尾, 白底单色),
而那份矢量是从同一张描出来的, 见 `tools/vector-trace.py`。**墨迹宽度 0.46 这条规矩三处共用**
(那份矢量 / 这里的位图 / 通知栏那个白模的 0.12 内缩), 换标记时主人点名"图案占比要和现在的一样",
所以这几个数一个字节都没动

用法:
  python tools/make-icons.py
  python tools/make-icons.py --source <mark.png> --res <app/src/main/res>

生成:
  mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.webp        方形, 背景是自适应图标那条渐变 + 标记
  mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.webp  同样内容裁成圆
  drawable/ic_notification.png                        24dp 单色小图标, 通知栏用

**不进构建**: 这几个文件是提交进仓库的产物, Gradle 不认识这个脚本, 换图标时手工跑一次
"""

import argparse
import shutil
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover - 只在没装 Pillow 的机器上会走到
    sys.exit('this script needs Pillow: python -m pip install Pillow')

# Android 的密度档与它对应的启动器图标边长, 单位是像素
DENSITIES = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}

# 通知小图标是 24dp, 而通知图标取密度最高的那一档就够: 系统自己会按屏幕缩放
NOTIFICATION_PX = 96

# 品牌图是"白纸 + 一层蓝": 蓝色那一片自己的亮度是 135, 于是 alpha = (255 - L) / (255 - 135),
# 而纸纹那点 0.02 的雾清掉 (不清掉的话白模上会有一层几乎看不见的脏点)
INK_LUMA = 135.0
ALPHA_FLOOR = 0.06

# 通知小图标的安全区: 24dp 里图案只占中间这一段, 四周留白, 免得被裁到
NOTIFICATION_INSET = 0.12

# 标记在画布上占多宽 —— 与 `drawable/ic_launcher_foreground.xml` 里 group 那个变换同一个数
# (2026-10-06 定的: Android keyline 那圈是 44/108 = 0.407, 这里留一点余量)
INK_WIDTH = 0.46

# 自适应图标那条背景渐变, 与 `drawable/ic_launcher_background.xml` 同一条 ((0,0) 到 (108,108))
BACKGROUND_STOPS = (
    (0.14, (0xD4, 0xDB, 0xE9)),
    (0.49, (0xFB, 0xFB, 0xFB)),
    (0.83, (0xE1, 0xE6, 0xEE)),
)

# 标记自己的颜色 (品牌图里那层蓝, 亮度的中位数就是它)
MARK_RGB = (0x01, 0xB4, 0xFF)


def mark_mask(image):
    """哪几个像素是标记 (一张灰度遮罩)

    白底那张画里的"墨量"就是亮度: 蓝色那一片自己的亮度是 135, 于是
    alpha = (255 - L) / (255 - 135) —— 边缘那圈抗锯齿自然落成半透明的 alpha, 与手画的遮罩一个
    效果。纸纹给的 0.02 那点雾 (亮度 252 上下) 清掉, 否则白模上会浮一层看不见的脏点

    先合成到白底上再取亮度: 源图是 RGBA, 直接转灰度会把透明的部分当成黑色
    """
    flat = Image.alpha_composite(
        Image.new('RGBA', image.size, (255, 255, 255, 255)), image.convert('RGBA'))
    # grey 的 'L' 就是 Rec.601 那条 (0.299R + 0.587G + 0.114B), 与上面那条算式同一把尺子
    grey = flat.convert('L')
    # 一张 256 项的查找表: 比 INK_LUMA 更亮的不算墨, 其余按"离白有多远"折算成 alpha
    table = [
        0 if value > 255 - ALPHA_FLOOR * (255 - INK_LUMA)
        else round(min(1.0, (255 - value) / (255 - INK_LUMA)) * 255)
        for value in range(256)
    ]
    return grey.point(table, 'L')


def mark_box(mask):
    """标记实际占的那块矩形, 兜底是整张图"""
    return mask.getbbox() or (0, 0, mask.width, mask.height)


def glyph(mask, size, inset, ink=False):
    """把标记抠出来, 缩放并居中放进一块 size x size 的透明画布

    居中按**裁切框**算, 不按标记自己的边框算: 标记比它那块框矮, 按边框居中会让它整体靠在
    下边。通知栏那个位置只有 24dp, 偏一点看得出来

    `ink` 只是给人看的: 通知小图标交给系统时必须是一层白模 (系统拿它当模板染色), 所以默认
    是白的 —— 而白的在白底预览图上什么也看不见, 要检查形状就把它染黑
    """
    patch_mask = mask.crop(mark_box(mask))
    inner = max(1, round(size * (1 - 2 * inset)))
    scale = inner / max(patch_mask.width, patch_mask.height)
    target = (max(1, round(patch_mask.width * scale)), max(1, round(patch_mask.height * scale)))
    patch_mask = patch_mask.resize(target, Image.LANCZOS).convert('L')

    colour = (0, 0, 0, 255) if ink else (255, 255, 255, 255)
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    patch = Image.new('RGBA', target, colour)
    patch.putalpha(patch_mask)
    canvas.paste(patch, ((size - target[0]) // 2, (size - target[1]) // 2), patch)
    return canvas


def background(size):
    """自适应图标那条背景渐变

    与 `drawable/ic_launcher_background.xml` 同一档参数 (从 (0,0) 到 (108,108) 的三档线性渐变),
    所以这里的 t 就是 (x + y) / (2 * (size - 1)) —— 一块方块上那两端的对角线值一样
    """
    stops = BACKGROUND_STOPS
    data = bytearray()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            if t <= stops[0][0]:
                colour = stops[0][1]
            elif t >= stops[-1][0]:
                colour = stops[-1][1]
            else:
                colour = stops[-1][1]
                for index in range(len(stops) - 1):
                    low, high = stops[index], stops[index + 1]
                    if low[0] <= t <= high[0]:
                        blend = (t - low[0]) / (high[0] - low[0])
                        colour = tuple(
                            round(low[1][channel] + (high[1][channel] - low[1][channel]) * blend)
                            for channel in range(3)
                        )
                        break
            data.extend(colour)
    return Image.frombytes('RGB', (size, size), bytes(data))


def launcher_icon(mask, size, round_corners):
    """一档位图回退: 与自适应图标同一个样子 —— 那条渐变打底 + 0.46 宽的标记

    缩放按**墨迹宽度**算 (不按高度), 与那份矢量里 group 的变换同一把尺子; `round_corners`
    那一份是清单里 `roundIcon` 要的圆, 裁在这里比让系统裁更可控
    """
    icon = background(size).convert('RGBA')
    patch_mask = mask.crop(mark_box(mask))
    inner = max(1, round(size * INK_WIDTH))
    height = max(1, round(patch_mask.height * inner / patch_mask.width))
    patch = Image.new('RGBA', (inner, height), MARK_RGB + (255,))
    patch.putalpha(patch_mask.resize((inner, height), Image.LANCZOS))
    icon.alpha_composite(patch, ((size - inner) // 2, (size - height) // 2))
    if round_corners:
        circle = Image.new('L', (size, size), 0)
        ImageDraw.Draw(circle).ellipse((0, 0, size - 1, size - 1), fill=255)
        icon.putalpha(circle)
    return icon


def main():
    parser = argparse.ArgumentParser(description='Render the Android launcher and notification icons')
    parser.add_argument(
        '--source',
        default='tools/brand/luwi-mark.png',
        help="Luwi's own mark, which every icon is rendered from",
    )
    parser.add_argument('--res', default='app/src/main/res', help='where the drawables live')
    parser.add_argument('--lock-screen', metavar='SRC', help='also install SRC as the lock screen wallpaper')
    parser.add_argument('--background', metavar='SRC', help='also install SRC as the home screen wallpaper')
    parser.add_argument('--preview', metavar='DIR', help='write PNG copies of everything here, for looking at')
    arguments = parser.parse_args()

    source_path = Path(arguments.source)
    if not source_path.is_file():
        sys.exit(f'no such source image: {source_path}')
    res = Path(arguments.res)
    if not res.is_dir():
        sys.exit(f'no such resource directory: {res}')

    source = Image.open(source_path)
    print(f'source {source_path} {source.width}x{source.height}')
    mask = mark_mask(source)
    box = mark_box(mask)
    print(f'  mark box {box} = {box[2] - box[0]}x{box[3] - box[1]}')

    preview = Path(arguments.preview) if arguments.preview else None
    if preview:
        preview.mkdir(parents=True, exist_ok=True)

    for density, size in DENSITIES.items():
        directory = res / f'mipmap-{density}'
        directory.mkdir(parents=True, exist_ok=True)
        for name, rounded in (('ic_launcher', False), ('ic_launcher_round', True)):
            icon = launcher_icon(mask, size, rounded)
            target = directory / f'{name}.webp'
            icon.save(target, 'WEBP', lossless=True, quality=100)
            if preview:
                icon.save(preview / f'{density}-{name}.png')
            print(f'  {target} {size}x{size}')

    notification = glyph(mask, NOTIFICATION_PX, inset=NOTIFICATION_INSET)
    drawable = res / 'drawable'
    drawable.mkdir(parents=True, exist_ok=True)
    notification_path = drawable / 'ic_notification.png'
    notification.save(notification_path, 'PNG', optimize=True)
    if preview:
        # 预览把形状染黑: 白色的白模放在白底看图工具里等于一张空图
        glyph(mask, NOTIFICATION_PX, inset=NOTIFICATION_INSET, ink=True).save(
            preview / 'ic_notification-ink.png'
        )
        notification.save(preview / 'ic_notification.png')
    print(f'  {notification_path} {NOTIFICATION_PX}x{NOTIFICATION_PX}')

    # 两样壁纸是手工步骤的产物: 源图给进来就顺手放到 res 里, 没给就不动, 免得把旧图覆盖成空的
    for option, name in (('lock_screen', 'lock_screen.png'), ('background', 'background.png')):
        path = getattr(arguments, option)
        if not path:
            continue
        shutil.copyfile(path, drawable / name)
        print(f'  {drawable / name}')

    print('done, now build: .\\gradlew.bat :app:assembleDebug')


if __name__ == '__main__':
    main()
