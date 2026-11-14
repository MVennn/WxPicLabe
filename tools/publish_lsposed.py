#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""publish_lsposed.py —— 把当前版本同步到 LSPosed 模块仓库。

## 为什么单独一个脚本

本模块有**两个仓库**，它们互不同步：

| 仓库 | 谁在用 |
|---|---|
| MVennn/WxPicLabe（源码） | 源码、Issue、下载入口 |
| Xposed-Modules-Repo/io.github.MVennn.WxPicLabel（模块仓库） | **LSPosed 网站与 App 只读这里** |

在源码仓库发 Release，模块仓库收不到任何东西 —— modules.lsposed.org 与 LSPosed App
的「更新」提示只读模块仓库。所以每次发版要**两边各发一份同 tag、同产物、同说明**的
Release，并把模块仓库那几个元文件与源码仓库对齐。手工做很容易只发一半，本脚本一次做完。

## 用法

    python tools/publish_lsposed.py --check        # 只查凭据对模块仓库有没有写权限
    python tools/publish_lsposed.py --dry-run      # 只打印要做什么
    python tools/publish_lsposed.py --sync-only    # 只同步元文件，不发 Release
    python tools/publish_lsposed.py                # 建/更新 Release + 同步元文件
    python tools/publish_lsposed.py --notes .work/release-body-1.0.0.md

默认 Release 说明取 .work/release-body-<versionName>.md；没有就报错，不瞎编正文。

## 元文件母本（都在本仓，见 xposed-repo/README.md）

| 模块仓库 | 母本 |
|---|---|
| README.md | 仓库根 README.md（两边逐字一致） |
| SUMMARY | xposed-repo/SUMMARY |
| SOURCE_URL | 本脚本按 SOURCE_URL 常量生成 |
| icon.svg | assets/icon.svg |

## 凭据

GS_GITHUB_TOKEN -> GH_TOKEN -> gh auth token（本机已登录 MVennn）。
**故意不读 GITHUB_TOKEN**：那是 Actions 自带的短命 token，写模块仓库会 403。

## 模块仓库描述（显示名）

只能在浏览器 About 齿轮里改 —— PATCH /repos/{owner}/{repo} 要 Admin，我们只有
maintain，接口返回 404。描述为空 ⇒ 模块不会出现在 modules.lsposed.org。
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
WORK = os.path.join(ROOT, ".work")

SOURCE_REPO = os.environ.get("GITHUB_SOURCE_REPO", "MVennn/WxPicLabe")
SOURCE_URL = os.environ.get("WX_SOURCE_URL", "https://github.com/MVennn/WxPicLabe")
MODULE_REPO = os.environ.get(
    "GITHUB_MODULE_REPO", "Xposed-Modules-Repo/io.github.MVennn.WxPicLabel")
API = "https://api.github.com"

try:  # Windows 控制台默认 cp936，中文会乱码
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass


def token():
    for name in ("GS_GITHUB_TOKEN", "GH_TOKEN"):
        value = os.environ.get(name, "").strip()
        if value:
            return value
    try:
        out = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    except OSError:
        pass
    sys.exit("[X] 没有可用凭据：设 GS_GITHUB_TOKEN 或 GH_TOKEN，或先 gh auth login")


def api(tok, method, path, payload=None, raw=None, ctype=None):
    url = path if path.startswith("http") else API + path
    if raw is not None:
        data, headers = raw, {"Content-Type": ctype, "Content-Length": str(len(raw))}
    elif payload is not None:
        data, headers = json.dumps(payload).encode("utf-8"), {"Content-Type": "application/json"}
    else:
        data, headers = None, {}
    headers.update({"Authorization": "Bearer " + tok,
                    "Accept": "application/vnd.github+json",
                    "X-GitHub-Api-Version": "2022-11-28"})
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            body = r.read()
            return r.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body[:500]}


def brief(data):
    if isinstance(data, dict):
        return str(data.get("message") or data.get("raw") or data)[:200]
    return str(data)[:200]


def read_version():
    text = open(GRADLE, encoding="utf-8").read()
    vc = re.search(r"versionCode\s*=\s*(\d+)", text)
    vn = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not vc or not vn:
        sys.exit("[X] 读不到 app/build.gradle.kts 里的 versionCode / versionName")
    return int(vc.group(1)), vn.group(1)


def repo_files():
    summary = os.path.join(ROOT, "xposed-repo", "SUMMARY")
    if not os.path.exists(summary):
        sys.exit("[X] 缺 %s —— 它是模块页副标题的母本，别绕过它" % summary)
    return {
        "README.md": open(os.path.join(ROOT, "README.md"), encoding="utf-8").read(),
        "SUMMARY": open(summary, encoding="utf-8").read(),
        "SOURCE_URL": "%s\n" % SOURCE_URL,
        "icon.svg": open(os.path.join(ROOT, "assets", "icon.svg"), encoding="utf-8").read(),
    }


