// Dvergr Feed — Background service worker
// Handles manual capture (shortcut/click) and auto-capture on page load.
// Receives extracted data from content script, POSTs to dvergr.

const DEFAULT_URL = "http://localhost:17880";
const MAX_CAPTURES = 50;
const AUTO_CAPTURE_DELAY_MS = 5000; // Wait for SPA rendering

// Get dvergr base URL from storage
async function getDvergrUrl() {
  const { dvergrUrl } = await chrome.storage.local.get("dvergrUrl");
  return dvergrUrl || DEFAULT_URL;
}

// Check if auto-capture is enabled
async function isAutoCapture() {
  const { autoCapture } = await chrome.storage.local.get("autoCapture");
  return autoCapture !== false; // Default: on
}

// Store a capture in recent history
async function storeCapture(capture) {
  const { recentCaptures = [] } = await chrome.storage.local.get("recentCaptures");
  recentCaptures.unshift({
    url: capture.url,
    title: capture.title,
    timestamp: capture.timestamp,
    status: capture.status || "pending",
    auto: capture.auto || false
  });
  await chrome.storage.local.set({
    recentCaptures: recentCaptures.slice(0, MAX_CAPTURES)
  });
}

// Update the status of the most recent capture
async function updateLastCaptureStatus(status) {
  const { recentCaptures = [] } = await chrome.storage.local.get("recentCaptures");
  if (recentCaptures.length > 0) {
    recentCaptures[0].status = status;
    await chrome.storage.local.set({ recentCaptures });
  }
}

// Send page data to dvergr
async function sendToDvergr(data) {
  const baseUrl = await getDvergrUrl();
  const url = `${baseUrl}/api/intake/page`;

  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data)
    });

    if (response.ok) {
      const result = await response.json();
      await updateLastCaptureStatus(result.status || "received");
      return result;
    } else {
      await updateLastCaptureStatus(`error-${response.status}`);
      return { status: "error", error: `HTTP ${response.status}` };
    }
  } catch (err) {
    await updateLastCaptureStatus("connection-error");
    return { status: "error", error: err.message };
  }
}

// Inject content script and capture page
async function capturePage(tab, auto = false) {
  if (!tab || !tab.id || !tab.url) return;

  // Skip chrome:// and extension pages
  if (tab.url.startsWith("chrome://") || tab.url.startsWith("chrome-extension://")) {
    return;
  }

  try {
    await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      files: ["content.js"]
    });
    // Data arrives via onMessage listener below
  } catch (err) {
    console.error("Failed to inject content script:", err);
  }
}

// ============================================================================
// Auto-capture: fire on page load for matching domains
// ============================================================================

// Track recently auto-captured URLs to avoid duplicates on SPA navigations
const recentAutoCaptures = new Map(); // url -> timestamp

function shouldAutoCapture(url) {
  // Only auto-capture linkedin.com for now
  // (host_permissions already scoped to linkedin.com)
  try {
    const u = new URL(url);
    if (!u.hostname.endsWith("linkedin.com")) return false;

    // Deduplicate: skip if same URL captured in last 60s
    const now = Date.now();
    const lastCapture = recentAutoCaptures.get(url);
    if (lastCapture && (now - lastCapture) < 60000) return false;

    return true;
  } catch {
    return false;
  }
}

// Listen for completed navigations
chrome.webNavigation.onCompleted.addListener(async (details) => {
  // Only main frame, not iframes
  if (details.frameId !== 0) return;

  if (!await isAutoCapture()) return;
  if (!shouldAutoCapture(details.url)) return;

  // Mark as captured immediately to prevent duplicate from other listener
  recentAutoCaptures.set(details.url, Date.now());

  // Wait for SPA to finish rendering
  setTimeout(async () => {
    try {
      const tab = await chrome.tabs.get(details.tabId);
      if (!tab || tab.url !== details.url) return; // Tab navigated away

      // Clean old entries
      const cutoff = Date.now() - 120000;
      for (const [url, ts] of recentAutoCaptures) {
        if (ts < cutoff) recentAutoCaptures.delete(url);
      }

      await capturePage(tab, true);
    } catch (err) {
      // Tab may have been closed
      console.error("Auto-capture failed:", err);
    }
  }, AUTO_CAPTURE_DELAY_MS);
});

// Also capture on SPA history changes (LinkedIn uses pushState)
chrome.webNavigation.onHistoryStateUpdated.addListener(async (details) => {
  if (details.frameId !== 0) return;
  if (!await isAutoCapture()) return;
  if (!shouldAutoCapture(details.url)) return;

  // Mark immediately to dedup against onCompleted
  recentAutoCaptures.set(details.url, Date.now());

  setTimeout(async () => {
    try {
      const tab = await chrome.tabs.get(details.tabId);
      if (!tab || tab.url !== details.url) return;

      const cutoff = Date.now() - 120000;
      for (const [url, ts] of recentAutoCaptures) {
        if (ts < cutoff) recentAutoCaptures.delete(url);
      }

      await capturePage(tab, true);
    } catch (err) {
      console.error("Auto-capture (history) failed:", err);
    }
  }, AUTO_CAPTURE_DELAY_MS);
});

// ============================================================================
// Manual capture: keyboard shortcut and icon click
// ============================================================================

chrome.commands.onCommand.addListener(async (command) => {
  if (command === "capture-page") {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab) {
      await capturePage(tab, false);
    }
  }
});

chrome.action.onClicked.addListener(async (tab) => {
  await capturePage(tab, false);
});

// ============================================================================
// Receive extracted data from content script
// ============================================================================

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === "page-data") {
    const data = {
      ...message.data,
      source: "extension",
      timestamp: new Date().toISOString()
    };

    storeCapture(data);
    sendToDvergr(data).then(result => {
      sendResponse(result);
    });

    // Return true to indicate async response
    return true;
  }
});
