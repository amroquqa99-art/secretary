"""Browser coverage for the chat locale and bidirectional text seam.

The locale layer is intentionally small: English is the stable default, an
explicit ?lang= value persists, and Arabic switches the document to RTL while
message/composer text keeps content-aware direction.
"""
import http.server
import json
import threading
from pathlib import Path

import pytest
from playwright.sync_api import Page

pytestmark = [pytest.mark.browser, pytest.mark.slow]

WEB_DIR = Path(__file__).resolve().parent.parent / "web"


class _ChatHandler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        path = path.split("?", 1)[0].split("#", 1)[0]
        if path == "/chat":
            return str(WEB_DIR / "index.html")
        if path.startswith("/static/"):
            return str(WEB_DIR / path[len("/static/"):])
        return str(WEB_DIR / path.lstrip("/"))

    def log_message(self, *args):
        pass


@pytest.fixture(scope="module")
def locale_chat_base_url():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _ChatHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()


def _open(page: Page, base_url: str, suffix: str = ""):
    page.route(
        "https://cdn.jsdelivr.net/**",
        lambda route: route.fulfill(status=200, content_type="application/javascript", body=""),
    )
    page.route(
        "https://d3js.org/**",
        lambda route: route.fulfill(status=200, content_type="application/javascript", body=""),
    )

    def _api(route):
        url = route.request.url
        if url.endswith("/api/personas"):
            body = {"personas": [{"id": "primary", "label": "Primary", "capabilities": []}]}
        elif url.endswith("/api/chat/config"):
            body = {
                "remote_model_available": False,
                "remote_model_label": "",
                "local_model_available": False,
            }
        elif url.endswith("/api/hermes/status") or url.endswith("/api/agent/status"):
            body = {"available": False, "configured": False, "reachable": False}
        elif "/api/conversations" in url:
            body = []
        else:
            body = {}
        route.fulfill(status=200, content_type="application/json", body=json.dumps(body))

    page.route("**/api/**", _api)
    page.goto(f"{base_url}/chat{suffix}")
    page.wait_for_function("window.lifeChat && document.getElementById('inputField')?.getAttribute('dir') === 'auto'")


class TestLocaleSelection:
    def test_english_is_the_default(self, page: Page, locale_chat_base_url):
        _open(page, locale_chat_base_url)
        assert page.evaluate("document.documentElement.lang") == "en"
        assert page.evaluate("document.documentElement.dir") == "ltr"
        assert page.evaluate("window.lifeChat.locale") == "en"
        assert page.evaluate("window.lifeChat.direction") == "ltr"

    def test_arabic_query_selects_rtl_and_persists(self, page: Page, locale_chat_base_url):
        _open(page, locale_chat_base_url, "?lang=ar")
        assert page.evaluate("document.documentElement.lang") == "ar"
        assert page.evaluate("document.documentElement.dir") == "rtl"
        assert page.evaluate("localStorage.getItem('lifeos:locale')") == "ar"

        page.goto(f"{locale_chat_base_url}/chat")
        page.wait_for_function("window.lifeChat && window.lifeChat.locale === 'ar'")
        assert page.evaluate("document.documentElement.dir") == "rtl"

    def test_explicit_english_overrides_a_stored_arabic_locale(self, page: Page, locale_chat_base_url):
        page.add_init_script("localStorage.setItem('lifeos:locale', 'ar')")
        _open(page, locale_chat_base_url, "?lang=en")
        assert page.evaluate("window.lifeChat.locale") == "en"
        assert page.evaluate("document.documentElement.dir") == "ltr"
        assert page.evaluate("localStorage.getItem('lifeos:locale')") == "en"

    def test_set_locale_updates_live_state_and_rejects_unknown_locale(self, page: Page, locale_chat_base_url):
        _open(page, locale_chat_base_url)
        result = page.evaluate("""() => ({
            changed: window.lifeChat.setLocale('ar'),
            locale: window.lifeChat.locale,
            direction: window.lifeChat.direction,
            htmlLang: document.documentElement.lang,
            htmlDir: document.documentElement.dir,
            invalid: window.lifeChat.setLocale('xx')
        })""")
        assert result == {
            "changed": True,
            "locale": "ar",
            "direction": "rtl",
            "htmlLang": "ar",
            "htmlDir": "rtl",
            "invalid": False,
        }


class TestBidirectionalChatContent:
    def test_composer_and_messages_use_content_aware_direction(self, page: Page, locale_chat_base_url):
        _open(page, locale_chat_base_url, "?lang=ar")
        assert page.locator("#inputField").get_attribute("dir") == "auto"

        page.evaluate("window.addMessage('مرحبا LifeOS https://example.com', 'user')")
        content = page.locator(".message.user .message-content").last
        assert content.get_attribute("dir") == "auto"
        assert content.evaluate("el => getComputedStyle(el).textAlign") in ("start", "right")

    def test_arabic_prepaint_bootstrap_avoids_cross_screen_transition(self, page: Page, locale_chat_base_url):
        page.set_viewport_size({"width": 390, "height": 844})
        page.goto(f"{locale_chat_base_url}/chat?lang=ar", wait_until="domcontentloaded")
        snapshot = page.locator(".sidebar").evaluate("""el => {
            const style = getComputedStyle(el);
            const matrix = new DOMMatrixReadOnly(style.transform);
            return {
                htmlDir: document.documentElement.dir,
                right: style.right,
                translateX: matrix.m41,
            };
        }""")
        assert snapshot["htmlDir"] == "rtl"
        assert snapshot["right"] == "0px"
        assert snapshot["translateX"] > 0

    def test_mobile_sidebar_opens_from_the_inline_start_edge(self, page: Page, locale_chat_base_url):
        page.set_viewport_size({"width": 390, "height": 844})
        _open(page, locale_chat_base_url, "?lang=ar")
        metrics = page.locator(".sidebar").evaluate("""el => {
            const style = getComputedStyle(el);
            const matrix = new DOMMatrixReadOnly(style.transform);
            const rect = el.getBoundingClientRect();
            return {
                right: style.right,
                translateX: matrix.m41,
                rectLeft: rect.left,
                viewportWidth: window.innerWidth,
            };
        }""")
        assert metrics["right"] == "0px"
        assert metrics["translateX"] > 0
        assert metrics["rectLeft"] >= metrics["viewportWidth"] - 1
