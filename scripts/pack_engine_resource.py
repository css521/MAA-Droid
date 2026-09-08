#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把上游项目的资源重打包成 MAA-Droid 的引擎资源包(manifest + zip)。

为什么需要这个脚本:
    引擎的业务流程住在数据里(流水线 JSON + 模板图 + ONNX 模型),上游一改,
    我们希望 App 靠热更跟上而不必发 APK。但上游没有资源专用的发布 feed:
    LALC 只发一个 lalc.zip(249 MB,含 Python 运行时,无 manifest 无逐文件校验),
    没法做增量。所以由本脚本从上游 tag 里只取需要的四份目录(27 MB),
    生成带 sha256 的清单后发到 MAA-Droid 自己的 Release,App 只订阅这一条 feed。

清单协议形状参考 AhabAssistantLimbusCompany 的 module/resource_sync/manifest.py:
    manifest 级 schema_version / generated_at / revision(稳定标识) / files[] / packages[]
    条目级   path / sha256 / size

装载门闸:
    manifest.required_actions 列出该资源包的流水线实际用到、且上游有实现体的 action。
    App 侧装载前算 required_actions - ActionRegistry.keys,非空则拒绝该包并提示升级 App,
    而不是运行到一半才炸。本脚本同时与仓库内的 actions.txt 白名单对账并在缺失时告警。

用法:
    python scripts/pack_engine_resource.py --engine limbus \\
        --upstream ~/project/java/LixAssistantLimbusCompany --out /tmp/pack
    python scripts/pack_engine_resource.py --engine limbus --upstream ... --out ... --check-only
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from datetime import datetime, timezone

SCHEMA_VERSION = 1

# 每个引擎声明:上游仓库、资源根、要收哪些子目录(相对资源根,同时也是包内路径)
ENGINES = {
    "limbus": {
        "repo": "HSLix/LixAssistantLimbusCompany",
        "resource_root": "lalc_backend",
        # recognize/models 是 OCR(PP-OCRv5 det+rec,约 20 MB)所在目录。
        # 漏收它会让 OCR 永远拿不到模型 —— 上游把它放在 recognize/ 而不是 ai/model/,
        # 只按 ai/model 收会静默少两个文件,且因为 OCR 失败是"返回空表"而非报错,
        # 症状会表现为"镜牢商店与选饰品莫名走兜底分支"，极难溯源。
        "include": [
            "config/task",
            "config/language",
            "img",
            "ai/model",
            "recognize/models",
        ],
        # 流水线 JSON 所在目录(相对资源根),用于引用校验
        "pipeline_dir": "config/task",
        "template_dir": "img",
        "min_engine_version": 1,
    },
}

ACTION_REGISTER_RE = re.compile(r'@TaskExecution\.register\("([^"]+)"\)')


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def collect_files(resource_root: str, include: list[str]) -> list[str]:
    """返回相对 resource_root 的文件路径列表,按路径排序保证可复现。"""
    out: list[str] = []
    for sub in include:
        base = os.path.join(resource_root, sub)
        if not os.path.isdir(base):
            raise SystemExit(f"上游缺少目录: {base}")
        for root, _dirs, files in os.walk(base):
            for fn in files:
                if fn.startswith("."):
                    continue
                abs_p = os.path.join(root, fn)
                out.append(os.path.relpath(abs_p, resource_root))
    return sorted(out)


def load_pipeline(resource_root: str, pipeline_dir: str) -> dict[str, dict]:
    """加载并合并流水线 JSON;重名节点直接失败(上游不应出现)。"""
    nodes: dict[str, dict] = {}
    base = os.path.join(resource_root, pipeline_dir)
    for fn in sorted(os.listdir(base)):
        if not fn.endswith(".json"):
            continue
        with open(os.path.join(base, fn), encoding="utf-8") as f:
            data = json.load(f)
        for name, node in data.items():
            if name in nodes:
                raise SystemExit(f"流水线节点重名: {name} (见 {fn})")
            nodes[name] = node
    if not nodes:
        raise SystemExit(f"{base} 下没有流水线节点")
    return nodes


def template_basenames(resource_root: str, template_dir: str) -> set[str]:
    """模板按基名注册(LALC 的 recognize/img_registry.py 语义),语言目录同名是变体。"""
    names: set[str] = set()
    for root, _dirs, files in os.walk(os.path.join(resource_root, template_dir)):
        for fn in files:
            if fn.lower().endswith(".png"):
                names.add(os.path.splitext(fn)[0])
    return names


def referenced_templates(nodes: dict[str, dict]) -> set[str]:
    refs: set[str] = set()
    for node in nodes.values():
        t = (node.get("params") or {}).get("template")
        if isinstance(t, str):
            refs.add(t)
        elif isinstance(t, list):
            refs.update(x for x in t if isinstance(x, str))
    return refs