def check(tok):
    ok = True
    status, me = api(tok, "GET", "/user")
    if status != 200:
        print("  [X] token 无效（GET /user -> HTTP %s：%s）" % (status, brief(me)))
        return False
    print("  [OK] token 有效，身份：%s" % (me or {}).get("login", "?"))
    for repo in (SOURCE_REPO, MODULE_REPO):
        status, data = api(tok, "GET", "/repos/" + repo)
        if status != 200:
            print("  [X] %s 读不到（HTTP %s：%s）" % (repo, status, brief(data)))
            ok = False
            continue
        perms = (data or {}).get("permissions") or {}
        if not perms.get("push"):
            print("  [X] %s 没有写权限（push=%s）" % (repo, perms.get("push")))
            ok = False
            continue
        desc = (data or {}).get("description")
        print("  [OK] %s 可写（默认分支 %s）" % (repo, (data or {}).get("default_branch", "?")))
        if repo == MODULE_REPO and not desc:
            print("  [!] 模块仓库描述为空 —— 模块不会出现在 modules.lsposed.org，"
                  "去浏览器 About 齿轮填")
    return ok


def upsert_release(tok, tag, vn, notes, apk_name, apk_path, dry=False):
    status, rel = api(tok, "GET", "/repos/%s/releases/tags/%s" % (MODULE_REPO, tag))
    exists = status == 200
    if dry:
        print("      [dry-run] %s：%s Release %s（标题 v%s）"
              % (MODULE_REPO, "更新" if exists else "创建", tag, vn))
        return
    if exists:
        api(tok, "PATCH", "/repos/%s/releases/%d" % (MODULE_REPO, rel["id"]),
            {"name": "v" + vn, "body": notes})
    else:
        status, rel = api(tok, "POST", "/repos/%s/releases" % MODULE_REPO,
                          {"tag_name": tag, "name": "v" + vn, "body": notes,
                           "draft": False, "prerelease": False})
        if status not in (200, 201):
            sys.exit("[X] 建 Release 失败 HTTP %s：%s" % (status, brief(rel)))
    for asset in rel.get("assets", []):
        if asset["name"] == apk_name:
            api(tok, "DELETE", "/repos/%s/releases/assets/%d" % (MODULE_REPO, asset["id"]))
    data = open(apk_path, "rb").read()
    upload = rel["upload_url"].split("{")[0] + "?name=" + apk_name
    status, asset = api(tok, "POST", upload, raw=data,
                        ctype="application/vnd.android.package-archive")
    if status not in (200, 201):
        sys.exit("[X] 上传 %s 失败 HTTP %s：%s" % (apk_name, status, brief(asset)))
    print("      %s 已上传，%d 字节 -> %s"
          % (apk_name, asset.get("size", -1), rel.get("html_url")))


def sync_files(tok, dry=False):
    files = repo_files()
    clone = os.path.join(WORK, "lsposed-repo")
    if dry:
        for name in sorted(files):
            print("      [dry-run] 同步 %-12s -> %s" % (name, MODULE_REPO))
        return
    url = "https://github.com/%s.git" % MODULE_REPO
    if not os.path.isdir(os.path.join(clone, ".git")):
        shutil.rmtree(clone, ignore_errors=True)
        os.makedirs(WORK, exist_ok=True)
        subprocess.run(["git", "clone", "-q", url, clone], check=True)
    subprocess.run(["git", "pull", "-q", "--ff-only"], cwd=clone, check=False)
    subprocess.run(["git", "config", "user.name", "M.Venn"], cwd=clone, check=True)
    subprocess.run(["git", "config", "user.email",
                    "127460743+MVennn@users.noreply.github.com"], cwd=clone, check=True)
    changed = []
    for name, content in sorted(files.items()):
        dst = os.path.join(clone, name)
        if os.path.exists(dst) and open(dst, encoding="utf-8").read() == content:
            continue
        open(dst, "w", encoding="utf-8", newline="\n").write(content)
        changed.append(name)
    if not changed:
        print("      模块仓库元文件已是最新，无需同步")
        return
    subprocess.run(["git", "add", "--"] + changed, cwd=clone, check=True)
    subprocess.run(["git", "commit", "-q", "-m",
                    "Sync %s from source repo" % ", ".join(changed)], cwd=clone, check=True)
    subprocess.run(["git", "push", "-q", "origin", "HEAD:main"], cwd=clone, check=True)
    print("      已同步：%s" % ", ".join(changed))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只查凭据权限")
    ap.add_argument("--sync-only", action="store_true", help="只同步元文件")
    ap.add_argument("--dry-run", action="store_true", help="只打印，不写任何东西")
    ap.add_argument("--notes", help="Release 说明文件")
    args = ap.parse_args()

    vc, vn = read_version()
    tag = "%d-%s" % (vc, vn)
    tok = token()
    print("源码仓库：%s" % SOURCE_REPO)
    print("模块仓库：%s" % MODULE_REPO)
    print("版本：%s（tag %s）" % (vn, tag))

    if args.check:
        sys.exit(0 if check(tok) else 1)

    if not args.sync_only:
        notes_path = args.notes or os.path.join(WORK, "release-body-%s.md" % vn)
        if not os.path.exists(notes_path):
            sys.exit("[X] 找不到 Release 说明 %s —— 用 --notes 指定" % notes_path)
        notes = open(notes_path, encoding="utf-8").read()
        apk_name = "WxPicLabel_%s.apk" % tag
        apk_path = os.path.join(ROOT, "dist", apk_name)
        if not os.path.exists(apk_path):
            sys.exit("[X] 找不到 %s —— 先把发版产物放进 dist/" % apk_path)
        upsert_release(tok, tag, vn, notes, apk_name, apk_path, dry=args.dry_run)
    sync_files(tok, dry=args.dry_run)


if __name__ == "__main__":
    main()
