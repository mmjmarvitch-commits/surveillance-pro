/**
 * Supervision Pro – Background Service Worker (Chrome Extension)
 * Reçoit les événements du content script, les envoie au serveur avec authentification.
 * File d'attente locale si le serveur est injoignable.
 */

let config = {
  accepted: false,
  deviceId: null,
  deviceToken: null,
  serverURL: null,
  userName: null,
  eventCount: 0,
};

let pendingEvents = [];

chrome.storage.local.get(null, (data) => {
  if (data.accepted) {
    config = { ...config, ...data };
  }
  if (data._pendingEvents) {
    pendingEvents = data._pendingEvents;
    flushPending();
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.action === 'config_updated') {
    config = { ...config, ...message.config };
    sendResponse({ ok: true });
    return;
  }

  if (!config.accepted || !config.deviceId || !config.serverURL || !config.deviceToken) return;

  const event = buildEvent(message);
  if (event) sendToServer(event);
});

// Détecter les pages internes Chrome et les changements d'onglet
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (!config.accepted || !config.deviceToken) return;
  if (changeInfo.status === 'complete' && tab.url) {
    if (tab.url.startsWith('chrome://') || tab.url.startsWith('chrome-extension://') || tab.url.startsWith('edge://')) {
      sendToServer({
        type: 'chrome_page',
        payload: { url: tab.url, title: tab.title || '', timestamp: new Date().toISOString() }
      });
    }
  }
});

// Détecter les téléchargements
chrome.downloads?.onCreated?.addListener((item) => {
  if (!config.accepted || !config.deviceToken) return;
  sendToServer({
    type: 'chrome_download',
    payload: {
      url: item.url || item.finalUrl || '',
      filename: item.filename || '',
      mimeType: item.mime || '',
      fileSize: item.fileSize || 0,
      timestamp: new Date().toISOString()
    }
  });
});

function buildEvent(message) {
  const action = message.action;
  if (!action) return null;
  let payload = { ...message };
  delete payload.action;
  return { type: action, payload };
}

async function sendToServer(event) {
  const url = `${config.serverURL}/api/events`;
  const body = { type: event.type, payload: event.payload };

  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-device-token': config.deviceToken,
      },
      body: JSON.stringify(body),
    });

    if (!res.ok) throw new Error(`HTTP ${res.status}`);

    config.eventCount = (config.eventCount || 0) + 1;
    chrome.storage.local.set({ eventCount: config.eventCount });
  } catch (e) {
    pendingEvents.push(body);
    if (pendingEvents.length > 2000) pendingEvents = pendingEvents.slice(-2000);
    chrome.storage.local.set({ _pendingEvents: pendingEvents });
  }
}

async function flushPending() {
  if (!pendingEvents.length || !config.deviceToken || !config.serverURL) return;

  // Envoyer en batch via /api/sync
  const events = pendingEvents.map(e => ({
    type: e.type,
    payload: e.payload,
    timestamp: e.payload?.timestamp || new Date().toISOString()
  }));

  try {
    const res = await fetch(`${config.serverURL}/api/sync`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-device-token': config.deviceToken,
      },
      body: JSON.stringify({ events }),
    });

    if (!res.ok) throw new Error(`HTTP ${res.status}`);

    const data = await res.json();
    if (data.ok) {
      config.eventCount = (config.eventCount || 0) + (data.insertedEvents || 0);
      chrome.storage.local.set({ eventCount: config.eventCount });
      pendingEvents = [];
      chrome.storage.local.set({ _pendingEvents: [] });
    }
  } catch {
    // Garder en file d'attente, réessayer plus tard
  }
}

// Vider la file toutes les 30 secondes
setInterval(flushPending, 30000);
