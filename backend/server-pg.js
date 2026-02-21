/**
 * Serveur avec support PostgreSQL natif
 * Ce fichier remplace server.js quand DATABASE_URL est défini
 * 
 * Usage: node server-pg.js
 */

const express = require('express');
const cors = require('cors');
const compression = require('compression');
const path = require('path');
const fs = require('fs');
const http = require('http');
const crypto = require('crypto');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const { WebSocketServer } = require('ws');
const { Pool } = require('pg');

// ─── Configuration ───
const PORT = process.env.PORT || 3000;
const DATABASE_URL = process.env.DATABASE_URL;
const IS_PRODUCTION = process.env.NODE_ENV === 'production';

if (!DATABASE_URL) {
  console.error('❌ DATABASE_URL non défini. Utilisez server.js pour SQLite.');
  process.exit(1);
}

const JWT_SECRET = process.env.JWT_SECRET || crypto.randomBytes(64).toString('hex');

// ─── PostgreSQL Pool ───
const pool = new Pool({
  connectionString: DATABASE_URL,
  ssl: { rejectUnauthorized: false },
  max: 20,
});

const app = express();
const server = http.createServer(app);

// ─── Middleware ───
app.use(cors());
app.use(compression());
app.use(express.json({ limit: '30mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// ─── WebSocket ───
const wss = new WebSocketServer({ server, path: '/ws' });
const wsClients = new Set();

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.isAuthenticated = true;
  wsClients.add(ws);
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('close', () => wsClients.delete(ws));
});

function broadcast(data) {
  const msg = JSON.stringify(data);
  wsClients.forEach(ws => {
    if (ws.readyState === 1) ws.send(msg);
  });
}

// ─── Helper: Query PostgreSQL ───
async function query(sql, params = []) {
  const result = await pool.query(sql, params);
  return result.rows;
}

async function queryOne(sql, params = []) {
  const rows = await query(sql, params);
  return rows[0] || null;
}

async function run(sql, params = []) {
  const result = await pool.query(sql, params);
  return { changes: result.rowCount, lastId: result.rows[0]?.id };
}

// ─── Auth Middleware ───
function authRequired(req, res, next) {
  const token = req.headers.authorization?.replace('Bearer ', '');
  if (!token) return res.status(401).json({ error: 'Token requis' });
  try {
    req.admin = jwt.verify(token, JWT_SECRET);
    next();
  } catch { res.status(401).json({ error: 'Token invalide' }); }
}

// ─── Routes API ───

// Health check
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', db: 'postgresql', timestamp: new Date().toISOString() });
});

