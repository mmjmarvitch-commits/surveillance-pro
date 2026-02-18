/**
 * Supervision Pro – Intégration MDM (SimpleMDM)
 * 
 * Pour utiliser : créer un compte SimpleMDM (simplemdm.com)
 * et mettre la clé API dans la variable d'env SIMPLEMDM_API_KEY
 */

const MDM_API = 'https://a]]]].simplemdm.com/api/v1';
const MDM_KEY = process.env.SIMPLEMDM_API_KEY || '';

function mdmHeaders() {
  return {
    'Authorization': 'Basic ' + Buffer.from(MDM_KEY + ':').toString('base64'),
    'Content-Type': 'application/json'
  };
}

async function getDevices() {
  if (!MDM_KEY) return [];
  const res = await fetch(`${MDM_API}/devices`, { headers: mdmHeaders() });
  const data = await res.json();
  return data.data || [];
}

async function getInstalledApps(deviceId) {
  if (!MDM_KEY) return [];
  const res = await fetch(`${MDM_API}/devices/${deviceId}/installed_apps`, { headers: mdmHeaders() });
  const data = await res.json();
  return data.data || [];
}

async function lockDevice(deviceId) {
  if (!MDM_KEY) return { error: 'MDM non configuré' };
  const res = await fetch(`${MDM_API}/devices/${deviceId}/lock`, { method: 'POST', headers: mdmHeaders() });
  return res.json();
}

async function wipeDevice(deviceId) {
  if (!MDM_KEY) return { error: 'MDM non configuré' };
  const res = await fetch(`${MDM_API}/devices/${deviceId}/wipe`, { method: 'POST', headers: mdmHeaders() });
  return res.json();
}

module.exports = { getDevices, getInstalledApps, lockDevice, wipeDevice };
