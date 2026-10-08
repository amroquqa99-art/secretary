// Blocking pre-paint locale bootstrap for bilingual LifeOS surfaces.
//
// This intentionally has no imports: it runs in <head> before CSS paints, so
// an Arabic page never flashes or animates from LTR to RTL. Runtime modules
// resolve the same value again and own all later switching/persistence.
(() => {
  const directions = { en: 'ltr', ar: 'rtl' };

  function normalize(value) {
    if (!value || typeof value !== 'string') return null;
    const primary = value.trim().toLowerCase().split(/[-_]/, 1)[0];
    return Object.prototype.hasOwnProperty.call(directions, primary) ? primary : null;
  }

  let locale = null;
  try {
    locale = normalize(new URLSearchParams(window.location.search).get('lang'));
    if (!locale) locale = normalize(window.localStorage.getItem('lifeos:locale'));
  } catch (e) {
    locale = null;
  }

  if (!locale) locale = 'en';
  document.documentElement.lang = locale;
  document.documentElement.dir = directions[locale];
})();
