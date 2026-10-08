"""Synthetic, server-free browser coverage of bilingual journal trend views."""
import copy
import http.server
import json
import threading
from datetime import date, timedelta
from pathlib import Path
from urllib.parse import urlparse

import pytest
from playwright.sync_api import Page, expect

pytestmark = [pytest.mark.browser, pytest.mark.slow]
WEB_DIR = Path(__file__).resolve().parent.parent / "web"


class _Handler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        path = urlparse(path).path
        pages = {"/": "home.html", "/journal": "journal.html", "/journal/trends": "journal-trends.html"}
        return str(WEB_DIR / pages.get(path, path.removeprefix("/static/").lstrip("/")))

    def log_message(self, *args):
        pass


@pytest.fixture(scope="module")
def trends_url():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()


STRIP = {
    "start_date": "2026-01-01", "end_date": "2026-01-14",
    "total_entries": 3, "emotion_entries": 2,
    "days": [
        {"date": (date(2026, 1, 1) + timedelta(days=i)).isoformat(),
         "has_entry": i in (0, 1, 3), "primary_emotion": {0: "Happy", 3: "هادئ"}.get(i)}
        for i in range(14)
    ],
}
SCALARS = {
    "start_date": "2026-01-01", "end_date": "2026-01-14", "total_entries": 3,
    "series": [
        {"field": field, "points": [
            {"date": day, "value": value}
            for day, value in zip(("2026-01-01", "2026-01-02", "2026-01-04"), values)
        ]}
        for field, values in (("mood", (2, 3, 4)), ("stress", (4, 3, 2)),
                              ("sleep", (5, 6, 7)), ("body", (1, 2, 3)))
    ],
    "correlations": [
        {"pair": ["mood", "stress"], "n": 3, "r": -0.8},
        {"pair": ["sleep", "mood"], "n": 3, "r": 0.6},
        {"pair": ["sleep", "body"], "n": 3, "r": 0.05},
        {"pair": ["mood", "body"], "n": 3, "r": 0.2},
        {"pair": ["stress", "body"], "n": 3, "r": -0.2},
        {"pair": ["stress", "sleep"], "n": 1, "r": None},
    ],
}
TAXONOMY = {
    "total_entries": 3, "emotion_entries": 2, "taxonomy_source": "form",
    "group_order": ["Happy", "Sad", "Unplaced"],
    "branches": [
        {"group": "Happy", "label": "Cozy", "used": True, "count": 2},
        {"group": "Happy", "label": "Giddy", "used": False, "count": 0},
        {"group": "Sad", "label": "Wobbly", "used": False, "count": 0},
    ],
    "extra_used": [{"group": "Unplaced", "label": "هادئ", "used": True, "count": 1}],
}


def _open(page, base, *, locale="en", data=None):
    responses = {"strip": STRIP, "scalars": SCALARS, "taxonomy": TAXONOMY, **(data or {})}
    requests = []

    def handle(route):
        endpoint = urlparse(route.request.url).path.rsplit("/", 1)[-1]
        requests.append(route.request.url)
        body = responses.get(endpoint, {})
        route.fulfill(content_type="application/json", body=json.dumps(body))

    page.route("**/api/**", handle)
    page.goto(f"{base}/journal/trends?lang={locale}")
    return requests


def _ar_number(page, value, options=None):
    return page.evaluate("([v, o]) => new Intl.NumberFormat('ar-u-nu-latn', o).format(v)", [value, options or {}])


def _ar_date(page, value):
    return page.evaluate("v => new Intl.DateTimeFormat('ar-u-nu-latn', {year:'numeric',month:'long',day:'numeric',timeZone:'UTC'}).format(new Date(v+'T00:00:00Z'))", value)


def test_arabic_formatting_uses_zero_to_nine_digits(page: Page, trends_url):
    """Arabic UI dates, counts and percentages retain the requested 0–9 digits."""
    _open(page, trends_url, locale="ar-PS")
    expect(page.locator("#taxonomySvg")).to_be_visible()
    values = page.evaluate("""async () => {
        const {formatNumber, formatPercent, formatDate} = await import('/static/journal-locale.js');
        return [formatNumber(1234567890, {useGrouping: false}), formatPercent(.75), formatDate('2026-01-14')];
    }""")
    assert values[0] == "1234567890"
    assert "75.0" in values[1]
    assert "14" in values[2] and "2026" in values[2] and "يناير" in values[2]
    assert not any("\u0660" <= char <= "\u0669" for value in values for char in value)
    expect(page.locator("html")).to_have_attribute("dir", "rtl")


