"""Browser test for the /journal emotion-wheel view.

Serves `web/` itself from an ephemeral port and stubs every `/api/journal/`
call, so this runs without a live server — same pattern as
test_voice_mic_block_ui_browser.py. Carries no `requires_server` marker, so
it's part of the pre-push gate (`browser and not requires_server`).

All stubbed data below is invented — no real journal content.
"""
import http.server
import json
import threading
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest
from playwright.sync_api import Page, expect

pytestmark = [pytest.mark.browser, pytest.mark.slow]

WEB_DIR = Path(__file__).resolve().parent.parent / "web"


class _JournalHandler(http.server.SimpleHTTPRequestHandler):
    """Serves the journal page the way api/main.py does: `/journal` is
    journal.html, everything else hangs off `/static/` or the web root."""

    def translate_path(self, path):
        path = path.split("?", 1)[0].split("#", 1)[0]
        if path == "/":
            return str(WEB_DIR / "home.html")
        if path == "/journal":
            return str(WEB_DIR / "journal.html")
        if path.startswith("/static/"):
            return str(WEB_DIR / path[len("/static/"):])
        return str(WEB_DIR / path.lstrip("/"))

    def log_message(self, *args):  # keep pytest output clean
        pass


@pytest.fixture(scope="module")
def journal_base_url():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _JournalHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()


_NONEMPTY_RESPONSE = {
    "window": "all-time",
    "start_date": None,
    "end_date": "2026-06-30",
    "total_entries": 4,
    "emotion_entries": 4,
    "wheel": [
        {
            "value": "Happy",
            "count": 3,
            "children": [
                {"value": "Cozy", "count": 2, "children": []},
                {"value": "Giddy", "count": 1, "children": []},
            ],
        },
        {"value": "Bad", "count": 1, "children": [{"value": "Wobbly", "count": 1, "children": []}]},
    ],
}

_EMPTY_RESPONSE = {
    "window": "day",
    "start_date": "2026-06-30",
    "end_date": "2026-06-30",
    "total_entries": 0,
    "emotion_entries": 0,
    "wheel": [],
}

_PARTIAL_COVERAGE_RESPONSE = {
    "window": "week",
    "start_date": "2026-06-24",
    "end_date": "2026-06-30",
    "total_entries": 7,
    "emotion_entries": 1,
    "wheel": [{"value": "Happy", "count": 1, "children": []}],
}


def _open_journal(page: Page, base_url, *, response_by_window=None, default=_NONEMPTY_RESPONSE, suffix=""):
    response_by_window = response_by_window or {}

    def handler(route):
        url = urlparse(route.request.url)
        if "/api/journal/emotions" in url.path:
            window = parse_qs(url.query).get("window", ["all-time"])[0]
            body = response_by_window.get(window, default)
            route.fulfill(status=200, content_type="application/json", body=json.dumps(body))
        else:
            route.fulfill(status=200, content_type="application/json", body="{}")

    page.route("**/api/**", handler)
    page.goto(f"{base_url}/journal{suffix}")
    page.wait_for_selector("#sampleBanner")


class TestJournalWheelView:
    def test_default_window_renders_sample_size_and_legend(self, page: Page, journal_base_url):
        _open_journal(page, journal_base_url)
        expect(page.locator("#sampleBanner")).to_contain_text("4")
        expect(page.locator("#sampleBanner")).to_contain_text("journal entries")
        # One legend row per top-level emotion.
        expect(page.locator(".legend-row")).to_have_count(2)
        expect(page.locator(".legend-row").first).to_contain_text("Happy")
        # Wheel wedges rendered as SVG paths, at least one per tree node.
        expect(page.locator("#wheelSvg path")).to_have_count(5)

    def test_thin_sample_gets_a_visible_caveat(self, page: Page, journal_base_url):
        thin_response = {
            **_NONEMPTY_RESPONSE, "total_entries": 2, "emotion_entries": 2,
            "wheel": [{"value": "Sad", "count": 2, "children": []}],
        }
        _open_journal(page, journal_base_url, default=thin_response)
        expect(page.locator("#sampleBanner")).to_contain_text("small sample")

    def test_empty_window_shows_no_entries_message_and_no_wheel(self, page: Page, journal_base_url):
        _open_journal(page, journal_base_url, default=_EMPTY_RESPONSE)
        expect(page.locator("#sampleBanner")).to_contain_text("No journal entries")
        expect(page.locator("#wheelSvg path")).to_have_count(0)
        expect(page.locator(".legend-row")).to_have_count(0)

    def test_partial_emotion_coverage_names_both_counts(self, page: Page, journal_base_url):
        # 7 dated entries, only 1 carried emotion data — the banner must say
        # so explicitly rather than reporting "1 entry" as if that were the
        # whole window (the exact bug FIX 2 closed).
        _open_journal(page, journal_base_url, default=_PARTIAL_COVERAGE_RESPONSE)
        expect(page.locator("#sampleBanner")).to_contain_text("1")
        expect(page.locator("#sampleBanner")).to_contain_text("7")
        expect(page.locator("#sampleBanner")).to_contain_text("emotion data")
        expect(page.locator("#wheelSvg path")).to_have_count(1)

    def test_window_pill_click_refetches_and_updates_banner(self, page: Page, journal_base_url):
        by_window = {
            "all-time": _NONEMPTY_RESPONSE,
            "day": _EMPTY_RESPONSE,
        }
        _open_journal(page, journal_base_url, response_by_window=by_window)
        expect(page.locator("#sampleBanner")).to_contain_text("4")

        page.locator('.window-pill[data-window="day"]').click()
        expect(page.locator("#sampleBanner")).to_contain_text("No journal entries")
        expect(page.locator('.window-pill[data-window="day"]')).to_have_class("window-pill active")
        expect(page.locator('.window-pill[data-window="all-time"]')).not_to_have_class(
            "window-pill active")


