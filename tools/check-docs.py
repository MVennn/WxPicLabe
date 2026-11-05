#!/usr/bin/env python3
"""WxPicLabe 文档门禁。

检查 docs/ 下的文档是否与 docs/README.md 索引保持一致：

1. docs/*.md 是否全部被 docs/README.md 链接（孤儿文档）；
2. 每篇是否在开头有 "> **一句话**" 块；
3. docs/README.md 里的相对链接是否全部可解析（断链）；
4. 文件名是否符合全大写 + 连字符。

用法（仓根执行）:
    python tools\\check-docs.py

退出码: 0 全部通过；1 有失败项（含 docs/ 目录或索引缺失）。
仅用 Python 3 标准库；只读，不写文件，不联网。
"""

from __future__ import annotations

import os
import re
import stat
import sys
import urllib.parse

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS_DIR = os.path.join(REPO_ROOT, "docs")
INDEX_NAME = "README.md"

# [文字](目标)
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
# 行首的 "> **一句话**"，允许 > 与 ** 之间留空格
ONELINER_RE = re.compile(r"^\s*>\s*\*\*一句话\*\*", re.MULTILINE)
# 全大写 + 连字符，例如 DOC-RULES.md / FEATURE-IMAGE-LABEL.md
NAME_RE = re.compile(r"^[A-Z0-9]+(?:-[A-Z0-9]+)*\.md$")
# 这些开头的链接不当作仓内相对路径
SKIP_PREFIXES = ("http://", "https://", "mailto:", "tel:", "file:", "#")
HEAD_LINES = 20
REPARSE_ATTR = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)


def is_link_like(entry: os.DirEntry) -> bool:
    """符号链接或 Windows 联接（reparse point）都跳过，避免穿透到仓外。"""
    try:
        if entry.is_symlink():
            return True
        st = entry.stat(follow_symlinks=False)
    except OSError:
        return True
    attrs = getattr(st, "st_file_attributes", 0)
    return bool(attrs & REPARSE_ATTR)


def scan_docs():
    """返回按文件名排序的 [(name, path)]；docs/ 打不开时返回 None。"""
    try:
        scanner = os.scandir(DOCS_DIR)
    except OSError as exc:
        print("[FAIL] 无法读取 docs/ 目录: %s" % exc)
        return None

    docs = []
    with scanner:
        for entry in scanner:
            if entry.is_dir(follow_symlinks=False):
                continue
            if is_link_like(entry):
                continue
            if not entry.name.lower().endswith(".md"):
                continue
            docs.append((entry.name, entry.path))
    docs.sort()
    return docs


def read_text(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def iter_relative_links(text: str):
    """产出 (原文, 归一化后的相对目标)；跳过外链与纯锚点。"""
    for raw in LINK_RE.findall(text):
        target = raw.strip()
        if target.startswith("<") and target.endswith(">"):
            target = target[1:-1].strip()
        if not target or target.startswith(SKIP_PREFIXES):
            continue
        target = target.split("#", 1)[0].split("?", 1)[0].strip()
        if not target:
            continue
        yield raw.strip(), urllib.parse.unquote(target)


def main() -> int:
    docs = scan_docs()
    if docs is None:
        return 1

    index_path = os.path.join(DOCS_DIR, INDEX_NAME)
    if not os.path.isfile(index_path):
        print("[FAIL] 索引缺失: docs/%s" % INDEX_NAME)
        return 1

    errors = []
    index_text = read_text(index_path)
    linked = {}

    for raw, target in iter_relative_links(index_text):
        if os.path.isabs(target) or target.startswith("/"):
            errors.append("索引里出现绝对路径: docs/%s -> %s" % (INDEX_NAME, raw))
            continue
        resolved = os.path.normpath(os.path.join(DOCS_DIR, target))
        if not os.path.isfile(resolved):
            errors.append("断链: docs/%s -> %s" % (INDEX_NAME, raw))
            continue
        linked[os.path.normcase(resolved)] = raw

    for name, path in docs:
        text = read_text(path)
        head = "\n".join(text.splitlines()[:HEAD_LINES])
        if not ONELINER_RE.search(head):
            errors.append("缺一句话块: docs/%s" % name)
        if not NAME_RE.match(name):
            errors.append("命名不合规(全大写+连字符): docs/%s" % name)
        if name != INDEX_NAME and os.path.normcase(path) not in linked:
            errors.append("索引漏项: docs/%s 未被 docs/%s 链接" % (name, INDEX_NAME))

    for name, _path in docs:
        print("[ OK ] docs/%s" % name)

    if errors:
        print("")
        for item in errors:
            print("[FAIL] %s" % item)
        print("")
        print("门禁失败: 扫描 %d 篇文档, %d 项问题" % (len(docs), len(errors)))
        return 1

    print("")
    print("门禁通过: 扫描 %d 篇文档, 索引完整, 无断链, 无孤儿" % len(docs))
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except (AttributeError, ValueError):
        pass
    sys.exit(main())