@pytest.mark.parametrize("locale,title,direction", [
    ("en", "Journal Trends", "ltr"), ("ar", "تغيّر مشاعر اليوميات", "rtl"),
])
def test_shell_and_all_three_views(page: Page, trends_url, locale, title, direction):
    _open(page, trends_url, locale=locale)
    expect(page.locator("html")).to_have_attribute("lang", locale)
    expect(page.locator("html")).to_have_attribute("dir", direction)
    expect(page.locator("h1")).to_have_text(title)
    expect(page.locator("#localePicker")).to_have_value(locale)
    expect(page.locator(".scalar-row")).to_have_count(4)
    expect(page.locator(".scatter-cell")).to_have_count(6)
    expect(page.locator(".taxonomy-name")).to_have_text(["Cozy", "Giddy", "Wobbly", "هادئ+"])
    expect(page.locator("#taxonomySvg")).to_have_attribute("aria-label", f"{_ar_number(page, 1)} من {_ar_number(page, 3)} من مشاعر النموذج استُخدمت مرة واحدة على الأقل" if locale == "ar" else "1 of 3 of the form's feelings used at least once")
    expect(page.locator(".wheel-center-num")).to_have_text(f"{_ar_number(page, 1)}/{_ar_number(page, 3)}" if locale == "ar" else "1/3")
    expect(page.locator("#stripNote")).to_contain_text("يناير" if locale == "ar" else "2026-01-01")
    expect(page.locator(".strip-day.no-feeling")).to_have_attribute("title", f"{_ar_date(page, '2026-01-02')}: مدخل مسجل بلا مشاعر" if locale == "ar" else "2026-01-02: entry logged, no feeling recorded")
    expect(page.locator(".scalar-label")).to_have_text(["المزاج", "التوتر", "نوم الليلة السابقة", "الجسم"] if locale == "ar" else ["Mood", "Stress", "Prior-night sleep", "Body"])
    expect(page.locator("#correlationNote")).to_contain_text("لا يُرسم خط اتجاه" if locale == "ar" else "No trend line is fitted")


def _geometry(page):
    return page.locator("#taxonomySvg path, .scalar-row svg line, .scalar-row svg circle, .scatter-svg circle").evaluate_all(
        "els => els.map(e => [e.tagName, ...['d','fill','cx','cy','x1','x2','y1','y2'].map(a=>e.getAttribute(a))])"
    )


def test_live_switch_reuses_data_and_preserves_geometry(page: Page, trends_url):
    requests = _open(page, trends_url)
    expect(page.locator("#taxonomySvg")).to_be_visible()
    expect(page.locator(".scatter-cell")).to_have_count(6)
    geometry = _geometry(page)
    strip = page.locator(".strip-day").evaluate_all("els=>els.map(e=>[e.className,e.getAttribute('style')])")
    assert len(requests) == 3
    page.locator("#localePicker").select_option("ar")
    expect(page.locator("h1")).to_have_text("تغيّر مشاعر اليوميات")
    assert _geometry(page) == geometry
    assert page.locator(".strip-day").evaluate_all("els=>els.map(e=>[e.className,e.getAttribute('style')])") == strip
    assert len(requests) == 3
    # Coordinate directions stay left-to-right, while the surrounding UI is RTL.
    assert page.locator("#stripScroll").evaluate("e=>getComputedStyle(e).direction") == "ltr"
    page.locator("#localePicker").select_option("en")
    expect(page.locator(".scalar-label").first).to_have_text("Mood")
    assert _geometry(page) == geometry
    assert len(requests) == 3


def test_window_does_not_refetch_full_history(page: Page, trends_url):
    requests = _open(page, trends_url, locale="ar")
    expect(page.locator("#taxonomySvg")).to_be_visible()
    expect(page.locator(".strip-week")).to_have_count(3)
    page.locator('[data-window="week"]').click()
    expect(page.locator('[data-window="week"]')).to_have_class("window-pill active")
    page.wait_for_function("document.getElementById('scalarNote').textContent.includes('مدخلات')")
    page.wait_for_timeout(50)
    assert len([r for r in requests if "/strip" in r]) == 1
    assert len([r for r in requests if "window=week" in r]) == 2
    expect(page.locator(".section-badge")).to_have_text("السجل الكامل دائمًا")