def validate_pipeline(nodes: dict[str, dict]) -> list[str]:
    """照搬 LALC workflow/task_registry.py validate_task_references 的语义。

    action / next / interrupt / params.origin / params.disable_node 都必须指向已注册节点。
    上游一旦引入断引用,这里就拦住,不会打出一个跑到中途才崩的资源包。
    """
    errors: list[str] = []
    for name, node in nodes.items():
        action = node.get("action", "empty")
        if action not in nodes:
            errors.append(f"节点 {name} 的 action 指向未注册节点: {action}")
        for field in ("next", "interrupt"):
            val = node.get(field)
            if val is None:
                continue
            if not isinstance(val, list):
                errors.append(f"节点 {name} 的 {field} 必须是列表")
                continue
            for ref in val:
                if ref not in nodes:
                    errors.append(f"节点 {name} 的 {field} 指向未注册节点: {ref}")
        params = node.get("params") or {}
        for key in ("origin", "disable_node"):
            ref = params.get(key)
            if ref is not None and ref not in nodes:
                errors.append(f"节点 {name} 的 params.{key} 指向未注册节点: {ref}")
    return errors


def implemented_actions(resource_root_parent: str) -> set[str]:
    """扫上游 Python 里的 @TaskExecution.register,得到"有实现体"的 action 名。

    流水线里的 action 名分两类:有实现体的(需要我们在 Kotlin 里实现),
    以及纯路由的(action 指向的节点本身没有 handler,execute 直接走 func())。
    只有前者需要进 required_actions 门闸。
    """
    found: set[str] = set()
    for root, _dirs, files in os.walk(resource_root_parent):
        if "obsolete" in root or "/." in root:
            continue
        for fn in files:
            if not fn.endswith(".py"):
                continue
            try:
                with open(os.path.join(root, fn), encoding="utf-8", errors="ignore") as f:
                    found.update(ACTION_REGISTER_RE.findall(f.read()))
            except OSError:
                continue
    return found


def upstream_revision(upstream: str) -> tuple[str, str]:
    """返回 (tag, commit);不是 git 仓库时给空串,由调用方(CI)自行填。"""
    def git(*args: str) -> str:
        try:
            return subprocess.run(
                ["git", "-C", upstream, *args],
                capture_output=True, text=True, check=True,
            ).stdout.strip()
        except (subprocess.CalledProcessError, FileNotFoundError):
            return ""
    return git("describe", "--tags", "--abbrev=0"), git("rev-parse", "HEAD")


def action_allowlist_path(repo_root: str, engine: str) -> str:
    """
    白名单文件路径。模块已按层分组(engine/<名字>/),旧的平铺路径(engine-<名字>/)
    一并兼容,便于在老分支上跑同一份脚本。
    """
    candidates = [
        os.path.join(repo_root, "engine", engine, "actions.txt"),
        os.path.join(repo_root, f"engine-{engine}", "actions.txt"),
    ]
    for path in candidates:
        if os.path.isfile(path):
            return path
    return candidates[0]


def read_action_allowlist(repo_root: str, engine: str) -> set[str] | None:
    """仓库内已实现的 action 白名单;文件不存在返回 None。"""
    path = action_allowlist_path(repo_root, engine)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return {
            line.strip() for line in f
            if line.strip() and not line.lstrip().startswith("#")
        }


