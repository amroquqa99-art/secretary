// Shared locale runtime for LifeOS web surfaces.
//
// Only surfaces that opt into this module (and the pre-paint bootstrap) become
// bilingual. Untranslated pages stay untouched until their own copy/RTL work
// lands, avoiding an English UI being flipped to RTL merely because Arabic was
// selected elsewhere.
export const STORAGE_KEY = 'lifeos:locale';
export const SUPPORTED_LOCALES = new Set(['en', 'ar']);

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

export function persistLocale(locale) {
  const normalized = normalizeLocale(locale);
  if (!normalized) return false;
  try {
    window.localStorage.setItem(STORAGE_KEY, normalized);
    return true;
  } catch (e) {
    return false;
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

export function applyDocumentLocale(locale = resolveLocale(), { persist = false } = {}) {
  const normalized = normalizeLocale(locale) || 'en';
  const direction = directionForLocale(normalized);

  document.documentElement.lang = normalized;
  document.documentElement.dir = direction;
  if (persist) persistLocale(normalized);

  return { locale: normalized, direction };
}

export function translateAnnotated(root, translate) {
  root.querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = translate(el.dataset.i18n);
  });

  for (const [attribute, datasetKey] of [
    ['placeholder', 'i18nPlaceholder'],
    ['title', 'i18nTitle'],
    ['aria-label', 'i18nAriaLabel'],
  ]) {
    const dataAttribute = datasetKey.replace(/[A-Z]/g, c => '-' + c.toLowerCase());
    root.querySelectorAll(`[data-${dataAttribute}]`).forEach((el) => {
      el.setAttribute(attribute, translate(el.dataset[datasetKey]));
    });
  }
}
