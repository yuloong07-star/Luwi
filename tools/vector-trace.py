"""把品牌图描成自适应图标的前景矢量

为什么需要这个脚本 (2026-10-10 主人: "图标全部改用这个, 包括 ball, 注意图案占比要和现在的一样"):
新的标记是一张水彩画 (一圈水花里一只鲸尾, 白底单色), 而它在这个仓库里要落在三处 ——
`drawable/ic_launcher_foreground.xml` 同时是**自适应图标的前景与单色层** (Android 13 的主题图标),
以及**球上那个染白的标记** (`overlay/Ball.kt`) 的来源。位图在头两处都能用, 但单色层与球那一处
只吃 alpha 通道, 而位图进 XML 只有 `<bitmap>` 一条路 (尺寸写死、还要再管一层缩放), 所以这条
流水线的产物是矢量: 把水花按等值线描成多边形, 用 `evenOdd` 填充, 环里的洞与那些飞溅点都在

源图是 `tools/brand/luwi-mark.png` (1024 的白底单色画, 是主人给的原件), 抠法是"亮度当墨量":
蓝色那一片自己的亮度是 135, 于是 alpha = (255 - L) / (255 - 135), 而纸纹那点 0.02 的雾清掉

尺寸**不在这里算**: 那份矢量里 group 的 scale 与 translate 由 `tools/vector-ink.py` 带着那面
`--ink-width` 旗标解出来 (默认 0.46) —— 一处数学两处用。那个 0.46 是 2026-10-06 定下来的
(Android keyline 那圈是 44/108 = 0.407, dsh 自己那张图是 0.726, 主人两次说过"图标被放大了"),
这次换标记主人点名"图案占比要和现在的一样", 所以这个数一个字节没动

用法 (两步, 顺序不能反):
  python tools/trace-mark.py                                # 描边 -> 写回那份 XML
  python tools/vector-ink.py --write --ink-width 0.46       # 解尺寸 -> 再写一次那份 XML

**不进构建**: 与 `tools/make-icons.py` 一样是手工跑一次的工具, 产物提交进仓库
"""

import argparse
import sys
from pathlib import Path

try:
    import numpy as np
    from PIL import Image
    from skimage import measure
except ImportError:  # pragma: no cover - 只在没装依赖的机器上会走到
    sys.exit('this script needs Pillow, numpy and scikit-image: '
             'python -m pip install Pillow numpy scikit-image')

# 与 make-icons.py 同一个数: 品牌图里那层蓝自己的亮度 (255 减它就是满墨那一下)
INK_LUMA = 135.0
# 纸纹给的那点雾 (亮度 252 上下) 要清掉, 否则飞溅点之外还会多一圈几乎看不见的脏东西
ALPHA_FLOOR = 0.06
# 等值线的简化容差 (源图像素): 1024 上 1.5 px, 落到 192 px 的启动器图标上是 0.28 px
TOLERANCE = 1.5

# 描出来的那一份: group 的四个数先留一档 (**必须留着**, vector-ink.py 靠它们定位), 尺寸那一步会填
TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Luwi 自己的标记: 一圈水花里一只鲸尾 (主人 2026-10-10 换的, 原来这里是 dsh 那只鲸鱼)

     它由 tools/trace-mark.py 从 tools/brand/luwi-mark.png 描出来, 那一份是白底单色的水彩画。
     **不要手改 pathData**: 要改样子就换品牌图再跑那两步 (描边, 再用 vector-ink.py 的 write 旗标)。
     这一份同时被三处用: 自适应图标的前景与单色层 (mipmap-anydpi/ic_launcher.xml), 以及球上那个
     染白的标记 (overlay/Ball.kt) —— 只吃 alpha 的位图在单色层那一处不够用, 所以是矢量。

     这份注释里**不能出现两个连着的中横线**: XML 注释不许有它, aapt2 报的是 ParseError

     那条 0.46 是 2026-10-06 定下来的 (启动器图标在手机上被放大过两回), 换标记时主人点名
     "图案占比要和现在的一样", 于是这个数一个字节没动, 只换了形状与颜色。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:scaleX="1"
        android:scaleY="1"
        android:translateX="0"
        android:translateY="0">
        <path
            android:fillColor="#01B4FF"
            android:fillType="evenOdd"
            android:pathData="{path}" />
    </group>
