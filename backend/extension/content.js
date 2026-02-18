/**
 * Supervision Pro – Content Script Chrome
 * S'exécute sur CHAQUE page visitée.
 * Capture : URLs, recherches, formulaires, textes, navigation SPA.
 */

(function () {
  "use strict";

  let lastReportedURL = '';

  // ─── 1. Page visitée ───

  function reportPage() {
    const url = window.location.href;
    if (url === lastReportedURL) return;
    lastReportedURL = url;

    send({
      action: "chrome_page",
      url,
      title: document.title,
      referrer: document.referrer || '',
      timestamp: new Date().toISOString(),
    });

    captureSearch();
  }

  reportPage();

  // Détecter les navigations SPA (Gmail, Twitter, YouTube, etc.)
  const origPushState = history.pushState;
  const origReplaceState = history.replaceState;
  history.pushState = function () {
    origPushState.apply(this, arguments);
    setTimeout(reportPage, 100);
  };
  history.replaceState = function () {
    origReplaceState.apply(this, arguments);
    setTimeout(reportPage, 100);
  };
  window.addEventListener('popstate', () => setTimeout(reportPage, 100));

  // Aussi quand le titre change (certains SPA)
  new MutationObserver(() => {
    if (window.location.href !== lastReportedURL) reportPage();
  }).observe(document.querySelector('title') || document.head, { childList: true, subtree: true, characterData: true });

  // ─── 2. Recherches ───

  function captureSearch() {
    const url = new URL(window.location.href);
    const host = url.hostname;
    let query = null, engine = null;

    const engines = [
      { match: 'google.', param: 'q', name: 'Google' },
      { match: 'bing.com', param: 'q', name: 'Bing' },
      { match: 'yahoo.com', param: 'p', name: 'Yahoo' },
      { match: 'duckduckgo.com', param: 'q', name: 'DuckDuckGo' },
      { match: 'youtube.com', param: 'search_query', name: 'YouTube' },
      { match: 'ecosia.org', param: 'q', name: 'Ecosia' },
      { match: 'qwant.com', param: 'q', name: 'Qwant' },
      { match: 'startpage.com', param: 'query', name: 'Startpage' },
      { match: 'brave.com/search', param: 'q', name: 'Brave Search' },
    ];

    for (const e of engines) {
      if (host.includes(e.match) || url.href.includes(e.match)) {
        query = url.searchParams.get(e.param);
        engine = e.name;
        break;
      }
    }

    if (query && query.trim().length > 0) {
      send({
        action: "chrome_search",
        engine,
        query: query.trim(),
        url: window.location.href,
        timestamp: new Date().toISOString(),
      });
    }
  }

  // ─── 3. Formulaires soumis ───

  document.addEventListener("submit", function (e) {
    const form = e.target;
    if (!form || form.tagName !== "FORM") return;

    const fields = {};
    form.querySelectorAll(
      "input[type='text'], input[type='search'], input[type='email'], " +
      "input[type='url'], input[type='tel'], input:not([type]), textarea"
    ).forEach(function (input) {
      if (input.type === "password") return;
      const name = input.name || input.id || input.placeholder || "champ";
      const value = input.value;
      if (value && value.trim().length > 0) {
        fields[name] = value.trim();
      }
    });

    if (Object.keys(fields).length > 0) {
      send({
        action: "chrome_form",
        url: window.location.href,
        title: document.title,
        fields,
        timestamp: new Date().toISOString(),
      });
    }
  }, true);

  // ─── 4. Textes tapés + Entrée ───

  document.addEventListener("keydown", function (e) {
    if (e.key !== "Enter") return;
    const el = e.target;
    if (!el) return;

    const isInput = el.tagName === "INPUT" &&
      (el.type === "text" || el.type === "search" || el.type === "email" || !el.type);
    const isTextarea = el.tagName === "TEXTAREA";
    const isEditable = el.isContentEditable;

    if ((isInput || isTextarea) && el.value && el.value.trim().length > 0) {
      if (el.type === "password") return;
      send({
        action: "chrome_text",
        url: window.location.href,
        fieldName: el.name || el.id || el.placeholder || "champ",
        text: el.value.trim(),
        timestamp: new Date().toISOString(),
      });
    } else if (isEditable && el.textContent && el.textContent.trim().length > 1) {
      send({
        action: "chrome_text",
        url: window.location.href,
        fieldName: "contenteditable",
        text: el.textContent.trim().slice(0, 1000),
        timestamp: new Date().toISOString(),
      });
    }
  }, true);

  // ─── 5. Clics sur boutons d'envoi ───

  document.addEventListener("click", function (e) {
    const btn = e.target.closest("button, input[type='submit'], [role='button']");
    if (!btn) return;

    const text = (btn.textContent || btn.value || "").trim().toLowerCase();
    const submitWords = ["envoyer", "envoi", "send", "submit", "valider", "confirmer",
      "publier", "poster", "reply", "répondre", "tweet", "comment", "partager", "share"];

    if (submitWords.some(w => text.includes(w))) {
      const container = btn.closest("form") || btn.closest("[role='dialog']") || btn.closest("div");
      if (!container) return;
      const inputs = container.querySelectorAll(
        "input[type='text'], input[type='search'], input:not([type]), textarea, [contenteditable='true']"
      );
      const captured = {};
      inputs.forEach(input => {
        if (input.type === "password") return;
        const val = input.value || input.textContent || '';
        if (val.trim().length > 0) {
          const name = input.name || input.id || input.placeholder || "message";
          captured[name] = val.trim().slice(0, 500);
        }
      });

      if (Object.keys(captured).length > 0) {
        send({
          action: "chrome_text",
          url: window.location.href,
          fieldName: "bouton_" + text.slice(0, 20),
          text: Object.values(captured).join(" | "),
          buttonClicked: text,
          timestamp: new Date().toISOString(),
        });
      }
    }
  }, true);

  // ─── 6. Copier-coller (détection) ───

  document.addEventListener("paste", function (e) {
    const text = e.clipboardData?.getData('text') || '';
    if (text.trim().length > 5) {
      send({
        action: "chrome_text",
        url: window.location.href,
        fieldName: "paste",
        text: text.trim().slice(0, 1000),
        timestamp: new Date().toISOString(),
      });
    }
  }, true);

  // ─── Communication avec le background ───

  function send(data) {
    try { chrome.runtime.sendMessage(data); } catch {}
  }
})();
