const express = require('express');
const cors = require('cors');
const compression = require('compression');
const path = require('path');
const fs = require('fs');
const http = require('http');
const crypto = require('crypto');
const archiver = require('archiver');
const Database = require('better-sqlite3');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const { WebSocketServer } = require('ws');
const PDFDocument = require('pdfkit');

// Firebase (optionnel - pour FCM et sync temps réel)
const firebase = require('./firebase-config');
const { initializeFirebase, sendCommand: sendFCMCommand, isFCMEnabled, syncEventToFirestore } = firebase;

// ─── Dossier stockage photos ───
const PHOTOS_DIR = path.join(__dirname, 'photos');
if (!fs.existsSync(PHOTOS_DIR)) fs.mkdirSync(PHOTOS_DIR, { recursive: true });

// ─── Dossier stockage audio (vocaux, enregistrements d'appels) ───
const AUDIO_DIR = path.join(__dirname, 'audio');
if (!fs.existsSync(AUDIO_DIR)) fs.mkdirSync(AUDIO_DIR, { recursive: true });

const app = express();
const PORT = process.env.PORT || 3000;

// ─── SECRETS CRYPTOGRAPHIQUES ───
// En production, ces valeurs DOIVENT être dans les variables d'environnement.
const IS_PRODUCTION = process.env.NODE_ENV === 'production';

// Vérification stricte en production
if (IS_PRODUCTION) {
  const missingVars = [];
  if (!process.env.JWT_SECRET) missingVars.push('JWT_SECRET');
  if (!process.env.DEVICE_ENCRYPTION_KEY) missingVars.push('DEVICE_ENCRYPTION_KEY');
  if (!process.env.DATA_ENCRYPTION_KEY) missingVars.push('DATA_ENCRYPTION_KEY');
  
  if (missingVars.length > 0) {
    console.error('\n  ❌ ERREUR CRITIQUE: Variables d\'environnement manquantes en production:');
    missingVars.forEach(v => console.error(`     - ${v}`));
    console.error('\n  Consultez .env.example pour la configuration.\n');
    process.exit(1);
  }
}

const JWT_SECRET = process.env.JWT_SECRET || crypto.randomBytes(64).toString('hex');
const DEVICE_ENCRYPTION_KEY = process.env.DEVICE_ENCRYPTION_KEY || crypto.randomBytes(32).toString('hex');
const DATA_ENCRYPTION_KEY = process.env.DATA_ENCRYPTION_KEY || crypto.randomBytes(32).toString('hex');

if (!process.env.JWT_SECRET && !IS_PRODUCTION) {
  console.warn('  ⚠️  JWT_SECRET non défini (mode développement).');
  console.warn('  ⚠️  Les sessions seront invalidées au redémarrage.\n');
}

// ─── Base de données SQLite local ───

const DB_PATH = path.join(__dirname, 'surveillance.db');
const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');
console.log('  [DB] SQLite local');

db.exec(`
  CREATE TABLE IF NOT EXISTS devices (
    deviceId TEXT PRIMARY KEY,
    deviceName TEXT NOT NULL DEFAULT 'Appareil inconnu',
    userId TEXT,
    userName TEXT,
    acceptanceVersion TEXT DEFAULT '1.0',
    acceptanceDate TEXT,
    registeredAt TEXT NOT NULL,
    fcmToken TEXT,
    lastSeen TEXT,
    osVersion TEXT,
    appVersion TEXT
  );
  CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deviceId TEXT NOT NULL,
    type TEXT NOT NULL,
    payload TEXT DEFAULT '{}',
    receivedAt TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS admins (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    passwordHash TEXT NOT NULL,
    createdAt TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS keywords (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    word TEXT NOT NULL,
    category TEXT DEFAULT 'general',
    createdAt TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS alerts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deviceId TEXT NOT NULL,
    keyword TEXT NOT NULL,
    eventType TEXT,
    context TEXT,
    url TEXT,
    createdAt TEXT NOT NULL,
    seen INTEGER DEFAULT 0
  );
  CREATE TABLE IF NOT EXISTS commands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deviceId TEXT NOT NULL,
    type TEXT NOT NULL,
    payload TEXT DEFAULT '{}',
    status TEXT DEFAULT 'pending',
    createdAt TEXT NOT NULL,
    executedAt TEXT
  );
  CREATE TABLE IF NOT EXISTS photos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deviceId TEXT NOT NULL,
    commandId INTEGER,
    filename TEXT NOT NULL,
    mimeType TEXT DEFAULT 'image/jpeg',
    sizeBytes INTEGER DEFAULT 0,
    source TEXT DEFAULT 'auto',
    sourceApp TEXT DEFAULT '',
    metadata TEXT DEFAULT '{}',
    receivedAt TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_events_deviceId ON events(deviceId);
  CREATE INDEX IF NOT EXISTS idx_events_type ON events(type);
  CREATE INDEX IF NOT EXISTS idx_events_receivedAt ON events(receivedAt);
  CREATE INDEX IF NOT EXISTS idx_alerts_seen ON alerts(seen);
  CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    adminId INTEGER,
    adminUsername TEXT,
    action TEXT NOT NULL,
    detail TEXT DEFAULT '',
    ip TEXT DEFAULT '',
    userAgent TEXT DEFAULT '',
    createdAt TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS login_attempts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ip TEXT NOT NULL,
    username TEXT DEFAULT '',
    success INTEGER DEFAULT 0,
    createdAt TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS security_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_commands_deviceId ON commands(deviceId);
  CREATE INDEX IF NOT EXISTS idx_commands_status ON commands(status);
  CREATE INDEX IF NOT EXISTS idx_photos_deviceId ON photos(deviceId);
  CREATE INDEX IF NOT EXISTS idx_audit_createdAt ON audit_log(createdAt);
  CREATE INDEX IF NOT EXISTS idx_login_attempts_ip ON login_attempts(ip);
  CREATE INDEX IF NOT EXISTS idx_login_attempts_createdAt ON login_attempts(createdAt);

  CREATE TABLE IF NOT EXISTS sync_config (
    deviceId TEXT PRIMARY KEY,
    syncIntervalMinutes INTEGER DEFAULT 15,
    syncOnWifiOnly INTEGER DEFAULT 0,
    syncOnChargingOnly INTEGER DEFAULT 0,
    modulesEnabled TEXT DEFAULT '{"location":true,"browser":true,"apps":true,"photos":false,"network":false}',
    photoQuality REAL DEFAULT 0.5,
    photoMaxWidth INTEGER DEFAULT 1280,
    updatedAt TEXT
  );

  CREATE TABLE IF NOT EXISTS consent_texts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    version TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL DEFAULT 'Politique de supervision',
    body TEXT NOT NULL,
    createdAt TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS consent_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deviceId TEXT NOT NULL,
    userId TEXT,
    userName TEXT,
    consentVersion TEXT NOT NULL,
    consentText TEXT NOT NULL,
    ipAddress TEXT,
    userAgent TEXT,
    signature TEXT DEFAULT '',
    consentedAt TEXT NOT NULL,
    revokedAt TEXT
  );
  CREATE INDEX IF NOT EXISTS idx_consent_deviceId ON consent_records(deviceId);

  CREATE TABLE IF NOT EXISTS data_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deviceId TEXT NOT NULL,
    type TEXT NOT NULL,
    status TEXT DEFAULT 'pending',
    adminNote TEXT DEFAULT '',
    createdAt TEXT NOT NULL,
    processedAt TEXT
  );
  CREATE INDEX IF NOT EXISTS idx_data_requests_status ON data_requests(status);

  CREATE INDEX IF NOT EXISTS idx_events_device_time ON events(deviceId, receivedAt);
  CREATE INDEX IF NOT EXISTS idx_events_type_time ON events(type, receivedAt);
  CREATE INDEX IF NOT EXISTS idx_alerts_deviceId ON alerts(deviceId);
  CREATE INDEX IF NOT EXISTS idx_alerts_createdAt ON alerts(createdAt);

  CREATE TABLE IF NOT EXISTS pending_commands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deviceId TEXT NOT NULL,
    command TEXT NOT NULL,
    params TEXT DEFAULT '{}',
    status TEXT DEFAULT 'pending',
    result TEXT,
    createdAt TEXT NOT NULL,
    executedAt TEXT
  );
  CREATE INDEX IF NOT EXISTS idx_pending_commands_deviceId ON pending_commands(deviceId);
  CREATE INDEX IF NOT EXISTS idx_pending_commands_status ON pending_commands(status);

  CREATE TABLE IF NOT EXISTS geofence_zones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    radiusMeters REAL NOT NULL DEFAULT 100,
    alertOnEnter INTEGER DEFAULT 1,
    alertOnExit INTEGER DEFAULT 1,
    isActive INTEGER DEFAULT 1,
    createdAt TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_geofence_active ON geofence_zones(isActive);
`);

// Migration : ajouter severity aux alertes si elle n'existe pas
const alertCols = db.prepare("PRAGMA table_info(alerts)").all().map(c => c.name);
if (!alertCols.includes('severity')) db.exec("ALTER TABLE alerts ADD COLUMN severity TEXT DEFAULT 'warning'");
if (!alertCols.includes('source')) db.exec("ALTER TABLE alerts ADD COLUMN source TEXT DEFAULT 'keyword'");

// Migration : ajouter consentGiven aux devices si elle n'existe pas
const deviceCols = db.prepare("PRAGMA table_info(devices)").all().map(c => c.name);
if (!deviceCols.includes('consentGiven')) db.exec("ALTER TABLE devices ADD COLUMN consentGiven INTEGER DEFAULT 0");

// Migration : ajouter blockedApps à sync_config si elle n'existe pas
const syncCols = db.prepare("PRAGMA table_info(sync_config)").all().map(c => c.name);
if (!syncCols.includes('blockedApps')) db.exec("ALTER TABLE sync_config ADD COLUMN blockedApps TEXT DEFAULT '{}'");

// Insérer le texte de consentement par défaut s'il n'existe pas
const consentExists = db.prepare('SELECT COUNT(*) as c FROM consent_texts').get().c;
if (!consentExists) {
  db.prepare('INSERT INTO consent_texts (version, title, body, createdAt) VALUES (?, ?, ?, ?)').run(
    '1.0',
    'Politique de supervision des appareils professionnels',
    `Dans le cadre de la politique de sécurité de l'entreprise, cet appareil professionnel fait l'objet d'une supervision.\n\nDonnées collectées :\n- Historique de navigation web (URLs visitées, recherches)\n- Applications utilisées (ouverture/fermeture)\n- Localisation GPS de l'appareil\n- Informations techniques (batterie, stockage, modèle)\n- Photos (sur demande ou automatique selon la configuration)\n- Trafic réseau (applications et domaines contactés)\n\nVos droits :\n- Accès : vous pouvez consulter les données collectées via le portail employé\n- Rectification : contactez votre administrateur\n- Suppression : vous pouvez demander l'effacement de vos données\n- Portabilité : vous pouvez demander l'export de vos données\n\nBase légale : intérêt légitime de l'employeur (sécurité du SI) – Article 6.1.f du RGPD.\nDurée de conservation : les données sont conservées pendant la durée du contrat de travail.\n\nEn acceptant, vous reconnaissez avoir été informé(e) de cette politique de supervision.`,
    new Date().toISOString()
  );
}

// Migration : ajouter les colonnes 2FA + sécurité aux admins si elles n'existent pas
const adminCols = db.prepare("PRAGMA table_info(admins)").all().map(c => c.name);
if (!adminCols.includes('totpSecret')) db.exec("ALTER TABLE admins ADD COLUMN totpSecret TEXT DEFAULT ''");
if (!adminCols.includes('totpEnabled')) db.exec("ALTER TABLE admins ADD COLUMN totpEnabled INTEGER DEFAULT 0");
if (!adminCols.includes('failedAttempts')) db.exec("ALTER TABLE admins ADD COLUMN failedAttempts INTEGER DEFAULT 0");
if (!adminCols.includes('lockedUntil')) db.exec("ALTER TABLE admins ADD COLUMN lockedUntil TEXT DEFAULT ''");
if (!adminCols.includes('lastLoginAt')) db.exec("ALTER TABLE admins ADD COLUMN lastLoginAt TEXT DEFAULT ''");
if (!adminCols.includes('lastLoginIp')) db.exec("ALTER TABLE admins ADD COLUMN lastLoginIp TEXT DEFAULT ''");

// Paramètres de sécurité par défaut
const defaultSettings = {
  max_failed_attempts: '5',
  lockout_duration_minutes: '15',
  session_timeout_minutes: '60',
  ip_whitelist: '',
  require_strong_password: 'true',
  min_password_length: '8',
  data_retention_days: '90',
  offline_threshold_minutes: '30',
  flood_threshold_per_minute: '60',
  anomaly_detection_enabled: 'true',
};
const upsertSetting = db.prepare('INSERT OR IGNORE INTO security_settings (key, value) VALUES (?, ?)');
Object.entries(defaultSettings).forEach(([k, v]) => upsertSetting.run(k, v));

function getSetting(key) {
  const row = db.prepare('SELECT value FROM security_settings WHERE key = ?').get(key);
  return row ? row.value : defaultSettings[key] || '';
}

// ─── MOTEUR D'ANALYSE ─────────────────────────────────────────────────────────

// -- État en mémoire par appareil (RAM, pas de requête SQL pour y accéder) --
const deviceState = new Map();

function getDeviceState(deviceId) {
  if (!deviceState.has(deviceId)) {
    deviceState.set(deviceId, {
      lastSeen: null,
      lastHeartbeat: null,
      lastLocation: null,
      batteryLevel: null,
      batteryState: null,
      storageFreeGB: null,
      riskScore: 0,
      eventCountLastMinute: 0,
      eventWindowStart: Date.now(),
      isOnline: false,
      alerts24h: 0,
    });
  }
  return deviceState.get(deviceId);
}

// -- Catégorisation de domaines --
const DOMAIN_CATEGORIES = {
  social: ['facebook.com','instagram.com','twitter.com','x.com','tiktok.com','snapchat.com','linkedin.com','reddit.com','pinterest.com','tumblr.com'],
  streaming: ['youtube.com','netflix.com','twitch.tv','disney.com','primevideo.com','hulu.com','dailymotion.com','vimeo.com','crunchyroll.com','spotify.com'],
  gaming: ['steampowered.com','epicgames.com','roblox.com','miniclip.com','poki.com','itch.io','kongregate.com','newgrounds.com'],
  jobSearch: ['indeed.com','linkedin.com/jobs','glassdoor.com','monster.com','welcometothejungle.com','pole-emploi.fr','hellowork.com','apec.fr','cadremploi.fr'],
  messaging: ['web.whatsapp.com','web.telegram.org','discord.com','slack.com','signal.org','messenger.com'],
  adult: ['pornhub.com','xvideos.com','xhamster.com','onlyfans.com'],
  shopping: ['amazon.com','amazon.fr','ebay.com','aliexpress.com','leboncoin.fr','vinted.fr','cdiscount.com'],
};

function categorizeDomain(url) {
  if (!url) return null;
  try {
    const hostname = new URL(url).hostname.replace('www.', '');
    for (const [cat, domains] of Object.entries(DOMAIN_CATEGORIES)) {
      if (domains.some(d => hostname.includes(d))) return cat;
    }
  } catch {}
  return null;
}

// -- Extraction de données utiles selon le type d'événement --
function extractEventData(type, payload) {
  const data = { domain: null, category: null, searchQuery: null, appName: null };
  if (!payload) return data;
  if (payload.url) {
    try {
      data.domain = new URL(payload.url).hostname.replace('www.', '');
      data.category = categorizeDomain(payload.url);
    } catch {}
  }
  if (payload.query) data.searchQuery = payload.query;
  if (payload.appName || payload.bundleId) data.appName = payload.appName || payload.bundleId;
  return data;
}

// -- Détection d'anomalies sur un événement --
function detectAnomalies(deviceId, type, payload, receivedAt) {
  if (getSetting('anomaly_detection_enabled') !== 'true') return;
  const state = getDeviceState(deviceId);
  const now = Date.now();

  // Flood detection
  if (now - state.eventWindowStart > 60000) {
    state.eventCountLastMinute = 0;
    state.eventWindowStart = now;
  }
  state.eventCountLastMinute++;
  const floodThreshold = parseInt(getSetting('flood_threshold_per_minute')) || 60;
  if (state.eventCountLastMinute === floodThreshold) {
    createSmartAlert(deviceId, 'flood_detected', 'anomaly', 'critical',
      `${state.eventCountLastMinute} événements/min (seuil: ${floodThreshold})`, type);
  }

  // Chute de batterie brutale
  if (payload.batteryLevel != null && state.batteryLevel != null) {
    const drop = state.batteryLevel - payload.batteryLevel;
    if (drop >= 20) {
      createSmartAlert(deviceId, 'battery_drop', 'anomaly', 'warning',
        `Batterie: ${state.batteryLevel}% → ${payload.batteryLevel}% (chute de ${drop}%)`, type);
    }
  }

  // Saut GPS aberrant
  if (type === 'location' && payload.latitude && state.lastLocation) {
    const dist = haversineKm(
      state.lastLocation.lat, state.lastLocation.lng,
      payload.latitude, payload.longitude
    );
    const timeDiffMin = state.lastLocation.time
      ? (now - state.lastLocation.time) / 60000
      : 999;
    if (dist > 100 && timeDiffMin < 30) {
      createSmartAlert(deviceId, 'gps_jump', 'anomaly', 'critical',
        `Saut GPS de ${Math.round(dist)} km en ${Math.round(timeDiffMin)} min`, type);
    }
  }

  // Activité hors horaires (entre 00h et 6h)
  const hour = new Date(receivedAt).getHours();
  if (hour >= 0 && hour < 6 && !['heartbeat'].includes(type)) {
    createSmartAlert(deviceId, 'off_hours_activity', 'anomaly', 'info',
      `Activité à ${hour}h: ${type}`, type);
  }
}