// Login
app.post('/api/auth/login', async (req, res) => {
  try {
    const { username, password } = req.body;
    const admin = await queryOne('SELECT * FROM admins WHERE username = $1', [username]);
    if (!admin || !bcrypt.compareSync(password, admin.passwordHash)) {
      return res.status(401).json({ error: 'Identifiants invalides' });
    }
    const token = jwt.sign({ id: admin.id, username: admin.username }, JWT_SECRET, { expiresIn: '24h' });
    res.json({ token, admin: { id: admin.id, username: admin.username } });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Stats
app.get('/api/stats', authRequired, async (req, res) => {
  try {
    const devices = await queryOne('SELECT COUNT(*) as c FROM devices');
    const events = await queryOne('SELECT COUNT(*) as c FROM events');
    const todayStart = new Date(); todayStart.setHours(0, 0, 0, 0);
    const eventsToday = await queryOne('SELECT COUNT(*) as c FROM events WHERE "receivedAt" > $1', [todayStart.toISOString()]);
    const alerts = await queryOne('SELECT COUNT(*) as c FROM alerts WHERE seen = 0');
    
    res.json({
      totalDevices: parseInt(devices.c),
      totalEvents: parseInt(events.c),
      eventsToday: parseInt(eventsToday.c),
      unseenAlerts: parseInt(alerts.c),
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Devices
app.get('/api/devices', authRequired, async (req, res) => {
  try {
    const devices = await query('SELECT * FROM devices ORDER BY "registeredAt" DESC');
    res.json(devices);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/devices/register', async (req, res) => {
  try {
    const { deviceId, deviceName, osVersion, appVersion } = req.body;
    if (!deviceId) return res.status(400).json({ error: 'deviceId requis' });
    
    const existing = await queryOne('SELECT * FROM devices WHERE "deviceId" = $1', [deviceId]);
    if (existing) {
      await run('UPDATE devices SET "lastSeen" = $1, "osVersion" = $2, "appVersion" = $3 WHERE "deviceId" = $4',
        [new Date().toISOString(), osVersion, appVersion, deviceId]);
    } else {
      await run('INSERT INTO devices ("deviceId", "deviceName", "registeredAt", "lastSeen", "osVersion", "appVersion") VALUES ($1, $2, $3, $3, $4, $5)',
        [deviceId, deviceName || 'Appareil', new Date().toISOString(), osVersion, appVersion]);
    }
    
    const token = jwt.sign({ type: 'device', deviceId }, JWT_SECRET, { expiresIn: '365d' });
    res.json({ success: true, token, deviceId });
    broadcast({ type: 'device_registered', deviceId });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Events
app.get('/api/events', authRequired, async (req, res) => {
  try {
    const { deviceId, type, limit = 100 } = req.query;
    let sql = 'SELECT * FROM events WHERE 1=1';
    const params = [];
    let idx = 1;
    
    if (deviceId) { sql += ` AND "deviceId" = $${idx++}`; params.push(deviceId); }
    if (type) { sql += ` AND type = $${idx++}`; params.push(type); }
    sql += ` ORDER BY "receivedAt" DESC LIMIT $${idx}`;
    params.push(parseInt(limit));
    
    const events = await query(sql, params);
    res.json(events.map(e => ({ ...e, payload: JSON.parse(e.payload || '{}') })));
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/events', async (req, res) => {
  try {
    const { deviceId, type, payload } = req.body;
    if (!deviceId || !type) return res.status(400).json({ error: 'deviceId et type requis' });
    
    await run('INSERT INTO events ("deviceId", type, payload, "receivedAt") VALUES ($1, $2, $3, $4)',
      [deviceId, type, JSON.stringify(payload || {}), new Date().toISOString()]);
    
    await run('UPDATE devices SET "lastSeen" = $1 WHERE "deviceId" = $2', [new Date().toISOString(), deviceId]);
    
    broadcast({ type: 'new_event', deviceId, eventType: type });
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Sync (batch events)
app.post('/api/sync', async (req, res) => {
  try {
    const { deviceId, events: eventsList } = req.body;
    if (!deviceId) return res.status(400).json({ error: 'deviceId requis' });
    
    let count = 0;
    for (const evt of (eventsList || [])) {
      await run('INSERT INTO events ("deviceId", type, payload, "receivedAt") VALUES ($1, $2, $3, $4)',
        [deviceId, evt.type, JSON.stringify(evt.payload || {}), evt.timestamp || new Date().toISOString()]);
      count++;
    }
    
    await run('UPDATE devices SET "lastSeen" = $1 WHERE "deviceId" = $2', [new Date().toISOString(), deviceId]);
    
    broadcast({ type: 'sync', deviceId, count });
    res.json({ success: true, received: count });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Alerts
app.get('/api/alerts', authRequired, async (req, res) => {
  try {
    const alerts = await query('SELECT * FROM alerts ORDER BY "createdAt" DESC LIMIT 100');
    res.json(alerts);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Pending commands
app.get('/api/commands/:deviceId/pending', async (req, res) => {
  try {
    const commands = await query('SELECT * FROM pending_commands WHERE "deviceId" = $1 AND status = $2',
      [req.params.deviceId, 'pending']);
    res.json(commands.map(c => ({ ...c, params: JSON.parse(c.params || '{}') })));
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/commands/:deviceId/ack', async (req, res) => {
  try {
    const { commandId, result } = req.body;
    await run('UPDATE pending_commands SET status = $1, result = $2, "executedAt" = $3 WHERE id = $4',
      ['executed', JSON.stringify(result || {}), new Date().toISOString(), commandId]);
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post('/api/commands/:deviceId', authRequired, async (req, res) => {
  try {
    const { command, params } = req.body;
    const result = await pool.query(
      'INSERT INTO pending_commands ("deviceId", command, params, "createdAt") VALUES ($1, $2, $3, $4) RETURNING id',
      [req.params.deviceId, command, JSON.stringify(params || {}), new Date().toISOString()]
    );
    res.json({ success: true, commandId: result.rows[0].id });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Serve dashboard
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// ─── Initialisation ───
async function init() {
  try {
    // Test connexion
    await pool.query('SELECT NOW()');
    console.log('  [DB] PostgreSQL connecté ✓');
    
    // Créer les tables
    await pool.query(`
      CREATE TABLE IF NOT EXISTS devices (
        "deviceId" TEXT PRIMARY KEY,
        "deviceName" TEXT DEFAULT 'Appareil',
        "userId" TEXT,
        "userName" TEXT,
        "registeredAt" TEXT NOT NULL,
        "lastSeen" TEXT,
        "osVersion" TEXT,
        "appVersion" TEXT,
        "consentGiven" INTEGER DEFAULT 0
      )
    `);
    await pool.query(`
      CREATE TABLE IF NOT EXISTS events (
        id SERIAL PRIMARY KEY,
        "deviceId" TEXT NOT NULL,
        type TEXT NOT NULL,
        payload TEXT DEFAULT '{}',
        "receivedAt" TEXT NOT NULL
      )
    `);
    await pool.query(`
      CREATE TABLE IF NOT EXISTS admins (
        id SERIAL PRIMARY KEY,
        username TEXT UNIQUE NOT NULL,
        "passwordHash" TEXT NOT NULL,
        "createdAt" TEXT NOT NULL
      )
    `);
    await pool.query(`
      CREATE TABLE IF NOT EXISTS alerts (
        id SERIAL PRIMARY KEY,
        "deviceId" TEXT NOT NULL,
        keyword TEXT,
        context TEXT,
        severity TEXT DEFAULT 'warning',
        "createdAt" TEXT NOT NULL,
        seen INTEGER DEFAULT 0
      )
    `);
    await pool.query(`
      CREATE TABLE IF NOT EXISTS pending_commands (
        id SERIAL PRIMARY KEY,
        "deviceId" TEXT NOT NULL,
        command TEXT NOT NULL,
        params TEXT DEFAULT '{}',
        status TEXT DEFAULT 'pending',
        result TEXT,
        "createdAt" TEXT NOT NULL,
        "executedAt" TEXT
      )
    `);
    
    // Index
    try { await pool.query('CREATE INDEX IF NOT EXISTS idx_events_deviceId ON events("deviceId")'); } catch {}
    try { await pool.query('CREATE INDEX IF NOT EXISTS idx_events_receivedAt ON events("receivedAt")'); } catch {}
    try { await pool.query('CREATE INDEX IF NOT EXISTS idx_pending_commands_deviceId ON pending_commands("deviceId")'); } catch {}
    
    // Admin par défaut
    const adminRes = await pool.query('SELECT COUNT(*) as c FROM admins');
    if (parseInt(adminRes.rows[0].c) === 0) {
      const hash = bcrypt.hashSync('00P002!Surv2026', 10);
      await pool.query('INSERT INTO admins (username, "passwordHash", "createdAt") VALUES ($1, $2, $3)',
        ['admin', hash, new Date().toISOString()]);
      console.log('  [DB] Admin créé: admin / 00P002!Surv2026');
    }
    
    console.log('  [DB] Tables créées ✓');
    
    // Démarrer le serveur
    server.listen(PORT, () => {
      console.log(`\n  ╔═══════════════════════════════════════════════════════════╗`);
      console.log(`  ║         Surveillance Pro – PostgreSQL                     ║`);
      console.log(`  ╠═══════════════════════════════════════════════════════════╣`);
      console.log(`  ║  Dashboard  : http://localhost:${PORT}${' '.repeat(27 - PORT.toString().length)}║`);
      console.log(`  ║  API        : http://localhost:${PORT}/api${' '.repeat(23 - PORT.toString().length)}║`);
      console.log(`  ║  Database   : PostgreSQL (PERSISTANT)                     ║`);
      console.log(`  ╚═══════════════════════════════════════════════════════════╝\n`);
    });
  } catch (e) {
    console.error('❌ Erreur initialisation:', e.message);
    process.exit(1);
  }
}

init();

// Cleanup
process.on('SIGINT', () => { pool.end(); process.exit(0); });
process.on('SIGTERM', () => { pool.end(); process.exit(0); });