def main() -> int:
    ap = argparse.ArgumentParser(description="重打包上游资源为引擎资源包")
    ap.add_argument("--engine", required=True, choices=sorted(ENGINES))
    ap.add_argument(
        "--allow-missing-actions",
        action="store_true",
        help="允许动作白名单缺失(仅引擎骨架阶段);默认缺失即失败",
    )
    ap.add_argument("--upstream", required=True, help="上游仓库本地路径(CI 里是 checkout 出的 tag)")
    ap.add_argument("--out", required=True, help="产物输出目录")
    ap.add_argument("--tag", default="", help="覆盖上游 tag(非 git 目录时用)")
    ap.add_argument("--check-only", action="store_true", help="只做校验,不产出 zip")
    args = ap.parse_args()

    cfg = ENGINES[args.engine]
    upstream = os.path.abspath(os.path.expanduser(args.upstream))
    resource_root = os.path.join(upstream, cfg["resource_root"])
    if not os.path.isdir(resource_root):
        return fail(f"资源根不存在: {resource_root}")

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    # ---- 校验 ----
    nodes = load_pipeline(resource_root, cfg["pipeline_dir"])
    errors = validate_pipeline(nodes)

    have = template_basenames(resource_root, cfg["template_dir"])
    refs = referenced_templates(nodes)
    missing_tpl = sorted(refs - have)
    if missing_tpl:
        errors.append(f"流水线引用了 {len(missing_tpl)} 个不存在的模板: {missing_tpl[:10]}")

    # 语言目录结构必须保留:模板按基名注册,zh/en 同名是语言变体,去重会毁掉切语言
    tpl_base = os.path.join(resource_root, cfg["template_dir"])
    lang_dirs = [d for d in os.listdir(tpl_base) if os.path.isdir(os.path.join(tpl_base, d))]
    if len(lang_dirs) < 2:
        errors.append(f"{cfg['template_dir']} 下语言目录疑似缺失: {lang_dirs}")

    if errors:
        for e in errors:
            print(f"  [错误] {e}", file=sys.stderr)
        return fail(f"上游资源校验未通过({len(errors)} 项),不产出资源包")

    pipeline_actions = {n.get("action", "empty") for n in nodes.values()}
    impl = implemented_actions(resource_root)
    required = sorted(pipeline_actions & impl)
    routing_only = sorted(pipeline_actions - impl)

    tag, commit = upstream_revision(upstream)
    tag = args.tag or tag

    print(f"引擎        {args.engine}")
    print(f"上游        {cfg['repo']}  tag={tag or '未知'}  commit={commit[:12] or '未知'}")
    print(f"流水线节点  {len(nodes)}")
    print(f"action      共 {len(pipeline_actions)};需实现 {len(required)};纯路由 {len(routing_only)}")
    print(f"模板        引用 {len(refs)} / 基名 {len(have)};语言目录 {sorted(lang_dirs)}")

    allow = read_action_allowlist(repo_root, args.engine)
    if allow is None and args.allow_missing_actions:
        print(f"[提示] 按 --allow-missing-actions 跳过白名单对账")
    elif allow is None:
        # 不能静默跳过：白名单是兼容门闸 required_actions 的对账依据,漏掉它就会发布一个
        # 「App 装载时才发现动作缺失」的包。模块路径调整过后尤其容易踩到这条,故直接失败。
        expected = action_allowlist_path(repo_root, args.engine)
        raise SystemExit(
            f"[错误] 找不到动作白名单 {expected}\n"
            f"       它是资源包 required_actions 的对账依据,缺失时无法保证门闸有效。\n"
            f"       若确为引擎骨架阶段(尚无任何实现),显式传 --allow-missing-actions。"
        )
    else:
        unimplemented = sorted(set(required) - allow)
        stale = sorted(allow - set(required))
        if unimplemented:
            print(f"[告警] 上游需要但仓库未实现的 action({len(unimplemented)}): {unimplemented}")
            print("       App 装载该包时会被门闸拒绝并提示升级")
        if stale:
            print(f"[提示] 仓库实现了但上游流水线不再引用: {stale}")

    if args.check_only:
        print("\n--check-only,不产出资源包")
        return 0

    # ---- 产出 ----
    os.makedirs(args.out, exist_ok=True)
    rel_files = collect_files(resource_root, cfg["include"])
    entries = []
    for rel in rel_files:
        abs_p = os.path.join(resource_root, rel)
        entries.append({
            "path": rel.replace(os.sep, "/"),
            "sha256": sha256_of(abs_p),
            "size": os.path.getsize(abs_p),
        })

    # revision:对 (path, sha256) 求稳定摘要,与打包时间无关,同步层可据此判断有无变化
    rev = hashlib.sha256(
        "\n".join(f"{e['path']}:{e['sha256']}" for e in entries).encode()
    ).hexdigest()

    zip_name = f"{args.engine}-resource-{tag or rev[:12]}.zip"
    zip_path = os.path.join(args.out, zip_name)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for e in entries:
            zf.write(os.path.join(resource_root, e["path"]), e["path"])

    manifest = {
        "schema_version": SCHEMA_VERSION,
        "engine": args.engine,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "revision": rev,
        "upstream": {"repo": cfg["repo"], "tag": tag, "commit": commit},
        "min_engine_version": cfg["min_engine_version"],
        "required_actions": required,
        "routing_only_actions": routing_only,
        "files": entries,
        "packages": [{
            "path": zip_name,
            "sha256": sha256_of(zip_path),
            "size": os.path.getsize(zip_path),
            "format": "zip",
        }],
    }
    manifest_path = os.path.join(args.out, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")

    total = sum(e["size"] for e in entries)
    print(f"\n产出        {zip_path}")
    print(f"            {os.path.getsize(zip_path) / 1048576:.1f} MB (原始 {total / 1048576:.1f} MB / {len(entries)} 文件)")
    print(f"            {manifest_path}")
    print(f"revision    {rev[:16]}…")
    return 0


def fail(msg: str) -> int:
    print(f"[失败] {msg}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
