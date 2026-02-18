/**
 * Supervision Pro – Portail Employé
 * Page read-only accessible par l'employé pour consulter ce qui est collecté
 */

const API = '';
let deviceId = sessionStorage.getItem('emp_deviceId');
let deviceToken = sessionStorage.getItem('emp_deviceToken');

function deviceHeaders() {
  return { 'Content-Type': 'application/json', 'x-device-token': deviceToken || '' };
}

function fmtTime(iso) {
  if (!iso) return '–';
  return new Date(iso).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function esc(s) {
  if (s == null) return '';
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

// ─── Auth ───

async function empLogin() {
  const id = document.getElementById('emp-device-id').value.trim();
  const errEl = document.getElementById('emp-error');
  errEl.style.display = 'none';
  if (!id) { errEl.textContent = 'Entrez l\'identifiant de votre appareil'; errEl.style.display = 'block'; return; }

  try {
    // Register/re-register to get a device token
    const r = await fetch(`${API}/api/devices/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: id })
    });
    const data = await r.json();
    if (!data.ok) {
      errEl.textContent = data.error || 'Appareil non trouvé';
      errEl.style.display = 'block';
      return;
    }
    deviceId = id;
    deviceToken = data.deviceToken;
    sessionStorage.setItem('emp_deviceId', deviceId);
    sessionStorage.setItem('emp_deviceToken', deviceToken);
    showPortal();
  } catch (e) {
    errEl.textContent = 'Erreur de connexion au serveur';
    errEl.style.display = 'block';
  }
}

function empLogout() {
  deviceId = null;
  deviceToken = null;
  sessionStorage.removeItem('emp_deviceId');
  sessionStorage.removeItem('emp_deviceToken');
  document.getElementById('auth-screen').style.display = 'block';
  document.getElementById('portal').style.display = 'none';
}

// Auto-login if stored
if (deviceId && deviceToken) {
  showPortal();
}

// ─── Portal ───

async function showPortal() {
  document.getElementById('auth-screen').style.display = 'none';
  document.getElementById('portal').style.display = 'block';
  await loadStatus();
}

async function loadStatus() {
  try {
    const r = await fetch(`${API}/api/employee/${deviceId}/status`, { headers: deviceHeaders() });
    if (!r.ok) {
      if (r.status === 401 || r.status === 403) {
        empLogout();
        const errEl = document.getElementById('emp-error');
        errEl.textContent = 'Session expirée ou accès refusé. Reconnectez-vous.';
        errEl.style.display = 'block';
        return;
      }
      throw new Error('Erreur serveur');
    }
    const data = await r.json();
    renderStatus(data);
  } catch (e) {
    console.error('Erreur chargement statut:', e);
  }
}

function renderStatus(data) {
  const { device, config, consent, stats, currentConsentVersion } = data;

  // Device info
  document.getElementById('emp-device-name').textContent = device.deviceName || device.deviceId;
  document.getElementById('emp-user-name').textContent = device.userName || '–';

  // Supervision status
  const statusEl = document.getElementById('emp-supervision-status');
  if (device.consentGiven) {
    statusEl.innerHTML = '<span class="badge badge-active">Active</span>';
  } else {
    statusEl.innerHTML = '<span class="badge badge-inactive">Inactive (consentement requis)</span>';
  }

  // Sync info
  document.getElementById('emp-last-sync').textContent = stats.lastSync ? fmtTime(stats.lastSync) : 'Jamais';
  document.getElementById('emp-sync-freq').textContent = config.syncIntervalMinutes ? `Toutes les ${config.syncIntervalMinutes} minutes` : '–';

  // Modules
  const modules = config.modulesEnabled || {};
  const moduleLabels = {
    location: { label: 'Localisation GPS', icon: '📍' },
    browser: { label: 'Navigation web', icon: '🌐' },
    apps: { label: 'Applications', icon: '📱' },
    photos: { label: 'Photos', icon: '📷' },
    network: { label: 'Trafic réseau', icon: '📡' },
  };
  const modsEl = document.getElementById('emp-modules');
  modsEl.innerHTML = Object.entries(moduleLabels).map(([key, { label, icon }]) => {
    const active = !!modules[key];
    return `<span class="badge badge-module ${active ? 'on' : ''}">${icon} ${label} : ${active ? 'OUI' : 'NON'}</span>`;
  }).join('');

  // Stats
  document.getElementById('emp-total-events').textContent = stats.totalEvents || 0;
  document.getElementById('emp-events-today').textContent = stats.eventsToday || 0;
  document.getElementById('emp-total-photos').textContent = stats.totalPhotos || 0;

  // Type breakdown
  const breakdownEl = document.getElementById('emp-type-breakdown');
  if (stats.typeCounts && Object.keys(stats.typeCounts).length) {
    const typeLabels = {
      heartbeat: 'Heartbeats', browser: 'Navigation', device_info: 'Infos appareil',
      app_opened: 'Apps ouvertes', app_closed: 'Apps fermées', location: 'GPS',
      network_traffic: 'Trafic réseau', safari_page: 'Safari', chrome_page: 'Chrome',
      safari_search: 'Recherches Safari', chrome_search: 'Recherches Chrome',
      photo_captured: 'Photos', safari_form: 'Formulaires', chrome_form: 'Formulaires',
    };
    breakdownEl.innerHTML = '<p style="font-size:0.75rem;color:var(--text-dim);margin-bottom:4px">Détail par type :</p>' +
      Object.entries(stats.typeCounts)
        .sort((a, b) => b[1] - a[1])
        .map(([type, count]) => `<div class="status-row"><span class="status-label" style="font-size:0.75rem">${typeLabels[type] || type}</span><span class="status-value" style="font-size:0.75rem">${count}</span></div>`)
        .join('');
  }

  // Consent info
  const consentInfoEl = document.getElementById('emp-consent-info');
  if (consent) {
    consentInfoEl.innerHTML = `
      <div class="status-row"><span class="status-label">Version</span><span class="status-value">v${esc(consent.version)}</span></div>
      <div class="status-row"><span class="status-label">Signé par</span><span class="status-value">${esc(consent.userName || '–')}</span></div>
      <div class="status-row"><span class="status-label">Date de signature</span><span class="status-value">${fmtTime(consent.consentedAt)}</span></div>
    `;
  } else {
    consentInfoEl.innerHTML = '<p style="color:var(--warning);font-size:0.8rem">Aucun consentement enregistré pour cet appareil.</p>';
  }

  // Load consent text
  loadConsentText();

  // Check for existing data requests
  checkExistingRequests();
}

async function loadConsentText() {
  try {
    const r = await fetch(`${API}/api/consent/text`);
    const data = await r.json();
    if (data.body) {
      document.getElementById('emp-consent-text').textContent = `[v${data.version}] ${data.title}\n\n${data.body}`;
    } else {
      document.getElementById('emp-consent-text').textContent = 'Aucun texte de consentement disponible.';
    }
  } catch (e) {
    document.getElementById('emp-consent-text').textContent = 'Erreur de chargement.';
  }
}

async function checkExistingRequests() {
  // We can't easily check existing requests from the employee side since that endpoint requires admin auth
  // So we just show the buttons and let the server handle duplicate detection
  document.getElementById('emp-request-status').innerHTML = '';
}

// ─── RGPD Requests ───

async function empRequestData(type) {
  const typeLabel = type === 'export' ? 'l\'export' : 'la suppression';
  if (!confirm(`Êtes-vous sûr de vouloir demander ${typeLabel} de vos données ? L'administrateur traitera votre demande.`)) return;

  try {
    const r = await fetch(`${API}/api/employee/${deviceId}/data-request`, {
      method: 'POST',
      headers: deviceHeaders(),
      body: JSON.stringify({ type })
    });
    const data = await r.json();
    if (data.ok) {
      alert(data.message);
      const statusEl = document.getElementById('emp-request-status');
      statusEl.innerHTML = `<p style="color:var(--success);font-size:0.8rem;padding:0.5rem;background:var(--surface2);border-radius:6px;border:1px solid var(--success)">&#10003; Votre demande de ${typeLabel} a été enregistrée. L'administrateur la traitera prochainement.</p>`;
    } else {
      alert(data.error || 'Erreur');
    }
  } catch (e) {
    alert('Erreur de connexion au serveur');
  }
}

// Enter key on device ID field
document.getElementById('emp-device-id').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') empLogin();
});
