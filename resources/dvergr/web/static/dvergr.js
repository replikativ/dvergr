// The dashboard's page script (served from this origin: the pages allow no
// inline script). The session itself is attached by the service worker
// (dvergr-sw.js); this keeps it: it takes a new session id from the login
// redirect's fragment, registers the worker, logs out, and handles a session
// the server no longer accepts.
(function () {
  'use strict';
  var DB = 'dvergr', STORE = 'session', RETRY = 'dvergr-retried';

  function db() {
    return new Promise(function (resolve, reject) {
      var open = indexedDB.open(DB, 1);
      open.onupgradeneeded = function () { open.result.createObjectStore(STORE); };
      open.onsuccess = function () { resolve(open.result); };
      open.onerror = function () { reject(open.error); };
    });
  }
  function put(sid) {
    return db().then(function (d) {
      return new Promise(function (resolve) {
        var tx = d.transaction(STORE, 'readwrite'), st = tx.objectStore(STORE);
        if (sid) st.put(sid, 'sid'); else st.delete('sid');
        tx.oncomplete = function () { resolve(); };
        tx.onerror = function () { resolve(); };
      });
    });
  }
  function get() {
    return db().then(function (d) {
      return new Promise(function (resolve) {
        var g = d.transaction(STORE, 'readonly').objectStore(STORE).get('sid');
        g.onsuccess = function () { resolve(g.result || null); };
        g.onerror = function () { resolve(null); };
      });
    });
  }
  function worker() {
    if (!('serviceWorker' in navigator)) return Promise.reject(new Error('this browser has no service workers (an http page that is not localhost?)'));
    return navigator.serviceWorker.register('/dvergr-sw.js', { scope: '/' })
      .then(function () { return navigator.serviceWorker.ready; });
  }
  function showLogin(message) {
    var box = document.getElementById('login');
    if (box) box.style.display = 'block';
    var note = document.getElementById('login-note');
    if (note && message) note.textContent = message;
  }
  function loggedOut() { return put(null).then(function () { location.replace('/'); }); }

  var state = document.documentElement.getAttribute('data-dvergr-auth');
  var m = location.hash.match(/^#sid=([0-9a-f]{64})$/);
  var ready = m
    ? put(m[1]).then(function () { history.replaceState(null, '', location.pathname + location.search); })
    : Promise.resolve();

  ready.then(function () {
    if (state === 'rejected') {
      // the server refused the session this browser holds: drop it, never retry it
      sessionStorage.removeItem(RETRY);
      return put(null).then(function () { showLogin('Your session ended; log in again.'); });
    }
    if (state === 'shell') {
      return get().then(function (sid) {
        if (!sid) return showLogin();
        if (sessionStorage.getItem(RETRY)) {
          sessionStorage.removeItem(RETRY);
          return showLogin('The service worker did not attach the session; log in again.');
        }
        return worker().then(function () {
          // once: the reload goes through the worker, with the session
          sessionStorage.setItem(RETRY, '1');
          location.reload();
        }).catch(function (e) { showLogin('This browser cannot keep the session: ' + e.message); });
      });
    }
    sessionStorage.removeItem(RETRY);
    return worker().catch(function () {});
  });

  document.addEventListener('htmx:responseError', function (e) {
    if (e.detail && e.detail.xhr && e.detail.xhr.status === 401) loggedOut();
  });
  document.addEventListener('submit', function (e) {
    var c = e.target.getAttribute && e.target.getAttribute('data-confirm');
    if (c && !window.confirm(c)) e.preventDefault();
  });
  document.addEventListener('click', function (e) {
    var t = e.target.closest ? e.target.closest('[data-dvergr-logout],[data-app]') : null;
    if (!t) return;
    e.preventDefault();
    if (t.hasAttribute('data-dvergr-logout')) {
      fetch('/logout', { method: 'POST' }).then(loggedOut, loggedOut);
      return;
    }
    // a room's app: a capability link minted now (the app is sandboxed and
    // carries no session), opened in a new window without an opener
    var w = window.open('about:blank', '_blank');
    fetch('/api/app-link?room=' + encodeURIComponent(t.getAttribute('data-app')))
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (j) { if (!j.url) throw new Error('no link'); if (w) { w.opener = null; w.location = j.url; } })
      .catch(function (err) { if (w) w.close(); t.title = 'could not open the app: ' + err.message; });
  });

  // ---- the room page ----
  function roomPage() {
    var box = document.querySelector('.msg-list');
    if (box) {
      // keep the chat at the newest message while the user is near the bottom
      var stick = true;
      box.addEventListener('scroll', function () { stick = (box.scrollHeight - box.scrollTop - box.clientHeight) < 80; });
      document.body.addEventListener('htmx:afterSwap', function (e) { if (e.target === box && stick) box.scrollTop = box.scrollHeight; });
    }
    var btn = document.getElementById('mic-btn');
    if (btn) {
      // voice input: record, then POST the webm/opus to /rooms/<slug>/voice
      var rec = null, chunks = [];
      btn.addEventListener('click', function () {
        if (rec && rec.state === 'recording') { rec.stop(); return; }
        navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
          chunks = []; rec = new MediaRecorder(stream, { mimeType: 'audio/webm' });
          rec.ondataavailable = function (e) { chunks.push(e.data); };
          rec.onstop = function () {
            stream.getTracks().forEach(function (t) { t.stop(); });
            btn.textContent = '…'; btn.style.borderColor = '#333';
            fetch('/rooms/' + btn.dataset.slug + '/voice',
                  { method: 'POST', body: new Blob(chunks, { type: 'audio/webm' }), headers: { 'Content-Type': 'audio/webm' } })
              .then(function (r) { return r.json(); })
              .then(function (j) { btn.textContent = '🎤'; if (j.error) btn.title = j.error; })
              .catch(function () { btn.textContent = '🎤'; btn.title = 'upload failed'; });
          };
          rec.start(); btn.textContent = '⏺'; btn.style.borderColor = '#b85252';
        }).catch(function () { btn.title = 'microphone unavailable'; });
      });
    }
    var d = document.querySelector('.ctx-footer');
    if (d) {
      // the Context panel remembers open/closed per room
      var k = 'ctx-open:' + d.dataset.slug, v = localStorage.getItem(k);
      if (v === '1') d.open = true; else if (v === '0') d.open = false;
      d.addEventListener('toggle', function () { localStorage.setItem(k, d.open ? '1' : '0'); });
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', roomPage); else roomPage();
})();
