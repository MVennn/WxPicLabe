#!/usr/bin/env python3
r"""凭据扫描门禁：查私钥头、常见令牌前缀、赋值型凭据、带口令的连接串。

只扫本仓文本文件，跳过 .git/.work/.gradle-home/.android-home/.tmp/build/dist、
二进制与签名材料。命中打印 文件:行号:[规则] 脱敏片段，退出码 1。

用法（仓根）: python tools\check-secrets.py
"""

import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {".git", ".work", ".gradle", ".gradle-home", ".android-home", ".tmp",
             "build", "dist", "out", "apkout", "node_modules", "__pycache__"}
SKIP_EXT = {".apk", ".aab", ".jar", ".dex", ".class", ".keystore", ".jks", ".png",
            ".jpg", ".webp", ".ico", ".zip", ".so", ".dll", ".bin", ".lock"}

RULES = [
    ("私钥头", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----")),
    ("AWS Key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub Token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}")),
    ("Slack Token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}")),
    ("Google API Key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("带口令连接串", re.compile(r"[a-zA-Z][a-zA-Z0-9+.\-]*://[^/\s:@]+:[^/\s@]+@")),
    ("赋值型凭据", re.compile(r"(?i)\b(?:api[_-]?key|secret|token|passwd|password|pwd)\b\s*[:=]\s*[\"']?[^\s\"']{6,}")),
]
PLACEHOLDER = ("<REDACTED", "<REPLACE", "xxx", "***", "your_", "example", "getenv", "getProperty",
               "System.getenv", "TODO", "placeholder")


def skip_line(line):
    low = line.lower()
    return any(h.lower() in low for h in PLACEHOLDER)


def mask(text, span):
    s, e = span
    seg = text[s:e]
    return text[:s] + seg[:2] + "***" + text[e:]
    return text


def main():
    hits = 0
    scanned = 0
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not os.path.islink(os.path.join(base, d))]
        for name in files:
            ext = os.path.splitext(name)[1].lower()
            if ext in SKIP_EXT:
                continue
            path = os.path.join(base, name)
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    lines = fh.readlines()
            except OSError:
                continue
            scanned += 1
            for i, line in enumerate(lines, 1):
                if skip_line(line):
                    continue
                for label, rx in RULES:
                    m = rx.search(line)
                    if m:
                        print("%s:%d: [%s] %s" % (os.path.relpath(path, ROOT).replace(os.sep, "/"), i, label,
                                                  mask(line.strip(), (m.start(), m.end()))[:120]))
                        hits += 1
                        break
    print("---")
    print("扫描完成: 扫描 %d 个文件, 命中 %d 处" % (scanned, hits))
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
