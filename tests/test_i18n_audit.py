"""Unit coverage for the localization/RTL audit tool."""

from pathlib import Path

import pytest

pytestmark = pytest.mark.unit


def test_audit_file_finds_localization_and_direction_risks(tmp_path: Path):
    from scripts.i18n_audit import audit_file

    web = tmp_path / "web"
    web.mkdir()
    page = web / "sample.html"
    page.write_text(
        """<!doctype html>
<html lang="en">
<style>
.card { margin-left: 12px; text-align: left; }
</style>
<body>
  <h1>Hello world</h1>
  <input placeholder="Search notes">
  <script>const locale = "en-US";</script>
</body>
</html>
""",
        encoding="utf-8",
    )

    report = audit_file(page, web)

    assert report["html_lang"][0]["value"] == "en"
    assert any(item["value"] == "Hello world" for item in report["hardcoded_text"])
    assert any(
        item["attribute"] == "placeholder" and item["value"] == "Search notes"
        for item in report["hardcoded_attributes"]
    )
    props = {item["property"] for item in report["physical_css"]}
    assert {"margin-left", "text-align"} <= props
    assert report["fixed_locales"][0]["value"] == "en-US"


def test_audit_tree_aggregates_supported_web_files(tmp_path: Path):
    from scripts.i18n_audit import audit_tree

    web = tmp_path / "web"
    web.mkdir()
    (web / "a.html").write_text('<html lang="en"><body><p>Alpha</p></body></html>', encoding="utf-8")
    (web / "b.js").write_text('const x = "en-GB";', encoding="utf-8")
    (web / "ignore.txt").write_text("left: 1px", encoding="utf-8")

    report = audit_tree(web)

    assert report["totals"]["files"] == 2
    assert report["totals"]["html_lang"] == 1
    assert report["totals"]["hardcoded_text"] >= 1
    assert report["totals"]["fixed_locales"] == 1


def test_dynamic_code_fragments_are_not_counted_as_visible_copy(tmp_path: Path):
    from scripts.i18n_audit import audit_file

    web = tmp_path / "web"
    web.mkdir()
    page = web / "sample.html"
    dynamic = "$" + "{item.title}"
    page.write_text(
        f"<html><body><div>{dynamic}</div><div>مرحبا</div></body></html>",
        encoding="utf-8",
    )

    report = audit_file(page, web)
    values = [item["value"] for item in report["hardcoded_text"]]

    assert dynamic not in values
    assert "مرحبا" in values