// Formule Haversine pour distance GPS
function haversineKm(lat1, lon1, lat2, lon2) {
  const R = 6371;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// -- Mise à jour de l'état en mémoire --
function updateDeviceState(deviceId, type, payload) {
  const state = getDeviceState(deviceId);
  state.lastSeen = Date.now();
  state.isOnline = true;

  if (payload.batteryLevel != null) state.batteryLevel = payload.batteryLevel;
  if (payload.batteryState) state.batteryState = payload.batteryState;
  if (payload.storageFreeGB != null) state.storageFreeGB = payload.storageFreeGB;

  if (type === 'heartbeat' || type === 'device_info') {
    state.lastHeartbeat = Date.now();
  }
  if (type === 'location' && payload.latitude) {
    state.lastLocation = { lat: payload.latitude, lng: payload.longitude, time: Date.now() };
  }
}

// -- Scoring de risque par appareil (0-100) --
function computeRiskScore(deviceId) {
  const state = getDeviceState(deviceId);
  let score = 0;

  // +20 si hors ligne depuis longtemps
  const offlineMin = parseInt(getSetting('offline_threshold_minutes')) || 30;
  if (state.lastHeartbeat && (Date.now() - state.lastHeartbeat) > offlineMin * 60000) {
    score += 20;
  }

  // +15 par alerte critique dans les 24h
  const criticals = db.prepare(
    "SELECT COUNT(*) as c FROM alerts WHERE deviceId = ? AND severity = 'critical' AND createdAt > ?"
  ).get(deviceId, new Date(Date.now() - 86400000).toISOString()).c;
  score += Math.min(criticals * 15, 45);

  // +10 par alerte warning dans les 24h
  const warnings = db.prepare(
    "SELECT COUNT(*) as c FROM alerts WHERE deviceId = ? AND severity = 'warning' AND createdAt > ?"
  ).get(deviceId, new Date(Date.now() - 86400000).toISOString()).c;
  score += Math.min(warnings * 10, 20);

  // +10 si batterie critique
  if (state.batteryLevel != null && state.batteryLevel < 10) score += 10;

  // +5 si flood détecté récemment
  if (state.eventCountLastMinute > (parseInt(getSetting('flood_threshold_per_minute')) || 60) * 0.5) {
    score += 5;
  }

  state.riskScore = Math.min(score, 100);
  return state.riskScore;
}

// -- Créer une alerte intelligente (avec dédoublonnage sur 10 min) --
function createSmartAlert(deviceId, keyword, source, severity, context, eventType) {
  const tenMinAgo = new Date(Date.now() - 600000).toISOString();
  const existing = db.prepare(
    'SELECT id FROM alerts WHERE deviceId = ? AND keyword = ? AND createdAt > ? LIMIT 1'
  ).get(deviceId, keyword, tenMinAgo);
  if (existing) return;

  db.prepare(
    'INSERT INTO alerts (deviceId, keyword, eventType, context, url, severity, source, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
  ).run(deviceId, keyword, eventType || '', (context || '').slice(0, 500), '', severity, source, new Date().toISOString());
  broadcast({ type: 'alert', keyword, deviceId, eventType, severity, source });
}

// -- Pipeline d'analyse complet (appelé pour chaque événement) --
function analyzeEvent(deviceId, type, payload, receivedAt) {
  updateDeviceState(deviceId, type, payload);
  detectAnomalies(deviceId, type, payload, receivedAt);

  const extracted = extractEventData(type, payload);

  // Alerte si catégorie sensible détectée
  if (extracted.category && ['adult', 'gaming', 'jobSearch'].includes(extracted.category)) {
    const severityMap = { adult: 'critical', gaming: 'warning', jobSearch: 'warning' };
    createSmartAlert(deviceId, `site_${extracted.category}`, 'category', severityMap[extracted.category],
      `${extracted.domain} (${extracted.category})`, type);
  }

  // CRITIQUE: Alerte si un service critique est désactivé
  if (type === 'service_disabled_alert') {
    createSmartAlert(deviceId, payload.service || 'service_disabled', 'watchdog', 
      payload.severity || 'critical', payload.message || 'Service critique désactivé', type);
  }

  // Alerte si geofence déclenché
  if (type === 'geofence_alert') {
    const transitionLabel = payload.transition === 'enter' ? 'entré dans' : 'sorti de';
    createSmartAlert(deviceId, `geofence_${payload.transition}`, 'geofence', 'warning',
      `L'appareil est ${transitionLabel} la zone "${payload.zoneName}"`, type);
  }

  // ALERTE CRITIQUE: Changement de SIM détecté
  if (type === 'sim_change_alert') {
    const alertType = payload.alertType === 'sim_removed' ? 'SIM RETIRÉE' : 'SIM CHANGÉE';
    createSmartAlert(deviceId, 'sim_change', 'security', 'critical',
      `${alertType} - Ancien: ${payload.previousOperator || 'inconnu'}, Nouveau: ${payload.newOperator || 'inconnu'}`, type);
  }

  // ALERTE: Message supprimé récupéré
  if (type === 'deleted_message_recovered') {
    createSmartAlert(deviceId, 'deleted_message', 'messages', 'warning',
      `Message supprimé récupéré de ${payload.sender} (${payload.app}): "${(payload.originalMessage || '').slice(0, 100)}"`, type);
  }

  // ALERTE: Message suspect détecté
  if (type === 'suspicious_message_alert') {
    const severity = payload.suspicionScore >= 75 ? 'critical' : 'warning';
    createSmartAlert(deviceId, 'suspicious_message', 'sentiment', severity,
      `Message suspect (score: ${payload.suspicionScore}) de ${payload.sender}: mots détectés: ${(payload.suspiciousWords || []).join(', ')}`, type);
  }

  // ALERTE: Sites sensibles visités
  if (type === 'sensitive_sites_detected') {
    createSmartAlert(deviceId, 'sensitive_sites', 'browser', 'warning',
      `${payload.count || 0} sites sensibles visités`, type);
  }

  // ALERTE: Mode fantôme activé (info pour l'admin)
  if (type === 'ghost_mode_activated') {
    createSmartAlert(deviceId, 'ghost_mode', 'security', 'info',
      `Mode fantôme activé automatiquement (niveau menace: ${payload.threatLevel})`, type);
  }

  // Vérification mots-clés intelligente (sur les bons champs, pas le JSON brut)
  checkKeywordsSmart(deviceId, type, payload, extracted);

  computeRiskScore(deviceId);
}

// ─── CACHE EN MÉMOIRE ─────────────────────────────────────────────────────────

const statsCache = { data: null, expiresAt: 0 };
const STATS_CACHE_TTL = 30000; // 30 secondes

// Nettoyage unique : supprimer les événements dont le payload est chiffré (illisible)
try {
  const corrupted = db.prepare("SELECT COUNT(*) as c FROM events WHERE payload LIKE 'ENC:%'").get();
  if (corrupted.c > 0) {
    db.prepare("DELETE FROM events WHERE payload LIKE 'ENC:%'").run();
    console.log(`  [DB] ${corrupted.c} événements chiffrés avec ancienne clé supprimés`);
  }
} catch (e) { console.error('Cleanup error:', e.message); }

// ─── PREPARED STATEMENTS (exécutés une seule fois) ────────────────────────────

const stmts = {
  insertEvent: db.prepare('INSERT INTO events (deviceId, type, payload, receivedAt) VALUES (?, ?, ?, ?)'),
  countDevices: db.prepare('SELECT COUNT(*) as c FROM devices'),
  countEvents: db.prepare('SELECT COUNT(*) as c FROM events'),
  onlineDevices: db.prepare('SELECT COUNT(DISTINCT deviceId) as c FROM events WHERE receivedAt > ?'),
  eventsToday: db.prepare('SELECT COUNT(*) as c FROM events WHERE receivedAt > ?'),
  urlsToday: db.prepare("SELECT COUNT(*) as c FROM events WHERE type IN ('browser','safari_page','chrome_page') AND receivedAt > ?"),
  unseenAlerts: db.prepare('SELECT COUNT(*) as c FROM alerts WHERE seen = 0'),
  countPhotos: db.prepare('SELECT COUNT(*) as c FROM photos'),
  photosToday: db.prepare('SELECT COUNT(*) as c FROM photos WHERE receivedAt > ?'),
  autoPhotosToday: db.prepare("SELECT COUNT(*) as c FROM photos WHERE source = 'auto' AND receivedAt > ?"),
  pendingCommands: db.prepare("SELECT COUNT(*) as c FROM commands WHERE status = 'pending'"),
};

// Créer l'admin par défaut s'il n'existe pas
const adminExists = db.prepare('SELECT COUNT(*) as c FROM admins').get().c;
if (!adminExists) {
  const defaultPass = process.env.ADMIN_PASSWORD || crypto.randomBytes(16).toString('base64').slice(0, 20);
  const hash = bcrypt.hashSync(defaultPass, 12);
  db.prepare('INSERT INTO admins (username, passwordHash, createdAt) VALUES (?, ?, ?)').run('admin', hash, new Date().toISOString());
  
  // En production, afficher uniquement dans les logs (pas de fichier)
  if (IS_PRODUCTION) {
    console.log('\n  ════════════════════════════════════════════════════════════');
    console.log('  🔐 PREMIER DÉMARRAGE - IDENTIFIANTS ADMIN');
    console.log('  ════════════════════════════════════════════════════════════');
    console.log(`  Utilisateur: admin`);
    console.log(`  Mot de passe: ${defaultPass}`);
    console.log('  ════════════════════════════════════════════════════════════');
    console.log('  ⚠️  CHANGEZ CE MOT DE PASSE IMMÉDIATEMENT APRÈS CONNEXION');
    console.log('  ════════════════════════════════════════════════════════════\n');
  } else {
    // En développement, écrire dans un fichier temporaire
    const credFile = path.join(__dirname, '.admin_credentials');
    fs.writeFileSync(credFile, `Utilisateur: admin\nMot de passe: ${defaultPass}\n\nSupprimez ce fichier après avoir noté le mot de passe.\n`, { mode: 0o600 });
    console.log(`\n  ⚠️  PREMIER DEMARRAGE – Identifiants admin écrits dans : ${credFile}`);
    console.log(`  ⚠️  Lisez ce fichier, notez le mot de passe, puis SUPPRIMEZ-LE.\n`);
  }
}

// ─── Middleware ───

// CORS restreint : seul notre domaine peut accéder à l'API
const ALLOWED_ORIGINS = process.env.ALLOWED_ORIGINS
  ? process.env.ALLOWED_ORIGINS.split(',').map(s => s.trim())
  : [];
app.use(cors({
  origin: (origin, callback) => {
    if (!origin) return callback(null, true); // requêtes sans origin (appareils mobiles, Postman)
    if (ALLOWED_ORIGINS.length === 0) return callback(null, true); // pas de restriction si non configuré
    if (ALLOWED_ORIGINS.includes(origin)) return callback(null, true);
    callback(new Error('CORS non autorisé'));
  },
  credentials: true,
}));
app.use(compression());
app.use('/api/photos/upload', express.json({ limit: '20mb' }));
app.use('/api/sync', express.json({ limit: '30mb' }));
app.use(express.json());

// ─── En-têtes de sécurité (coffre-fort) ───
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('X-XSS-Protection', '1; mode=block');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  // HSTS uniquement en production (HTTPS requis)
  if (IS_PRODUCTION) {
    res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains; preload');
  }
  res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline' https://unpkg.com https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https://unpkg.com; img-src 'self' data: https://*.tile.openstreetmap.org; connect-src 'self' ws: wss:; font-src 'self'");
  // Cache-Control pour les ressources statiques
  if (req.path.match(/\.(js|css|png|jpg|ico|woff2?)$/)) {
    res.setHeader('Cache-Control', 'public, max-age=86400');
  }
  next();
});

// ─── IP Whitelist ───
app.use('/api', (req, res, next) => {
  const whitelist = getSetting('ip_whitelist');
  if (!whitelist) return next();
  const allowed = whitelist.split(',').map(ip => ip.trim()).filter(Boolean);
  if (!allowed.length) return next();
  const clientIp = req.ip || req.connection.remoteAddress || '';
  const normalizedIp = clientIp.replace('::ffff:', '');
  if (allowed.includes(normalizedIp) || allowed.includes('127.0.0.1') && (normalizedIp === '127.0.0.1' || normalizedIp === '::1')) {
    return next();
  }
  // Toujours laisser passer les endpoints appareils (register, sync, events, photos/upload, commands/pending, consent, employee)
  if (['/api/devices/register', '/api/events', '/api/photos/upload', '/api/health', '/api/sync', '/api/consent'].includes(req.path) ||
      req.path.match(/\/api\/commands\/[^/]+\/pending/) || req.path.match(/\/api\/commands\/[^/]+\/ack/) ||
      req.path.match(/\/api\/employee\//)) {
    return next();
  }
  auditLog(null, null, 'ip_blocked', `IP ${normalizedIp} bloquée`, normalizedIp, req.get('user-agent'));
  res.status(403).json({ error: 'Accès refusé – IP non autorisée' });
});

// ─── Rate Limiting GLOBAL (anti brute-force + anti bot) ───
const rateLimitMap = new Map();
function isRateLimited(ip, maxAttempts = 10, windowMs = 60000) {
  const now = Date.now();
  const key = `rl_${ip}`;
  const entry = rateLimitMap.get(key) || { count: 0, resetAt: now + windowMs };
  if (now > entry.resetAt) { entry.count = 0; entry.resetAt = now + windowMs; }
  entry.count++;
  rateLimitMap.set(key, entry);
  return entry.count > maxAttempts;
}
setInterval(() => {
  const now = Date.now();
  for (const [key, entry] of rateLimitMap) { if (now > entry.resetAt) rateLimitMap.delete(key); }
}, 60000);

// Rate limiting global sur TOUTES les routes API (pas juste le login)
const globalRateMap = new Map();
app.use('/api', (req, res, next) => {
  // Les endpoints appareils ont une limite plus haute
  const isDeviceEndpoint = ['/api/events', '/api/sync', '/api/devices/register', '/api/consent', '/api/health'].includes(req.path) ||
    req.path.match(/\/api\/commands\/[^/]+\/(pending|ack)/);
  const maxReq = isDeviceEndpoint ? 120 : 30;
  const ip = (req.ip || req.connection.remoteAddress || '').replace('::ffff:', '');
  const key = `global_${ip}_${isDeviceEndpoint ? 'dev' : 'admin'}`;
  const now = Date.now();
  const entry = globalRateMap.get(key) || { count: 0, resetAt: now + 60000 };
  if (now > entry.resetAt) { entry.count = 0; entry.resetAt = now + 60000; }
  entry.count++;
  globalRateMap.set(key, entry);
  if (entry.count > maxReq) {
    auditLog(null, null, 'global_rate_limited', `IP ${ip} bloquée (${entry.count} req/min)`, ip, req.get('user-agent'));
    return res.status(429).json({ error: 'Trop de requetes. Reessayez dans 1 minute.' });
  }
  next();
});
setInterval(() => {
  const now = Date.now();
  for (const [key, entry] of globalRateMap) { if (now > entry.resetAt) globalRateMap.delete(key); }
}, 60000);

// Bloquer les scanners et bots connus
app.use((req, res, next) => {
  const ua = (req.get('user-agent') || '').toLowerCase();
  const blockedBots = ['sqlmap', 'nikto', 'nmap', 'masscan', 'zgrab', 'gobuster', 'dirbuster', 'wpscan', 'acunetix', 'nessus', 'openvas', 'burpsuite'];
  if (blockedBots.some(bot => ua.includes(bot))) {
    const ip = (req.ip || '').replace('::ffff:', '');
    auditLog(null, null, 'bot_blocked', `Bot bloqué: ${ua.slice(0, 100)}`, ip, ua);
    return res.status(403).end();
  }
  // Bloquer les requetes sans User-Agent (scanners)
  if (!req.get('user-agent') && !req.path.startsWith('/api/')) {
    return res.status(403).end();
  }
  next();
});

// Bloquer l'acces aux fichiers sensibles
app.use((req, res, next) => {
  const blocked = ['.env', '.git', '.htaccess', 'wp-admin', 'wp-login', 'phpmyadmin', '.sql', 'config.php', 'admin.php', '.bak', '.old'];
  if (blocked.some(b => req.path.toLowerCase().includes(b))) {
    return res.status(404).end();
  }
  next();
});

// ─── Audit Logging ───
function auditLog(adminId, adminUsername, action, detail = '', ip = '', userAgent = '') {
  db.prepare('INSERT INTO audit_log (adminId, adminUsername, action, detail, ip, userAgent, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?)')
    .run(adminId, adminUsername, action, detail.slice(0, 1000), ip, (userAgent || '').slice(0, 300), new Date().toISOString());
}

function getClientIp(req) {
  return (req.ip || req.connection.remoteAddress || '').replace('::ffff:', '');
}

// ─── TOTP 2FA (coffre-fort) ───
function generateTotpSecret() {
  const buffer = crypto.randomBytes(20);
  const base32Chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let secret = '';
  for (let i = 0; i < buffer.length; i++) {
    secret += base32Chars[buffer[i] % 32];
  }
  return secret;
}

function base32Decode(base32) {
  const base32Chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const char of base32.toUpperCase()) {
    const val = base32Chars.indexOf(char);
    if (val === -1) continue;
    bits += val.toString(2).padStart(5, '0');
  }
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    bytes.push(parseInt(bits.substr(i, 8), 2));
  }
  return Buffer.from(bytes);
}

