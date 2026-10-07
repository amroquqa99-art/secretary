"""Browser coverage for the shared locale runtime on the LifeOS home surface."""

import http.server
import threading
from pathlib import Path

import pytest
from playwright.sync_api import Page

pytestmark = [pytest.mark.browser, pytest.mark.slow]

WEB_DIR = Path(__file__).resolve().parent.parent / "web"


class _HomeHandler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        path = path.split("?", 1)[0].split("#", 1)[0]
        if path in {"/home", "/chat", "/crm", "/agents", "/journal"}:
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
    page.keyboard.press(key)
    page.wait_for_url(f"**{path}")
    assert page.url.endswith(path)
