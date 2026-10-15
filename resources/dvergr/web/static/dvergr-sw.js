// dvergr's service worker: it attaches the browser session to this origin's
// requests. The session id lives in IndexedDB (this origin's, i.e. this
// port's, shared by its tabs; never a cookie, which every port of the host
// would receive). Every same-origin request but a room app's gets
// `Authorization: Bearer <id>`: navigations, form posts, htmx, fetch, new tabs,
// back and forward, so pages render natively.
'use strict';

var DB = 'dvergr', STORE = 'session';

function sessionId() {
  return new Promise(function (resolve) {
    var open = indexedDB.open(DB, 1);
    open.onupgradeneeded = function () { open.result.createObjectStore(STORE); };
    open.onerror = function () { resolve(null); };
    open.onsuccess = function () {
      try {
        var get = open.result.transaction(STORE, 'readonly').objectStore(STORE).get('sid');
        get.onsuccess = function () { resolve(get.result || null); };
        get.onerror = function () { resolve(null); };
      } catch (e) { resolve(null); }
    };
  });
}

// Whether request `req` (url, method, mode, referrer) gets the session.
// Only this origin (parsed, never a prefix test), never a room app (sandboxed,
// opened by capability) or this file. A navigation reaches this worker
// whoever started it, so a request that changes something (any method but
// GET/HEAD) must also come from this origin: its referrer, which a page can
// strip but not forge, must be ours, and an empty one fails closed. A GET
// (a bookmark, a new tab, a link from elsewhere) gets the session: no GET
// route changes anything (`dvergr.web.server`).
function attaches(req, origin) {
  var u;
  try { u = new URL(req.url); } catch (e) { return false; }
  if (u.origin !== origin || u.pathname.indexOf('/apps/') === 0 || u.pathname === '/dvergr-sw.js') return false;
  var method = String(req.method || 'GET').toUpperCase();
  if (method === 'GET' || method === 'HEAD') return true;
  if (!req.referrer || req.referrer === 'about:client') return false;
  try { return new URL(req.referrer).origin === origin; } catch (e) { return false; }
}

if (typeof self !== 'undefined' && self.addEventListener) {
  self.addEventListener('install', function () { self.skipWaiting(); });
  self.addEventListener('activate', function (e) { e.waitUntil(self.clients.claim()); });
  self.addEventListener('fetch', function (e) {
    var req = e.request;
    if (!attaches(req, self.location.origin)) return;
    e.respondWith((async function () {
      var sid = await sessionId();
      if (!sid || req.headers.has('Authorization')) return fetch(req);
      var headers = new Headers(req.headers);
      headers.set('Authorization', 'Bearer ' + sid);
      // the server's backstop: a session's changes must say they came through
      // this check (an older worker without it never adds this)
      headers.set('X-Dvergr-Request', '1');
      var init = {
        method: req.method,
        headers: headers,
        credentials: 'same-origin',
        // 'only-if-cached' is for same-origin mode only, which a new Request cannot take
        cache: req.cache === 'only-if-cached' ? 'default' : req.cache,
        integrity: req.integrity,
        keepalive: req.keepalive,
        signal: req.signal,
        // a navigation follows its redirects itself (and asks us again)
        redirect: req.mode === 'navigate' ? 'manual' : req.redirect
      };
      if (req.method !== 'GET' && req.method !== 'HEAD') init.body = await req.clone().arrayBuffer();
      return fetch(new Request(req.url, init));
    })());
  });
}

if (typeof module !== 'undefined') module.exports = { attaches: attaches };