@pytest.mark.parametrize("locale", ["en", "ar"])
def test_journal_locale_covers_controls_counts_dates_and_authored_labels(page: Page, journal_base_url, locale):
    _open_journal(page, journal_base_url, suffix=f"?lang={locale}")
    expect(page.locator("html")).to_have_attribute("lang", locale)
    expect(page.locator("html")).to_have_attribute("dir", "rtl" if locale == "ar" else "ltr")
    expect(page.locator("h1")).to_have_text("عجلة مشاعر اليوميات" if locale == "ar" else "Journal Emotion Wheel")
    expect(page.locator("#localePicker")).to_have_value(locale)
    expect(page.locator('[data-window="day"]')).to_have_text("يوم" if locale == "ar" else "Day")
    expect(page.locator(".legend-value").first).to_have_text("Happy")
    expect(page.locator(".legend-value").first).to_have_attribute("dir", "auto")
    expected_count = page.evaluate("locale => new Intl.NumberFormat(locale).format(3)", locale)
    expected_percent = page.evaluate("locale => new Intl.NumberFormat(locale, {style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1}).format(.75)", locale)
    expect(page.locator(".legend-count").first).to_have_text(f"{expected_count} ({expected_percent})")
    date = page.evaluate("new Intl.DateTimeFormat('ar', {year:'numeric', month:'long', day:'numeric', timeZone:'UTC'}).format(new Date('2026-06-30T00:00:00Z'))") if locale == "ar" else "2026-06-30"
    expect(page.locator("#sampleBanner")).to_contain_text(date)
    page.locator(".wedge").first.dispatch_event("mouseenter")
    expect(page.locator("#tooltip")).to_contain_text(f"Happy: {expected_count} ({expected_percent})")


def test_journal_picker_reformats_cached_data_without_refetch_or_mirroring_geometry(page: Page, journal_base_url):
    calls = []
    page.on("request", lambda request: calls.append(request.url) if "/api/journal/emotions" in request.url else None)
    _open_journal(page, journal_base_url)
    expect(page.locator(".legend-row")).to_have_count(2)
    geometry = page.locator(".wedge").evaluate_all("els => els.map(el => [el.getAttribute('d'), el.getAttribute('fill')])")
    page.locator("#localePicker").select_option("ar")
    expect(page.locator("#sampleBanner")).to_contain_text("اليوميات")
    assert page.locator(".wedge").evaluate_all("els => els.map(el => [el.getAttribute('d'), el.getAttribute('fill')])") == geometry
    expect(page.locator(".legend-value").first).to_have_text("Happy")
    assert len(calls) == 1
    page.locator("#localePicker").select_option("en")
    expect(page.locator("#sampleBanner")).to_contain_text("journal entries")
    assert len(calls) == 1


@pytest.mark.parametrize("counts,phrase", [
    ((0, 0), "لا توجد مدخلات"),
    ((3, 0), "لا تحتوي على بيانات مشاعر"),
    ((2, 2), "عينة صغيرة"),
    ((7, 1), "النسب أدناه محسوبة من"),
    ((8, 8), "مدخلات اليوميات"),
])
def test_arabic_sample_states_preserve_coverage_and_thin_sample_caveat(page: Page, journal_base_url, counts, phrase):
    total, emotions = counts
    data = {**_NONEMPTY_RESPONSE, "total_entries": total, "emotion_entries": emotions,
            "wheel": [{"value": "Happy", "count": emotions, "children": []}] if emotions else []}
    _open_journal(page, journal_base_url, suffix="?lang=ar", default=data)
    expect(page.locator("#sampleBanner")).to_contain_text(phrase)
    if emotions:
        expect(page.locator(".legend-count")).to_contain_text(page.evaluate("new Intl.NumberFormat('ar', {style:'percent', minimumFractionDigits:1, maximumFractionDigits:1}).format(1)"))
        expected = page.evaluate("n => new Intl.NumberFormat('ar').format(n)", total)
        expect(page.locator("#sampleBanner")).to_contain_text(expected)
    else:
        expect(page.locator(".wedge")).to_have_count(0)
    assert page.locator("#sampleBanner").evaluate("el => el.classList.contains('sample-thin')") == (0 < emotions < 5)


