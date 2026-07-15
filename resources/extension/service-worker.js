// simmis Web Intake — background service worker
// Auto-captures the DOM of pages on your ALLOWLIST (plus manual capture
// anywhere via the shortcut), and POSTs each to your simmis /pages archive,
// authenticated with your JWT. See doc/web-intake-design.md in simmis.

const DEFAULT_URL = "https://dev.simm.is";
const MAX_CAPTURES = 50;
const AUTO_CAPTURE_DELAY_MS = 5000; // wait for SPA rendering

// Default allowlist — editable in the popup. Deny-by-default: only these hosts
// auto-capture. Manual capture (shortcut / icon) works anywhere.
const DEFAULT_ALLOWLIST = ["linkedin.com", "news.ycombinator.com"];

// Hard sensitive-domain denylist — NEVER auto-captured even if allowlisted.
// Matched as substrings of the hostname. Not user-overridable.
const SENSITIVE_DENY = [
  "bank", "paypal.com", "stripe.com", "wise.com",
  "mail.google.com", "outlook.", "mail.yahoo", "proton.me", "protonmail",
  "accounts.google", "login.", "signin", "auth.",
  "health", ".gov", "id.me"
];

async function cfg() {
  const c = await chrome.storage.local.get(["simmisUrl", "simmisToken", "allowlist", "autoCapture"]);
  return {
    baseUrl: (c.simmisUrl || DEFAULT_URL).replace(/\/+$/, ""),
    token: c.simmisToken || null,
    allowlist: Array.isArray(c.allowlist) ? c.allowlist : DEFAULT_ALLOWLIST,
    autoCapture: c.autoCapture !== false
  };
}

// ---------------------------------------------------------------------------
// Capture history for the popup
// ---------------------------------------------------------------------------

async function storeCapture(capture) {
  const { recentCaptures = [] } = await chrome.storage.local.get("recentCaptures");
  recentCaptures.unshift({
    url: capture.url, title: capture.title,
    timestamp: capture.timestamp, status: capture.status || "pending",
    auto: capture.auto || false
  });
  await chrome.storage.local.set({ recentCaptures: recentCaptures.slice(0, MAX_CAPTURES) });
}

async function updateLastCaptureStatus(status) {
  const { recentCaptures = [] } = await chrome.storage.local.get("recentCaptures");
  if (recentCaptures.length > 0) {
    recentCaptures[0].status = status;
    await chrome.storage.local.set({ recentCaptures });
  }
}

// ---------------------------------------------------------------------------
// Send to simmis — authenticated
// ---------------------------------------------------------------------------

async function sendToSimmis(data) {
  const { baseUrl, token } = await cfg();
  if (!token) {
    await updateLastCaptureStatus("not-logged-in");
    return { status: "not-logged-in" };
  }
  try {
    const resp = await fetch(`${baseUrl}/pages`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify(data)
    });
    if (resp.ok) {
      await updateLastCaptureStatus("received");
      return { status: "received" };
    }
    // 401 → token expired; the popup prompts a re-login
    const status = resp.status === 401 ? "auth-expired" : `error-${resp.status}`;
    await updateLastCaptureStatus(status);
    return { status };
  } catch (err) {
    await updateLastCaptureStatus("connection-error");
    return { status: "error", error: err.message };
  }
}

// ---------------------------------------------------------------------------
// Capture
// ---------------------------------------------------------------------------

async function capturePage(tab) {
  if (!tab || !tab.id || !tab.url) return;
  if (tab.url.startsWith("chrome://") || tab.url.startsWith("chrome-extension://")) return;
  try {
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["content.js"] });
    // data arrives via onMessage below
  } catch (err) {
    console.error("Failed to inject content script:", err);
  }
}

function hostname(url) {
  try { return new URL(url).hostname; } catch { return ""; }
}

function isSensitive(host) {
  return SENSITIVE_DENY.some(s => host.includes(s));
}

const recentAutoCaptures = new Map(); // url -> timestamp (dedup)

async function shouldAutoCapture(url) {
  const { allowlist, autoCapture } = await cfg();
  if (!autoCapture) return false;
  const host = hostname(url);
  if (!host || isSensitive(host)) return false;
  if (!allowlist.some(d => host === d || host.endsWith("." + d) || host.includes(d))) return false;
  const now = Date.now();
  const last = recentAutoCaptures.get(url);
  if (last && (now - last) < 60000) return false; // 60s dedup
  return true;
}

function scheduleAutoCapture(details) {
  recentAutoCaptures.set(details.url, Date.now()); // mark now to dedup listeners
  setTimeout(async () => {
    try {
      const tab = await chrome.tabs.get(details.tabId);
      if (!tab || tab.url !== details.url) return;
      const cutoff = Date.now() - 120000;
      for (const [u, ts] of recentAutoCaptures) if (ts < cutoff) recentAutoCaptures.delete(u);
      await capturePage(tab);
    } catch (err) { /* tab closed */ }
  }, AUTO_CAPTURE_DELAY_MS);
}

chrome.webNavigation.onCompleted.addListener(async (d) => {
  if (d.frameId !== 0) return;
  if (await shouldAutoCapture(d.url)) scheduleAutoCapture(d);
});

chrome.webNavigation.onHistoryStateUpdated.addListener(async (d) => {
  if (d.frameId !== 0) return;
  if (await shouldAutoCapture(d.url)) scheduleAutoCapture(d);
});

// Manual capture — shortcut + icon click, works anywhere (not gated on allowlist)
chrome.commands.onCommand.addListener(async (command) => {
  if (command === "capture-page") {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab) await capturePage(tab);
  }
});
chrome.action.onClicked.addListener(async (tab) => { await capturePage(tab); });

// Receive extracted data from the content script
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === "page-data") {
    const data = { ...message.data, source: "extension", timestamp: new Date().toISOString() };
    storeCapture(data);
    sendToSimmis(data).then(result => sendResponse(result));
    return true; // async response
  }
});
