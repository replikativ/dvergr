// simmis Web Intake — popup logic

const DEFAULT_URL = "https://dev.simm.is";
const DEFAULT_ALLOWLIST = ["linkedin.com", "news.ycombinator.com"];

const $ = (id) => document.getElementById(id);

async function init() {
  const c = await chrome.storage.local.get(
    ["simmisUrl", "simmisToken", "simmisUser", "allowlist", "autoCapture"]);
  $("urlInput").value = c.simmisUrl || DEFAULT_URL;
  $("autoCapture").checked = c.autoCapture !== false;
  $("allowlistInput").value = (Array.isArray(c.allowlist) ? c.allowlist : DEFAULT_ALLOWLIST).join("\n");
  renderAuth(c.simmisToken, c.simmisUser);
  await loadCaptures();
}

function renderAuth(token, user) {
  const signedIn = !!token;
  $("loginSection").classList.toggle("hidden", signedIn);
  $("accountSection").classList.toggle("hidden", !signedIn);
  $("captureBtn").disabled = !signedIn;
  $("statusDot").className = "status-dot " + (signedIn ? "connected" : "error");
  if (signedIn && user) $("accountLabel").textContent = `Signed in — ${user}`;
}

// --- login / logout ---------------------------------------------------------

$("loginBtn").addEventListener("click", async () => {
  const baseUrl = $("urlInput").value.trim().replace(/\/+$/, "") || DEFAULT_URL;
  const email = $("emailInput").value.trim();
  const password = $("passwordInput").value;
  $("loginError").textContent = "";
  if (!email || !password) { $("loginError").textContent = "Email and password required."; return; }
  try {
    const resp = await fetch(`${baseUrl}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password })
    });
    if (!resp.ok) { $("loginError").textContent = `Sign-in failed (${resp.status}).`; return; }
    const body = await resp.json();
    const token = body.access_token || body.token;
    if (!token) { $("loginError").textContent = "No token in response."; return; }
    await chrome.storage.local.set({ simmisUrl: baseUrl, simmisToken: token, simmisUser: email });
    $("passwordInput").value = "";
    renderAuth(token, email);
  } catch (err) {
    $("loginError").textContent = `Could not reach ${baseUrl}.`;
  }
});

$("logoutBtn").addEventListener("click", async () => {
  await chrome.storage.local.remove(["simmisToken", "simmisUser"]);
  renderAuth(null, null);
});

// --- settings ---------------------------------------------------------------

$("saveBtn").addEventListener("click", async () => {
  const url = $("urlInput").value.trim().replace(/\/+$/, "") || DEFAULT_URL;
  const allowlist = $("allowlistInput").value.split("\n")
    .map(s => s.trim().replace(/^https?:\/\//, "").replace(/\/.*$/, "").toLowerCase())
    .filter(Boolean);
  await chrome.storage.local.set({ simmisUrl: url, allowlist });
  $("saveBtn").textContent = "Saved ✓";
  setTimeout(() => { $("saveBtn").textContent = "Save"; }, 1200);
});

$("autoCapture").addEventListener("change", async (e) => {
  await chrome.storage.local.set({ autoCapture: e.target.checked });
});

// --- manual capture ---------------------------------------------------------

$("captureBtn").addEventListener("click", async () => {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab && tab.id) {
    try {
      await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["content.js"] });
      setTimeout(loadCaptures, 1500);
    } catch (err) { console.error("Capture failed:", err); }
  }
});

// --- recent captures --------------------------------------------------------

async function loadCaptures() {
  const { recentCaptures = [] } = await chrome.storage.local.get("recentCaptures");
  const list = $("capturesList");
  if (recentCaptures.length === 0) { list.innerHTML = '<div class="empty">No captures yet</div>'; return; }
  list.innerHTML = recentCaptures.slice(0, 10).map(c => {
    const time = new Date(c.timestamp).toLocaleTimeString();
    const s = c.status || "pending";
    const cls = s === "received" ? "received" : (s === "pending" ? "pending" : "error");
    const autoTag = c.auto ? ' <span style="color:#666;font-size:10px">[auto]</span>' : '';
    return `<div class="capture-item">
      <div class="capture-title">${escapeHtml(c.title || c.url)}${autoTag}</div>
      <div class="capture-meta">${time} <span class="capture-status ${cls}">${escapeHtml(s)}</span></div>
    </div>`;
  }).join("");
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text == null ? "" : String(text);
  return div.innerHTML;
}

init();
