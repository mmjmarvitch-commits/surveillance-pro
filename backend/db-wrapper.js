/**
 * Database Wrapper - Supporte SQLite (sync) et PostgreSQL (async)
 * Détecte automatiquement DATABASE_URL pour utiliser PostgreSQL
 */

const path = require('path');
const fs = require('fs');

let db = null;
let isPostgres = false;
let pool = null;

// ═══════════════════════════════════════════════════════════════════════════════
// INITIALISATION
// ═══════════════════════════════════════════════════════════════════════════════

async function init() {
  const DATABASE_URL = process.env.DATABASE_URL;
  
  if (DATABASE_URL) {
    // PostgreSQL (persistant)
    const { Pool } = require('pg');
    pool = new Pool({
      connectionString: DATABASE_URL,
      ssl: { rejectUnauthorized: false },
      max: 20,
      idleTimeoutMillis: 30000,
    });
    
    try {
      const client = await pool.connect();
      await client.query('SELECT NOW()');
      client.release();
      isPostgres = true;
      db = pool;
      console.log('  [DB] PostgreSQL connecté (PERSISTANT) ✓');
      await createPostgresTables();
      return { db, isPostgres: true };
    } catch (err) {
      console.error('  [DB] Erreur PostgreSQL:', err.message);
      console.log('  [DB] Fallback vers SQLite...');
    }
  }
  
  // SQLite (fallback ou développement)
  const Database = require('better-sqlite3');
  const DB_PATH = path.join(__dirname, 'surveillance.db');
  db = new Database(DB_PATH);
  db.pragma('journal_mode = WAL');
  isPostgres = false;
  console.log('  [DB] SQLite local (éphémère sur Render)');
  return { db, isPostgres: false };
}

// ═══════════════════════════════════════════════════════════════════════════════
// CRÉATION DES TABLES POSTGRESQL
// ═══════════════════════════════════════════════════════════════════════════════

