#!/usr/bin/env python3
"""Inventory localization and RTL work across LifeOS web surfaces."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_WEB = ROOT / "web"

TEXT_RE = re.compile(r">([^<>\\n]{2,160})<")
ATTR_RE = re.compile(r"\\b(placeholder|title|aria-label)=[\\\"\\\'](.*?)[\\\"\\\']", re.I)
HTML_LANG_RE = re.compile(r"<html\\b[^>]*\\blang=[\\\"\\\']([^\\\"\\\']+)", re.I)
PHYSICAL_CSS_RE = re.compile(r"\\b(left|right|margin-left|margin-right|padding-left|padding-right|border-left|border-right|text-align)\\s*:\\s*([^;}{]+)", re.I)
FIXED_LOCALE_RE = re.compile(r"[\\\"\\\'](en-US|en-GB|ar-SA|ar-EG|ar-PS)[\\\"\\\']", re.I)


def line_number(text: str, offset: int) -> int:
    return text.count("\\n", 0, offset) + 1


def likely_visible(value: str) -> bool:
    value = re.sub(r"\\s+", " ", value).strip()
    if not value or not any(ch.isalpha() for ch in value):
        return False
    if value.startswith(("http://", "https://", "//", "/*", "*")):
        return False
    return not any(token in value for token in ("${", "=>", "function(", "const ", "let ", "var "))


def audit_file(path: Path, web_root: Path) -> dict:
    text = path.read_text(encoding="utf-8", errors="replace")
    report = {
        "path": str(path.relative_to(web_root.parent)),
        "html_lang": [],
        "hardcoded_text": [],
        "hardcoded_attributes": [],
        "physical_css": [],
        "fixed_locales": [],
    }
    for match in HTML_LANG_RE.finditer(text):
        report["html_lang"].append({"line": line_number(text, match.start()), "value": match.group(1)})
    if path.suffix.lower() == ".html":
        for match in TEXT_RE.finditer(text):
            value = re.sub(r"\\s+", " ", match.group(1)).strip()
            if likely_visible(value):
                report["hardcoded_text"].append({"line": line_number(text, match.start()), "value": value})
        for match in ATTR_RE.finditer(text):
            value = match.group(2).strip()
            if likely_visible(value):
                report["hardcoded_attributes"].append({"line": line_number(text, match.start()), "attribute": match.group(1).lower(), "value": value})
    for match in PHYSICAL_CSS_RE.finditer(text):
        report["physical_css"].append({"line": line_number(text, match.start()), "property": match.group(1).lower(), "value": match.group(2).strip()})
    for match in FIXED_LOCALE_RE.finditer(text):
        report["fixed_locales"].append({"line": line_number(text, match.start()), "value": match.group(1)})
    return report


def audit_tree(web_root: Path) -> dict:
    extensions = {".html", ".js", ".css", ".webmanifest"}
    files = sorted(p for p in web_root.rglob("*") if p.is_file() and p.suffix.lower() in extensions)
    reports = [audit_file(path, web_root) for path in files]
    totals = {"files": len(reports), "html_lang": 0, "hardcoded_text": 0, "hardcoded_attributes": 0, "physical_css": 0, "fixed_locales": 0}
    for report in reports:
        for key in ("html_lang", "hardcoded_text", "hardcoded_attributes", "physical_css", "fixed_locales"):
            totals[key] += len(report[key])
    return {"web_root": str(web_root), "totals": totals, "files": reports}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--web-root", type=Path, default=DEFAULT_WEB)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = audit_tree(args.web_root)
    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
