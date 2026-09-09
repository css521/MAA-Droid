#!/usr/bin/env python3
"""同步 LALC 的 UI 图鉴，模型和执行资源由 Android 内的资源安装器更新。"""
import argparse
import json
import shutil
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path, help="本地 LixAssistantLimbusCompany 仓库")
    args = parser.parse_args()
    source = args.upstream.resolve()
    backend = source / "lalc_backend"
    target = Path(__file__).resolve().parents[1] / "engine/limbus/src/main/assets/lalc/ui"
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    titles = json.loads((backend / "config/language/zh/ego_gifts.json").read_text())
    weights = json.loads((backend / "config/theme_pack_cfg.json").read_text())
    catalog = {"upstream": "HSLix/LixAssistantLimbusCompany", "commit": revision, "gifts": [], "packs": []}
    for folder, key in [("ego_gifts", "gifts"), ("theme_packs", "packs")]:
        for image in sorted((backend / "img/general" / folder).rglob("*.png")):
            name = image.stem
            catalog[key].append({"name": name, "title": titles.get(name, name) if key == "gifts" else name,
                                 "style": image.parent.name if key == "gifts" else "", "path": image.relative_to(backend).as_posix(),
                                 "weight": weights.get(name, {}).get("weight", 10)})
    for folder in ["sinners", "mirror/stars", "ego_gifts", "theme_packs"]:
        for image in sorted((backend / "img/general" / folder).rglob("*.png")):
            destination = target / image.relative_to(backend)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(image, destination)
    target.mkdir(parents=True, exist_ok=True)
    (target / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    print(f"LALC {revision}: {len(catalog['gifts'])} gifts, {len(catalog['packs'])} theme packs")


if __name__ == "__main__":
    main()
