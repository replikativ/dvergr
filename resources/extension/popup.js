// Dvergr Feed — Popup logic

const DEFAULT_URL = "http://localhost:17880";

async function init() {
  // Load saved URL
  const { dvergrUrl } = await chrome.storage.local.get("dvergrUrl");
  document.getElementById("urlInput").value = dvergrUrl || DEFAULT_URL;

  // Load auto-capture setting
  const { autoCapture } = await chrome.storage.local.get("autoCapture");
  document.getElementById("autoCapture").checked = autoCapture !== false; // Default: on

  // Check connection
  await checkConnection();

  // Load recent captures
  await loadCaptures();
}

async function checkConnection() {
  const dot = document.getElementById("statusDot");
  const { dvergrUrl } = await chrome.storage.local.get("dvergrUrl");
  const baseUrl = dvergrUrl || DEFAULT_URL;

  try {
    const resp = await fetch(`${baseUrl}/api/health`, { signal: AbortSignal.timeout(3000) });
    if (resp.ok) {
      dot.className = "status-dot connected";
    } else {
      dot.className = "status-dot error";
    }
  } catch {
    dot.className = "status-dot error";
  }
}

async function loadCaptures() {
  const { recentCaptures = [] } = await chrome.storage.local.get("recentCaptures");
  const list = document.getElementById("capturesList");

  if (recentCaptures.length === 0) {
    list.innerHTML = '<div class="empty">No captures yet</div>';
    return;
  }

  list.innerHTML = recentCaptures.slice(0, 10).map(c => {
    const time = new Date(c.timestamp).toLocaleTimeString();
    const statusClass = (c.status || "").startsWith("error") ? "error" :
                        c.status === "filtered" ? "filtered" :
                        c.status === "received" ? "received" : "pending";
    const autoTag = c.auto ? ' <span style="color:#666;font-size:10px">[auto]</span>' : '';
    return `<div class="capture-item">
      <div class="capture-title">${escapeHtml(c.title || c.url)}${autoTag}</div>
      <div class="capture-meta">
        ${time}
        <span class="capture-status ${statusClass}">${c.status}</span>
      </div>
    </div>`;
  }).join("");
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text;
  return div.innerHTML;
}

// Save URL
document.getElementById("saveBtn").addEventListener("click", async () => {
  const url = document.getElementById("urlInput").value.trim().replace(/\/+$/, "");
  await chrome.storage.local.set({ dvergrUrl: url || DEFAULT_URL });
  await checkConnection();
});

// Auto-capture toggle
document.getElementById("autoCapture").addEventListener("change", async (e) => {
  await chrome.storage.local.set({ autoCapture: e.target.checked });
});

// Capture button
document.getElementById("captureBtn").addEventListener("click", async () => {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab && tab.id) {
    try {
      await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        files: ["content.js"]
      });
      // Wait a moment then refresh captures
      setTimeout(loadCaptures, 1500);
    } catch (err) {
      console.error("Capture failed:", err);
    }
  }
});

init();
