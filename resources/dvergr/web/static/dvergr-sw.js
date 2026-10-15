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

// Whether `url` gets the session: this origin only (parsed, never a prefix
// test), and never a room app (sandboxed, opened by capability) or this file.
function attaches(url, origin) {
  var u;
  try { u = new URL(url); } catch (e) { return false; }
  return u.origin === origin && u.pathname.indexOf('/apps/') !== 0 && u.pathname !== '/dvergr-sw.js';
}

if (typeof self !== 'undefined' && self.addEventListener) {
  self.addEventListener('install', function () { self.skipWaiting(); });
  self.addEventListener('activate', function (e) { e.waitUntil(self.clients.claim()); });
  self.addEventListener('fetch', function (e) {
    var req = e.request;
    if (!attaches(req.url, self.location.origin)) return;
    e.respondWith((async function () {
      var sid = await sessionId();
      if (!sid || req.headers.has('Authorization')) return fetch(req);
      var headers = new Headers(req.headers);
      headers.set('Authorization', 'Bearer ' + sid);
      var init = {
        method: req.method,
        headers: headers,
        credentials: 'same-origin',
        cache: req.cache,
        // a navigation follows its redirects itself (and asks us again)
        redirect: req.mode === 'navigate' ? 'manual' : req.redirect
      };
      if (req.method !== 'GET' && req.method !== 'HEAD') init.body = await req.clone().arrayBuffer();
      return fetch(new Request(req.url, init));
    })());
  });
}

if (typeof module !== 'undefined') module.exports = { attaches: attaches };