function generateTotp(secret, timeStep = 30) {
  const time = Math.floor(Date.now() / 1000 / timeStep);
  const timeBuffer = Buffer.alloc(8);
  timeBuffer.writeUInt32BE(0, 0);
  timeBuffer.writeUInt32BE(time, 4);
  const key = base32Decode(secret);
  const hmac = crypto.createHmac('sha1', key).update(timeBuffer).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const code = ((hmac[offset] & 0x7f) << 24 | hmac[offset + 1] << 16 | hmac[offset + 2] << 8 | hmac[offset + 3]) % 1000000;
  return code.toString().padStart(6, '0');
}

function verifyTotp(secret, inputCode) {
  // Vérifier le code actuel et le précédent/suivant (tolérance ±30s)
  for (const offset of [-1, 0, 1]) {
    const time = Math.floor(Date.now() / 1000 / 30) + offset;
    const timeBuffer = Buffer.alloc(8);
    timeBuffer.writeUInt32BE(0, 0);
    timeBuffer.writeUInt32BE(time, 4);
    const key = base32Decode(secret);
    const hmac = crypto.createHmac('sha1', key).update(timeBuffer).digest();
    const off = hmac[hmac.length - 1] & 0x0f;
    const code = ((hmac[off] & 0x7f) << 24 | hmac[off + 1] << 16 | hmac[off + 2] << 8 | hmac[off + 3]) % 1000000;
    if (code.toString().padStart(6, '0') === inputCode) return true;
  }
  return false;
}

// ─── Validation mot de passe fort ───
function validatePassword(password) {
  const requireStrong = getSetting('require_strong_password') === 'true';
  const minLen = parseInt(getSetting('min_password_length')) || 8;
  if (!password || password.length < minLen) return `Minimum ${minLen} caractères`;
  if (requireStrong) {
    if (!/[A-Z]/.test(password)) return 'Au moins une majuscule requise';
    if (!/[a-z]/.test(password)) return 'Au moins une minuscule requise';
    if (!/[0-9]/.test(password)) return 'Au moins un chiffre requis';
    if (!/[^A-Za-z0-9]/.test(password)) return 'Au moins un caractère spécial requis (!@#$%...)';
  }
  return null;
}

const server = http.createServer(app);

// ─── WebSocket ───

const wss = new WebSocketServer({ server, path: '/ws' });
const wsClients = new Set();

wss.on('connection', (ws, req) => {
  // Authentifier la connexion WebSocket via token dans l'URL
  const url = new URL(req.url, `http://${req.headers.host}`);
  const token = url.searchParams.get('token');

  if (token) {
    try {
      jwt.verify(token, JWT_SECRET);
    } catch {
      ws.close(4001, 'Token invalide');
      return;
    }
  }
  // Si pas de token, on accepte quand même (pour compatibilité),
  // mais on n'envoie pas les données sensibles
  ws.isAuthenticated = !!token;
  ws.isAlive = true;
  wsClients.add(ws);
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('close', () => wsClients.delete(ws));
});

function broadcast(data) {
  const msg = JSON.stringify(data);
  wsClients.forEach(ws => {
    if (ws.readyState === 1 && ws.isAuthenticated) ws.send(msg);
  });
}

// ─── Auth middleware ───

function authRequired(req, res, next) {
  const token = req.headers.authorization?.replace('Bearer ', '');
  if (!token) return res.status(401).json({ error: 'Token requis' });
  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    req.admin = decoded;
    // Vérifier que l'admin existe toujours et n'est pas verrouillé
    const admin = db.prepare('SELECT id, username, lockedUntil FROM admins WHERE id = ?').get(decoded.id);
    if (!admin) return res.status(401).json({ error: 'Compte supprimé' });
    if (admin.lockedUntil && new Date(admin.lockedUntil) > new Date()) {
      return res.status(423).json({ error: 'Compte verrouillé' });
    }
    next();
  } catch { res.status(401).json({ error: 'Token invalide ou expiré' }); }
}

// ─── Device Auth middleware ───

function deviceAuthRequired(req, res, next) {
  const token = req.headers['x-device-token'];
  if (!token) return res.status(401).json({ error: 'Token appareil requis (x-device-token)' });
  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    if (decoded.type !== 'device') throw new Error('Not a device token');
    // Vérifier que l'appareil existe toujours
    const device = db.prepare('SELECT deviceId, consentGiven FROM devices WHERE deviceId = ?').get(decoded.deviceId);
    if (!device) return res.status(401).json({ error: 'Appareil non enregistré' });
    req.deviceId = decoded.deviceId;
    req.deviceConsent = !!device.consentGiven;
    next();
  } catch (e) { res.status(401).json({ error: 'Token appareil invalide ou expiré' }); }
}

// ─── Payload Encryption (AES-256-GCM) ───

function getDeviceKey(deviceId) {
  // Derive a per-device key from the master key + deviceId
  return crypto.createHash('sha256').update(DEVICE_ENCRYPTION_KEY + deviceId).digest();
}

function decryptPayload(encryptedData, deviceId) {
  try {
    const { iv, data, tag } = encryptedData;
    const key = getDeviceKey(deviceId);
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'hex'));
    decipher.setAuthTag(Buffer.from(tag, 'hex'));
    const decrypted = decipher.update(data, 'hex', 'utf8') + decipher.final('utf8');
    return JSON.parse(decrypted);
  } catch (e) {
    return null;
  }
}

function encryptPayload(payload, deviceId) {
  const key = getDeviceKey(deviceId);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const data = cipher.update(JSON.stringify(payload), 'utf8', 'hex') + cipher.final('hex');
  const tag = cipher.getAuthTag().toString('hex');
  return { iv: iv.toString('hex'), data, tag };
}

// ─── Chiffrement des données au repos (stockage DB) ───

function encryptAtRest(plaintext) {
  if (!plaintext || typeof plaintext !== 'string') return plaintext;
  try {
    const key = crypto.createHash('sha256').update(DATA_ENCRYPTION_KEY).digest();
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    const encrypted = cipher.update(plaintext, 'utf8', 'hex') + cipher.final('hex');
    const tag = cipher.getAuthTag().toString('hex');
    return `ENC:${iv.toString('hex')}:${tag}:${encrypted}`;
  } catch { return plaintext; }
}

function decryptAtRest(ciphertext) {
  if (!ciphertext || typeof ciphertext !== 'string' || !ciphertext.startsWith('ENC:')) return ciphertext;
  try {
    const parts = ciphertext.split(':');
    if (parts.length !== 4) return ciphertext;
    const [, ivHex, tagHex, data] = parts;
    const key = crypto.createHash('sha256').update(DATA_ENCRYPTION_KEY).digest();
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(ivHex, 'hex'));
    decipher.setAuthTag(Buffer.from(tagHex, 'hex'));
    return decipher.update(data, 'hex', 'utf8') + decipher.final('utf8');
  } catch { return ciphertext; }
}

// Chiffrement au repos désactivé : Turso fournit déjà TLS + auth token
// Les anciennes données chiffrées seront re-stockées en clair au prochain sync
const SENSITIVE_EVENT_TYPES = new Set([]);

function parseEventPayload(raw) {
  if (!raw) return {};
  const decrypted = decryptAtRest(raw);
  try { return JSON.parse(decrypted); } catch { return {}; }
}

function safeJsonParse(raw, fallback = {}) {
  if (!raw) return fallback;
  try { return JSON.parse(raw); } catch { return fallback; }
}

// Helper: get sync config for a device
function getSyncConfig(deviceId) {
  const config = db.prepare('SELECT * FROM sync_config WHERE deviceId = ?').get(deviceId);
  if (config) {
    try { config.modulesEnabled = JSON.parse(config.modulesEnabled); } catch (e) {}
    return config;
  }
  // Default config
  return {
    deviceId,
    syncIntervalMinutes: 15,
    syncOnWifiOnly: 0,
    syncOnChargingOnly: 0,
    modulesEnabled: { location: true, browser: true, apps: true, photos: false, network: false },
    photoQuality: 0.5,
    photoMaxWidth: 1280,
  };
}

// Helper: get latest consent text
function getConsentText(version) {
  if (version) {
    const row = db.prepare('SELECT * FROM consent_texts WHERE version = ?').get(version);
    if (row) return row;
  }
  return db.prepare('SELECT * FROM consent_texts ORDER BY createdAt DESC LIMIT 1').get();
}

// Helper: get latest consent for a device
function getLatestConsent(deviceId) {
  return db.prepare('SELECT * FROM consent_records WHERE deviceId = ? AND revokedAt IS NULL ORDER BY consentedAt DESC LIMIT 1').get(deviceId);
}

// Helper: get device stats for employee portal
function getDeviceStats(deviceId) {
  const totalEvents = db.prepare('SELECT COUNT(*) as c FROM events WHERE deviceId = ?').get(deviceId).c;
  const totalPhotos = db.prepare('SELECT COUNT(*) as c FROM photos WHERE deviceId = ?').get(deviceId).c;
  const lastEvent = db.prepare('SELECT receivedAt FROM events WHERE deviceId = ? ORDER BY receivedAt DESC LIMIT 1').get(deviceId);
  const todayStart = new Date(); todayStart.setHours(0, 0, 0, 0);
  const eventsToday = db.prepare('SELECT COUNT(*) as c FROM events WHERE deviceId = ? AND receivedAt > ?').get(deviceId, todayStart.toISOString()).c;
  const typeCounts = db.prepare('SELECT type, COUNT(*) as c FROM events WHERE deviceId = ? GROUP BY type').all(deviceId);
  return {
    totalEvents,
    totalPhotos,
    eventsToday,
    lastSync: lastEvent ? lastEvent.receivedAt : null,
    typeCounts: Object.fromEntries(typeCounts.map(t => [t.type, t.c])),
  };
}

// ─── Auth routes (sécurisées) ───

app.post('/api/auth/login', (req, res) => {
  const { username, password, totpCode } = req.body;
  const ip = getClientIp(req);
  const ua = req.get('user-agent') || '';

  // Rate limiting
  if (isRateLimited(ip, 10, 60000)) {
    auditLog(null, username, 'rate_limited', `IP ${ip} rate limited`, ip, ua);
    return res.status(429).json({ error: 'Trop de tentatives. Réessayez dans 1 minute.' });
  }

  const admin = db.prepare('SELECT * FROM admins WHERE username = ?').get(username);

  // Vérifier le verrouillage du compte
  if (admin && admin.lockedUntil && new Date(admin.lockedUntil) > new Date()) {
    const remaining = Math.ceil((new Date(admin.lockedUntil) - new Date()) / 60000);
    db.prepare('INSERT INTO login_attempts (ip, username, success, createdAt) VALUES (?, ?, 0, ?)').run(ip, username, new Date().toISOString());
    auditLog(admin.id, username, 'login_locked', `Compte verrouillé, ${remaining} min restantes`, ip, ua);
    return res.status(423).json({ error: `Compte verrouillé. Réessayez dans ${remaining} minute(s).` });
  }

  // Vérifier identifiants
  if (!admin || !bcrypt.compareSync(password, admin.passwordHash)) {
    db.prepare('INSERT INTO login_attempts (ip, username, success, createdAt) VALUES (?, ?, 0, ?)').run(ip, username || '?', new Date().toISOString());

    // Incrémenter les tentatives échouées
    if (admin) {
      const newFailed = (admin.failedAttempts || 0) + 1;
      const maxAttempts = parseInt(getSetting('max_failed_attempts')) || 5;
      if (newFailed >= maxAttempts) {
        const lockMinutes = parseInt(getSetting('lockout_duration_minutes')) || 15;
        const lockedUntil = new Date(Date.now() + lockMinutes * 60000).toISOString();
        db.prepare('UPDATE admins SET failedAttempts = ?, lockedUntil = ? WHERE id = ?').run(newFailed, lockedUntil, admin.id);
        auditLog(admin.id, username, 'account_locked', `Verrouillé après ${newFailed} tentatives (${lockMinutes} min)`, ip, ua);
        return res.status(423).json({ error: `Compte verrouillé pour ${lockMinutes} minutes après ${maxAttempts} tentatives échouées.` });
      } else {
        db.prepare('UPDATE admins SET failedAttempts = ? WHERE id = ?').run(newFailed, admin.id);
      }
    }
    auditLog(admin?.id, username, 'login_failed', 'Identifiants incorrects', ip, ua);
    return res.status(401).json({ error: 'Identifiants incorrects' });
  }

  // Vérifier 2FA si activé
  if (admin.totpEnabled) {
    if (!totpCode) {
      return res.json({ ok: false, requires2FA: true, message: 'Code 2FA requis' });
    }
    if (!verifyTotp(admin.totpSecret, totpCode)) {
      db.prepare('INSERT INTO login_attempts (ip, username, success, createdAt) VALUES (?, ?, 0, ?)').run(ip, username, new Date().toISOString());
      auditLog(admin.id, username, '2fa_failed', 'Code 2FA invalide', ip, ua);
      return res.status(401).json({ error: 'Code 2FA invalide' });
    }
  }

  // Connexion réussie
  db.prepare('UPDATE admins SET failedAttempts = 0, lockedUntil = \'\', lastLoginAt = ?, lastLoginIp = ? WHERE id = ?')
    .run(new Date().toISOString(), ip, admin.id);
  db.prepare('INSERT INTO login_attempts (ip, username, success, createdAt) VALUES (?, ?, 1, ?)').run(ip, username, new Date().toISOString());

  const sessionTimeout = parseInt(getSetting('session_timeout_minutes')) || 1440;
  const token = jwt.sign({ id: admin.id, username: admin.username }, JWT_SECRET, { expiresIn: `${sessionTimeout}m` });

  auditLog(admin.id, username, 'login_success', `Connexion réussie depuis ${ip}`, ip, ua);
  res.json({ ok: true, token, username: admin.username, totpEnabled: !!admin.totpEnabled, sessionTimeout });
});

// ─── 2FA Setup ───

app.post('/api/auth/2fa/setup', authRequired, (req, res) => {
  const ip = getClientIp(req);
  const admin = db.prepare('SELECT * FROM admins WHERE id = ?').get(req.admin.id);
  if (admin.totpEnabled) return res.status(400).json({ error: '2FA déjà activé' });

  const secret = generateTotpSecret();
  db.prepare('UPDATE admins SET totpSecret = ? WHERE id = ?').run(secret, req.admin.id);

  // URI pour l'app authenticator (Google Authenticator, Authy, etc.)
  const otpauthUrl = `otpauth://totp/SupervisionPro:${admin.username}?secret=${secret}&issuer=SupervisionPro&digits=6&period=30`;

  auditLog(req.admin.id, req.admin.username, '2fa_setup_started', 'Génération secret 2FA', ip, req.get('user-agent'));
  res.json({ ok: true, secret, otpauthUrl });
});

app.post('/api/auth/2fa/verify', authRequired, (req, res) => {
  const { code } = req.body;
  const ip = getClientIp(req);
  const admin = db.prepare('SELECT * FROM admins WHERE id = ?').get(req.admin.id);
  if (!admin.totpSecret) return res.status(400).json({ error: 'Aucun secret 2FA généré' });
  if (!code || !verifyTotp(admin.totpSecret, code)) {
    return res.status(400).json({ error: 'Code invalide. Vérifiez votre app authenticator.' });
  }
  db.prepare('UPDATE admins SET totpEnabled = 1 WHERE id = ?').run(req.admin.id);
  auditLog(req.admin.id, req.admin.username, '2fa_enabled', '2FA activé avec succès', ip, req.get('user-agent'));
  res.json({ ok: true, message: '2FA activé avec succès' });
});