async function createPostgresTables() {
  const bcrypt = require('bcryptjs');
  
  const tables = [
    `CREATE TABLE IF NOT EXISTS devices (
      "deviceId" TEXT PRIMARY KEY,
      "deviceName" TEXT NOT NULL DEFAULT 'Appareil inconnu',
      "userId" TEXT,
      "userName" TEXT,
      "acceptanceVersion" TEXT DEFAULT '1.0',
      "acceptanceDate" TEXT,
      "registeredAt" TEXT NOT NULL,
      "fcmToken" TEXT,
      "lastSeen" TEXT,
      "osVersion" TEXT,
      "appVersion" TEXT,
      "consentGiven" INTEGER DEFAULT 0
    )`,
    `CREATE TABLE IF NOT EXISTS events (
      id SERIAL PRIMARY KEY,
      "deviceId" TEXT NOT NULL,
      type TEXT NOT NULL,
      payload TEXT DEFAULT '{}',
      "receivedAt" TEXT NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS admins (
      id SERIAL PRIMARY KEY,
      username TEXT UNIQUE NOT NULL,
      "passwordHash" TEXT NOT NULL,
      "createdAt" TEXT NOT NULL,
      "totpSecret" TEXT DEFAULT '',
      "totpEnabled" INTEGER DEFAULT 0,
      "failedAttempts" INTEGER DEFAULT 0,
      "lockedUntil" TEXT DEFAULT '',
      "lastLoginAt" TEXT DEFAULT '',
      "lastLoginIp" TEXT DEFAULT ''
    )`,
    `CREATE TABLE IF NOT EXISTS keywords (
      id SERIAL PRIMARY KEY,
      word TEXT NOT NULL,
      category TEXT DEFAULT 'general',
      "createdAt" TEXT NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS alerts (
      id SERIAL PRIMARY KEY,
      "deviceId" TEXT NOT NULL,
      keyword TEXT NOT NULL,
      "eventType" TEXT,
      context TEXT,
      url TEXT,
      "createdAt" TEXT NOT NULL,
      seen INTEGER DEFAULT 0,
      severity TEXT DEFAULT 'warning',
      source TEXT DEFAULT 'keyword'
    )`,
    `CREATE TABLE IF NOT EXISTS commands (
      id SERIAL PRIMARY KEY,
      "deviceId" TEXT NOT NULL,
      type TEXT NOT NULL,
      payload TEXT DEFAULT '{}',
      status TEXT DEFAULT 'pending',
      "createdAt" TEXT NOT NULL,
      "executedAt" TEXT
    )`,
    `CREATE TABLE IF NOT EXISTS photos (
      id SERIAL PRIMARY KEY,
      "deviceId" TEXT NOT NULL,
      "commandId" INTEGER,
      filename TEXT NOT NULL,
      "mimeType" TEXT DEFAULT 'image/jpeg',
      "sizeBytes" INTEGER DEFAULT 0,
      source TEXT DEFAULT 'auto',
      "sourceApp" TEXT DEFAULT '',
      metadata TEXT DEFAULT '{}',
      "receivedAt" TEXT NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS audit_log (
      id SERIAL PRIMARY KEY,
      "adminId" INTEGER,
      "adminUsername" TEXT,
      action TEXT NOT NULL,
      detail TEXT DEFAULT '',
      ip TEXT DEFAULT '',
      "userAgent" TEXT DEFAULT '',
      "createdAt" TEXT NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS login_attempts (
      id SERIAL PRIMARY KEY,
      ip TEXT NOT NULL,
      username TEXT DEFAULT '',
      success INTEGER DEFAULT 0,
      "createdAt" TEXT NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS security_settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS sync_config (
      "deviceId" TEXT PRIMARY KEY,
      "syncIntervalMinutes" INTEGER DEFAULT 15,
      "syncOnWifiOnly" INTEGER DEFAULT 0,
      "syncOnChargingOnly" INTEGER DEFAULT 0,
      "modulesEnabled" TEXT DEFAULT '{"location":true,"browser":true,"apps":true,"photos":false,"network":false}',
      "photoQuality" REAL DEFAULT 0.5,
      "photoMaxWidth" INTEGER DEFAULT 1280,
      "updatedAt" TEXT,
      "blockedApps" TEXT DEFAULT '{}'
    )`,
    `CREATE TABLE IF NOT EXISTS consent_texts (
      id SERIAL PRIMARY KEY,
      version TEXT NOT NULL UNIQUE,
      title TEXT NOT NULL DEFAULT 'Politique de supervision',
      body TEXT NOT NULL,
      "createdAt" TEXT NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS consent_records (
      id SERIAL PRIMARY KEY,
      "deviceId" TEXT NOT NULL,
      "userId" TEXT,
      "userName" TEXT,
      "consentVersion" TEXT NOT NULL,
      "consentText" TEXT NOT NULL,
      "ipAddress" TEXT,
      "userAgent" TEXT,
      signature TEXT DEFAULT '',
      "consentedAt" TEXT NOT NULL,
      "revokedAt" TEXT
    )`,
    `CREATE TABLE IF NOT EXISTS data_requests (
      id SERIAL PRIMARY KEY,
      "deviceId" TEXT NOT NULL,
      type TEXT NOT NULL,
      status TEXT DEFAULT 'pending',
      "adminNote" TEXT DEFAULT '',
      "createdAt" TEXT NOT NULL,
      "processedAt" TEXT
    )`,
    `CREATE TABLE IF NOT EXISTS pending_commands (
      id SERIAL PRIMARY KEY,
      "deviceId" TEXT NOT NULL,
      command TEXT NOT NULL,
      params TEXT DEFAULT '{}',
      status TEXT DEFAULT 'pending',
      result TEXT,
      "createdAt" TEXT NOT NULL,
      "executedAt" TEXT
    )`,
    `CREATE TABLE IF NOT EXISTS geofence_zones (
      id SERIAL PRIMARY KEY,
      name TEXT NOT NULL,
      latitude REAL NOT NULL,
      longitude REAL NOT NULL,
      "radiusMeters" REAL NOT NULL DEFAULT 100,
      "alertOnEnter" INTEGER DEFAULT 1,
      "alertOnExit" INTEGER DEFAULT 1,
      "isActive" INTEGER DEFAULT 1,
      "createdAt" TEXT NOT NULL
    )`,
  ];

  for (const sql of tables) {
    try { await pool.query(sql); } catch (e) { /* table existe déjà */ }
  }

  // Index
  const indexes = [
    'CREATE INDEX IF NOT EXISTS idx_events_deviceId ON events("deviceId")',
    'CREATE INDEX IF NOT EXISTS idx_events_type ON events(type)',
    'CREATE INDEX IF NOT EXISTS idx_events_receivedAt ON events("receivedAt")',
    'CREATE INDEX IF NOT EXISTS idx_alerts_seen ON alerts(seen)',
    'CREATE INDEX IF NOT EXISTS idx_commands_deviceId ON commands("deviceId")',
    'CREATE INDEX IF NOT EXISTS idx_commands_status ON commands(status)',
    'CREATE INDEX IF NOT EXISTS idx_photos_deviceId ON photos("deviceId")',
    'CREATE INDEX IF NOT EXISTS idx_pending_commands_deviceId ON pending_commands("deviceId")',
  ];
  for (const idx of indexes) {
    try { await pool.query(idx); } catch (e) { }
  }

  // Admin par défaut
  const adminRes = await pool.query('SELECT COUNT(*) as c FROM admins');
  if (parseInt(adminRes.rows[0].c) === 0) {
    const hash = bcrypt.hashSync('00P002!Surv2026', 10);
    await pool.query(
      'INSERT INTO admins (username, "passwordHash", "createdAt") VALUES ($1, $2, $3)',
      ['admin', hash, new Date().toISOString()]
    );
    console.log('  [DB] Admin créé: admin / 00P002!Surv2026');
  }

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
  for (const [k, v] of Object.entries(defaultSettings)) {
    try {
      await pool.query(
        'INSERT INTO security_settings (key, value) VALUES ($1, $2) ON CONFLICT (key) DO NOTHING',
        [k, v]
      );
    } catch (e) { }
  }

  // Texte de consentement par défaut
  const consentRes = await pool.query('SELECT COUNT(*) as c FROM consent_texts');
  if (parseInt(consentRes.rows[0].c) === 0) {
    await pool.query(
      'INSERT INTO consent_texts (version, title, body, "createdAt") VALUES ($1, $2, $3, $4)',
      ['1.0', 'Politique de supervision', 'Texte de consentement par défaut...', new Date().toISOString()]
    );
  }

  console.log('  [DB] Tables PostgreSQL créées ✓');
}

// ═══════════════════════════════════════════════════════════════════════════════
// GETTERS
// ═══════════════════════════════════════════════════════════════════════════════

function getDb() { return db; }
function getIsPostgres() { return isPostgres; }
function getPool() { return pool; }

module.exports = {
  init,
  getDb,
  getIsPostgres,
  getPool,
};
