(() => {
  "use strict";

  const STORAGE_KEY = "lifeos:locale";
  const DEFAULT_LOCALE = "en";
  const SUPPORTED = new Set(["en", "ar"]);

  const MESSAGES = Object.freeze({
    en: Object.freeze({
      "common.language": "العربية",
      "home.tagline": "Your personal knowledge system",
      "home.chat.title": "Chat",
      "home.chat.description": "Ask questions about your notes, calendar, and emails",
      "home.crm.title": "CRM",
      "home.crm.description": "Manage relationships and view your network",
      "home.agents.title": "Agents",
      "home.agents.description": "See what your agents are working on right now",
      "home.journal.title": "Journal",
      "home.journal.description": "See which emotions dominate your entries, and how they trend",
      "chat.new": "New chat",
      "chat.search.placeholder": "Search conversations...",
      "chat.status.ready": "Ready",
      "chat.welcome.title": "Welcome to LifeOS",
      "chat.welcome.body": "Your personal knowledge assistant. Ask about your notes, calendar, emails, or anything in your vault.",
      "chat.input.placeholder": "Ask a question...",
      "chat.send": "Send message",
      "chat.people.placeholder": "Search people..."
    }),
    ar: Object.freeze({
      "common.language": "English",
      "home.tagline": "نظامك الشخصي للمعرفة والحياة",
      "home.chat.title": "المحادثة",
      "home.chat.description": "اسأل عن ملاحظاتك وتقويمك ورسائلك",
      "home.crm.title": "العلاقات",
      "home.crm.description": "أدر علاقاتك واستعرض شبكة معارفك",
      "home.agents.title": "الوكلاء",
      "home.agents.description": "تابع ما يعمل عليه وكلاؤك الآن",
      "home.journal.title": "اليوميات",
      "home.journal.description": "استعرض المشاعر الغالبة على يومياتك وكيف تتغير مع الوقت",
      "chat.new": "محادثة جديدة",
      "chat.search.placeholder": "ابحث في المحادثات...",
      "chat.status.ready": "جاهز",
      "chat.welcome.title": "مرحبًا بك في LifeOS",
      "chat.welcome.body": "مساعدك الشخصي للمعرفة. اسأل عن ملاحظاتك وتقويمك ورسائلك أو أي شيء في خزنتك.",
      "chat.input.placeholder": "اكتب سؤالك...",
      "chat.send": "إرسال الرسالة",
      "chat.people.placeholder": "ابحث عن أشخاص..."
    })
  });

  function normalizeLocale(value) {
    const primary = String(value || "").trim().toLowerCase().split("-")[0];
    return SUPPORTED.has(primary) ? primary : "";
  }

  function storedLocale() {
    try {
      return normalizeLocale(window.localStorage.getItem(STORAGE_KEY));
    } catch (_) {
      return "";
    }
  }

  function queryLocale() {
    try {
      return normalizeLocale(new URL(window.location.href).searchParams.get("lang"));
    } catch (_) {
      return "";
    }
  }

  function initialLocale() {
    return queryLocale() || storedLocale() || DEFAULT_LOCALE;
  }

  function message(locale, key) {
    return MESSAGES[locale]?.[key] ?? MESSAGES[DEFAULT_LOCALE]?.[key] ?? key;
  }

  function applyTranslations(locale, root = document) {
    root.querySelectorAll("[data-i18n]").forEach((node) => {
      node.textContent = message(locale, node.dataset.i18n);
    });
    root.querySelectorAll("[data-i18n-placeholder]").forEach((node) => {
      node.setAttribute("placeholder", message(locale, node.dataset.i18nPlaceholder));
    });
    root.querySelectorAll("[data-i18n-title]").forEach((node) => {
      node.setAttribute("title", message(locale, node.dataset.i18nTitle));
    });
  }

  function applyLocale(locale, { persist = true } = {}) {
    const resolved = normalizeLocale(locale) || DEFAULT_LOCALE;
    document.documentElement.lang = resolved;
    document.documentElement.dir = resolved === "ar" ? "rtl" : "ltr";
    if (persist) {
      try {
        window.localStorage.setItem(STORAGE_KEY, resolved);
      } catch (_) {
        // Locale remains active for this document when storage is unavailable.
      }
    }
    applyTranslations(resolved);
    document.querySelectorAll("[data-locale-toggle]").forEach((button) => {
      button.textContent = message(resolved, "common.language");
      button.setAttribute("lang", resolved === "ar" ? "en" : "ar");
      button.setAttribute("dir", resolved === "ar" ? "ltr" : "rtl");
    });
    window.dispatchEvent(new CustomEvent("lifeos:locale-changed", {
      detail: { locale: resolved, direction: document.documentElement.dir }
    }));
    return resolved;
  }

  function bindLocaleToggles() {
    document.querySelectorAll("[data-locale-toggle]").forEach((button) => {
      if (button.dataset.localeBound === "true") return;
      button.dataset.localeBound = "true";
      button.addEventListener("click", () => {
        const next = document.documentElement.lang === "ar" ? "en" : "ar";
        applyLocale(next);
      });
    });
  }

  function init() {
    bindLocaleToggles();
    applyLocale(initialLocale(), { persist: Boolean(queryLocale() || storedLocale()) });
  }

  window.lifeosI18n = Object.freeze({
    applyLocale,
    currentLocale: () => document.documentElement.lang || DEFAULT_LOCALE,
    t: (key) => message(document.documentElement.lang || DEFAULT_LOCALE, key),
    supportedLocales: () => [...SUPPORTED]
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init, { once: true });
  } else {
    init();
  }
})();