@pytest.mark.parametrize("older_failure", [False, True])
def test_latest_window_owns_data_and_error_feedback(page: Page, trends_url, older_failure):
    """Late responses cannot replace the data or feedback of the selected window."""
    _open(page, trends_url, locale="ar")
    expect(page.locator("#taxonomySvg")).to_be_visible()
    held = []
    page.route("**/api/journal/scalars?*", lambda route: held.append(route))
    page.route("**/api/journal/taxonomy?*", lambda route: held.append(route))
    page.locator('[data-window="week"]').click()
    page.wait_for_function("document.getElementById('scalarNote').textContent.includes('جارٍ')")
    page.locator('[data-window="month"]').click()
    expect(page.locator('[data-window="month"]')).to_have_class("window-pill active")
    page.locator("#localePicker").select_option("en")
    assert len(held) == 4

    def finish(route, total):
        endpoint = urlparse(route.request.url).path.rsplit("/", 1)[-1]
        data = SCALARS if endpoint == "scalars" else TAXONOMY
        route.fulfill(content_type="application/json", body=json.dumps({**data, "total_entries": total}))

    for route in held[2:]:
        finish(route, 12)
    expect(page.locator("#scalarNote")).to_contain_text("12 journal entries")
    for route in held[:2]:
        if older_failure:
            route.fulfill(status=503)
        else:
            finish(route, 7)
    # The event-loop turn after both late responses must still show the newer data.
    page.wait_for_timeout(100)
    expect(page.locator("#scalarNote")).to_contain_text("12 journal entries")
    expect(page.locator("#taxonomyNote")).to_contain_text("of 12 entries")
    expect(page.locator('[data-window="month"]')).to_have_class("window-pill active")


@pytest.mark.parametrize("locale", ["ar", "en"])
def test_mobile_layout_and_numeric_direction(page: Page, trends_url, locale):
    page.set_viewport_size({"width": 390, "height": 844})
    _open(page, trends_url, locale=locale)
    expect(page.locator("#taxonomySvg")).to_be_visible()
    expect(page.locator(".scatter-cell")).to_have_count(6)
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    # Negative correlation remains in readable mathematical order.
    negative = _ar_number(page, -0.8, {"signDisplay": "always", "minimumFractionDigits": 2, "maximumFractionDigits": 2})
    expect(page.locator(".scatter-footnote").first).to_contain_text(negative if locale == "ar" else "-0.80")
    assert page.locator(".scatter-footnote bdi").first.evaluate("e => getComputedStyle(e).direction") == "ltr"


@pytest.mark.parametrize("locale", ["ar", "en"])
def test_empty_states(page: Page, trends_url, locale):
    scalars = copy.deepcopy(SCALARS)
    for series in scalars["series"]:
        series["points"] = []
    _open(page, trends_url, locale=locale, data={
        "strip": {**STRIP, "days": []}, "scalars": scalars,
        "taxonomy": {**TAXONOMY, "branches": [], "extra_used": [], "group_order": []},
    })
    expect(page.locator("#stripNote")).to_have_text("لم تُسجّل مدخلات يوميات بعد." if locale == "ar" else "No journal entries recorded yet.")
    expect(page.locator("#scalarNote")).to_contain_text("لا توجد بيانات للمزاج" if locale == "ar" else "No mood/stress/sleep/body data")
    expect(page.locator(".empty-state")).to_have_text("لا توجد مشاعر مسجلة في هذه الفترة." if locale == "ar" else "No feelings recorded in this window.")
    expect(page.locator("#taxonomySvg")).to_have_count(0)