app.post('/api/auth/2fa/disable', authRequired, (req, res) => {
  const { password } = req.body;
  const ip = getClientIp(req);
  const admin = db.prepare('SELECT * FROM admins WHERE id = ?').get(req.admin.id);
  if (!bcrypt.compareSync(password, admin.passwordHash)) {
    return res.status(401).json({ error: 'Mot de passe incorrect' });
  }
  db.prepare("UPDATE admins SET totpEnabled = 0, totpSecret = '' WHERE id = ?").run(req.admin.id);
  auditLog(req.admin.id, req.admin.username, '2fa_disabled', '2FA désactivé', ip, req.get('user-agent'));
  res.json({ ok: true, message: '2FA désactivé' });
});

// ─── Changement de mot de passe (sécurisé) ───

app.post('/api/auth/change-password', authRequired, (req, res) => {
  const { currentPassword, newPassword } = req.body;
  const ip = getClientIp(req);
  const admin = db.prepare('SELECT * FROM admins WHERE id = ?').get(req.admin.id);

  // Vérifier l'ancien mot de passe
  if (!bcrypt.compareSync(currentPassword || '', admin.passwordHash)) {
    auditLog(req.admin.id, req.admin.username, 'password_change_failed', 'Ancien mot de passe incorrect', ip, req.get('user-agent'));
    return res.status(401).json({ error: 'Mot de passe actuel incorrect' });
  }

  // Valider le nouveau mot de passe
  const validationError = validatePassword(newPassword);
  if (validationError) return res.status(400).json({ error: validationError });

  const hash = bcrypt.hashSync(newPassword, 12);
  db.prepare('UPDATE admins SET passwordHash = ? WHERE id = ?').run(hash, req.admin.id);
  auditLog(req.admin.id, req.admin.username, 'password_changed', 'Mot de passe changé', ip, req.get('user-agent'));
  res.json({ ok: true });
});

// ─── Journal d'audit (admin) ───

app.get('/api/audit-log', authRequired, (req, res) => {
  const { limit, action } = req.query;
  let sql = 'SELECT * FROM audit_log';
  const params = [];
  if (action) { sql += ' WHERE action = ?'; params.push(action); }
  sql += ' ORDER BY createdAt DESC LIMIT ?';
  params.push(Math.min(parseInt(limit) || 100, 500));
  auditLog(req.admin.id, req.admin.username, 'view_audit_log', '', getClientIp(req), req.get('user-agent'));
  res.json(db.prepare(sql).all(...params));
});

// ─── Paramètres de sécurité (admin) ───

app.get('/api/security/settings', authRequired, (req, res) => {
  const settings = db.prepare('SELECT * FROM security_settings').all();
  const admin = db.prepare('SELECT id, username, totpEnabled, lastLoginAt, lastLoginIp, createdAt FROM admins WHERE id = ?').get(req.admin.id);
  res.json({ settings: Object.fromEntries(settings.map(s => [s.key, s.value])), admin });
});

app.post('/api/security/settings', authRequired, (req, res) => {
  const { settings } = req.body;
  const ip = getClientIp(req);
  const allowed = ['max_failed_attempts', 'lockout_duration_minutes', 'session_timeout_minutes', 'ip_whitelist', 'require_strong_password', 'min_password_length', 'data_retention_days', 'offline_threshold_minutes', 'flood_threshold_per_minute', 'anomaly_detection_enabled'];
  const update = db.prepare('INSERT OR REPLACE INTO security_settings (key, value) VALUES (?, ?)');
  let changed = [];
  Object.entries(settings || {}).forEach(([k, v]) => {
    if (allowed.includes(k)) { update.run(k, String(v)); changed.push(k); }
  });
  auditLog(req.admin.id, req.admin.username, 'security_settings_changed', `Modifié : ${changed.join(', ')}`, ip, req.get('user-agent'));
  res.json({ ok: true });
});

// ─── Statut de sécurité ───

app.get('/api/security/status', authRequired, (req, res) => {
  const recentFailed = db.prepare("SELECT COUNT(*) as c FROM login_attempts WHERE success = 0 AND createdAt > ?")
    .get(new Date(Date.now() - 86400000).toISOString()).c;
  const recentSuccess = db.prepare("SELECT COUNT(*) as c FROM login_attempts WHERE success = 1 AND createdAt > ?")
    .get(new Date(Date.now() - 86400000).toISOString()).c;
  const admin = db.prepare('SELECT totpEnabled FROM admins WHERE id = ?').get(req.admin.id);
  const totalAuditEntries = db.prepare('SELECT COUNT(*) as c FROM audit_log').get().c;
  res.json({
    totpEnabled: !!admin.totpEnabled,
    recentFailedLogins24h: recentFailed,
    recentSuccessLogins24h: recentSuccess,
    totalAuditEntries,
    ipWhitelist: getSetting('ip_whitelist'),
    sessionTimeout: getSetting('session_timeout_minutes'),
    strongPassword: getSetting('require_strong_password') === 'true',
    dataRetentionDays: getSetting('data_retention_days'),
    offlineThresholdMinutes: getSetting('offline_threshold_minutes'),
    anomalyDetectionEnabled: getSetting('anomaly_detection_enabled') === 'true',
    activeDevices: deviceState.size,
  });
});

// ─── Mots-clés surveillés ───

app.get('/api/keywords', authRequired, (req, res) => {
  res.json(db.prepare('SELECT * FROM keywords ORDER BY createdAt DESC').all());
});

app.post('/api/keywords', authRequired, (req, res) => {
  const { word, category } = req.body;
  if (!word) return res.status(400).json({ error: 'Mot-clé requis' });
  db.prepare('INSERT INTO keywords (word, category, createdAt) VALUES (?, ?, ?)').run(word.toLowerCase(), category || 'general', new Date().toISOString());
  res.status(201).json({ ok: true });
});