def test_journal_explicit_locale_overrides_stored_choice_and_persists_to_home(page: Page, journal_base_url):
    page.add_init_script("if (!localStorage.getItem('lifeos:locale')) localStorage.setItem('lifeos:locale', 'en')")
    _open_journal(page, journal_base_url, suffix="?lang=ar-PS")
    expect(page.locator("html")).to_have_attribute("lang", "ar")
    assert page.evaluate("localStorage.getItem('lifeos:locale')") == "ar"
    page.goto(f"{journal_base_url}/")
    expect(page.locator("#localePicker")).to_have_value("ar")
    page.locator("#localePicker").select_option("en")
    page.goto(f"{journal_base_url}/journal")
    expect(page.locator("html")).to_have_attribute("lang", "en")


def test_journal_locale_survives_denied_browser_storage(page: Page, journal_base_url):
    page.add_init_script("Object.defineProperty(window, 'localStorage', {get() { throw new Error('storage denied'); }});")
    _open_journal(page, journal_base_url, suffix="?lang=ar")
    expect(page.locator("h1")).to_have_text("عجلة مشاعر اليوميات")
    expect(page.locator(".legend-row")).to_have_count(2)
    page.locator("#localePicker").select_option("en")
    expect(page.locator("html")).to_have_attribute("dir", "ltr")


@pytest.mark.parametrize("locale", ["en", "ar"])
def test_mobile_journal_has_no_horizontal_overflow(page: Page, journal_base_url, locale):
    page.set_viewport_size({"width": 390, "height": 844})
    _open_journal(page, journal_base_url, suffix=f"?lang={locale}")
    expect(page.locator(".wedge")).to_have_count(5)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    page.locator('[data-window="week"]').click()
    expect(page.locator('[data-window="week"]')).to_have_class("window-pill active")


def test_journal_api_error_reformats_when_language_changes(page: Page, journal_base_url):
    page.route("**/api/journal/emotions?*", lambda route: route.fulfill(status=503, body="{}"))
    page.goto(f"{journal_base_url}/journal?lang=ar")
    expect(page.locator("#sampleBanner")).to_contain_text("تعذر التحميل")
    page.locator("#localePicker").select_option("en")
    expect(page.locator("#sampleBanner")).to_have_text("Failed to load: API error: 503")


def test_journal_escaping_preserves_literal_authored_labels_and_date_text(page: Page, journal_base_url):
    data = {**_NONEMPTY_RESPONSE, "end_date": '<img src=x onerror="window.injected=true">',
            "wheel": [{"value": '<img src=x> هادئ', "count": 4, "children": []}]}
    _open_journal(page, journal_base_url, suffix="?lang=ar", default=data)
    expect(page.locator(".legend-value")).to_have_text('<img src=x> هادئ')
    expect(page.locator("#sampleBanner")).to_contain_text(data["end_date"])
    assert page.locator("#wheelCard img, #sampleBanner img").count() == 0
    assert page.evaluate("window.injected === undefined")


def test_journal_prepaint_sets_rtl_before_runtime_module_loads(page: Page, journal_base_url):
    page.route("**/static/journal-locale.js", lambda route: route.abort())
    page.goto(f"{journal_base_url}/journal?lang=ar", wait_until="domcontentloaded")
    expect(page.locator("html")).to_have_attribute("dir", "rtl")
    expect(page.locator("html")).to_have_attribute("lang", "ar")


def test_journal_network_error_uses_localized_feedback(page: Page, journal_base_url):
    page.route("**/api/journal/emotions?*", lambda route: route.abort())
    page.goto(f"{journal_base_url}/journal?lang=ar")
    expect(page.locator("#sampleBanner")).to_have_text("تعذر التحميل: تعذر الاتصال بالخادم")
    page.locator("#localePicker").select_option("en")
    expect(page.locator("#sampleBanner")).to_have_text("Failed to load: Network request failed")


def test_journal_calendar_date_does_not_shift_in_western_time_zone(browser, journal_base_url):
    with browser.new_context(timezone_id="America/Los_Angeles") as context:
        page = context.new_page()
        _open_journal(page, journal_base_url, suffix="?lang=ar")
        expected = page.evaluate("new Intl.DateTimeFormat('ar', {year:'numeric', month:'long', day:'numeric', timeZone:'UTC'}).format(new Date('2026-06-30T00:00:00Z'))")
        expect(page.locator("#sampleBanner")).to_contain_text(expected)
