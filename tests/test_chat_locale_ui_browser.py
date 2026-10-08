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
from playwright.sync_api import Page, expect

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
        elif url.endswith(("/api/hermes/status", "/api/agent/status")):
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


    def test_arabic_copy_is_applied_to_primary_chat_controls(self, page: Page, locale_chat_base_url):
        _open(page, locale_chat_base_url, "?lang=ar")
        assert page.locator("#localePicker").input_value() == "ar"
        assert page.locator(".welcome h2").inner_text() == "مرحبًا بك في LifeOS"
        assert page.locator(".welcome p").inner_text().startswith("مساعدك الشخصي للمعرفة")
        assert page.locator("#inputField").get_attribute("placeholder") == "اكتب سؤالك..."
        assert page.locator("#modeTextBtn").inner_text() == "نص"
        assert page.locator("#modeVoiceBtn").inner_text() == "صوت"
        assert page.locator("#modelPicker option[value='gemma']").inner_text() == "Gemma (محلي)"
        assert page.locator("#voiceAuto").locator("xpath=..").inner_text().strip().endswith("تلقائي")

    def test_language_picker_switches_copy_without_navigation(self, page: Page, locale_chat_base_url):
        _open(page, locale_chat_base_url, "?lang=en")
        page.locator("#localePicker").select_option("ar")
        assert page.evaluate("window.lifeChat.locale") == "ar"
        assert page.evaluate("document.documentElement.dir") == "rtl"
        assert page.locator("#inputField").get_attribute("placeholder") == "اكتب سؤالك..."
        assert page.locator("#localePicker").get_attribute("title") == "اللغة"

        page.locator("#localePicker").select_option("en")
        assert page.evaluate("window.lifeChat.locale") == "en"
        assert page.evaluate("document.documentElement.dir") == "ltr"
        assert page.locator("#inputField").get_attribute("placeholder") == "Ask a question..."


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


@pytest.mark.parametrize("locale", ["en", "ar"])
def test_conversation_dates_follow_locale_with_english_parity(page: Page, locale_chat_base_url, locale):
    # Playwright's Python clock accepts epoch seconds; JS Date returns milliseconds.
    page.clock.install(time=page.evaluate("new Date(2026, 9, 7, 15, 30).getTime()") / 1000)
    assert page.evaluate("new Date().getFullYear()") == 2026
    _open(page, locale_chat_base_url, f"?lang={locale}")
    labels = page.evaluate("""async () => {
        const { formatDate } = await import('/static/chat/conversations.js');
        const now = Date.now();
        return {
            empty: formatDate(''),
            fresh: formatDate(new Date(now - 30000).toISOString()),
            minutes: formatDate(new Date(now - 5 * 60000).toISOString()),
            hours: formatDate(new Date(now - 2 * 3600000).toISOString()),
            yesterday: formatDate(new Date(now - 86400000).toISOString()),
            older: formatDate(new Date(now - 20 * 86400000).toISOString()),
            lastYear: formatDate('2025-06-10T12:00:00Z'),
        };
    }""")
    assert labels["empty"] == ""
    if locale == "en":
        assert labels["fresh"] == "Just now"
        assert labels["minutes"] == "5m ago"
        assert labels["hours"] == "2h ago"
        assert labels["yesterday"].startswith("Yesterday ")
        assert "Sep" in labels["older"]
        assert "2025" in labels["lastYear"]
    else:
        assert labels["fresh"] == "الآن"
        five = page.evaluate("new Intl.NumberFormat('ar-u-nu-latn').format(5)")
        assert labels["minutes"] == f"قبل {five} دقائق"
        assert labels["hours"] == "قبل ساعتين"
        assert labels["yesterday"].startswith("أمس ")
        assert "سبتمبر" in labels["older"]
        year = page.evaluate("new Intl.NumberFormat('ar-u-nu-latn', {useGrouping: false}).format(2025)")
        assert year in labels["lastYear"]