def test_arabic_correlation_tiers_and_temporal_order(page: Page, trends_url):
    _open(page, trends_url, locale="ar")
    expect(page.locator(".scatter-summary")).to_contain_text("تتحرك معًا:")
    expect(page.locator(".scatter-summary")).to_contain_text("تتحرك في اتجاهين متعاكسين:")
    expect(page.locator(".scatter-summary")).to_contain_text("تتحرك معًا قليلًا:")
    expect(page.locator(".scatter-summary")).to_contain_text("اتجاهان متعاكسان قليلًا:")
    expect(page.locator(".scatter-summary")).to_contain_text("لا علاقة واضحة:")
    expect(page.locator(".scatter-summary")).to_contain_text("مدخلات مشتركة غير كافية:")
    expect(page.locator(".scatter-sentence").nth(1)).to_have_text("بعد نوم أكثر في الليلة السابقة، يميل تقييم المزاج في اليوم التالي إلى الارتفاع.")
    expect(page.locator(".scatter-sentence").nth(2)).to_contain_text("اليوم التالي")
    expect(page.locator(".scatter-sentence").last).to_contain_text(_ar_number(page, 1))
    expect(page.locator(".wheel-finding")).to_contain_text("Sad")
    expect(page.locator(".wheel-caption").nth(1)).to_contain_text("غير محسوبة")


def test_no_correlation_or_paired_points(page: Page, trends_url):
    scalars = copy.deepcopy(SCALARS)
    for c in scalars["correlations"]:
        c["r"] = None
    scalars["series"][1]["points"] = []
    _open(page, trends_url, locale="ar", data={"scalars": scalars})
    expect(page.locator(".scatter-summary")).to_have_text("المدخلات التي تسجل القيمتين في هذه الفترة غير كافية لمقارنة أي من هذه المقاييس.")
    expect(page.locator(".scatter-empty").first).to_have_text("لا توجد قيم مشتركة في هذه الفترة.")


def test_logged_only_taxonomy(page: Page, trends_url):
    _open(page, trends_url, locale="ar", data={"taxonomy": {**TAXONOMY, "taxonomy_source": "observed"}})
    expect(page.locator("#taxonomyNote")).to_contain_text("قائمة أسئلة النموذج غير متاحة")
    expect(page.locator(".taxonomy-name .taxonomy-extra")).to_have_attribute("title", "مسجل لكنه ليس من خيارات النموذج")


@pytest.mark.parametrize("endpoint", ["strip", "scalars", "taxonomy"])
@pytest.mark.parametrize("failure", ["api", "network"])
def test_failure_is_localized_and_live_switchable(page: Page, trends_url, endpoint, failure):
    _open(page, trends_url, locale="ar")
    expect(page.locator("#taxonomySvg")).to_be_visible()
    page.route(f"**/api/journal/{endpoint}*", lambda r: r.fulfill(status=503) if failure == "api" else r.abort())
    page.reload()
    note = page.locator("#stripNote" if endpoint == "strip" else "#scalarNote")
    expect(note).to_contain_text("تعذر التحميل")
    expect(note).to_contain_text(_ar_number(page, 503) if failure == "api" else "تعذر الاتصال بالخادم")
    page.locator("#localePicker").select_option("en")
    expect(note).to_contain_text("Failed to load")
    expect(note).to_contain_text("503" if failure == "api" else "Network request failed")


def test_shared_locale_and_regional_query(page: Page, trends_url):
    _open(page, trends_url, locale="ar-PS")
    expect(page.locator("html")).to_have_attribute("lang", "ar")
    page.locator('a[href="/journal"]').click()
    expect(page.locator("h1")).to_have_text("عجلة مشاعر اليوميات")
    page.goto(f"{trends_url}/journal/trends")
    expect(page.locator("h1")).to_have_text("تغيّر مشاعر اليوميات")


def test_storage_denial(page: Page, trends_url):
    page.add_init_script("Object.defineProperty(window, 'localStorage', {get() {throw new Error('denied')}})")
    _open(page, trends_url, locale="ar")
    expect(page.locator("#taxonomySvg")).to_be_visible()
    expect(page.locator("h1")).to_have_text("تغيّر مشاعر اليوميات")
    page.locator("#localePicker").select_option("en")
    expect(page.locator("h1")).to_have_text("Journal Trends")


