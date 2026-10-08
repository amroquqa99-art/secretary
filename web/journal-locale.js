import {
  applyDocumentLocale,
  formattingLocale,
  normalizeLocale,
  resolveLocale,
  translateAnnotated,
} from './i18n/core.js';

const TRANSLATIONS = {
  en: {
    page_title: 'LifeOS · Journal Emotion Wheel',
    title: 'Journal Emotion Wheel',
    language: 'Language',
    trends: 'Trend views',
    home: 'Home',
    day: 'Day',
    week: 'Week',
    month: 'Month',
    quarter: 'Quarter',
    all_time: 'All-time',
    loading: 'Loading…',
    through: 'through {end}',
    date_range: '{start} to {end}',
    no_entries: 'No journal entries in this window ({range}).',
    no_emotions: '{total} journal {entry_word} in this window ({range}), but none logged emotion data.',
    all_emotions: '{total} journal {entry_word} in this window ({range}){caveat}',
    some_emotions: '{emotion} of {total} entries had emotion data in this window ({range}) — percentages below are out of {emotion}{caveat}',
    entry_one: 'entry',
    entry_many: 'entries',
    thin_all: ' — small sample, read the wheel loosely.',
    thin_some: '; small sample, read the wheel loosely.',
    failed: 'Failed to load: {error}',
    api_error: 'API error: {status}',
    network_error: 'Network request failed',
  },
  ar: {
    page_title: 'LifeOS · عجلة مشاعر اليوميات',
    title: 'عجلة مشاعر اليوميات',
    language: 'اللغة',
    trends: 'تغيرات المشاعر',
    home: 'الرئيسية',
    day: 'يوم',
    week: 'أسبوع',
    month: 'شهر',
    quarter: 'ربع سنة',
    all_time: 'كل الفترات',
    loading: 'جارٍ التحميل…',
    through: 'حتى {end}',
    date_range: 'من {start} إلى {end}',
    no_entries: 'لا توجد مدخلات يوميات في هذه الفترة ({range}).',
    no_emotions: 'مدخلات اليوميات في هذه الفترة ({range}): {total}، لكنها لا تحتوي على بيانات مشاعر.',
    all_emotions: 'مدخلات اليوميات في هذه الفترة ({range}): {total}، وكلها تحتوي على بيانات مشاعر{caveat}',
    some_emotions: 'مدخلات اليوميات التي تحتوي على بيانات مشاعر في هذه الفترة ({range}): {emotion} من {total} — النسب أدناه محسوبة من {emotion}{caveat}',
    entry_one: 'مدخل',
    entry_many: 'مدخلات',
    thin_all: ' — عينة صغيرة، اقرأ العجلة بحذر.',
    thin_some: '؛ عينة صغيرة، اقرأ العجلة بحذر.',
    failed: 'تعذر التحميل: {error}',
    api_error: 'خطأ من الخادم: {status}',
    network_error: 'تعذر الاتصال بالخادم',
  },
};

let locale = 'en';
let pageTranslations = {};

export function t(key, values = {}) {
  const template = pageTranslations[locale]?.[key] ?? TRANSLATIONS[locale][key]
    ?? pageTranslations.en?.[key] ?? TRANSLATIONS.en[key] ?? key;
  return template.replace(/\{(\w+)\}/g, (match, name) => values[name] ?? match);
}

export function formatNumber(value, options = {}) {
  return new Intl.NumberFormat(formattingLocale(locale), options).format(value);
}

export function formatMonth(value) {
  return new Intl.DateTimeFormat(formattingLocale(locale), { month: 'short', timeZone: 'UTC' }).format(value);
}

export function formatPercent(value) {
  return new Intl.NumberFormat(formattingLocale(locale), {
    style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1,
  }).format(value);
}

export function formatDate(value) {
  // English retains the ISO labels. Calendar dates have no time zone:
  // formatting at UTC prevents a west-of-UTC browser showing the prior day.
  if (locale === 'en' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
  return new Intl.DateTimeFormat(formattingLocale(locale), {
    year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC',
  }).format(new Date(`${value}T00:00:00Z`));
}

export function initJournalLocale(onChange, translations = {}) {
  pageTranslations = translations;
  const picker = document.getElementById('localePicker');
  function apply(value, persist = false) {
    locale = applyDocumentLocale(value, { persist }).locale;
    translateAnnotated(document, t);
    picker.value = locale;
  }
  apply(resolveLocale());
  picker.addEventListener('change', () => {
    const next = normalizeLocale(picker.value);
    if (!next) return;
    apply(next, true);
    onChange();
  });
}