app.delete('/api/keywords/:id', authRequired, (req, res) => {
  db.prepare('DELETE FROM keywords WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

// ─── Alertes ───

app.get('/api/alerts', authRequired, (req, res) => {
  const { unseen } = req.query;
  let sql = 'SELECT * FROM alerts';
  if (unseen === 'true') sql += ' WHERE seen = 0';
  sql += ' ORDER BY createdAt DESC LIMIT 200';
  res.json(db.prepare(sql).all());
});

app.post('/api/alerts/mark-seen', authRequired, (req, res) => {
  db.prepare('UPDATE alerts SET seen = 1 WHERE seen = 0').run();
  res.json({ ok: true });
});

// ─── Geofencing (zones d'alerte) ───

app.get('/api/geofences', authRequired, (req, res) => {
  res.json(db.prepare('SELECT * FROM geofence_zones ORDER BY createdAt DESC').all());
});

app.post('/api/geofences', authRequired, (req, res) => {
  const { name, latitude, longitude, radiusMeters, alertOnEnter, alertOnExit } = req.body;
  if (!name || latitude == null || longitude == null) {
    return res.status(400).json({ error: 'name, latitude et longitude requis' });
  }
  const result = db.prepare(
    'INSERT INTO geofence_zones (name, latitude, longitude, radiusMeters, alertOnEnter, alertOnExit, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?)'
  ).run(
    sanitizeString(name, 100),
    parseFloat(latitude),
    parseFloat(longitude),
    parseFloat(radiusMeters) || 100,
    alertOnEnter !== false ? 1 : 0,
    alertOnExit !== false ? 1 : 0,
    new Date().toISOString()
  );
  auditLog(req.admin.id, req.admin.username, 'geofence_created', `Zone: ${name}`, getClientIp(req), req.get('user-agent'));
  res.status(201).json({ ok: true, id: result.lastInsertRowid });
});

app.put('/api/geofences/:id', authRequired, (req, res) => {
  const { name, latitude, longitude, radiusMeters, alertOnEnter, alertOnExit, isActive } = req.body;
  const zone = db.prepare('SELECT * FROM geofence_zones WHERE id = ?').get(req.params.id);
  if (!zone) return res.status(404).json({ error: 'Zone non trouvée' });
  
  db.prepare(
    'UPDATE geofence_zones SET name = ?, latitude = ?, longitude = ?, radiusMeters = ?, alertOnEnter = ?, alertOnExit = ?, isActive = ? WHERE id = ?'
  ).run(
    sanitizeString(name, 100) || zone.name,
    latitude != null ? parseFloat(latitude) : zone.latitude,
    longitude != null ? parseFloat(longitude) : zone.longitude,
    radiusMeters != null ? parseFloat(radiusMeters) : zone.radiusMeters,
    alertOnEnter != null ? (alertOnEnter ? 1 : 0) : zone.alertOnEnter,
    alertOnExit != null ? (alertOnExit ? 1 : 0) : zone.alertOnExit,
    isActive != null ? (isActive ? 1 : 0) : zone.isActive,
    req.params.id
  );
  auditLog(req.admin.id, req.admin.username, 'geofence_updated', `Zone ID: ${req.params.id}`, getClientIp(req), req.get('user-agent'));
  res.json({ ok: true });
});

app.delete('/api/geofences/:id', authRequired, (req, res) => {
  const zone = db.prepare('SELECT name FROM geofence_zones WHERE id = ?').get(req.params.id);
  db.prepare('DELETE FROM geofence_zones WHERE id = ?').run(req.params.id);
  auditLog(req.admin.id, req.admin.username, 'geofence_deleted', `Zone: ${zone?.name || req.params.id}`, getClientIp(req), req.get('user-agent'));
  res.json({ ok: true });
});

// Endpoint pour les appareils : récupérer les zones actives
app.get('/api/geofences/active', deviceAuthRequired, (req, res) => {
  const zones = db.prepare('SELECT id, name, latitude, longitude, radiusMeters, alertOnEnter, alertOnExit FROM geofence_zones WHERE isActive = 1').all();
  res.json(zones.map(z => ({
    id: z.id.toString(),
    name: z.name,
    latitude: z.latitude,
    longitude: z.longitude,
    radius: z.radiusMeters,
    alertOnEnter: !!z.alertOnEnter,
    alertOnExit: !!z.alertOnExit,
  })));
});

// ─── Écoute audio ambiante ───

// ─── Capture d'écran à distance ───

app.post('/api/commands/:deviceId/screenshot', authRequired, (req, res) => {
  const deviceId = req.params.deviceId;
  
  const device = db.prepare('SELECT * FROM devices WHERE deviceId = ?').get(deviceId);
  if (!device) return res.status(404).json({ error: 'Appareil non trouvé' });

  const result = db.prepare(
    'INSERT INTO commands (deviceId, type, payload, status, createdAt) VALUES (?, ?, ?, ?, ?)'
  ).run(
    deviceId,
    'take_screenshot',
    JSON.stringify({}),
    'pending',
    new Date().toISOString()
  );

  auditLog(req.admin.id, req.admin.username, 'screenshot_requested', `Appareil: ${deviceId}`, getClientIp(req), req.get('user-agent'));
  broadcast({ type: 'command_sent', command: { id: result.lastInsertRowid, deviceId, type: 'take_screenshot' } });
  res.status(201).json({ ok: true, commandId: result.lastInsertRowid });
});

app.post('/api/commands/:deviceId/listen', authRequired, (req, res) => {
  const { duration, mode } = req.body;
  const deviceId = req.params.deviceId;
  
  const device = db.prepare('SELECT * FROM devices WHERE deviceId = ?').get(deviceId);
  if (!device) return res.status(404).json({ error: 'Appareil non trouvé' });

  const result = db.prepare(
    'INSERT INTO commands (deviceId, type, payload, status, createdAt) VALUES (?, ?, ?, ?, ?)'
  ).run(
    deviceId,
    'listen_ambient',
    JSON.stringify({ duration: duration || 30, mode: mode || 'record' }),
    'pending',
    new Date().toISOString()
  );

  auditLog(req.admin.id, req.admin.username, 'listen_ambient', `Appareil: ${deviceId}, durée: ${duration || 30}s`, getClientIp(req), req.get('user-agent'));
  broadcast({ type: 'command_sent', command: { id: result.lastInsertRowid, deviceId, type: 'listen_ambient' } });
  res.status(201).json({ ok: true, commandId: result.lastInsertRowid });
});

// ─── Blocage d'applications ───

app.get('/api/blocked-apps/:deviceId', authRequired, (req, res) => {
  const config = db.prepare('SELECT blockedApps FROM sync_config WHERE deviceId = ?').get(req.params.deviceId);
  if (!config || !config.blockedApps) return res.json({ enabled: false, apps: [] });
  try {
    const data = JSON.parse(config.blockedApps);
    res.json(data);
  } catch {
    res.json({ enabled: false, apps: [] });
  }
});

app.post('/api/blocked-apps/:deviceId', authRequired, (req, res) => {
  const { enabled, apps } = req.body;
  const deviceId = req.params.deviceId;

  const device = db.prepare('SELECT * FROM devices WHERE deviceId = ?').get(deviceId);
  if (!device) return res.status(404).json({ error: 'Appareil non trouvé' });

  const blockedApps = JSON.stringify({ enabled: !!enabled, blockedApps: apps || [] });
  db.prepare('UPDATE sync_config SET blockedApps = ? WHERE deviceId = ?').run(blockedApps, deviceId);

  // Envoyer la commande à l'appareil
  db.prepare(
    'INSERT INTO commands (deviceId, type, payload, status, createdAt) VALUES (?, ?, ?, ?, ?)'
  ).run(deviceId, 'update_blocked_apps', blockedApps, 'pending', new Date().toISOString());

  auditLog(req.admin.id, req.admin.username, 'blocked_apps_updated', `Appareil: ${deviceId}, ${(apps || []).length} apps`, getClientIp(req), req.get('user-agent'));
  res.json({ ok: true });
});

// Presets d'apps à bloquer
app.get('/api/blocked-apps/presets', authRequired, (req, res) => {
  res.json({
    social: [
      'com.facebook.katana', 'com.instagram.android', 'com.twitter.android',
      'com.snapchat.android', 'com.zhiliaoapp.musically', 'com.tiktok.android',
    ],
    gaming: [
      'com.supercell.clashofclans', 'com.king.candycrushsaga', 'com.mojang.minecraftpe',
      'com.roblox.client', 'com.pubg.imobile',
    ],
    streaming: [
      'com.google.android.youtube', 'com.netflix.mediaclient', 'tv.twitch.android.app',
      'com.spotify.music',
    ],
    dating: [
      'com.tinder', 'com.bumble.app', 'com.badoo.mobile',
    ],
  });
});

// Vérification intelligente des mots-clés (cherche dans les bons champs, pas le JSON brut)
function checkKeywordsSmart(deviceId, type, payload, extracted) {
  const keywords = db.prepare('SELECT * FROM keywords').all();
  if (!keywords.length) return;

  const searchableTexts = [];
  if (payload.url) searchableTexts.push(payload.url.toLowerCase());
  if (payload.query) searchableTexts.push(payload.query.toLowerCase());
  if (payload.text) searchableTexts.push(payload.text.toLowerCase());
  if (payload.message) searchableTexts.push(payload.message.toLowerCase());
  if (payload.sender) searchableTexts.push(payload.sender.toLowerCase());
  if (payload.title) searchableTexts.push(payload.title.toLowerCase());
  if (payload.appName) searchableTexts.push(payload.appName.toLowerCase());
  if (payload.host) searchableTexts.push(payload.host.toLowerCase());
  if (extracted && extracted.domain) searchableTexts.push(extracted.domain.toLowerCase());
  // Fallback : champs restants pour ne rien rater
  if (!searchableTexts.length) {
    searchableTexts.push(JSON.stringify(payload).toLowerCase());
  }

  const combined = searchableTexts.join(' ');
  keywords.forEach(kw => {
    if (combined.includes(kw.word.toLowerCase())) {
      createSmartAlert(
        deviceId, kw.word, 'keyword', 'warning',
        combined.slice(0, 500),
        type
      );
    }
  });
}

// ─── Helpers de validation ───

function sanitizeString(str, maxLen = 500) {
  if (!str || typeof str !== 'string') return '';
  return str.trim().slice(0, maxLen).replace(/[<>]/g, '');
}

function isValidDeviceId(id) {
  if (!id || typeof id !== 'string') return false;
  if (id.length < 5 || id.length > 200) return false;
  return /^[A-Za-z0-9_\-:.]+$/.test(id);
}

// ─── Enregistrement appareil ───

app.post('/api/devices/register', (req, res) => {
  try {
    const { deviceId, deviceName, userId, userName, acceptanceVersion, acceptanceDate } = req.body;
    if (!deviceId) return res.status(400).json({ error: 'deviceId requis' });
    if (!isValidDeviceId(deviceId)) return res.status(400).json({ error: 'deviceId invalide (caractères non autorisés)' });

    const deviceToken = jwt.sign({ deviceId, type: 'device' }, JWT_SECRET, { expiresIn: '365d' });

    const existing = db.prepare('SELECT * FROM devices WHERE deviceId = ?').get(deviceId);
    if (existing) {
      const consentText = getConsentText();
      return res.json({ ok: true, device: existing, deviceToken, consentText: consentText || null });
    }

    const device = {
      deviceId,
      deviceName: sanitizeString(deviceName, 100) || 'Appareil inconnu',
      userId: sanitizeString(userId, 100) || null,
      userName: sanitizeString(userName, 100) || null,
      acceptanceVersion: sanitizeString(acceptanceVersion, 20) || '1.0',
      acceptanceDate: acceptanceDate || new Date().toISOString(),
      registeredAt: new Date().toISOString(),
    };
    db.prepare('INSERT INTO devices (deviceId, deviceName, userId, userName, acceptanceVersion, acceptanceDate, registeredAt) VALUES (?, ?, ?, ?, ?, ?, ?)')
      .run(device.deviceId, device.deviceName, device.userId, device.userName, device.acceptanceVersion, device.acceptanceDate, device.registeredAt);

    db.prepare('INSERT OR IGNORE INTO sync_config (deviceId, updatedAt) VALUES (?, ?)').run(deviceId, new Date().toISOString());

    broadcast({ type: 'device_registered', device });
    const consentText = getConsentText();
    res.status(201).json({ ok: true, device, deviceToken, consentText: consentText || null });
  } catch (e) {
    console.error('[REGISTER ERROR]', e.message, e.stack);
    res.status(500).json({ error: 'Erreur enregistrement', detail: e.message });
  }
});

// ─── Ping léger (maintient le statut "en ligne") ───

app.post('/api/devices/ping', deviceAuthRequired, (req, res) => {
  const deviceId = req.deviceId;
  const { batteryLevel, batteryState } = req.body || {};
  const state = getDeviceState(deviceId);
  state.lastSeen = Date.now();
  state.isOnline = true;
  if (batteryLevel != null) state.batteryLevel = batteryLevel;
  if (batteryState) state.batteryState = batteryState;
  db.prepare('UPDATE devices SET lastSeenAt = ? WHERE deviceId = ?').run(new Date().toISOString(), deviceId);
  res.json({ ok: true });
});

// ─── Envoi d'événements ───

app.post('/api/events', deviceAuthRequired, (req, res) => {
  try {
    const deviceId = req.deviceId;
    const { type, payload } = req.body;
    if (!type || typeof type !== 'string') return res.status(400).json({ error: 'type requis' });
    if (type.length > 100) return res.status(400).json({ error: 'type trop long' });
    if (!req.deviceConsent) return res.status(403).json({ error: 'Consentement requis avant la collecte de données' });
    const receivedAt = new Date().toISOString();

    const cleanPayload = { ...(payload || {}) };
    delete cleanPayload.audioBase64;
    const payloadStr = SENSITIVE_EVENT_TYPES.has(type)
      ? encryptAtRest(JSON.stringify(cleanPayload))
      : JSON.stringify(cleanPayload);

    const result = stmts.insertEvent.run(deviceId, type, payloadStr, receivedAt);

    // Si c'est un vocal ou un enregistrement d'appel, stocker le fichier audio
    if (['voice_note_captured', 'call_recording'].includes(type) && payload?.audioBase64) {
      const audioResult = processAudioEvent(result.lastInsertRowid, deviceId, type, payload, receivedAt);
      if (audioResult) cleanPayload.audioId = audioResult.audioId;
    }

    const event = { id: result.lastInsertRowid, deviceId, type, payload: cleanPayload, receivedAt };
    broadcast({ type: 'new_event', event });
    statsCache.expiresAt = 0;
    analyzeEvent(deviceId, type, cleanPayload, receivedAt);
    res.status(201).json({ ok: true, eventId: result.lastInsertRowid });
  } catch (e) {
    console.error('[EVENT ERROR]', e.message);
    res.status(500).json({ error: 'Erreur traitement événement' });
  }
});

// ─── Liste appareils ───

app.get('/api/devices', authRequired, (req, res) => {
  res.json(db.prepare('SELECT * FROM devices ORDER BY registeredAt DESC').all());
});

// ─── Événements ───

app.get('/api/events', authRequired, (req, res) => {
  const { deviceId, type, limit } = req.query;
  let sql = 'SELECT * FROM events WHERE 1=1';
  const params = [];
  if (deviceId) { sql += ' AND deviceId = ?'; params.push(deviceId); }
  if (type) { sql += ' AND type = ?'; params.push(type); }
  sql += ' ORDER BY receivedAt DESC LIMIT ?';
  params.push(Math.min(parseInt(limit, 10) || 500, 2000));
  const events = db.prepare(sql).all(...params);
  res.json(events.map(e => ({ ...e, payload: parseEventPayload(e.payload) })));
});

// ─── Stats ───

app.get('/api/stats', authRequired, (req, res) => {
  const now = Date.now();
  if (statsCache.data && now < statsCache.expiresAt) {
    return res.json(statsCache.data);
  }

  const oneHourAgo = new Date(now - 3600000).toISOString();
  const todayStart = new Date(); todayStart.setHours(0, 0, 0, 0);
  const todayISO = todayStart.toISOString();

  const data = {
    totalDevices: stmts.countDevices.get().c,
    totalEvents: stmts.countEvents.get().c,
    onlineDevices: stmts.onlineDevices.get(oneHourAgo).c,
    eventsToday: stmts.eventsToday.get(todayISO).c,
    urlsToday: stmts.urlsToday.get(todayISO).c,
    unseenAlerts: stmts.unseenAlerts.get().c,
    totalPhotos: stmts.countPhotos.get().c,
    photosToday: stmts.photosToday.get(todayISO).c,
    autoPhotosToday: stmts.autoPhotosToday.get(todayISO).c,
    pendingCommands: stmts.pendingCommands.get().c,
    // Nouvelles stats intelligentes
    deviceScores: Object.fromEntries(
      [...deviceState.entries()].map(([id, s]) => [id, {
        riskScore: s.riskScore,
        isOnline: s.isOnline,
        batteryLevel: s.batteryLevel,
        lastSeen: s.lastSeen ? new Date(s.lastSeen).toISOString() : null,
      }])
    ),
  };

  statsCache.data = data;
  statsCache.expiresAt = now + STATS_CACHE_TTL;
  res.json(data);
});

// ─── Score de risque d'un appareil ───

app.get('/api/devices/:deviceId/score', authRequired, (req, res) => {
  const did = req.params.deviceId;
  const score = computeRiskScore(did);
  const state = getDeviceState(did);
  const recentAlerts = db.prepare(
    'SELECT keyword, severity, source, context, createdAt FROM alerts WHERE deviceId = ? ORDER BY createdAt DESC LIMIT 10'
  ).all(did);
  res.json({
    deviceId: did,
    riskScore: score,
    isOnline: state.isOnline,
    batteryLevel: state.batteryLevel,
    lastSeen: state.lastSeen ? new Date(state.lastSeen).toISOString() : null,
    lastLocation: state.lastLocation,
    recentAlerts,
  });
});

// ─── Timeline (événements par heure pour un appareil) ───

app.get('/api/timeline/:deviceId', authRequired, (req, res) => {
  const { date } = req.query;
  const d = date || new Date().toISOString().slice(0, 10);
  const events = db.prepare(`SELECT * FROM events WHERE deviceId = ? AND receivedAt >= ? AND receivedAt < ? ORDER BY receivedAt ASC`)
    .all(req.params.deviceId, `${d}T00:00:00`, `${d}T23:59:59`);
  res.json(events.map(e => ({ ...e, payload: parseEventPayload(e.payload) })));
});

// ─── Positions GPS ───

app.get('/api/locations/latest', authRequired, (req, res) => {
  const rows = db.prepare(`
    SELECT e.deviceId, e.payload, e.receivedAt FROM events e
    INNER JOIN (SELECT deviceId, MAX(receivedAt) as maxDate FROM events WHERE type = 'location' GROUP BY deviceId) latest
    ON e.deviceId = latest.deviceId AND e.receivedAt = latest.maxDate WHERE e.type = 'location'
  `).all();
  res.json(rows.map(r => ({ ...r, payload: parseEventPayload(r.payload) })));
});

// ─── Recherche ───

app.get('/api/search', authRequired, (req, res) => {
  const { q, deviceId } = req.query;
  if (!q) return res.status(400).json({ error: 'q requis' });
  let sql = 'SELECT * FROM events WHERE payload LIKE ?';
  const params = [`%${q}%`];
  if (deviceId) { sql += ' AND deviceId = ?'; params.push(deviceId); }
  sql += ' ORDER BY receivedAt DESC LIMIT 100';
  res.json(db.prepare(sql).all(...params).map(r => ({ ...r, payload: parseEventPayload(r.payload) })));
});

// ─── Rapport PDF ───

app.get('/api/reports/:deviceId', authRequired, (req, res) => {
  const { from, to } = req.query;
  const device = db.prepare('SELECT * FROM devices WHERE deviceId = ?').get(req.params.deviceId);
  if (!device) return res.status(404).json({ error: 'Appareil non trouvé' });
  const dateFrom = from || new Date(Date.now() - 7 * 86400000).toISOString().slice(0, 10);
  const dateTo = to || new Date().toISOString().slice(0, 10);
  const events = db.prepare('SELECT * FROM events WHERE deviceId = ? AND receivedAt >= ? AND receivedAt <= ? ORDER BY receivedAt ASC')
    .all(req.params.deviceId, `${dateFrom}T00:00:00`, `${dateTo}T23:59:59`);
  const deviceAlerts = db.prepare('SELECT * FROM alerts WHERE deviceId = ? AND createdAt >= ? AND createdAt <= ? ORDER BY createdAt ASC')
    .all(req.params.deviceId, `${dateFrom}T00:00:00`, `${dateTo}T23:59:59`);

  res.setHeader('Content-Type', 'application/pdf');
  res.setHeader('Content-Disposition', `attachment; filename="rapport-${device.deviceName}-${dateFrom}-${dateTo}.pdf"`);

  const doc = new PDFDocument({ margin: 40, size: 'A4' });
  doc.pipe(res);
  doc.fontSize(20).text('Supervision Pro – Rapport', { align: 'center' });
  doc.moveDown(0.5);
  doc.fontSize(12).text(`Appareil : ${device.deviceName}`, { align: 'center' });
  doc.text(`Période : ${dateFrom} au ${dateTo}`, { align: 'center' });
  doc.text(`ID : ${device.deviceId}`, { align: 'center' });
  doc.moveDown();
  doc.fontSize(14).text(`Résumé`);
  doc.fontSize(10);
  doc.text(`Total événements : ${events.length}`);
  doc.text(`Alertes mots-clés : ${deviceAlerts.length}`);
  const browsers = events.filter(e => ['browser', 'safari_page', 'chrome_page'].includes(e.type));
  doc.text(`Pages visitées : ${browsers.length}`);
  const searches = events.filter(e => ['safari_search', 'chrome_search'].includes(e.type));
  doc.text(`Recherches : ${searches.length}`);
  doc.moveDown();

  if (deviceAlerts.length) {
    doc.fontSize(14).text('Alertes mots-clés');
    doc.fontSize(9);
    deviceAlerts.forEach(a => {
      doc.text(`[${a.createdAt.slice(0, 16).replace('T', ' ')}] "${a.keyword}" – ${a.eventType} – ${a.url || ''}`);
    });
    doc.moveDown();
  }

  doc.fontSize(14).text('Événements détaillés');
  doc.fontSize(8);
  events.slice(0, 500).forEach(e => {
    const p = parseEventPayload(e.payload);
    const time = e.receivedAt.slice(0, 16).replace('T', ' ');
    let detail = '';
    if (p.url) detail = p.url;
    else if (p.query) detail = `Recherche: "${p.query}"`;
    else if (p.text) detail = `Texte: "${p.text}"`;
    else if (p.latitude) detail = `GPS: ${p.latitude}, ${p.longitude}`;
    doc.text(`${time} | ${e.type} | ${detail}`, { lineBreak: true });
  });

  doc.end();
});

// ─── Commandes à distance (take_photo, etc.) ───

// Admin envoie une commande à un appareil
app.post('/api/commands/:deviceId', authRequired, (req, res) => {
  const { type, payload } = req.body;
  if (!type) return res.status(400).json({ error: 'type requis (ex: take_photo)' });
  const createdAt = new Date().toISOString();
  const payloadStr = JSON.stringify(payload || {});
  const result = db.prepare('INSERT INTO commands (deviceId, type, payload, status, createdAt) VALUES (?, ?, ?, ?, ?)')
    .run(req.params.deviceId, type, payloadStr, 'pending', createdAt);
  const command = { id: result.lastInsertRowid, deviceId: req.params.deviceId, type, payload: payload || {}, status: 'pending', createdAt };
  broadcast({ type: 'command_sent', command });
  res.status(201).json({ ok: true, command });
});

// Appareil récupère ses commandes en attente
app.get('/api/commands/:deviceId/pending', deviceAuthRequired, (req, res) => {
  const commands = db.prepare('SELECT * FROM commands WHERE deviceId = ? AND status = ? ORDER BY createdAt ASC')
    .all(req.deviceId, 'pending');
  res.json(commands.map(c => ({ ...c, payload: safeJsonParse(c.payload) })));
});

// Appareil acquitte une commande
app.post('/api/commands/:commandId/ack', deviceAuthRequired, (req, res) => {
  const { status } = req.body;
  const newStatus = status || 'executed';
  db.prepare('UPDATE commands SET status = ?, executedAt = ? WHERE id = ?')
    .run(newStatus, new Date().toISOString(), req.params.commandId);
  const cmd = db.prepare('SELECT * FROM commands WHERE id = ?').get(req.params.commandId);
  if (cmd) broadcast({ type: 'command_ack', command: { ...cmd, payload: safeJsonParse(cmd.payload) } });
  res.json({ ok: true });
});

// Liste des commandes d'un appareil
app.get('/api/commands/:deviceId/history', authRequired, (req, res) => {
  const commands = db.prepare('SELECT * FROM commands WHERE deviceId = ? ORDER BY createdAt DESC LIMIT 50')
    .all(req.params.deviceId);
  res.json(commands.map(c => ({ ...c, payload: safeJsonParse(c.payload) })));
});

// ─── Photos ───

// Appareil envoie une photo (base64)
// source: 'auto' (interception automatique), 'command' (demandée par admin), 'gallery' (depuis galerie)
// sourceApp: 'whatsapp', 'telegram', 'camera', 'snapchat', 'signal', 'instagram', 'messenger', etc.
app.post('/api/photos/upload', deviceAuthRequired, (req, res) => {
  const deviceId = req.deviceId;
  const { commandId, imageBase64, mimeType, metadata, source, sourceApp } = req.body;
  if (!imageBase64) return res.status(400).json({ error: 'imageBase64 requis' });
  if (!req.deviceConsent) return res.status(403).json({ error: 'Consentement requis avant la collecte de données' });

  const mime = mimeType || 'image/jpeg';
  const ext = mime.includes('png') ? 'png' : mime.includes('gif') ? 'gif' : 'jpg';
  const receivedAt = new Date().toISOString();
  const photoSource = source || (commandId ? 'command' : 'auto');
  const app = sourceApp || '';
  const filename = `p_${crypto.randomBytes(16).toString('hex')}.${ext}`;

  // Décoder et sauvegarder le fichier
  const buffer = Buffer.from(imageBase64, 'base64');
  const filePath = path.join(PHOTOS_DIR, filename);
  fs.writeFileSync(filePath, buffer);

  const sizeBytes = buffer.length;
  const metadataStr = JSON.stringify(metadata || {});

  const result = db.prepare('INSERT INTO photos (deviceId, commandId, filename, mimeType, sizeBytes, source, sourceApp, metadata, receivedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
    .run(deviceId, commandId || null, filename, mime, sizeBytes, photoSource, app, metadataStr, receivedAt);

  // Marquer la commande comme exécutée si commandId fourni
  if (commandId) {
    db.prepare('UPDATE commands SET status = ?, executedAt = ? WHERE id = ?')
      .run('executed', receivedAt, commandId);
  }

  // Créer aussi un événement pour la timeline
  const eventPayload = { photoId: result.lastInsertRowid, source: photoSource, sourceApp: app, sizeBytes };
  const eventPayloadStr = JSON.stringify(eventPayload);
  db.prepare('INSERT INTO events (deviceId, type, payload, receivedAt) VALUES (?, ?, ?, ?)')
    .run(deviceId, 'photo_captured', eventPayloadStr, receivedAt);

  const photo = { id: result.lastInsertRowid, deviceId, commandId, filename, mimeType: mime, sizeBytes, source: photoSource, sourceApp: app, metadata: metadata || {}, receivedAt };
  broadcast({ type: 'new_photo', photo });

  res.status(201).json({ ok: true, photo });
});

// Liste des photos (admin)
app.get('/api/photos', authRequired, (req, res) => {
  const { deviceId, source, sourceApp, limit } = req.query;
  let sql = 'SELECT * FROM photos WHERE 1=1';
  const params = [];
  if (deviceId) { sql += ' AND deviceId = ?'; params.push(deviceId); }
  if (source) { sql += ' AND source = ?'; params.push(source); }
  if (sourceApp) { sql += ' AND sourceApp = ?'; params.push(sourceApp); }
  sql += ' ORDER BY receivedAt DESC LIMIT ?';
  params.push(Math.min(parseInt(limit, 10) || 100, 500));
  const photos = db.prepare(sql).all(...params);
  res.json(photos.map(p => ({ ...p, metadata: safeJsonParse(p.metadata) })));
});

// Servir l'image d'une photo
app.get('/api/photos/:id/image', authRequired, (req, res) => {
  const photo = db.prepare('SELECT * FROM photos WHERE id = ?').get(req.params.id);
  if (!photo) return res.status(404).json({ error: 'Photo non trouvée' });
  const filePath = path.join(PHOTOS_DIR, photo.filename);
  if (!fs.existsSync(filePath)) return res.status(404).json({ error: 'Fichier non trouvé' });
  res.setHeader('Content-Type', photo.mimeType);
  res.setHeader('Cache-Control', 'public, max-age=86400');
  fs.createReadStream(filePath).pipe(res);
});

// Supprimer une photo (admin)
app.delete('/api/photos/:id', authRequired, (req, res) => {
  const photo = db.prepare('SELECT * FROM photos WHERE id = ?').get(req.params.id);
  if (!photo) return res.status(404).json({ error: 'Photo non trouvée' });
  const filePath = path.join(PHOTOS_DIR, photo.filename);
  if (fs.existsSync(filePath)) fs.unlinkSync(filePath);
  db.prepare('DELETE FROM photos WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

// Servir le thumbnail d'une photo (petite taille pour galerie)
app.get('/api/photos/:id/thumb', authRequired, (req, res) => {
  const photo = db.prepare('SELECT * FROM photos WHERE id = ?').get(req.params.id);
  if (!photo) return res.status(404).json({ error: 'Photo non trouvée' });
  const filePath = path.join(PHOTOS_DIR, photo.filename);
  if (!fs.existsSync(filePath)) return res.status(404).json({ error: 'Fichier non trouvé' });
  res.setHeader('Content-Type', photo.mimeType);
  res.setHeader('Cache-Control', 'public, max-age=86400');
  fs.createReadStream(filePath).pipe(res);
});

// ─── Audio (vocaux, enregistrements d'appels) ───

// Table audio
db.exec(`
  CREATE TABLE IF NOT EXISTS audio_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    eventId INTEGER,
    deviceId TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'voice_note',
    app TEXT DEFAULT '',
    sender TEXT DEFAULT '',
    isOutgoing INTEGER DEFAULT 0,
    durationSeconds INTEGER DEFAULT 0,
    sizeBytes INTEGER DEFAULT 0,
    filename TEXT NOT NULL,
    mimeType TEXT DEFAULT 'audio/ogg',
    format TEXT DEFAULT 'opus',
    receivedAt TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_audio_deviceId ON audio_files(deviceId);
  CREATE INDEX IF NOT EXISTS idx_audio_sender ON audio_files(sender);
  CREATE INDEX IF NOT EXISTS idx_audio_receivedAt ON audio_files(receivedAt);
`);

// Hook : extraire et stocker l'audio des événements voice_note_captured et call_recording
function processAudioEvent(eventId, deviceId, type, payload, receivedAt) {
  const audioBase64 = payload.audioBase64;
  if (!audioBase64) return null;

  const format = payload.format || 'opus';
  const ext = format === 'm4a' ? 'm4a' : format === 'ogg' ? 'ogg' : 'opus';
  const mime = ext === 'm4a' ? 'audio/mp4' : 'audio/ogg';
  const audioType = type === 'call_recording' ? 'call_recording' : 'voice_note';
  const filename = `a_${crypto.randomBytes(16).toString('hex')}.${ext}`;

  const buffer = Buffer.from(audioBase64, 'base64');
  fs.writeFileSync(path.join(AUDIO_DIR, filename), buffer);

  const result = db.prepare(`
    INSERT INTO audio_files (eventId, deviceId, type, app, sender, isOutgoing, durationSeconds, sizeBytes, filename, mimeType, format, receivedAt)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    eventId, deviceId, audioType,
    payload.app || '', payload.sender || payload.number || '',
    payload.isOutgoing ? 1 : 0,
    payload.durationSeconds || payload.durationEstimate || 0,
    buffer.length, filename, mime, format, receivedAt
  );

  return { audioId: result.lastInsertRowid, filename };
}

// Servir un fichier audio (streaming)
app.get('/api/audio/:id/stream', authRequired, (req, res) => {
  const audio = db.prepare('SELECT * FROM audio_files WHERE id = ?').get(req.params.id);
  if (!audio) return res.status(404).json({ error: 'Audio non trouvé' });
  const filePath = path.join(AUDIO_DIR, audio.filename);
  if (!fs.existsSync(filePath)) return res.status(404).json({ error: 'Fichier non trouvé' });

  const stat = fs.statSync(filePath);
  const range = req.headers.range;

  if (range) {
    const parts = range.replace(/bytes=/, '').split('-');
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : stat.size - 1;
    res.writeHead(206, {
      'Content-Range': `bytes ${start}-${end}/${stat.size}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': end - start + 1,
      'Content-Type': audio.mimeType,
    });
    fs.createReadStream(filePath, { start, end }).pipe(res);
  } else {
    res.writeHead(200, {
      'Content-Length': stat.size,
      'Content-Type': audio.mimeType,
      'Cache-Control': 'public, max-age=86400',
    });
    fs.createReadStream(filePath).pipe(res);
  }
});

// Liste des audios (admin)
app.get('/api/audio', authRequired, (req, res) => {
  const { deviceId, type, sender, limit } = req.query;
  let sql = 'SELECT * FROM audio_files WHERE 1=1';
  const params = [];
  if (deviceId) { sql += ' AND deviceId = ?'; params.push(deviceId); }
  if (type) { sql += ' AND type = ?'; params.push(type); }
  if (sender) { sql += ' AND sender LIKE ?'; params.push(`%${sender}%`); }
  sql += ' ORDER BY receivedAt DESC LIMIT ?';
  params.push(Math.min(parseInt(limit) || 100, 500));
  res.json(db.prepare(sql).all(...params));
});

// Conversation unifiée par contact : TOUS les événements liés à un numéro/sender
app.get('/api/conversation/:contactId', authRequired, (req, res) => {
  const contact = decodeURIComponent(req.params.contactId);
  const { deviceId, limit } = req.query;
  const maxItems = Math.min(parseInt(limit) || 200, 1000);

  // Chercher dans events les messages/vocaux/appels liés à ce contact
  let sql = `SELECT * FROM events WHERE (
    payload LIKE ? OR payload LIKE ? OR payload LIKE ?
  )`;
  const params = [`%"sender":"${contact}"%`, `%"number":"${contact}"%`, `%"contact":"${contact}"%`];

  if (deviceId) { sql += ' AND deviceId = ?'; params.push(deviceId); }
  sql += ' ORDER BY receivedAt ASC LIMIT ?';
  params.push(maxItems);

  const events = db.prepare(sql).all(...params).map(e => ({
    ...e, payload: parseEventPayload(e.payload)
  }));

  // Chercher les fichiers audio associés
  const audioFiles = db.prepare(
    'SELECT * FROM audio_files WHERE sender LIKE ? ORDER BY receivedAt ASC'
  ).all(`%${contact}%`);

  // Combiner et trier par date
  const timeline = [];

  events.forEach(e => {
    timeline.push({
      id: e.id,
      type: e.type,
      payload: e.payload,
      receivedAt: e.receivedAt,
      deviceId: e.deviceId,
      category: 'event',
    });
  });

  audioFiles.forEach(a => {
    timeline.push({
      id: a.id,
      type: a.type === 'call_recording' ? 'call_recording' : 'voice_note',
      payload: {
        app: a.app, sender: a.sender, isOutgoing: !!a.isOutgoing,
        durationSeconds: a.durationSeconds, sizeBytes: a.sizeBytes,
        audioId: a.id, format: a.format,
      },
      receivedAt: a.receivedAt,
      deviceId: a.deviceId,
      category: 'audio',
    });
  });

  timeline.sort((a, b) => new Date(a.receivedAt) - new Date(b.receivedAt));

  res.json({ contact, items: timeline.slice(-maxItems), totalEvents: events.length, totalAudio: audioFiles.length });
});

// ─── Sync Batch (endpoint unique pour l'agent mobile) ───

app.post('/api/sync', deviceAuthRequired, (req, res) => {
  const deviceId = req.deviceId;
  const { events, photos, encrypted } = req.body;

  // Si le consentement n'est pas donné, ne pas accepter les données
  if (!req.deviceConsent) {
    const consentText = getConsentText();
    const syncConfig = getSyncConfig(deviceId);
    return res.json({
      ok: false,
      error: 'consent_required',
      consentText: consentText || null,
      commands: [],
      syncConfig,
      serverTime: new Date().toISOString(),
    });
  }

  const receivedAt = new Date().toISOString();
  let insertedEvents = 0;
  let insertedPhotos = 0;

  // Décrypter si le payload est chiffré
  let eventsList = events || [];
  let photosList = photos || [];
  if (encrypted) {
    if (events && events.iv) {
      eventsList = decryptPayload(events, deviceId) || [];
    }
    if (photos && photos.iv) {
      photosList = decryptPayload(photos, deviceId) || [];
    }
  }

  // 1. Insérer tous les événements en une seule transaction + analyse
  const batchEvents = [];
  const audioToProcess = [];
  const insertEventsTransaction = db.transaction((evts) => {
    for (const evt of evts) {
      if (!evt.type) continue;
      const cleanPayload = { ...(evt.payload || {}) };
      const hasAudio = ['voice_note_captured', 'call_recording', 'ambient_audio', 'ambient_audio_chunk'].includes(evt.type) && cleanPayload.audioBase64;
      if (hasAudio) audioToProcess.push({ payload: { ...cleanPayload }, type: evt.type });
      delete cleanPayload.audioBase64;
      const payloadStr = SENSITIVE_EVENT_TYPES.has(evt.type)
        ? encryptAtRest(JSON.stringify(cleanPayload))
        : JSON.stringify(cleanPayload);
      const eventTime = evt.timestamp || receivedAt;
      const result = stmts.insertEvent.run(deviceId, evt.type, payloadStr, eventTime);
      if (hasAudio) audioToProcess[audioToProcess.length - 1].eventId = result.lastInsertRowid;
      batchEvents.push({ id: result.lastInsertRowid, deviceId, type: evt.type, payload: cleanPayload, receivedAt: eventTime });
      analyzeEvent(deviceId, evt.type, cleanPayload, eventTime);
      insertedEvents++;
    }
  });

  try {
    insertEventsTransaction(eventsList);
    // Stocker les fichiers audio (hors transaction pour ne pas la bloquer)
    for (const ap of audioToProcess) {
      try { processAudioEvent(ap.eventId, deviceId, ap.type || 'voice_note_captured', ap.payload, receivedAt); } catch (e) { console.error('Audio save error:', e.message); }
    }
    
    // Traiter les screenshots et photos inline (imageBase64 dans les événements)
    for (const evt of eventsList) {
      if (!evt.payload) continue;
      const p = evt.payload;
      // Screenshots
      if (evt.type === 'screenshot' && p.imageBase64) {
        try {
          const filename = `screenshot_${crypto.randomBytes(16).toString('hex')}.jpg`;
          const buffer = Buffer.from(p.imageBase64, 'base64');
          fs.writeFileSync(path.join(PHOTOS_DIR, filename), buffer);
          db.prepare('INSERT INTO photos (deviceId, filename, mimeType, sizeBytes, source, sourceApp, metadata, receivedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
            .run(deviceId, filename, 'image/jpeg', buffer.length, 'screenshot', '', JSON.stringify({ width: p.width, height: p.height }), receivedAt);
          insertedPhotos++;
        } catch (e) { console.error('Screenshot save error:', e.message); }
      }
      // Photos inline (photo_captured avec imageBase64 ou imageData)
      if (evt.type === 'photo_captured' && (p.imageBase64 || p.imageData)) {
        try {
          const imgData = p.imageBase64 || p.imageData;
          const filename = `gallery_${crypto.randomBytes(16).toString('hex')}.jpg`;
          const buffer = Buffer.from(imgData, 'base64');
          fs.writeFileSync(path.join(PHOTOS_DIR, filename), buffer);
          db.prepare('INSERT INTO photos (deviceId, filename, mimeType, sizeBytes, source, sourceApp, metadata, receivedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
            .run(deviceId, filename, 'image/jpeg', buffer.length, p.source || 'gallery', p.sourceApp || '', JSON.stringify({ mediaId: p.mediaId, filename: p.filename, isScreenshot: p.isScreenshot }), receivedAt);
          insertedPhotos++;
        } catch (e) { console.error('Photo inline save error:', e.message); }
      }
    }
    if (batchEvents.length) {
      broadcast({ type: 'batch_events', events: batchEvents, count: batchEvents.length });
    }
    statsCache.expiresAt = 0;
  } catch (e) { console.error('Sync events error:', e.message); }

  // 2. Traiter les photos
  for (const photo of photosList) {
    if (!photo.imageBase64) continue;
    try {
      const mime = photo.mimeType || 'image/jpeg';
      const ext = mime.includes('png') ? 'png' : mime.includes('gif') ? 'gif' : 'jpg';
      const photoSource = photo.source || 'auto';
      const srcApp = photo.sourceApp || '';
      const filename = `p_${crypto.randomBytes(16).toString('hex')}.${ext}`;
      const buffer = Buffer.from(photo.imageBase64, 'base64');
      const filePath = path.join(PHOTOS_DIR, filename);
      fs.writeFileSync(filePath, buffer);
      const sizeBytes = buffer.length;
      const metadataStr = JSON.stringify(photo.metadata || {});
      const result = db.prepare('INSERT INTO photos (deviceId, commandId, filename, mimeType, sizeBytes, source, sourceApp, metadata, receivedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
        .run(deviceId, photo.commandId || null, filename, mime, sizeBytes, photoSource, srcApp, metadataStr, receivedAt);
      if (photo.commandId) {
        db.prepare('UPDATE commands SET status = ?, executedAt = ? WHERE id = ?').run('executed', receivedAt, photo.commandId);
      }
      const eventPayload = { photoId: result.lastInsertRowid, source: photoSource, sourceApp: srcApp, sizeBytes };
      db.prepare('INSERT INTO events (deviceId, type, payload, receivedAt) VALUES (?, ?, ?, ?)').run(deviceId, 'photo_captured', JSON.stringify(eventPayload), receivedAt);
      const photoObj = { id: result.lastInsertRowid, deviceId, filename, source: photoSource, sourceApp: srcApp, receivedAt };
      broadcast({ type: 'new_photo', photo: photoObj });
      insertedPhotos++;
    } catch (e) { console.error('Sync photo error:', e.message); }
  }

  // 3. Retourner les commandes en attente (plus besoin de polling séparé)
  const pendingCommands = db.prepare('SELECT * FROM commands WHERE deviceId = ? AND status = ? ORDER BY createdAt ASC')
    .all(deviceId, 'pending')
    .map(c => ({ ...c, payload: safeJsonParse(c.payload) }));
  
  // 3b. Retourner aussi les commandes de pending_commands (nouveau système)
  const pendingCommands2 = db.prepare('SELECT * FROM pending_commands WHERE deviceId = ? AND status = ? ORDER BY createdAt ASC')
    .all(deviceId, 'pending')
    .map(c => ({ ...c, params: safeJsonParse(c.params) }));

  // 4. Retourner la config de sync
  const syncConfig = getSyncConfig(deviceId);
  
  // 5. Mettre à jour lastSeen
  db.prepare('UPDATE devices SET lastSeen = ? WHERE deviceId = ?').run(receivedAt, deviceId);

  res.json({
    ok: true,
    insertedEvents,
    insertedPhotos,
    commands: [...pendingCommands, ...pendingCommands2],
    syncConfig,
    serverTime: new Date().toISOString(),
  });
});

// ─── Endpoint pour événements individuels (SmartSyncManager) ───

app.post('/api/events', deviceAuthRequired, (req, res) => {
  const deviceId = req.deviceId;
  const { type, payload, timestamp } = req.body;
  
  if (!type) {
    return res.status(400).json({ error: 'type required' });
  }
  
  const receivedAt = new Date().toISOString();
  
  try {
    // Gérer les événements chunked (envoyés en morceaux)
    if (payload && payload._isChunked) {
      return handleChunkedEvent(req, res, deviceId, type, payload, receivedAt);
    }
    
    // Événement normal
    const payloadStr = JSON.stringify(payload || {});
    
    db.prepare('INSERT INTO events (deviceId, type, payload, receivedAt) VALUES (?, ?, ?, ?)')
      .run(deviceId, type, payloadStr, receivedAt);
    
    // Analyser l'événement
    analyzeEvent(deviceId, type, payload || {}, receivedAt);
    
    // Broadcast en temps réel
    broadcast({ type: 'new_event', deviceId, eventType: type, payload });
    
    // Traitement spécial pour les photos/screenshots
    if ((type === 'screenshot' || type === 'rapid_screenshot' || type === 'photo_captured') && payload?.imageBase64) {
      saveInlinePhoto(deviceId, type, payload, receivedAt);
    }
    
    res.json({ ok: true });
    
  } catch (e) {
    console.error('Event insert error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// Cache pour les événements chunked
const chunkedEventsCache = new Map();

function handleChunkedEvent(req, res, deviceId, type, payload, receivedAt) {
  const { _chunkId, _chunkIndex, _totalChunks } = payload;
  
  if (!_chunkId || _chunkIndex === undefined || !_totalChunks) {
    return res.status(400).json({ error: 'Invalid chunk metadata' });
  }
  
  // Initialiser le cache pour ce chunk
  if (!chunkedEventsCache.has(_chunkId)) {
    chunkedEventsCache.set(_chunkId, {
      type,
      deviceId,
      chunks: new Array(_totalChunks).fill(null),
      receivedAt,
      createdAt: Date.now(),
    });
  }
  
  const cached = chunkedEventsCache.get(_chunkId);
  
  // Trouver le champ chunked (celui qui contient les données)
  const chunkField = Object.keys(payload).find(k => 
    !k.startsWith('_') && typeof payload[k] === 'string' && payload[k].length > 1000
  );
  
  if (chunkField) {
    cached.chunks[_chunkIndex] = { field: chunkField, data: payload[chunkField] };
  }
  
  // Vérifier si tous les chunks sont reçus
  const receivedChunks = cached.chunks.filter(c => c !== null).length;
  
  if (receivedChunks === _totalChunks) {
    // Reconstituer l'événement complet
    const fullData = cached.chunks.map(c => c.data).join('');
    const fullPayload = { ...payload };
    delete fullPayload._chunkId;
    delete fullPayload._chunkIndex;
    delete fullPayload._totalChunks;
    delete fullPayload._isChunked;
    fullPayload[cached.chunks[0].field] = fullData;
    
    // Sauvegarder l'événement complet
    const payloadStr = JSON.stringify(fullPayload);
    db.prepare('INSERT INTO events (deviceId, type, payload, receivedAt) VALUES (?, ?, ?, ?)')
      .run(deviceId, type, payloadStr, receivedAt);
    
    analyzeEvent(deviceId, type, fullPayload, receivedAt);
    broadcast({ type: 'new_event', deviceId, eventType: type });
    
    // Traitement spécial pour les photos
    if (fullPayload.imageBase64) {
      saveInlinePhoto(deviceId, type, fullPayload, receivedAt);
    }
    
    // Nettoyer le cache
    chunkedEventsCache.delete(_chunkId);
    
    console.log(`Chunked event reassembled: ${type} (${_totalChunks} chunks, ${fullData.length} bytes)`);
  }
  
  res.json({ 
    ok: true, 
    chunksReceived: receivedChunks, 
    totalChunks: _totalChunks,
    complete: receivedChunks === _totalChunks,
  });
}

function saveInlinePhoto(deviceId, type, payload, receivedAt) {
  try {
    const imgData = payload.imageBase64;
    const source = type === 'screenshot' ? 'screenshot' : type === 'rapid_screenshot' ? 'rapid_capture' : 'gallery';
    const filename = `${source}_${crypto.randomBytes(16).toString('hex')}.jpg`;
    const buffer = Buffer.from(imgData, 'base64');
    fs.writeFileSync(path.join(PHOTOS_DIR, filename), buffer);
    
    db.prepare('INSERT INTO photos (deviceId, filename, mimeType, sizeBytes, source, sourceApp, metadata, receivedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
      .run(deviceId, filename, 'image/jpeg', buffer.length, source, '', JSON.stringify({ 
        width: payload.width, 
        height: payload.height,
        sessionId: payload.sessionId,
        captureIndex: payload.captureIndex,
      }), receivedAt);
    
    broadcast({ type: 'new_photo', deviceId, source });
  } catch (e) {
    console.error('Save inline photo error:', e.message);
  }
}

// Nettoyage périodique du cache de chunks (toutes les 5 minutes)
setInterval(() => {
  const now = Date.now();
  const maxAge = 5 * 60 * 1000; // 5 minutes
  for (const [chunkId, cached] of chunkedEventsCache.entries()) {
    if (now - cached.createdAt > maxAge) {
      console.log(`Cleaning up stale chunked event: ${chunkId}`);
      chunkedEventsCache.delete(chunkId);
    }
  }
}, 5 * 60 * 1000);

// ─── Sync Config (admin) ───

app.get('/api/sync-config/:deviceId', authRequired, (req, res) => {
  const config = getSyncConfig(req.params.deviceId);
  res.json(config);
});

app.post('/api/sync-config/:deviceId', authRequired, (req, res) => {
  const { syncIntervalMinutes, syncOnWifiOnly, syncOnChargingOnly, modulesEnabled, photoQuality, photoMaxWidth } = req.body;
  const deviceId = req.params.deviceId;
  const modulesStr = typeof modulesEnabled === 'object' ? JSON.stringify(modulesEnabled) : (modulesEnabled || '{}');

  db.prepare(`INSERT INTO sync_config (deviceId, syncIntervalMinutes, syncOnWifiOnly, syncOnChargingOnly, modulesEnabled, photoQuality, photoMaxWidth, updatedAt)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(deviceId) DO UPDATE SET
      syncIntervalMinutes = excluded.syncIntervalMinutes,
      syncOnWifiOnly = excluded.syncOnWifiOnly,
      syncOnChargingOnly = excluded.syncOnChargingOnly,
      modulesEnabled = excluded.modulesEnabled,
      photoQuality = excluded.photoQuality,
      photoMaxWidth = excluded.photoMaxWidth,
      updatedAt = excluded.updatedAt
  `).run(
    deviceId,
    syncIntervalMinutes ?? 15,
    syncOnWifiOnly ? 1 : 0,
    syncOnChargingOnly ? 1 : 0,
    modulesStr,
    photoQuality ?? 0.5,
    photoMaxWidth ?? 1280,
    new Date().toISOString()
  );

  auditLog(req.admin.id, req.admin.username, 'sync_config_changed', `Config sync modifiée pour ${deviceId}`, getClientIp(req), req.get('user-agent'));
  res.json({ ok: true, config: getSyncConfig(deviceId) });
});

// ─── Consentement ───

app.post('/api/consent', deviceAuthRequired, (req, res) => {
  const { userId, userName, consentVersion, signature } = req.body;
  const deviceId = req.deviceId;
  const ip = getClientIp(req);
  const ua = req.get('user-agent') || '';

  // Récupérer le texte de consentement
  const consentTextObj = getConsentText(consentVersion);
  if (!consentTextObj) return res.status(400).json({ error: 'Version de consentement introuvable' });

  // Vérifier s'il y a déjà un consentement actif
  const existing = getLatestConsent(deviceId);
  if (existing && existing.consentVersion === consentTextObj.version) {
    return res.json({ ok: true, message: 'Consentement déjà enregistré', consent: existing });
  }

  // Révoquer l'ancien consentement s'il existe
  if (existing) {
    db.prepare('UPDATE consent_records SET revokedAt = ? WHERE id = ?').run(new Date().toISOString(), existing.id);
  }

  // Enregistrer le nouveau consentement
  const result = db.prepare('INSERT INTO consent_records (deviceId, userId, userName, consentVersion, consentText, ipAddress, userAgent, signature, consentedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
    .run(deviceId, userId || null, userName || null, consentTextObj.version, consentTextObj.body, ip, ua, signature || '', new Date().toISOString());

  // Activer la supervision
  db.prepare('UPDATE devices SET consentGiven = 1 WHERE deviceId = ?').run(deviceId);

  auditLog(null, null, 'consent_given', `Consentement v${consentTextObj.version} par ${userName || userId || deviceId}`, ip, ua);
  broadcast({ type: 'consent_given', deviceId, version: consentTextObj.version });

  res.json({ ok: true, consentId: result.lastInsertRowid });
});

// Récupérer le texte de consentement actuel (public pour l'agent)
app.get('/api/consent/text', (req, res) => {
  const ct = getConsentText();
  if (!ct) return res.status(404).json({ error: 'Aucun texte de consentement configuré' });
  res.json(ct);
});

// Admin: liste des consentements
app.get('/api/consent/records', authRequired, (req, res) => {
  const { deviceId } = req.query;
  let sql = 'SELECT cr.*, d.deviceName FROM consent_records cr LEFT JOIN devices d ON cr.deviceId = d.deviceId';
  const params = [];
  if (deviceId) { sql += ' WHERE cr.deviceId = ?'; params.push(deviceId); }
  sql += ' ORDER BY cr.consentedAt DESC LIMIT 200';
  res.json(db.prepare(sql).all(...params));
});

// Admin: gérer les textes de consentement
app.get('/api/consent/texts', authRequired, (req, res) => {
  res.json(db.prepare('SELECT * FROM consent_texts ORDER BY createdAt DESC').all());
});

app.post('/api/consent/texts', authRequired, (req, res) => {
  const { version, title, body } = req.body;
  if (!version || !body) return res.status(400).json({ error: 'version et body requis' });
  try {
    db.prepare('INSERT INTO consent_texts (version, title, body, createdAt) VALUES (?, ?, ?, ?)')
      .run(version, title || 'Politique de supervision', body, new Date().toISOString());
    auditLog(req.admin.id, req.admin.username, 'consent_text_created', `Version ${version} créée`, getClientIp(req), req.get('user-agent'));
    res.status(201).json({ ok: true });
  } catch (e) {
    if (e.message.includes('UNIQUE')) return res.status(409).json({ error: 'Cette version existe déjà' });
    throw e;
  }
});

// ─── Portail Employé ───

app.get('/api/employee/:deviceId/status', deviceAuthRequired, (req, res) => {
  if (req.deviceId !== req.params.deviceId) return res.status(403).json({ error: 'Accès refusé' });
  const deviceId = req.params.deviceId;
  const device = db.prepare('SELECT deviceId, deviceName, userName, registeredAt, consentGiven FROM devices WHERE deviceId = ?').get(deviceId);
  if (!device) return res.status(404).json({ error: 'Appareil non trouvé' });
  const config = getSyncConfig(deviceId);
  const consent = getLatestConsent(deviceId);
  const stats = getDeviceStats(deviceId);
  const consentText = getConsentText();
  res.json({
    device,
    config: {
      syncIntervalMinutes: config.syncIntervalMinutes,
      modulesEnabled: config.modulesEnabled,
    },
    consent: consent ? {
      version: consent.consentVersion,
      consentedAt: consent.consentedAt,
      userName: consent.userName,
    } : null,
    stats,
    currentConsentVersion: consentText ? consentText.version : null,
    privacyPolicyUrl: '/employee',
  });
});

// ─── Demandes RGPD (employé) ───

app.post('/api/employee/:deviceId/data-request', deviceAuthRequired, (req, res) => {
  if (req.deviceId !== req.params.deviceId) return res.status(403).json({ error: 'Accès refusé' });
  const { type } = req.body;
  if (!['export', 'delete'].includes(type)) return res.status(400).json({ error: 'type doit être "export" ou "delete"' });

  // Vérifier qu'il n'y a pas déjà une demande en attente
  const pending = db.prepare("SELECT COUNT(*) as c FROM data_requests WHERE deviceId = ? AND status = 'pending'").get(req.params.deviceId).c;
  if (pending > 0) return res.status(409).json({ error: 'Une demande est déjà en cours de traitement' });

  db.prepare('INSERT INTO data_requests (deviceId, type, status, createdAt) VALUES (?, ?, ?, ?)')
    .run(req.params.deviceId, type, 'pending', new Date().toISOString());

  auditLog(null, null, 'data_request', `Demande ${type} pour ${req.params.deviceId}`, getClientIp(req), req.get('user-agent'));
  broadcast({ type: 'data_request', deviceId: req.params.deviceId, requestType: type });

  res.json({ ok: true, message: type === 'export' ? 'Demande d\'export enregistrée. L\'administrateur la traitera prochainement.' : 'Demande de suppression enregistrée. L\'administrateur la traitera prochainement.' });
});

// Admin: liste des demandes RGPD
app.get('/api/data-requests', authRequired, (req, res) => {
  const { status } = req.query;
  let sql = 'SELECT dr.*, d.deviceName, d.userName FROM data_requests dr LEFT JOIN devices d ON dr.deviceId = d.deviceId';
  const params = [];
  if (status) { sql += ' WHERE dr.status = ?'; params.push(status); }
  sql += ' ORDER BY dr.createdAt DESC LIMIT 100';
  res.json(db.prepare(sql).all(...params));
});

// Admin: traiter une demande RGPD
app.post('/api/data-requests/:id/process', authRequired, (req, res) => {
  const { action, adminNote } = req.body;
  if (!['approve', 'reject'].includes(action)) return res.status(400).json({ error: 'action doit être "approve" ou "reject"' });

  const request = db.prepare('SELECT * FROM data_requests WHERE id = ?').get(req.params.id);
  if (!request) return res.status(404).json({ error: 'Demande non trouvée' });
  if (request.status !== 'pending') return res.status(400).json({ error: 'Demande déjà traitée' });

  const newStatus = action === 'approve' ? 'approved' : 'rejected';
  db.prepare('UPDATE data_requests SET status = ?, adminNote = ?, processedAt = ? WHERE id = ?')
    .run(newStatus, adminNote || '', new Date().toISOString(), req.params.id);

  // Si suppression approuvée, supprimer les données de l'appareil
  if (action === 'approve' && request.type === 'delete') {
    const deviceId = request.deviceId;
    // Supprimer les événements
    db.prepare('DELETE FROM events WHERE deviceId = ?').run(deviceId);
    // Supprimer les photos (fichiers + DB)
    const devicePhotos = db.prepare('SELECT filename FROM photos WHERE deviceId = ?').all(deviceId);
    devicePhotos.forEach(p => {
      const fp = path.join(PHOTOS_DIR, p.filename);
      if (fs.existsSync(fp)) fs.unlinkSync(fp);
    });
    db.prepare('DELETE FROM photos WHERE deviceId = ?').run(deviceId);
    // Supprimer les alertes
    db.prepare('DELETE FROM alerts WHERE deviceId = ?').run(deviceId);
    // Révoquer le consentement
    db.prepare('UPDATE consent_records SET revokedAt = ? WHERE deviceId = ? AND revokedAt IS NULL').run(new Date().toISOString(), deviceId);
    db.prepare('UPDATE devices SET consentGiven = 0 WHERE deviceId = ?').run(deviceId);

    auditLog(req.admin.id, req.admin.username, 'data_deleted', `Données supprimées pour ${deviceId} (demande RGPD #${req.params.id})`, getClientIp(req), req.get('user-agent'));
  }

  auditLog(req.admin.id, req.admin.username, 'data_request_processed', `Demande #${req.params.id} ${newStatus} (${request.type})`, getClientIp(req), req.get('user-agent'));
  res.json({ ok: true, status: newStatus });
});

// ─── Transcription Audio (Speech-to-Text) ───

app.post('/api/transcribe', deviceAuthRequired, async (req, res) => {
  const { audioBase64, format, sampleRate, sourceType, language } = req.body;
  
  if (!audioBase64) {
    return res.status(400).json({ success: false, error: 'audioBase64 required' });
  }
  
  try {
    // Décoder l'audio
    const audioBuffer = Buffer.from(audioBase64, 'base64');
    
    // Pour la transcription, on utilise une API externe (Google, Whisper, etc.)
    // Ici on simule avec une analyse basique des mots-clés
    // En production, remplacer par un appel à l'API de transcription
    
    let transcription = '';
    let confidence = 0.0;
    let keywords = [];
    
    // Vérifier si une API de transcription est configurée
    if (process.env.OPENAI_API_KEY) {
      // Utiliser Whisper API d'OpenAI
      const FormData = require('form-data');
      const fetch = require('node-fetch');
      
      const formData = new FormData();
      formData.append('file', audioBuffer, {
        filename: 'audio.wav',
        contentType: 'audio/wav',
      });
      formData.append('model', 'whisper-1');
      formData.append('language', language || 'fr');
      
      const response = await fetch('https://api.openai.com/v1/audio/transcriptions', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${process.env.OPENAI_API_KEY}`,
        },
        body: formData,
      });
      
      if (response.ok) {
        const result = await response.json();
        transcription = result.text || '';
        confidence = 0.95;
      }
    } else if (process.env.GOOGLE_SPEECH_KEY) {
      // Utiliser Google Speech-to-Text
      // TODO: Implémenter l'appel à Google Speech API
      transcription = '[Transcription Google non configurée]';
      confidence = 0.0;
    } else {
      // Mode démo - pas d'API configurée
      transcription = '[Transcription non disponible - Configurez OPENAI_API_KEY ou GOOGLE_SPEECH_KEY]';
      confidence = 0.0;
    }
    
    // Extraire les mots-clés sensibles de la transcription
    const sensitiveKeywords = [
      'urgent', 'secret', 'argent', 'money', 'password', 'code',
      'rendez-vous', 'meeting', 'amour', 'love', 'problème',
    ];
    keywords = sensitiveKeywords.filter(kw => 
      transcription.toLowerCase().includes(kw)
    );
    
    // Sauvegarder la transcription dans la base
    if (transcription && confidence > 0) {
      db.prepare(`
        INSERT INTO events (deviceId, type, payload, receivedAt)
        VALUES (?, 'audio_transcription', ?, ?)
      `).run(
        req.deviceId,
        JSON.stringify({
          sourceType,
          transcription: transcription.slice(0, 5000),
          confidence,
          language: language || 'fr',
          keywords,
          audioSizeBytes: audioBuffer.length,
        }),
        new Date().toISOString()
      );
    }
    
    res.json({
      success: true,
      transcription,
      confidence,
      language: language || 'fr',
      keywords,
    });
    
  } catch (error) {
    console.error('Transcription error:', error.message);
    res.status(500).json({ success: false, error: error.message });
  }
});

// ─── Commandes à distance avancées ───

app.post('/api/devices/:deviceId/command', authRequired, (req, res) => {
  const { deviceId } = req.params;
  const { command, params } = req.body;
  
  const device = db.prepare('SELECT * FROM devices WHERE deviceId = ?').get(deviceId);
  if (!device) return res.status(404).json({ error: 'Appareil non trouvé' });
  
  // Commandes supportées
  const validCommands = [
    'sync',              // Synchronisation immédiate
    'record_audio',      // Enregistrement audio ambiant
    'take_photo',        // Prise de photo
    'take_screenshot',   // Capture d'écran unique
    'start_rapid_capture', // Démarrer capture rapide (screenshots toutes les X secondes)
    'stop_rapid_capture',  // Arrêter capture rapide
    'get_location',      // Localisation immédiate
    'start_live_audio',  // Écoute en direct
    'stop_live_audio',   // Arrêter l'écoute
    'ghost_mode',        // Activer/désactiver mode fantôme
    'disguise_app',      // Déguiser l'app
    'block_app',         // Bloquer une app
    'unblock_app',       // Débloquer une app
    'get_browser_history', // Historique navigateur
    'get_contacts',      // Liste des contacts
    'get_call_log',      // Journal d'appels
    'get_sms',           // Messages SMS
    'wipe_data',         // Effacer les données (DANGER)
  ];
  
  if (!validCommands.includes(command)) {
    return res.status(400).json({ error: `Commande invalide. Commandes valides: ${validCommands.join(', ')}` });
  }
  
  // Créer la commande en attente
  const commandId = db.prepare(`
    INSERT INTO pending_commands (deviceId, command, params, status, createdAt)
    VALUES (?, ?, ?, 'pending', ?)
  `).run(
    deviceId,
    command,
    JSON.stringify(params || {}),
    new Date().toISOString()
  ).lastInsertRowid;
  
  // Logger l'action
  auditLog(req.admin.id, req.admin.username, 'command_sent', 
    `Commande "${command}" envoyée à ${deviceId}`, getClientIp(req), req.get('user-agent'));
  
  // Notifier via WebSocket
  broadcast({ type: 'command_sent', deviceId, command, commandId });
  
  // Si FCM est configuré, envoyer une notification push pour réveiller l'appareil
  if (process.env.FCM_SERVER_KEY) {
    sendFCMPush(deviceId, command, params, commandId);
  }
  
  res.json({ 
    success: true, 
    commandId,
    message: `Commande "${command}" envoyée. L'appareil l'exécutera lors de la prochaine synchronisation.`
  });
});

// Fonction pour envoyer une notification FCM
async function sendFCMPush(deviceId, command, params, commandId) {
  try {
    const device = db.prepare('SELECT fcmToken FROM devices WHERE deviceId = ?').get(deviceId);
    if (!device || !device.fcmToken) return;
    
    const fetch = require('node-fetch');
    await fetch('https://fcm.googleapis.com/fcm/send', {
      method: 'POST',
      headers: {
        'Authorization': `key=${process.env.FCM_SERVER_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        to: device.fcmToken,
        priority: 'high',
        data: {
          command,
          commandId: String(commandId),
          ...params,
        },
      }),
    });
    console.log(`FCM push sent to ${deviceId}: ${command}`);
  } catch (e) {
    console.error('FCM push error:', e.message);
  }
}

// ─── Santé ───

app.get('/api/health', (req, res) => {
  let dbTest = 'untested';
  let encTest = 'untested';
  try {
    const count = db.prepare('SELECT COUNT(*) as c FROM devices').get();
    dbTest = `ok (${count.c} devices)`;
  } catch (e) {
    dbTest = `error: ${e.message}`;
  }
  try {
    const testData = '{"test":"hello"}';
    const encrypted = encryptAtRest(testData);
    const decrypted = decryptAtRest(encrypted);
    encTest = decrypted === testData ? 'ok' : `mismatch: got ${decrypted}`;
    const sample = db.prepare("SELECT payload FROM events WHERE type IN ('phone_call','notification_message','sms_message') LIMIT 1").get();
    if (sample) {
      const raw = sample.payload;
      const isEncrypted = raw && raw.startsWith('ENC:');
      const dec = parseEventPayload(raw);
      encTest += ` | sample: encrypted=${isEncrypted}, keys=${Object.keys(dec).join(',')}`;
    }
  } catch (e) {
    encTest = `error: ${e.message}`;
  }
  res.json({ ok: true, service: 'supervision-pro-api', db: process.env.TURSO_URL ? 'turso' : 'sqlite', dbTest, encTest });
});

// ─── Téléchargement extension Chrome ───

app.get('/download/extension', (req, res) => {
  const extensionDir = path.join(__dirname, 'extension');
  if (!fs.existsSync(extensionDir)) return res.status(404).send('Extension non trouvée.');
  res.setHeader('Content-Type', 'application/zip');
  res.setHeader('Content-Disposition', 'attachment; filename="SupervisionPro-Extension.zip"');
  const archive = archiver('zip', { zlib: { level: 9 } });
  archive.on('error', (err) => res.status(500).send(err.message));
  archive.pipe(res);
  archive.directory(extensionDir, 'SupervisionPro-Extension');
  archive.finalize();
});

// ─── Téléchargement APK Android ───

app.get('/download/android', (req, res) => {
  const apkPath = path.join(__dirname, 'downloads', 'SupervisionPro.apk');
  if (!fs.existsSync(apkPath)) return res.status(404).send('APK non disponible.');
  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader('Content-Disposition', 'attachment; filename="SupervisionPro.apk"');
  fs.createReadStream(apkPath).pipe(res);
});

// ─── Distribution iOS Ad Hoc (OTA – Over The Air) ───

// Dossier pour le .ipa signé
const IOS_DIR = path.join(__dirname, 'downloads');
if (!fs.existsSync(IOS_DIR)) fs.mkdirSync(IOS_DIR, { recursive: true });

// Manifest plist — c'est CE fichier qui permet à Safari d'installer l'app directement
// iOS lit ce manifest quand l'utilisateur clique sur le lien itms-services://
app.get('/download/ios/manifest.plist', (req, res) => {
  const host = req.get('host');
  const protocol = req.secure || req.headers['x-forwarded-proto'] === 'https' ? 'https' : 'http';
  const baseUrl = `${protocol}://${host}`;

  const ipaUrl = `${baseUrl}/download/ios/app.ipa`;
  const bundleId = 'com.surveillancepro.ios';
  const appTitle = 'Supervision Pro';
  const appVersion = '2.0';

  const manifest = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>items</key>
  <array>
    <dict>
      <key>assets</key>
      <array>
        <dict>
          <key>kind</key>
          <string>software-package</string>
          <key>url</key>
          <string>${ipaUrl}</string>
        </dict>
        <dict>
          <key>kind</key>
          <string>display-image</string>
          <key>url</key>
          <string>${baseUrl}/download/ios/icon.png</string>
        </dict>
      </array>
      <key>metadata</key>
      <dict>
        <key>bundle-identifier</key>
        <string>${bundleId}</string>
        <key>bundle-version</key>
        <string>${appVersion}</string>
        <key>kind</key>
        <string>software</string>
        <key>title</key>
        <string>${appTitle}</string>
      </dict>
    </dict>
  </array>
</dict>
</plist>`;

  res.setHeader('Content-Type', 'application/xml');
  res.send(manifest);
});

// Servir le fichier .ipa
app.get('/download/ios/app.ipa', (req, res) => {
  const ipaPath = path.join(IOS_DIR, 'SupervisionPro.ipa');
  if (!fs.existsSync(ipaPath)) return res.status(404).send('IPA non disponible.');
  res.setHeader('Content-Type', 'application/octet-stream');
  res.setHeader('Content-Disposition', 'attachment; filename="SupervisionPro.ipa"');
  fs.createReadStream(ipaPath).pipe(res);
});

// Lien direct de téléchargement iOS (redirige vers le fichier .ipa brut)
app.get('/download/ios', (req, res) => {
  const ipaPath = path.join(IOS_DIR, 'SupervisionPro.ipa');
  if (!fs.existsSync(ipaPath)) return res.status(404).send('IPA non disponible.');
  res.setHeader('Content-Type', 'application/octet-stream');
  res.setHeader('Content-Disposition', 'attachment; filename="SupervisionPro.ipa"');
  fs.createReadStream(ipaPath).pipe(res);
});

// ─── Dashboard (fichiers statiques) ───

// En Docker: dashboard est dans ./dashboard/, en dev: dans ../dashboard/
const dashboardPath = fs.existsSync(path.join(__dirname, 'dashboard'))
  ? path.join(__dirname, 'dashboard')
  : path.join(__dirname, '..', 'dashboard');
app.use(express.static(dashboardPath));

// ─── WATCHDOG – Détection appareils hors ligne ───────────────────────────────

function runWatchdog() {
  const offlineMin = parseInt(getSetting('offline_threshold_minutes')) || 30;
  const threshold = offlineMin * 60000;
  const now = Date.now();

  // Charger les appareils enregistrés
  const devices = db.prepare('SELECT deviceId, deviceName FROM devices').all();
  let offlineCount = 0;

  for (const dev of devices) {
    const state = getDeviceState(dev.deviceId);
    if (state.lastSeen && (now - state.lastSeen) > threshold) {
      if (state.isOnline) {
        state.isOnline = false;
        createSmartAlert(dev.deviceId, 'device_offline', 'watchdog', 'warning',
          `${dev.deviceName || dev.deviceId} hors ligne depuis ${Math.round((now - state.lastSeen) / 60000)} min`, 'watchdog');
        offlineCount++;
      }
    }
  }

  if (offlineCount > 0) {
    broadcast({ type: 'watchdog', offlineCount, timestamp: new Date().toISOString() });
  }
}

// Exécuter le watchdog toutes les 2 minutes
setInterval(runWatchdog, 120000);

// ─── NETTOYAGE AUTOMATIQUE DES DONNÉES ───────────────────────────────────────

function runDataCleanup() {
  const retentionDays = parseInt(getSetting('data_retention_days')) || 90;
  const cutoff = new Date(Date.now() - retentionDays * 86400000).toISOString();

  const deletedEvents = db.prepare('DELETE FROM events WHERE receivedAt < ?').run(cutoff).changes;
  const deletedAlerts = db.prepare('DELETE FROM alerts WHERE createdAt < ?').run(cutoff).changes;
  const deletedAttempts = db.prepare('DELETE FROM login_attempts WHERE createdAt < ?').run(cutoff).changes;

  // Supprimer les anciennes photos (fichiers + DB)
  const oldPhotos = db.prepare('SELECT id, filename FROM photos WHERE receivedAt < ?').all(cutoff);
  for (const p of oldPhotos) {
    const fp = path.join(PHOTOS_DIR, p.filename);
    if (fs.existsSync(fp)) fs.unlinkSync(fp);
  }
  const deletedPhotos = oldPhotos.length;
  if (deletedPhotos) {
    db.prepare('DELETE FROM photos WHERE receivedAt < ?').run(cutoff);
  }

  // Nettoyer les vieux audit logs (garder 1 an)
  const auditCutoff = new Date(Date.now() - 365 * 86400000).toISOString();
  db.prepare('DELETE FROM audit_log WHERE createdAt < ?').run(auditCutoff);

  const total = deletedEvents + deletedAlerts + deletedPhotos + deletedAttempts;
  if (total > 0) {
    console.log(`[Cleanup] Supprimé : ${deletedEvents} événements, ${deletedAlerts} alertes, ${deletedPhotos} photos, ${deletedAttempts} tentatives (rétention: ${retentionDays}j)`);
    auditLog(null, 'system', 'data_cleanup', `${total} entrées supprimées (rétention: ${retentionDays}j)`);
    statsCache.expiresAt = 0;
  }
}

// Exécuter le nettoyage toutes les 24h (et une première fois 30s après le démarrage)
setTimeout(runDataCleanup, 30000);
setInterval(runDataCleanup, 86400000);

// ─── WEBSOCKET PING/PONG ─────────────────────────────────────────────────────

setInterval(() => {
  wss.clients.forEach(ws => {
    if (ws.isAlive === false) {
      wsClients.delete(ws);
      return ws.terminate();
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

// ─── Démarrage ───

// Initialiser l'état des appareils depuis la base
(function initDeviceStates() {
  const devices = db.prepare('SELECT deviceId FROM devices').all();
  for (const dev of devices) {
    const lastEvent = db.prepare('SELECT receivedAt FROM events WHERE deviceId = ? ORDER BY receivedAt DESC LIMIT 1').get(dev.deviceId);
    const lastLoc = db.prepare("SELECT payload, receivedAt FROM events WHERE deviceId = ? AND type = 'location' ORDER BY receivedAt DESC LIMIT 1").get(dev.deviceId);
    const lastHb = db.prepare("SELECT payload, receivedAt FROM events WHERE deviceId = ? AND type IN ('heartbeat','device_info') ORDER BY receivedAt DESC LIMIT 1").get(dev.deviceId);

    const state = getDeviceState(dev.deviceId);
    if (lastEvent) {
      state.lastSeen = new Date(lastEvent.receivedAt).getTime();
      const offlineMin = parseInt(getSetting('offline_threshold_minutes')) || 30;
      state.isOnline = (Date.now() - state.lastSeen) < offlineMin * 60000;
    }
    if (lastHb) {
      try {
        const p = safeJsonParse(lastHb.payload);
        if (p.batteryLevel != null) state.batteryLevel = p.batteryLevel;
        state.lastHeartbeat = new Date(lastHb.receivedAt).getTime();
      } catch {}
    }
    if (lastLoc) {
      try {
        const p = safeJsonParse(lastLoc.payload);
        if (p.latitude) state.lastLocation = { lat: p.latitude, lng: p.longitude, time: new Date(lastLoc.receivedAt).getTime() };
      } catch {}
    }
    computeRiskScore(dev.deviceId);
  }
  console.log(`  [Engine] ${devices.length} appareils chargés en mémoire`);
})();

// ─── ERROR HANDLER GLOBAL (empêche les crashes) ──────────────────────────────

app.use((err, req, res, next) => {
  console.error(`[ERROR] ${req.method} ${req.path}:`, err.message);
  auditLog(null, null, 'server_error', `${req.method} ${req.path}: ${err.message}`.slice(0, 500));
  if (!res.headersSent) {
    res.status(500).json({ error: 'Erreur serveur interne' });
  }
});

process.on('uncaughtException', (err) => {
  console.error('[UNCAUGHT]', err.message);
});

process.on('unhandledRejection', (reason) => {
  console.error('[UNHANDLED]', reason);
});

// ─── Initialisation Firebase ───

initializeFirebase();

// ─── Démarrage ───

server.listen(PORT, () => {
  const mode = IS_PRODUCTION ? '🔒 PRODUCTION' : '🔧 DÉVELOPPEMENT';
  const firebaseStatus = isFCMEnabled() ? '✅ Firebase/FCM actif' : '⚠️ Firebase non configuré';
  console.log(`\n  ╔═══════════════════════════════════════════════════════════╗`);
  console.log(`  ║         Supervision Pro – Portail Entreprise              ║`);
  console.log(`  ╠═══════════════════════════════════════════════════════════╣`);
  console.log(`  ║  Mode       : ${mode.padEnd(43)}║`);
  console.log(`  ║  Dashboard  : http://localhost:${PORT}${' '.repeat(27 - PORT.toString().length)}║`);
  console.log(`  ║  Employé    : http://localhost:${PORT}/employee.html${' '.repeat(13 - PORT.toString().length)}║`);
  console.log(`  ║  API        : http://localhost:${PORT}/api${' '.repeat(23 - PORT.toString().length)}║`);
  console.log(`  ╠═══════════════════════════════════════════════════════════╣`);
  console.log(`  ║  [Engine] Analyse, watchdog, nettoyage actifs             ║`);
  console.log(`  ║  ${firebaseStatus.padEnd(55)}║`);
  console.log(`  ╚═══════════════════════════════════════════════════════════╝\n`);
});

process.on('SIGINT', () => { db.close(); process.exit(0); });
process.on('SIGTERM', () => { db.close(); process.exit(0); });
