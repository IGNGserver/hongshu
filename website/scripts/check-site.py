#!/usr/bin/env python3
"""Static checks for the Hongshu first-page site; no third-party dependencies."""
from __future__ import annotations

import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent.parent / "site"
ALLOWED_HOSTS = {"github.com", "hongshu.igng.net"}
MAX_FILE = 200 * 1024
MAX_TOTAL = 500 * 1024

errors: list[str] = []


class Page(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.refs: list[tuple[str, str]] = []
        self.ids: set[str] = set()
        self.meta: dict[str, str] = {}
        self.lang = ""
        self.title = False
        self.h1 = 0
        self.inline_script = False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "html":
            self.lang = a.get("lang", "")
        if tag == "title":
            self.title = True
        if tag == "h1":
            self.h1 += 1
        if "id" in a:
            self.ids.add(a["id"])
        if tag == "meta" and "name" in a:
            self.meta[a["name"]] = a.get("content", "")
        if tag == "script" and not a.get("src"):
            self.inline_script = True
        for key in ("href", "src"):
            if a.get(key):
                self.refs.append((key, a[key]))


def local_target(base: Path, ref: str) -> Path:
    path = urlparse(ref).path
    if path.startswith("/"):
        return ROOT / path.lstrip("/")
    return (base.parent / path).resolve()


def check_html(path: Path) -> None:
    rel = path.relative_to(ROOT)
    page = Page()
    page.feed(path.read_text(encoding="utf-8"))
    if not page.lang:
        errors.append(f"{rel}: <html> missing lang")
    if not page.title:
        errors.append(f"{rel}: missing <title>")
    if "viewport" not in page.meta:
        errors.append(f"{rel}: missing viewport meta")
    if not page.meta.get("description"):
        errors.append(f"{rel}: missing description meta")
    if page.h1 != 1:
        errors.append(f"{rel}: expected exactly one <h1>, found {page.h1}")
    if page.inline_script:
        errors.append(f"{rel}: inline script is not allowed (CSP script-src 'none')")
    for _, ref in page.refs:
        if ref.startswith("#"):
            if len(ref) > 1 and ref[1:] not in page.ids:
                errors.append(f"{rel}: missing anchor target {ref}")
            continue
        parsed = urlparse(ref)
        if parsed.scheme in ("http", "https"):
            if parsed.hostname not in ALLOWED_HOSTS:
                errors.append(f"{rel}: external host not allowed: {ref}")
            continue
        if parsed.scheme in ("mailto", "data"):
            continue
        target = local_target(path, ref)
        if target.is_dir():
            target = target / "index.html"
        if not target.is_file():
            errors.append(f"{rel}: missing local target {ref}")


def main() -> int:
    if not ROOT.is_dir():
        print(f"site directory missing: {ROOT}", file=sys.stderr)
        return 1
    files = [p for p in ROOT.rglob("*") if p.is_file()]
    total = 0
    for path in files:
        size = path.stat().st_size
        total += size
        if size > MAX_FILE:
            errors.append(f"{path.relative_to(ROOT)}: {size // 1024} KB exceeds {MAX_FILE // 1024} KB")
        if path.suffix == ".html":
            check_html(path)
    if total > MAX_TOTAL:
        errors.append(f"site total {total // 1024} KB exceeds {MAX_TOTAL // 1024} KB")
    for required in ("index.html", "404.html", "_headers", "robots.txt", "sitemap.xml"):
        if not (ROOT / required).is_file():
            errors.append(f"missing {required}")
    if not re.search(r"Sitemap:\s*https://hongshu\.igng\.net/sitemap\.xml", (ROOT / "robots.txt").read_text()):
        errors.append("robots.txt must point at https://hongshu.igng.net/sitemap.xml")
    if errors:
        print("\n".join(f"✗ {e}" for e in errors), file=sys.stderr)
        return 1
    print(f"site check passed: {len(files)} files, {total // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
