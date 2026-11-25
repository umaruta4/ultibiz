/* UltiBiz — offline-first sync client.
 *
 * A dependency-free IndexedDB wrapper with three stores:
 *   outbox    — the mutations the user made while offline
 *   snapshots — the last known server state of every document
 *   meta      — the sync cursor, the client id and the conflict list
 *
 * The outbox is pushed to /api/sync/push, the delta feed is pulled from
 * /api/sync/changes. Operations that the server rejects (a financial posting
 * while offline, for example) stay in the outbox with their reason, so the
 * status bar can tell the operator what went wrong.
 */
(function () {
  'use strict';

  var DB_NAME = 'ultibiz-sync';
  var DB_VERSION = 1;
  var STORES = { outbox: 'outbox', snapshots: 'snapshots', meta: 'meta' };
  var CLIENT_ID_KEY = 'clientId';
  var CURSOR_KEY = 'cursor';
  var CONFLICT_KEY = 'conflicts';

  // ---------------------------------------------------------------- IndexedDB

  function openDb() {
    return new Promise(function (resolve, reject) {
      if (!window.indexedDB) {
        reject(new Error('IndexedDB is not available'));
        return;
      }
      var request = window.indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = function () {
        var db = request.result;
        if (!db.objectStoreNames.contains(STORES.outbox)) {
          db.createObjectStore(STORES.outbox, { keyPath: 'opId' });
        }
        if (!db.objectStoreNames.contains(STORES.snapshots)) {
          db.createObjectStore(STORES.snapshots, { keyPath: 'key' });
        }
        if (!db.objectStoreNames.contains(STORES.meta)) {
          db.createObjectStore(STORES.meta, { keyPath: 'key' });
        }
      };
      request.onsuccess = function () { resolve(request.result); };
      request.onerror = function () { reject(request.error); };
    });
  }

  function withStore(name, mode, work) {
    return openDb().then(function (db) {
      return new Promise(function (resolve, reject) {
        var tx = db.transaction(name, mode);
        var store = tx.objectStore(name);
        var result = work(store);
        tx.oncomplete = function () {
          db.close();
          resolve(result && result.result !== undefined ? result.result : result);
        };
        tx.onerror = function () { db.close(); reject(tx.error); };
      });
    });
  }

  function put(store, value) {
    return withStore(store, 'readwrite', function (s) { return s.put(value); });
  }

  function get(store, key) {
    return withStore(store, 'readonly', function (s) { return s.get(key); });
  }

  function getAll(store) {
    return withStore(store, 'readonly', function (s) { return s.getAll(); });
  }

  function remove(store, key) {
    return withStore(store, 'readwrite', function (s) { return s.delete(key); });
  }

  // ------------------------------------------------------------------- meta

  function clientId() {
    return get(STORES.meta, CLIENT_ID_KEY).then(function (record) {
      if (record && record.value) {
        return record.value;
      }
      var id = 'web-' + Math.random().toString(36).slice(2, 10)
        + '-' + (window.crypto && window.crypto.randomUUID
          ? window.crypto.randomUUID().slice(0, 8) : Date.now().toString(36));
      return put(STORES.meta, { key: CLIENT_ID_KEY, value: id }).then(function () { return id; });
    });
  }

  function cursor() {
    return get(STORES.meta, CURSOR_KEY).then(function (record) {
      return record && record.value ? record.value : 0;
    });
  }

  function setCursor(value) {
    return put(STORES.meta, { key: CURSOR_KEY, value: value });
  }

  // ----------------------------------------------------------------- outbox

  /** Queues a mutation; it is pushed the next time the client is online. */
  function enqueue(entity, entityId, operation, baseVersion, payload) {
    return clientId().then(function (id) {
      var op = {
        opId: id + ':' + Date.now().toString(36) + ':' + Math.random().toString(36).slice(2, 8),
        clientId: id,
        entity: entity,
        entityId: entityId,
        operation: operation || 'UPDATE',
        baseVersion: baseVersion || 0,
        payload: payload || {},
        createdAt: new Date().toISOString(),
        attempts: 0,
        lastError: null
      };
      return put(STORES.outbox, op).then(function () { return op; });
    });
  }

  function outbox() {
    return getAll(STORES.outbox).then(function (items) {
      return (items || []).sort(function (a, b) {
        return String(a.createdAt).localeCompare(String(b.createdAt));
      });
    });
  }

  // ------------------------------------------------------------------ push

  function push() {
    return outbox().then(function (items) {
      if (!items.length) {
        return { results: [], pushed: 0 };
      }
      var body = {
        clientId: items[0].clientId,
        operations: items.map(function (item) {
          return {
            opId: item.opId,
            entity: item.entity,
            entityId: item.entityId,
            operation: item.operation,
            baseVersion: item.baseVersion,
            payload: item.payload
          };
        })
      };
      return fetch('/api/sync/push', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': csrfToken()
        },
        body: JSON.stringify(body)
      }).then(function (response) {
        if (!response.ok) {
          throw new Error('The server refused the push (HTTP ' + response.status + ')');
        }
        return response.json();
      }).then(function (answer) {
        var results = answer.results || [];
        return Promise.all(results.map(function (result) {
          if (result.result === 'APPLIED' || result.result === 'DUPLICATE') {
            return remove(STORES.outbox, result.opId).then(function () { return result; });
          }
          if (result.result === 'CONFLICT') {
            return recordConflict(result).then(function () {
              return remove(STORES.outbox, result.opId).then(function () { return result; });
            });
          }
          // REJECTED: keep it in the outbox with the reason
          return outbox().then(function (items) {
            var item = items.filter(function (i) { return i.opId === result.opId; })[0];
            if (item) {
              item.attempts = (item.attempts || 0) + 1;
              item.lastError = result.message || 'rejected';
              return put(STORES.outbox, item);
            }
            return null;
          }).then(function () { return result; });
        })).then(function (all) {
          return { results: all, pushed: all.length };
        });
      });
    });
  }

  function recordConflict(result) {
    return get(STORES.meta, CONFLICT_KEY).then(function (record) {
      var list = record && record.value ? record.value : [];
      list.push({
        opId: result.opId,
        entity: result.entity,
        entityId: result.entityId,
        fields: result.conflictingFields || [],
        message: result.message
      });
      return put(STORES.meta, { key: CONFLICT_KEY, value: list });
    });
  }

  // ------------------------------------------------------------------ pull

  function pull() {
    return cursor().then(function (since) {
      return fetch('/api/sync/changes?since=' + since, {
        headers: { 'X-CSRF-TOKEN': csrfToken() }
      }).then(function (response) {
        if (!response.ok) {
          throw new Error('The server refused the pull (HTTP ' + response.status + ')');
        }
        return response.json();
      }).then(function (answer) {
        var changes = answer.changes || [];
        return Promise.all(changes.map(function (change) {
          return put(STORES.snapshots, {
            key: change.entity + ':' + change.entityId,
            entity: change.entity,
            entityId: change.entityId,
            operation: change.operation,
            rowVersion: change.rowVersion,
            payload: change.payload,
            updatedAt: change.createdAt
          });
        })).then(function () {
          return setCursor(answer.cursor || since).then(function () {
            return { changes: changes.length, cursor: answer.cursor };
          });
        });
      });
    });
  }

  /** Pushes the outbox and pulls the delta feed. */
  function syncNow() {
    return push().then(function (pushed) {
      return pull().then(function (pulled) {
        return { pushed: pushed.pushed, pulled: pulled.changes };
      });
    });
  }

  // -------------------------------------------------------------- status

  function status() {
    return outbox().then(function (items) {
      var failed = items.filter(function (i) { return (i.attempts || 0) > 0; });
      return {
        online: navigator.onLine !== false,
        pending: items.length,
        failed: failed.length,
        reasons: failed.map(function (i) { return i.lastError; })
      };
    });
  }

  function renderStatus() {
    return status().then(function (state) {
      var text = (state.online ? 'online' : 'offline')
        + ' - ' + state.pending + ' pending / ' + state.failed + ' failed';
      document.querySelectorAll('[data-sync-status]').forEach(function (el) {
        el.textContent = text;
        el.classList.toggle('text-danger', !state.online);
        el.classList.toggle('text-warning', state.online && state.failed > 0);
      });
      return state;
    });
  }

  // ------------------------------------------------------------ offline UI

  function setOffline(offline) {
    document.querySelectorAll('[data-offline-banner]').forEach(function (el) {
      el.hidden = !offline;
    });
    document.body.classList.toggle('ub-offline', offline);
  }

  /** The operations that may not run without a connection. */
  function isOnlineOnly(form) {
    return form && form.hasAttribute('data-online-only');
  }

  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (navigator.onLine === false && isOnlineOnly(form)) {
      event.preventDefault();
      var message = form.getAttribute('data-offline-message')
        || 'This operation needs an online connection.';
      window.alert(message);
    }
  });

  window.addEventListener('online', function () {
    setOffline(false);
    renderStatus();
  });
  window.addEventListener('offline', function () { setOffline(true); });

  function csrfToken() {
    var meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.content : '';
  }

  // ------------------------------------------------------------------ init

  function init() {
    setOffline(navigator.onLine === false);
    renderStatus();
    setInterval(renderStatus, 30000);
    window.addEventListener('online', function () { syncNow().catch(function () { }); });
  }

  window.UltiBizSync = {
    enqueue: enqueue,
    push: push,
    pull: pull,
    syncNow: syncNow,
    status: status,
    outbox: outbox,
    cursor: cursor,
    init: init
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
