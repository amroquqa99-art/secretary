// Locale and writing-direction state for the chat surface.
//
// English remains the default. A locale changes only when the operator selects
// one explicitly (currently through ?lang=<locale> or setLocale()). This keeps
// existing installs stable while providing a deterministic seam for the
// language picker and translated resources added in later phases.

const STORAGE_KEY = 'lifeos:locale';
const SUPPORTED_LOCALES = new Set(['en', 'ar']);

const TRANSLATIONS = {
  en: {
    new_chat: 'New chat',
    search_conversations: 'Search conversations...',
    no_conversations: 'No conversations yet',
    agent_threads: 'Agent threads',
    refresh: 'Refresh',
    no_agent_threads: 'No agent threads',
    chat: 'Chat',
    crm: 'CRM',
    agents: 'Agents',
    remember_something: 'Remember something',
    remember: 'Remember',
    usage_stats: 'Click to view usage stats',
    session: 'Session:',
    ready: 'Ready',
    choose_persona: 'Choose persona',
    persona: 'Persona',
    orchestrates_title: 'This persona runs on LifeOS as a background Claude Code session',
    runs_on_lifeos: '⚙️ Runs on LifeOS',
    model_for_chat: 'Model for this chat',
    chat_model: 'Chat model',
    model_auto: '🤖 Auto',
    auto: 'Auto',
    gemma_local: 'Gemma (local)',
    remote: 'Remote',
    text_backend: 'Text backend',
    lifeos_backend: 'LifeOS backend',
    agent_backend: 'Agent backend',
    hermes_backend: 'Hermes backend',
    input_mode: 'Input mode',
    text_input: 'Text input',
    voice_input: 'Voice input',
    text: 'Text',
    voice: 'Voice',
    welcome_title: 'Welcome to LifeOS',
    welcome_body: 'Your personal knowledge assistant. Ask about your notes, calendar, emails, or anything in your vault.',
    calendar_tomorrow: '📅 Calendar tomorrow',
    open_action_items: '✅ Open action items',
    recent_meetings: '📝 Recent meetings',
    attach_files: 'Attach files',
    ask_question: 'Ask a question...',
    send_message: 'Send message',
    stop: 'Stop',
    tap_to_talk: 'Tap to talk',
    cancel_turn: 'Cancel turn',
    mute: 'Mute',
    listening: 'Listening',
    microphone_live: 'Microphone is live',
    language: 'Language',
  },
  ar: {
    new_chat: 'محادثة جديدة',
    search_conversations: 'ابحث في المحادثات...',
    no_conversations: 'لا توجد محادثات بعد',
    agent_threads: 'جلسات الوكلاء',
    refresh: 'تحديث',
    no_agent_threads: 'لا توجد جلسات وكلاء',
    chat: 'المحادثة',
    crm: 'العلاقات',
    agents: 'الوكلاء',
    remember_something: 'احفظ معلومة',
    remember: 'تذكّر',
    usage_stats: 'عرض إحصاءات الاستخدام',
    session: 'الجلسة:',
    ready: 'جاهز',
    choose_persona: 'اختر الشخصية',
    persona: 'الشخصية',
    orchestrates_title: 'هذه الشخصية تعمل عبر LifeOS كجلسة Claude Code في الخلفية',
    runs_on_lifeos: '⚙️ يعمل عبر LifeOS',
    model_for_chat: 'نموذج هذه المحادثة',
    chat_model: 'نموذج المحادثة',
    model_auto: '🤖 تلقائي',
    auto: 'تلقائي',
    gemma_local: 'Gemma (محلي)',
    remote: 'خارجي',
    text_backend: 'محرك النص',
    lifeos_backend: 'محرك LifeOS',
    agent_backend: 'محرك Agent',
    hermes_backend: 'محرك Hermes',
    input_mode: 'وضع الإدخال',
    text_input: 'إدخال نصي',
    voice_input: 'إدخال صوتي',
    text: 'نص',
    voice: 'صوت',
    welcome_title: 'مرحبًا بك في LifeOS',
    welcome_body: 'مساعدك الشخصي للمعرفة. اسأل عن ملاحظاتك، تقويمك، بريدك الإلكتروني، أو أي شيء في خزنتك.',
    calendar_tomorrow: '📅 تقويم الغد',
    open_action_items: '✅ الإجراءات المفتوحة',
    recent_meetings: '📝 الاجتماعات الأخيرة',
    attach_files: 'إرفاق ملفات',
    ask_question: 'اكتب سؤالك...',
    send_message: 'إرسال الرسالة',
    stop: 'إيقاف',
    tap_to_talk: 'اضغط للتحدث',
    cancel_turn: 'إلغاء الطلب',
    mute: 'كتم الصوت',
    listening: 'الاستماع',
    microphone_live: 'الميكروفون يعمل',
    language: 'اللغة',
  },
};

let localePicker = null;

export function t(key) {
  const active = TRANSLATIONS[localeState.locale] || TRANSLATIONS.en;
  return active[key] ?? TRANSLATIONS.en[key] ?? key;
}

export function translateChatUi(root = document) {
  root.querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = t(el.dataset.i18n);
  });
  for (const [attribute, datasetKey] of [
    ['placeholder', 'i18nPlaceholder'],
    ['title', 'i18nTitle'],
    ['aria-label', 'i18nAriaLabel'],
  ]) {
    root.querySelectorAll(`[data-${datasetKey.replace(/[A-Z]/g, c => '-' + c.toLowerCase())}]`).forEach((el) => {
      el.setAttribute(attribute, t(el.dataset[datasetKey]));
    });
  }
}

export function initLocalePicker(picker) {
  localePicker = picker || null;
  if (!localePicker) return;
  localePicker.value = localeState.locale;
  localePicker.addEventListener('change', () => {
    setLocale(localePicker.value);
  });
}


export function normalizeLocale(value) {
  if (!value || typeof value !== 'string') return null;
  const primary = value.trim().toLowerCase().split(/[-_]/, 1)[0];
  return SUPPORTED_LOCALES.has(primary) ? primary : null;
}

export function directionForLocale(locale) {
  return normalizeLocale(locale) === 'ar' ? 'rtl' : 'ltr';
}

function storedLocale() {
  try {
    return normalizeLocale(window.localStorage.getItem(STORAGE_KEY));
  } catch (e) {
    return null;
  }
}

function queryLocale() {
  try {
    const params = new URLSearchParams(window.location.search);
    return normalizeLocale(params.get('lang'));
  } catch (e) {
    return null;
  }
}

function persistLocale(locale) {
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch (e) {
    // Storage may be unavailable in private/locked-down browsing contexts.
  }
}

export function resolveLocale() {
  const explicit = queryLocale();
  if (explicit) {
    persistLocale(explicit);
    return explicit;
  }
  return storedLocale() || 'en';
}

export const localeState = { locale: 'en', direction: 'ltr' };

export function applyLocale(locale = resolveLocale(), { persist = false } = {}) {
  const normalized = normalizeLocale(locale) || 'en';
  const direction = directionForLocale(normalized);

  document.documentElement.lang = normalized;
  document.documentElement.dir = direction;
  localeState.locale = normalized;
  localeState.direction = direction;

  if (persist) persistLocale(normalized);
  return localeState;
}

export function setLocale(locale) {
  const normalized = normalizeLocale(locale);
  if (!normalized) return false;
  applyLocale(normalized, { persist: true });
  translateChatUi();
  if (localePicker) localePicker.value = normalized;
  return true;
}

applyLocale();
