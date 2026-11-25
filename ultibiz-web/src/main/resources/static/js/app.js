/* UltiBiz application JS — htmx wiring, CSRF, toasts, helpers. No deps. */
(function () {
  'use strict';

  // --- CSRF injection for htmx requests -----------------------------------
  // Spring Security exposes the token via <meta name="_csrf"> and the header
  // name via <meta name="_csrf_header">.
  document.body.addEventListener('htmx:configRequest', function (event) {
    var tokenMeta = document.querySelector('meta[name="_csrf"]');
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (tokenMeta && headerMeta) {
      event.detail.headers[headerMeta.content] = tokenMeta.content;
    }
  });

  // --- Error toast ---------------------------------------------------------
  function toast(message) {
    var el = document.createElement('div');
    el.className = 'ub-toast';
    el.setAttribute('role', 'alert');
    el.style.cssText =
      'position:fixed;bottom:1rem;right:1rem;z-index:1080;max-width:22rem;' +
      'padding:.6rem 1rem;border-radius:.5rem;' +
      'background:var(--ub-surface,#fff);color:var(--ub-text,#000);' +
      'border:1px solid var(--ub-border,#ccc);box-shadow:0 2px 8px rgba(0,0,0,.25);';
    el.textContent = message;
    document.body.appendChild(el);
    setTimeout(function () { el.remove(); }, 5000);
  }

  document.body.addEventListener('htmx:responseError', function () {
    toast(document.documentElement.getAttribute('lang') === 'id'
      ? 'Permintaan gagal. Silakan coba lagi.'
      : 'Request failed. Please try again.');
  });

  // --- Public helpers ------------------------------------------------------
  window.UltiBiz = window.UltiBiz || {};
  window.UltiBiz.confirmDelete = function (form) {
    var message = form.getAttribute('data-confirm') || 'Delete this record?';
    if (window.confirm(message)) {
      if (form.requestSubmit) {
        form.requestSubmit();
      } else {
        form.submit();
      }
    }
    return false;
  };
})();

// --- journal editor: live debit/credit balance ------------------------------
(function () {
  'use strict';
  function recalc() {
    var debit = 0, credit = 0;
    document.querySelectorAll('.journal-debit').forEach(function (el) {
      debit += parseFloat(el.value || '0') || 0;
    });
    document.querySelectorAll('.journal-credit').forEach(function (el) {
      credit += parseFloat(el.value || '0') || 0;
    });
    var d = document.getElementById('journal-total-debit');
    var c = document.getElementById('journal-total-credit');
    var diff = document.getElementById('journal-difference');
    if (d) { d.textContent = debit.toFixed(2); }
    if (c) { c.textContent = credit.toFixed(2); }
    if (diff) {
      var delta = debit - credit;
      diff.textContent = delta.toFixed(2);
      diff.classList.toggle('text-danger', Math.abs(delta) > 0.005);
      diff.classList.toggle('text-success', Math.abs(delta) <= 0.005);
    }
  }
  document.addEventListener('input', function (event) {
    var target = event.target;
    if (target && (target.classList.contains('journal-debit') || target.classList.contains('journal-credit'))) {
      recalc();
    }
  });
  document.addEventListener('DOMContentLoaded', recalc);
})();

// --- sidebar: open the group of the current page ---------------------------
// The server renders every group collapsed; here we find the link that best
// matches the current path (longest href wins), mark it active and open its
// parent group.
(function () {
  'use strict';
  var path = window.location.pathname;
  var best = null;
  document.querySelectorAll('.ub-sidebar a.nav-link[href]').forEach(function (link) {
    var href = link.getAttribute('href');
    if (!href || href === '#') { return; }
    var matches = href === '/' ? path === '/' : path.indexOf(href) === 0;
    if (matches && (!best || href.length > best.getAttribute('href').length)) {
      best = link;
    }
  });
  if (best) {
    best.classList.add('active');
    best.setAttribute('aria-current', 'page');
    var group = best.closest('.collapse');
    if (group) {
      group.classList.add('show');
      var toggle = document.querySelector('[data-bs-target="#' + group.id + '"]');
      if (toggle) { toggle.setAttribute('aria-expanded', 'true'); }
    }
  }
})();

// --- service worker -------------------------------------------------------
// The offline shell is registered after the load, so it never delays the page.
(function () {
  'use strict';
  if (!('serviceWorker' in navigator)) { return; }
  window.addEventListener('load', function () {
    navigator.serviceWorker.register('/sw.js').catch(function () {
      // no offline shell, the app still works online
    });
  });
})();
