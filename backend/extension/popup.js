/**
 * Supervision Pro – Popup Chrome Extension
 * Gère le consentement, l'enregistrement, et affiche l'état.
 */

const consentView = document.getElementById('consent-view');
const activeView = document.getElementById('active-view');
const nameInput = document.getElementById('user-name');
const serverInput = document.getElementById('server-url');
const btnAccept = document.getElementById('btn-accept');
const errorEl = document.getElementById('consent-error');

nameInput.addEventListener('input', () => {
  btnAccept.disabled = nameInput.value.trim().length < 2;
});

btnAccept.addEventListener('click', async () => {
  const userName = nameInput.value.trim();
  const serverURL = serverInput.value.trim().replace(/\/+$/, '');

  if (!userName || userName.length < 2) return;
  if (!serverURL) { showError("URL du serveur requise."); return; }

  btnAccept.disabled = true;
  btnAccept.textContent = "Connexion au serveur…";
  errorEl.style.display = 'none';

  // 1. Tester la connexion
  try {
    const res = await fetch(`${serverURL}/api/health`);
    if (!res.ok) throw new Error();
  } catch {
    showError("Impossible de contacter le serveur. Vérifiez l'URL.");
    resetButton(); return;
  }

  btnAccept.textContent = "Enregistrement…";

  // 2. Enregistrer l'appareil et récupérer le token
  const deviceId = 'CHROME-' + crypto.randomUUID();
  const acceptanceDate = new Date().toISOString();
  let deviceToken = null;

  try {
    const res = await fetch(`${serverURL}/api/devices/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId,
        deviceName: `Chrome – ${userName}`,
        userName,
        acceptanceVersion: '1.0',
        acceptanceDate,
      }),
    });
    const data = await res.json();
    if (!data.ok && !data.deviceToken) throw new Error(data.error || 'Erreur');
    deviceToken = data.deviceToken;
  } catch (e) {
    showError("Erreur lors de l'enregistrement: " + (e.message || ''));
    resetButton(); return;
  }

  if (!deviceToken) {
    showError("Le serveur n'a pas renvoyé de token. Vérifiez la version du backend.");
    resetButton(); return;
  }

  btnAccept.textContent = "Envoi du consentement…";

  // 3. Envoyer le consentement au serveur
  try {
    const res = await fetch(`${serverURL}/api/consent`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-device-token': deviceToken,
      },
      body: JSON.stringify({
        userName,
        consentVersion: '1.0',
      }),
    });
    const data = await res.json();
    if (!data.ok && !data.consentId) throw new Error(data.error || 'Erreur consent');
  } catch (e) {
    showError("Erreur consentement: " + (e.message || ''));
    resetButton(); return;
  }

  // 4. Tout OK : sauvegarder et activer
  const config = {
    accepted: true,
    deviceId,
    deviceToken,
    userName,
    serverURL,
    acceptanceDate,
    eventCount: 0,
  };
  await chrome.storage.local.set(config);
  chrome.runtime.sendMessage({ action: 'config_updated', config });
  showActiveView(config);
});

function showError(msg) {
  errorEl.textContent = msg;
  errorEl.style.display = 'block';
}

function resetButton() {
  btnAccept.disabled = false;
  btnAccept.textContent = "J'accepte et j'active la supervision";
}

function showActiveView(config) {
  consentView.classList.remove('visible');
  activeView.classList.add('visible');
  document.getElementById('info-user').textContent = config.userName || '–';
  document.getElementById('info-server').textContent = config.serverURL || '–';
  document.getElementById('info-date').textContent = config.acceptanceDate
    ? new Date(config.acceptanceDate).toLocaleDateString('fr-FR')
    : '–';
  document.getElementById('info-count').textContent = config.eventCount || 0;
}

chrome.storage.local.get(null, (data) => {
  if (data.accepted) showActiveView(data);
});