</vector>
'''


def mark_alpha(source):
    """品牌图那张"白纸上的一层蓝" -> 墨量 (0 到 1 的浮点数组)"""
    data = np.asarray(source.convert('RGBA')).astype(float)
    rgb, alpha = data[..., :3], data[..., 3:4] / 255.0
    flat = rgb * alpha + 255.0 * (1 - alpha)
    luma = 0.299 * flat[..., 0] + 0.587 * flat[..., 1] + 0.114 * flat[..., 2]
    ink = np.clip((255.0 - luma) / (255.0 - INK_LUMA), 0.0, 1.0)
    ink[ink < ALPHA_FLOOR] = 0.0
    return ink


def contours_of(ink, tolerance):
    """等值线 -> 简化多边形 (源图像素的坐标, 已经换成 x/y 而不是 row/col)"""
    polygons = []
    for contour in measure.find_contours(ink, 0.5):
        polygon = measure.approximate_polygon(contour, tolerance=tolerance)
        if len(polygon) < 4:
            continue
        polygons.append([(float(point[1]), float(point[0])) for point in polygon])
    if not polygons:
        raise SystemExit('this image has no ink at all, is it the wrong file')
    return polygons


def path_data(polygons):
    """一条 pathData 里把每个多边形写成一个子路径, 闭合交回起点

    只用 M / L / Z 三条命令: 曲线一条都没有, 所以 tools/vector-ink.py 解析包围盒时不必给弧做展平
    """
    parts = []
    for polygon in polygons:
        head = f'M{polygon[0][0]:.2f},{polygon[0][1]:.2f}'
        body = ''.join(f'L{x:.2f},{y:.2f}' for x, y in polygon[1:-1])
        parts.append(head + body + 'Z')
    return ''.join(parts)


def write(vector_path, path):
    """整份重写 (那份 XML 是这条流水线的产物): group 那四个数交回空档, 由 vector-ink.py 再解一次

    **一定要 LF**: 仓库是 `* text=auto eol=lf`, 而 aapt2 在 CRLF 的 XML 上是 ParseError, 所以
    `write_text` 要显式给 newline='' (Windows 上不给就会写成 CRLF)
    """
    vector_path.write_text(TEMPLATE.format(path=path), encoding='utf-8', newline='')
    print(f'wrote {vector_path}')


def main():
    parser = argparse.ArgumentParser(description='Trace the brand mark into the adaptive icon foreground')
    parser.add_argument('--source', default='tools/brand/luwi-mark.png', help='the brand artwork')
    parser.add_argument(
        '--vector',
        default='app/src/main/res/drawable/ic_launcher_foreground.xml',
        help='where the foreground vector lives',
    )
    parser.add_argument('--tolerance', type=float, default=TOLERANCE, help='simplify tolerance in source pixels')
    arguments = parser.parse_args()

    source_path = Path(arguments.source)
    if not source_path.is_file():
        sys.exit(f'no such source image: {source_path}')
    source = Image.open(source_path)
    ink = mark_alpha(source)

    polygons = contours_of(ink, arguments.tolerance)
    points = sum(len(polygon) for polygon in polygons)
    xs = [point[0] for polygon in polygons for point in polygon]
    ys = [point[1] for polygon in polygons for point in polygon]
    print(f'source {source_path} {source.width}x{source.height}')
    print(f'  {len(polygons)} contours, {points} points, ink box '
          f'{min(xs):.1f},{min(ys):.1f} -> {max(xs):.1f},{max(ys):.1f} '
          f'= {max(xs) - min(xs):.1f} x {max(ys) - min(ys):.1f}')

    data = path_data(polygons)
    print(f'  pathData {len(data)} 字符')
    write(Path(arguments.vector), data)
    print('now run: python tools/vector-ink.py --write --ink-width 0.46')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