def test_language_picker_refreshes_existing_sidebar_dates(page: Page, locale_chat_base_url):
    _open(page, locale_chat_base_url, "?lang=en")
    page.evaluate("""() => {
        window.lifeChat.state.allConversations = [{
            id: 'synthetic-locale-test', title: 'Synthetic conversation',
            updated_at: new Date(Date.now() - 5 * 60000).toISOString(),
        }];
        window.filterConversations();
    }""")
    label = page.locator(".conversation-date").first
    assert label.inner_text() == "5m ago"
    page.locator("#localePicker").select_option("ar")
    five = page.evaluate("new Intl.NumberFormat('ar-u-nu-latn').format(5)")
    assert label.inner_text() == f"قبل {five} دقائق"
    page.locator("#localePicker").select_option("en")
    assert label.inner_text() == "5m ago"


def _swipe(page: Page, start_x: int, end_x: int, end_y: int = 240):
    page.evaluate("""({startX, endX, endY}) => {
        const target = document.body;
        const start = new Touch({identifier: 1, target, clientX: startX, clientY: 240});
        const end = new Touch({identifier: 1, target, clientX: endX, clientY: endY});
        target.dispatchEvent(new TouchEvent('touchstart', {touches: [start], bubbles: true}));
        target.dispatchEvent(new TouchEvent('touchend', {changedTouches: [end], bubbles: true}));
    }""", {"startX": start_x, "endX": end_x, "endY": end_y})


@pytest.mark.parametrize("locale, edge, inward", [("en", 5, 100), ("ar", 385, 290)])
def test_mobile_sidebar_opens_from_locale_start_edge(page: Page, locale_chat_base_url, locale, edge, inward):
    page.set_viewport_size({"width": 390, "height": 844})
    _open(page, locale_chat_base_url, f"?lang={locale}")
    _swipe(page, edge, inward)
    assert page.locator(".sidebar").evaluate("el => el.classList.contains('open')")
    assert page.locator("#overlay").evaluate("el => el.classList.contains('visible')")

    _swipe(page, inward, edge)
    assert not page.locator(".sidebar").evaluate("el => el.classList.contains('open')")
    assert not page.locator("#overlay").evaluate("el => el.classList.contains('visible')")


@pytest.mark.parametrize("locale, edge, inward", [("en", 385, 290), ("ar", 5, 100)])
def test_mobile_sidebar_ignores_opposite_edge(page: Page, locale_chat_base_url, locale, edge, inward):
    page.set_viewport_size({"width": 390, "height": 844})
    _open(page, locale_chat_base_url, f"?lang={locale}")
    _swipe(page, edge, inward)
    assert not page.locator(".sidebar").evaluate("el => el.classList.contains('open')")


