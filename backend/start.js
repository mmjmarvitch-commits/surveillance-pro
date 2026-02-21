/**
 * Script de démarrage intelligent
 * Utilise PostgreSQL si DATABASE_URL est défini, sinon SQLite
 */

const { spawn } = require('child_process');
const path = require('path');

const DATABASE_URL = process.env.DATABASE_URL;

if (DATABASE_URL) {
  console.log('  [Start] DATABASE_URL détecté → PostgreSQL (persistant)');
  require('./server-pg.js');
} else {
  console.log('  [Start] Pas de DATABASE_URL → SQLite (éphémère)');
  require('./server.js');
}
