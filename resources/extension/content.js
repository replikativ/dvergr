// Dvergr Feed — Content script
// Injected on demand via chrome.scripting.executeScript.
// Captures the full DOM + metadata and sends back to service worker.
//
// Strategy: dump everything now, extract later.
// Raw HTML is archived on the server so extractors can be improved over time.

(function() {
  "use strict";

  const MAX_TEXT_LENGTH = 50000;

  function extractPageData() {
    const url = window.location.href;
    const title = document.title || "";

    // Full DOM HTML — the primary payload for archival
    const html = document.documentElement.outerHTML;

    // Meta tags — small, always useful for quick lookups
    const meta = {};
    document.querySelectorAll("meta").forEach(el => {
      const name = el.getAttribute("name") || el.getAttribute("property");
      const content = el.getAttribute("content");
      if (name && content) {
        meta[name] = content;
      }
    });

    // innerText — truncated, for quick agent access and Datahike indexing
    let text = "";
    try {
      text = (document.body.innerText || "").substring(0, MAX_TEXT_LENGTH);
    } catch (e) {
      text = "";
    }

    const data = { url, title, html, text, meta };

    // LinkedIn-specific: detect page type from URL for routing
    if (url.includes("linkedin.com")) {
      data.linkedin = {
        pageType: detectLinkedInPageType(url),
        ogTitle: meta["og:title"] || "",
        ogDescription: meta["og:description"] || ""
      };
    }

    return data;
  }

  function detectLinkedInPageType(url) {
    if (/linkedin\.com\/company\/[^/]+\/?$/.test(url)) return "company";
    if (/linkedin\.com\/company\/[^/]+\/about/.test(url)) return "company-about";
    if (/linkedin\.com\/company\/[^/]+\/people/.test(url)) return "company-people";
    if (/linkedin\.com\/company\/[^/]+\/jobs/.test(url)) return "company-jobs";
    if (/linkedin\.com\/in\/[^/]+/.test(url)) return "profile";
    if (/linkedin\.com\/jobs\/search/.test(url)) return "job-search";
    if (/linkedin\.com\/jobs\/view/.test(url)) return "job-detail";
    if (/linkedin\.com\/search\/results/.test(url)) return "search";
    return "other";
  }

  // Execute and send results
  const data = extractPageData();
  chrome.runtime.sendMessage({ type: "page-data", data: data });
})();
