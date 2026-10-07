"""Browser coverage for the shared locale runtime on the LifeOS home surface."""

import http.server
import json
import threading
from pathlib import Path
from urllib.parse import urlsplit

import pytest
from playwright.sync_api import Page

pytestmark = [pytest.mark.browser, pytest.mark.slow]

WEB_DIR = Path(__file__).resolve().parent.parent / "web"


class _HomeHandler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        path = path.split("?", 1)[0].split("#", 1)[0]
        if path == "/chat":
            return str(WEB_DIR / "index.html")
        if path in {"/home", "/crm", "/agents", "/journal"}:
            return str(WEB_DIR / "home.html")
        if path.startswith("/static/"):
            return str(WEB_DIR / path[len("/static/"):])
        return str(WEB_DIR / path.lstrip("/"))

    def log_message(self, *args):
        pass


@pytest.fixture(scope="module")
def locale_home_base_url():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _HomeHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()


def _open(page: Page, base_url: str, suffix: str = ""):
    page.goto(f"{base_url}/home{suffix}")
    page.wait_for_function("window.lifeHome && document.getElementById('localePicker')")


def test_arabic_home_is_rtl_and_translated(page: Page, locale_home_base_url):
    _open(page, locale_home_base_url, "?lang=ar")

    assert page.evaluate("document.documentElement.lang") == "ar"
    assert page.evaluate("document.documentElement.dir") == "rtl"
    assert page.evaluate("window.lifeHome.locale") == "ar"
    assert page.locator(".tagline").inner_text() == "نظامك الشخصي للمعرفة"
    assert page.locator(".card-title").all_inner_texts() == [
        "المحادثة",
        "العلاقات",
        "الوكلاء",
        "اليوميات",
    ]
    assert page.locator("#localePicker").input_value() == "ar"
    assert page.locator("#localePicker").get_attribute("title") == "اللغة"


def test_home_language_picker_switches_live_and_persists(page: Page, locale_home_base_url):
    _open(page, locale_home_base_url, "?lang=en")

    page.locator("#localePicker").select_option("ar")
    assert page.evaluate("window.lifeHome.locale") == "ar"
    assert page.evaluate("document.documentElement.dir") == "rtl"
    assert page.evaluate("localStorage.getItem('lifeos:locale')") == "ar"
    assert page.locator(".tagline").inner_text() == "نظامك الشخصي للمعرفة"

    page.goto(f"{locale_home_base_url}/home")
    page.wait_for_function("window.lifeHome && window.lifeHome.locale === 'ar'")
    assert page.evaluate("document.documentElement.dir") == "rtl"


def test_arabic_direction_is_resolved_before_home_module_state(page: Page, locale_home_base_url):
    page.add_init_script("localStorage.setItem('lifeos:locale', 'ar')")
    page.goto(f"{locale_home_base_url}/home", wait_until="domcontentloaded")

    assert page.evaluate("document.documentElement.lang") == "ar"
    assert page.evaluate("document.documentElement.dir") == "rtl"


@pytest.mark.parametrize(
    ("key", "path"),
    [("1", "/chat"), ("2", "/crm"), ("3", "/agents"), ("4", "/journal")],
)
def test_home_keyboard_shortcuts_survive_module_move(page: Page, locale_home_base_url, key, path):
    _open(page, locale_home_base_url, "?lang=en")
    page.route("https://cdn.jsdelivr.net/**", lambda route: route.abort())
    page.route("https://d3js.org/**", lambda route: route.abort())
    page.route("**/api/**", lambda route: route.fulfill(
        status=200, content_type="application/json", body="{}",
    ))
    page.keyboard.press(key)
    page.wait_for_url(f"**{path}")
    assert urlsplit(page.url).path == path


def test_home_shortcuts_do_not_interrupt_language_selection(page: Page, locale_home_base_url):
    _open(page, locale_home_base_url)
    page.locator("#localePicker").focus()
    page.keyboard.press("1")
    assert page.url.endswith("/home")


@pytest.mark.parametrize("key", ["Control+1", "Alt+2", "Meta+3", "Shift+4"])
def test_home_shortcuts_leave_modified_keys_alone(page: Page, locale_home_base_url, key):
    _open(page, locale_home_base_url)
    page.keyboard.press(key)
    assert page.url.endswith("/home")


def test_arabic_selection_is_shared_with_chat(page: Page, locale_home_base_url):
    _open(page, locale_home_base_url)
    page.locator("#localePicker").select_option("ar")
    page.route("https://cdn.jsdelivr.net/**", lambda route: route.abort())
    page.route("https://d3js.org/**", lambda route: route.abort())

    def chat_api(route):
        path = urlsplit(route.request.url).path
        if path == "/api/personas":
            body = {"personas": [{"id": "primary", "label": "Primary", "capabilities": []}]}
        elif path.startswith("/api/conversations"):
            body = []
        else:
            body = {}
        route.fulfill(status=200, content_type="application/json", body=json.dumps(body))

    page.route("**/api/**", chat_api)
    page.goto(f"{locale_home_base_url}/chat")
    page.wait_for_function("window.lifeChat && document.getElementById('inputField')?.getAttribute('dir') === 'auto'")

    assert page.evaluate("window.lifeChat.locale") == "ar"
    assert page.locator("#inputField").get_attribute("placeholder") == "اكتب سؤالك..."

    page.locator("#localePicker").select_option("en")
    _open(page, locale_home_base_url)
    assert page.evaluate("window.lifeHome.locale") == "en"
    assert page.locator(".tagline").inner_text() == "Your personal knowledge system"


@pytest.mark.parametrize("suffix, expected", [("?lang=ar", "ar"), ("", "en")])
def test_home_locale_works_when_storage_is_blocked(page: Page, locale_home_base_url, suffix, expected):
    page.add_init_script("""Object.defineProperty(window, 'localStorage', {
        get() { throw new DOMException('Blocked', 'SecurityError'); }
    });""")
    page.route("**/static/home.js", lambda route: route.abort())
    page.goto(f"{locale_home_base_url}/home{suffix}")
    assert page.evaluate("document.documentElement.lang") == expected

    page.unroute("**/static/home.js")
    _open(page, locale_home_base_url, suffix)
    assert page.evaluate("window.lifeHome.locale") == expected
    page.locator("#localePicker").select_option("ar")
    assert page.evaluate("window.lifeHome.locale") == "ar"
    assert page.evaluate("document.documentElement.dir") == "rtl"
