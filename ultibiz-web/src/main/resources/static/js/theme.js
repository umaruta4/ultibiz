/* UltiBiz theme bootstrap — runs before first paint to avoid FOUC. */
(function () {
  'use strict';

  var STORAGE_KEY = 'ultibiz-theme';

  function systemTheme() {
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      return 'dark';
    }
    return 'light';
  }

  function storedTheme() {
    try {
      var value = window.localStorage.getItem(STORAGE_KEY);
      return value === 'dark' || value === 'light' ? value : null;
    } catch (e) {
      return null;
    }
  }

  function apply(theme) {
    document.documentElement.dataset.bsTheme = theme;
    syncToggles(theme);
  }

  function syncToggles(theme) {
    var isDark = theme === 'dark';
    var buttons = document.querySelectorAll('[data-theme-toggle]');
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].setAttribute('aria-pressed', String(isDark));
      var icon = buttons[i].querySelector('[data-theme-icon]');
      if (icon) {
        icon.textContent = isDark ? '\u2600' : '\u263E'; // sun : moon
      }
    }
  }

  function persist(theme) {
    try {
      window.localStorage.setItem(STORAGE_KEY, theme);
    } catch (e) {
      /* storage unavailable — session-only theme */
    }
  }

  function csrfHeaders() {
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    var tokenMeta = document.querySelector('meta[name="_csrf"]');
    var headers = { 'Content-Type': 'application/json' };
    if (headerMeta && tokenMeta && headerMeta.content && tokenMeta.content) {
      headers[headerMeta.content] = tokenMeta.content;
    }
    return headers;
  }

  function notifyServer(theme) {
    if (!window.fetch) {
      return;
    }
    try {
      fetch('/api/me/theme', {
        method: 'POST',
        headers: csrfHeaders(),
        credentials: 'same-origin',
        keepalive: true,
        body: JSON.stringify({ theme: theme })
      }).catch(function () { /* fire-and-forget */ });
    } catch (e) {
      /* fire-and-forget */
    }
  }

  function toggle() {
    var current = document.documentElement.dataset.bsTheme === 'dark' ? 'dark' : 'light';
    var next = current === 'dark' ? 'light' : 'dark';
    persist(next);
    apply(next);
    notifyServer(next);
    return next;
  }

  // Initial application: stored preference wins, otherwise follow the OS.
  var initial = storedTheme() || systemTheme();
  apply(initial);

  // Follow OS changes only while the user has not made an explicit choice.
  if (window.matchMedia) {
    var media = window.matchMedia('(prefers-color-scheme: dark)');
    var onChange = function (event) {
      if (!storedTheme()) {
        apply(event.matches ? 'dark' : 'light');
      }
    };
    if (media.addEventListener) {
      media.addEventListener('change', onChange);
    } else if (media.addListener) {
      media.addListener(onChange);
    }
  }

  // Click wiring lives here (not inline onclick) so CSP script-src 'self' holds.
  function bindToggles() {
    var buttons = document.querySelectorAll('[data-theme-toggle]');
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].addEventListener('click', toggle);
    }
    syncToggles(document.documentElement.dataset.bsTheme);
  }

  // Re-sync toggle buttons once the DOM is ready (IIFE ran in <head>).
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindToggles);
  } else {
    bindToggles();
  }

  window.UltiBizTheme = {
    toggle: toggle,
    current: function () {
      return document.documentElement.dataset.bsTheme;
    }
  };
})();
