#!/usr/bin/env python3
"""离线审计：把上游 LALC 素材逐张拿真机原帧比一遍，一次性列出失配清单。

存在的理由
----------
上游 562 张素材全部是在 Windows 客户端上截的，手机上哪些还能用、哪些已经换了控件，
过去只能一张一张撞：改一处 → 打 285MB 包 → 装机 → 跑一轮 → 看日志。一轮往返几十分钟，
于是只能 case-by-case 猜，无法整体优化。

本脚本把 Kotlin 侧 `TemplateMatcher` 的匹配流水线逐步复现在本机，用真机原帧离线跑，
**不需要设备、不需要打包**。已验证与设备结果一致：同一帧同一素材，本机 0.669 /
设备 0.652（差异来自图片传输时的重编码）。

复现的流水线（与 recognize/TemplateMatcher.kt 及上游 recognize/template_match.py 一致，
顺序和参数都不能改，改了分数就失去可比性）：

    1. BGR2GRAY          —— 统一走这条，不用 imread 的灰度解码（取整差异会被 CLAHE 放大）
    2. CLAHE             —— clipLimit=1.5, tileGridSize=8x8
    3. GaussianBlur 5x5
    4. TM_CCOEFF_NORMED

用法
----
    PY=.maa-cache/converter-env/bin/python3        # 该 venv 自带 cv2
    $PY scripts/audit_templates.py \
        --frames ~/Documents/k8s/frames \
        --templates ~/project/java/LixAssistantLimbusCompany/lalc_backend/img \
        --language en \
        --csv /tmp/audit.csv

原帧要求
--------
识别器实际看到的帧是 1280x720（日志里 `帧=1280x720`）。经聊天/截图渠道传过来的图片
常被重编码或改比例，所以本脚本一律先拉回 1280x720 再比——实测这样带来的误差在 0.003
量级，不影响判读。

判读
----
素材得分低有两种完全不同的原因，脚本不会替你混为一谈：

  * 该素材对应的界面**不在**你给的原帧里 → 低分是正常的，标记为「未覆盖?」
  * 界面在帧里但仍然低分 → 素材真的失配

要让结论确定，用 --expect 提供「素材 → 应出现在哪张帧」的映射（JSON，见 --help）。
没有映射时，脚本给出最佳得分与最佳帧，由人判断。

`--scales` 会额外做多尺度扫描。它的作用是**排除**尺度假设：若某个尺度让分数跳到阈值
以上，说明是缩放问题（改一个全局系数即可）；若所有尺度都上不去，就是素材本身变了，
必须重截。实测 details.png 在 0.70~1.30 全扫最高只有 0.770 且位置错，属于后者。
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

try:
    import cv2
    import numpy as np
except ImportError:  # pragma: no cover
    sys.exit(
        "需要 cv2 与 numpy。用项目自带的 venv：\n"
        "  .maa-cache/converter-env/bin/python3 scripts/audit_templates.py ..."
    )

FRAME_WIDTH = 1280
FRAME_HEIGHT = 720

# 与 TemplateMatcher.kt 的常量逐一对应，不要单独调整
CLAHE_CLIP_LIMIT = 1.5
CLAHE_TILE = (8, 8)
BLUR_KERNEL = (5, 5)

# 流水线 template_match 的默认阈值（task_node.py 的 get_recognition_params）
DEFAULT_THRESHOLD = 0.85
# 低于此值视为「结构上不相干」，重截是唯一出路；之间的区间属于擦边，可考虑调阈值
CLEARLY_BROKEN = 0.70

_clahe = cv2.createCLAHE(clipLimit=CLAHE_CLIP_LIMIT, tileGridSize=CLAHE_TILE)


def preprocess(image: "np.ndarray") -> "np.ndarray":
    """灰度 → CLAHE → 高斯模糊。顺序与参数照抄 Kotlin 侧，改动会让分数失去可比性。"""
    gray = image if image.ndim == 2 else cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    return cv2.GaussianBlur(_clahe.apply(gray), BLUR_KERNEL, 0)


def match(prepared_screen, prepared_template):
    """返回 (峰值, 模板中心坐标)。模板大于画面时返回 (None, None)——上游此时直接算未命中。"""
    th, tw = prepared_template.shape[:2]
    sh, sw = prepared_screen.shape[:2]
    if th > sh or tw > sw:
        return None, None
    result = cv2.matchTemplate(prepared_screen, prepared_template, cv2.TM_CCOEFF_NORMED)
    _, peak, _, loc = cv2.minMaxLoc(result)
    return float(peak), (int(loc[0] + tw / 2), int(loc[1] + th / 2))


def load_frames(directory: Path) -> list[tuple[str, "np.ndarray"]]:
    frames = []
    for path in sorted(directory.iterdir()):
        if path.suffix.lower() not in {".png", ".jpg", ".jpeg", ".webp"}:
            continue
        image = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if image is None:
            print(f"[warn] 无法解码，跳过: {path.name}", file=sys.stderr)
            continue
        if (image.shape[1], image.shape[0]) != (FRAME_WIDTH, FRAME_HEIGHT):
            # 传输渠道常改尺寸/比例；一律拉回识别器实际使用的帧尺寸
            image = cv2.resize(image, (FRAME_WIDTH, FRAME_HEIGHT), interpolation=cv2.INTER_AREA)
        frames.append((path.name, image))
    return frames


def collect_templates(root: Path, language: str) -> list[tuple[str, Path]]:
    """按上游目录约定收集素材：general 为共用，另加所选语言目录。

    同名素材语言目录优先——与 Kotlin 侧 ResourcePackTemplateIndex 的选取一致。
    """
    chosen: dict[str, Path] = {}
    for bucket in ("general", language):
        base = root / bucket
        if not base.is_dir():
            print(f"[warn] 缺少素材目录: {base}", file=sys.stderr)
            continue
        for path in sorted(base.rglob("*.png")):
            chosen[path.stem] = path
    return sorted(chosen.items())


def verdict(best: float | None, expected_hit: bool | None) -> str:
    if best is None:
        return "模板大于画面"
    if best >= DEFAULT_THRESHOLD:
        return "命中"
    if expected_hit is False:
        return "未覆盖(预期不在帧中)"
    if best < 0:
        return "负相关-必须重截"
    if best < CLEARLY_BROKEN:
        return "失配-需重截" if expected_hit else "未覆盖?"
    return "擦边-可调阈值或重截"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="离线审计上游素材在真机原帧上的匹配情况",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='--expect 形如 {"details": "mirror_team.png", "skip_battle": "level_select.png"}；'
        "值为该素材应当命中的原帧文件名。",
    )
    parser.add_argument("--frames", required=True, type=Path, help="真机原帧目录")
    parser.add_argument("--templates", required=True, type=Path, help="上游 img 目录")
    parser.add_argument("--language", default="en", help="游戏语言目录，默认 en")
    parser.add_argument("--expect", type=Path, help="素材 → 原帧文件名 的 JSON 映射")
    parser.add_argument(
        "--scales",
        default="",
        help="额外的多尺度扫描，逗号分隔，例如 0.8,0.9,1.1,1.25。用于排除尺度假设",
    )
    parser.add_argument("--csv", type=Path, help="把完整结果写入 CSV")
    parser.add_argument("--only-problems", action="store_true", help="只打印非命中项")
    args = parser.parse_args()

    frames = load_frames(args.frames)
    if not frames:
        print(f"[error] {args.frames} 里没有可用图片", file=sys.stderr)
        return 2
    templates = collect_templates(args.templates, args.language)
    if not templates:
        print(f"[error] {args.templates} 里没有素材", file=sys.stderr)
        return 2

    expect: dict[str, str] = {}
    if args.expect:
        expect = json.loads(args.expect.read_text(encoding="utf-8"))

    scales = [float(s) for s in args.scales.split(",") if s.strip()]
    prepared_frames = [(name, preprocess(image), image) for name, image in frames]
    # 多尺度只在需要时构建，避免默认路径多花几倍时间
    scaled_frames: dict[float, list[tuple[str, "np.ndarray"]]] = {}
    for scale in scales:
        scaled_frames[scale] = [
            (
                name,
                preprocess(cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)),
            )
            for name, _, image in prepared_frames
        ]

    print(f"原帧 {len(frames)} 张，素材 {len(templates)} 张，语言 {args.language}")
    print(f"阈值 {DEFAULT_THRESHOLD}（低于 {CLEARLY_BROKEN} 视为结构性失配）")
    if scales:
        print(f"多尺度扫描: {scales}")
    print()

    rows = []
    for name, path in templates:
        template = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if template is None:
            rows.append({"素材": name, "结论": "解码失败", "最佳得分": "", "最佳帧": "",
                         "位置": "", "尺寸": "", "最佳尺度": ""})
            continue
        prepared_template = preprocess(template)

        best = (None, "", None, 1.0)  # (peak, frame, position, scale)
        for frame_name, prepared, _ in prepared_frames:
            peak, position = match(prepared, prepared_template)
            if peak is not None and (best[0] is None or peak > best[0]):
                best = (peak, frame_name, position, 1.0)
        for scale, entries in scaled_frames.items():
            for frame_name, prepared in entries:
                peak, position = match(prepared, prepared_template)
                if peak is None:
                    continue
                if best[0] is None or peak > best[0]:
                    origin = (int(position[0] / scale), int(position[1] / scale))
                    best = (peak, frame_name, origin, scale)

        wanted = expect.get(name)
        expected_hit = None if wanted is None else (wanted in {f for f, _ in frames})
        rows.append({
            "素材": name,
            "结论": verdict(best[0], expected_hit),
            "最佳得分": f"{best[0]:.3f}" if best[0] is not None else "",
            "最佳帧": best[1],
            "位置": f"{best[2][0]},{best[2][1]}" if best[2] else "",
            "尺寸": f"{template.shape[1]}x{template.shape[0]}",
            "最佳尺度": f"{best[3]:.2f}",
        })

    order = {"负相关-必须重截": 0, "失配-需重截": 1, "未覆盖?": 2, "擦边-可调阈值或重截": 3, "模板大于画面": 4, "解码失败": 5,
             "未覆盖(预期不在帧中)": 6, "命中": 7}
    rows.sort(key=lambda r: (order.get(r["结论"], 9), r["最佳得分"]))

    width = max(len(r["素材"]) for r in rows)
    for row in rows:
        if args.only_problems and row["结论"] == "命中":
            continue
        print(f'{row["素材"]:<{width}}  {row["最佳得分"]:>6}  x{row["最佳尺度"]:<5} '
              f'{row["尺寸"]:>8}  {row["结论"]:<20} {row["最佳帧"]} @{row["位置"]}')

    print()
    summary: dict[str, int] = {}
    for row in rows:
        summary[row["结论"]] = summary.get(row["结论"], 0) + 1
    for key in sorted(summary, key=lambda k: order.get(k, 9)):
        print(f"  {summary[key]:4d}  {key}")

    if args.csv:
        with args.csv.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        print(f"\n完整结果: {args.csv}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
