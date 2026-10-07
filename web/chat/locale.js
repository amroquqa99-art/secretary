// Locale and writing-direction state for the chat surface.
//
// English remains the default. A locale changes only when the operator selects
// one explicitly (currently through ?lang=<locale> or setLocale()). This keeps
// existing installs stable while providing a deterministic seam for the
// language picker and translated resources added in later phases.

const STORAGE_KEY = 'lifeos:locale';
const SUPPORTED_LOCALES = new Set(['en', 'ar']);

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
  return true;
}

applyLocale();