def test_switch_while_loading_does_not_issue_more_requests(page: Page, trends_url):
    held = []
    page.route("**/api/journal/**", lambda route: held.append(route))
    page.goto(f"{trends_url}/journal/trends?lang=ar", wait_until="domcontentloaded")
    expect(page.locator("#stripNote")).to_have_text("جارٍ التحميل…")
    expect(page.locator("#taxonomyNote")).to_have_text("جارٍ التحميل…")
    page.locator("#localePicker").select_option("en")
    expect(page.locator("#scalarNote")).to_have_text("Loading…")
    assert len(held) == 3
    for route in held:
        endpoint = urlparse(route.request.url).path.rsplit("/", 1)[-1]
        route.fulfill(content_type="application/json", body=json.dumps({"strip": STRIP, "scalars": SCALARS, "taxonomy": TAXONOMY}[endpoint]))
    expect(page.locator("#taxonomySvg")).to_be_visible()
    expect(page.locator("#stripNote")).to_contain_text("One square per day")
    assert len(held) == 3


def test_long_history_scrolls_within_mobile_page(page: Page, trends_url):
    page.set_viewport_size({"width": 390, "height": 844})
    strip = {**STRIP, "start_date": "2024-01-01", "end_date": "2026-09-26", "days": [
        {"date": (date(2024, 1, 1) + timedelta(days=i)).isoformat(), "has_entry": False, "primary_emotion": None}
        for i in range(1000)
    ]}
    _open(page, trends_url, locale="ar", data={"strip": strip})
    expect(page.locator("#taxonomySvg")).to_be_visible()
    assert page.locator("#stripScroll").evaluate("e=>e.scrollWidth>e.clientWidth")
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    assert page.locator("#stripGrid").evaluate("e=>getComputedStyle(e).getPropertyValue('--cell').trim()") == "4px"
    page.locator("#stripScroll").evaluate("e => { e.scrollLeft = 300; }")
    page.locator('[data-window="week"]').click()
    expect(page.locator("#scalarNote")).to_contain_text("مدخلات")
    assert page.locator("#stripScroll").evaluate("e => e.scrollLeft") == 300
    page.locator("#localePicker").select_option("en")
    assert page.locator("#stripScroll").evaluate("e => e.scrollLeft") == 300


@pytest.mark.parametrize("pair,r,sentence", [
    (["sleep", "mood"], -0.6, "بعد نوم أكثر في الليلة السابقة، يميل تقييم المزاج في اليوم التالي إلى الانخفاض."),
    (["mood", "sleep"], 0.6, "بعد نوم أكثر في الليلة السابقة، يميل تقييم المزاج في اليوم التالي إلى الارتفاع."),
    (["mood", "body"], 0.0, "لا تظهر علاقة واضحة بين المزاج والجسم في اليوم نفسه."),
])
def test_correlation_reading_branches(page: Page, trends_url, pair, r, sentence):
    _open(page, trends_url, locale="ar", data={"scalars": {**SCALARS, "correlations": [{"pair": pair, "n": 3, "r": r}]}})
    expect(page.locator(".scatter-sentence")).to_have_text(sentence)


def test_authored_html_is_literal(page: Page, trends_url):
    bad = '<img src=x onerror="window.injected=true">'
    strip, scalars, taxonomy = copy.deepcopy((STRIP, SCALARS, TAXONOMY))
    strip["days"][0]["primary_emotion"] = bad
    strip["start_date"] = bad
    scalars["end_date"] = bad
    taxonomy["branches"][0]["label"] = bad
    _open(page, trends_url, locale="ar", data={"strip": strip, "scalars": scalars, "taxonomy": taxonomy})
    expect(page.locator(".taxonomy-name").first).to_have_text(bad)
    expect(page.locator("#stripNote")).to_contain_text(bad)
    expect(page.locator("#scalarNote")).to_contain_text(bad)
    expect(page.locator("img")).to_have_count(0)
    assert page.evaluate("window.injected === undefined")


def test_prepaint_direction_without_modules(page: Page, trends_url):
    page.route("**/static/journal-locale.js", lambda route: route.abort())
    _open(page, trends_url, locale="ar")
    expect(page.locator("html")).to_have_attribute("dir", "rtl")


def test_dates_are_calendar_dates_in_western_timezone(browser, trends_url):
    context = browser.new_context(timezone_id="America/Los_Angeles")
    try:
        page = context.new_page()
        _open(page, trends_url, locale="ar")
        expect(page.locator("#stripNote")).to_contain_text(_ar_date(page, "2026-01-01"))
        expect(page.locator(".scalar-row svg title").first).to_have_text(f"{_ar_date(page, '2026-01-01')}: {_ar_number(page, 2)}")
    finally:
        context.close()
