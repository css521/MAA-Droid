#!/usr/bin/env python3
"""检查已下载的 LALC UI 图鉴；可导出诊断 JSON，不向 APK 复制图片或 catalog。"""
import argparse
import json
from pathlib import Path


def build_catalog(root):
    """Match LimbusCatalog.load: English resource IDs, optional Chinese gift titles."""
    language = root / "config/language/zh/ego_gifts.json"
    titles = json.loads(language.read_text(encoding="utf-8")) if language.is_file() else {}
    if not isinstance(titles, dict):
        raise ValueError("饰品语言表必须是 JSON object")
    catalog = {"gifts": [], "packs": []}
    gift_root = root / "img/general/ego_gifts"
    seen = set()
    for image in sorted(gift_root.rglob("*.png")):
        if not image.is_file() or image.stem in seen:
            continue
        seen.add(image.stem)
        title = titles.get(image.stem)
        catalog["gifts"].append({
            "name": image.stem,
            "title": title if isinstance(title, str) and title.strip() else image.stem,
            "style": "" if image.parent == gift_root else image.parent.relative_to(gift_root).as_posix(),
            "path": image.relative_to(root).as_posix(),
        })
    for image in sorted((root / "img/general/theme_packs").glob("*.png")):
        if image.is_file():
            catalog["packs"].append({"name": image.stem, "title": image.stem, "style": "",
                                     "path": image.relative_to(root).as_posix()})
    return catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path, help="本地 LALC 仓库、lalc_backend 或已安装资源根目录")
    parser.add_argument("--out", type=Path, help="可选的诊断 JSON 路径（资源目录外）；运行时不读取此文件")
    args = parser.parse_args()
    source = args.upstream.resolve()
    root = source / "lalc_backend" if (source / "lalc_backend").is_dir() else source
    if not (root / "img/general").is_dir():
        parser.error("缺少 img/general，请先下载或解包 LALC 资源")
    catalog = build_catalog(root)
    if args.out:
        target = args.out.resolve()
        if target == root or root in target.parents:
            parser.error("诊断文件不能写入资源目录，以免破坏 manifest 文件清单")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"LALC: {len(catalog['gifts'])} gifts, {len(catalog['packs'])} theme packs; "
          "卡包使用用户配置权重，未配置时为 10。APK assets 未写入。")


if __name__ == "__main__":
    main()
