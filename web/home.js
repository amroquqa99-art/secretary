import {
  applyDocumentLocale,
  normalizeLocale,
  resolveLocale,
  translateAnnotated,
} from './i18n/core.js';

const TRANSLATIONS = {
  en: {
    language: 'Language',
    tagline: 'Your personal knowledge system',
    chat: 'Chat',
    chat_description: 'Ask questions about your notes, calendar, and emails',
    crm: 'CRM',
    crm_description: 'Manage relationships and view your network',
    agents: 'Agents',
    agents_description: 'See what your agents are working on right now',
    journal: 'Journal',
    journal_description: 'See which emotions dominate your entries, and how they trend',
    press: 'Press',
    for_chat: 'for Chat,',
    for_crm: 'for CRM,',
    for_agents: 'for Agents, or',
    for_journal: 'for Journal',
  },
  ar: {
    language: 'اللغة',
    tagline: 'نظامك الشخصي للمعرفة',
    chat: 'المحادثة',
    chat_description: 'اسأل عن ملاحظاتك وتقويمك وبريدك الإلكتروني',
    crm: 'العلاقات',
    crm_description: 'أدر علاقاتك واستعرض شبكة معارفك',
    agents: 'الوكلاء',
    agents_description: 'تابع ما يعمل عليه وكلاؤك الآن',
    journal: 'اليوميات',
    journal_description: 'استكشف المشاعر الغالبة في يومياتك وكيف تتغير مع الوقت',
    press: 'اضغط',
    for_chat: 'للمحادثة،',
    for_crm: 'للعلاقات،',
    for_agents: 'للوكلاء، أو',
    for_journal: 'لليوميات',
  },
};

const state = { locale: 'en', direction: 'ltr' };

function t(key) {
  const active = TRANSLATIONS[state.locale] || TRANSLATIONS.en;
  return active[key] ?? TRANSLATIONS.en[key] ?? key;
}

function applyLocale(locale, { persist = false } = {}) {
  const next = applyDocumentLocale(locale, { persist });
  state.locale = next.locale;
  state.direction = next.direction;
  translateAnnotated(document, t);

  const picker = document.getElementById('localePicker');
  if (picker) picker.value = state.locale;

  return state;
}

function setLocale(locale) {
  const normalized = normalizeLocale(locale);
  if (!normalized) return false;
  applyLocale(normalized, { persist: true });
  return true;
}

function init() {
  applyLocale(resolveLocale());

  const picker = document.getElementById('localePicker');
  if (picker) {
    picker.addEventListener('change', () => {
      setLocale(picker.value);
    });
  }

  document.addEventListener('keydown', (event) => {
    if (event.key === '1') window.location.href = '/chat';
    if (event.key === '2') window.location.href = '/crm';
    if (event.key === '3') window.location.href = '/agents';
    if (event.key === '4') window.location.href = '/journal';
  });

  window.lifeHome = {
    get locale() { return state.locale; },
    get direction() { return state.direction; },
    setLocale,
  };
}

init();