@pytest.mark.parametrize("locale, edge, inward", [("en", 5, 100), ("ar", 385, 290)])
def test_mobile_sidebar_ignores_vertical_and_short_swipes(page: Page, locale_chat_base_url, locale, edge, inward):
    page.set_viewport_size({"width": 390, "height": 844})
    _open(page, locale_chat_base_url, f"?lang={locale}")
    _swipe(page, edge, inward, end_y=400)
    assert not page.locator(".sidebar").evaluate("el => el.classList.contains('open')")
    _swipe(page, edge, (edge + inward) // 2)
    assert not page.locator(".sidebar").evaluate("el => el.classList.contains('open')")


def test_mobile_sidebar_swipe_follows_live_locale_switch(page: Page, locale_chat_base_url):
    page.set_viewport_size({"width": 390, "height": 844})
    _open(page, locale_chat_base_url, "?lang=en")
    page.evaluate("window.lifeChat.setLocale('ar')")
    _swipe(page, 385, 290)
    assert page.locator(".sidebar").evaluate("el => el.classList.contains('open')")


@pytest.mark.parametrize("interruption", ["cancel", "multitouch"])
def test_mobile_sidebar_discards_interrupted_gestures(page: Page, locale_chat_base_url, interruption):
    page.set_viewport_size({"width": 390, "height": 844})
    _open(page, locale_chat_base_url, "?lang=ar")
    page.evaluate("""interruption => {
        const target = document.body;
        const start = new Touch({identifier: 1, target, clientX: 385, clientY: 240});
        const end = new Touch({identifier: 1, target, clientX: 290, clientY: 240});
        target.dispatchEvent(new TouchEvent('touchstart', {touches: [start], bubbles: true}));
        if (interruption === 'cancel') {
            target.dispatchEvent(new TouchEvent('touchcancel', {bubbles: true}));
        } else {
            const second = new Touch({identifier: 2, target, clientX: 350, clientY: 250});
            target.dispatchEvent(new TouchEvent('touchstart', {touches: [start, second], bubbles: true}));
        }
        target.dispatchEvent(new TouchEvent('touchend', {changedTouches: [end], bubbles: true}));
    }""", interruption)
    assert not page.locator(".sidebar").evaluate("el => el.classList.contains('open')")


@pytest.mark.parametrize("locale", ["en", "ar"])
def test_new_chat_keeps_selected_language(page: Page, locale_chat_base_url, locale):
    _open(page, locale_chat_base_url, f"?lang={locale}")
    page.evaluate("window.newChat()")
    expected = "مرحبًا بك في LifeOS" if locale == "ar" else "Welcome to LifeOS"
    assert page.locator(".welcome h2").inner_text() == expected
    assert page.locator("#inputField").get_attribute("placeholder") == (
        "اكتب سؤالك..." if locale == "ar" else "Ask a question..."
    )
    page.locator("#localePicker").select_option("en" if locale == "ar" else "ar")
    assert page.locator(".welcome h2").inner_text() == (
        "Welcome to LifeOS" if locale == "ar" else "مرحبًا بك في LifeOS"
    )
    assert page.locator(".suggestion").first.inner_text() == (
        "📅 Calendar tomorrow" if locale == "ar" else "📅 تقويم الغد"
    )


@pytest.mark.parametrize("locale", ["en", "ar"])
def test_sidebar_dynamic_labels_follow_locale_without_changing_titles(page: Page, locale_chat_base_url, locale):
    _open(page, locale_chat_base_url, f"?lang={locale}")
    empty = page.locator("#conversationsList .empty-conversations")
    assert empty.inner_text() == ("لا توجد محادثات بعد" if locale == "ar" else "No conversations yet")
    page.locator("#conversationSearch").fill("synthetic missing phrase")
    expect(empty).to_have_text("لا توجد محادثات مطابقة" if locale == "ar" else "No matching conversations")
    page.locator("#conversationSearch").fill("")
    expect(empty).to_have_text("لا توجد محادثات بعد" if locale == "ar" else "No conversations yet")
    page.evaluate("""() => {
        window.lifeChat.state.allConversations = [
            {id: 'synthetic-untitled', title: '', updated_at: ''},
            {id: 'synthetic-titled', title: '<img src=x> خطة Release', updated_at: ''},
        ];
        window.filterConversations();
    }""")
    titles = page.locator(".conversation-title")
    assert titles.nth(0).inner_text() == ("محادثة جديدة" if locale == "ar" else "New conversation")
    assert titles.nth(1).inner_text() == "<img src=x> خطة Release"
    assert page.locator(".conversation-title img").count() == 0
    page.locator("#localePicker").select_option("en" if locale == "ar" else "ar")
    assert titles.nth(0).inner_text() == ("New conversation" if locale == "ar" else "محادثة جديدة")
    assert titles.nth(1).inner_text() == "<img src=x> خطة Release"


@pytest.mark.parametrize("locale", ["en", "ar"])
def test_delete_confirmation_uses_selected_language_and_cancel_prevents_delete(page: Page, locale_chat_base_url, locale):
    _open(page, locale_chat_base_url, f"?lang={locale}")
    deletes = []
    page.on("request", lambda request: deletes.append(request.url) if request.method == "DELETE" else None)
    dialogs = []

    def dismiss(dialog):
        dialogs.append(dialog.message)
        dialog.dismiss()

    page.once("dialog", dismiss)
    page.evaluate("window.deleteConversation('synthetic-untitled')")
    assert dialogs == ["هل تريد حذف هذه المحادثة؟" if locale == "ar" else "Delete this conversation?"]
    assert deletes == []
