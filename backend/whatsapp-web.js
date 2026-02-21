/**
 * WhatsApp Web Integration avec Baileys
 * Capture les messages WhatsApp en temps réel via WhatsApp Web
 * 
 * Fonctionnement:
 * 1. Génère un QR code pour lier un compte WhatsApp
 * 2. Une fois lié, capture tous les messages (envoyés et reçus)
 * 3. Stocke les messages dans la base de données
 * 4. Envoie les messages au dashboard en temps réel via WebSocket
 */

const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, fetchLatestBaileysVersion } = require('@whiskeysockets/baileys');
const { Boom } = require('@hapi/boom');
const path = require('path');
const fs = require('fs');
const QRCode = require('qrcode');

// Dossier pour stocker les sessions WhatsApp
const SESSIONS_DIR = path.join(__dirname, 'whatsapp-sessions');
if (!fs.existsSync(SESSIONS_DIR)) fs.mkdirSync(SESSIONS_DIR, { recursive: true });

// Map des connexions WhatsApp actives (deviceId -> socket)
const activeConnections = new Map();

// Callbacks pour les événements
let onMessageCallback = null;
let onQRCallback = null;
let onConnectionCallback = null;

/**
 * Configure les callbacks pour les événements WhatsApp
 */
function setCallbacks({ onMessage, onQR, onConnection }) {
    if (onMessage) onMessageCallback = onMessage;
    if (onQR) onQRCallback = onQR;
    if (onConnection) onConnectionCallback = onConnection;
}

/**
 * Démarre une session WhatsApp pour un appareil
 * @param {string} deviceId - ID de l'appareil
 * @returns {Promise<{qrCode: string|null, connected: boolean}>}
 */
async function startSession(deviceId) {
    const sessionPath = path.join(SESSIONS_DIR, deviceId);
    
    // Vérifier si déjà connecté
    if (activeConnections.has(deviceId)) {
        const sock = activeConnections.get(deviceId);
        if (sock.user) {
            return { qrCode: null, connected: true, phone: sock.user.id };
        }
    }
    
    try {
        const { state, saveCreds } = await useMultiFileAuthState(sessionPath);
        const { version } = await fetchLatestBaileysVersion();
        
        const sock = makeWASocket({
            version,
            auth: state,
            printQRInTerminal: false,
            browser: ['Supervision Pro', 'Chrome', '120.0.0'],
            syncFullHistory: true,
        });
        
        activeConnections.set(deviceId, sock);
        
        // Gérer les événements de connexion
        sock.ev.on('connection.update', async (update) => {
            const { connection, lastDisconnect, qr } = update;
            
            if (qr) {
                // Générer le QR code en base64
                const qrBase64 = await QRCode.toDataURL(qr);
                console.log(`[WhatsApp] QR code généré pour ${deviceId}`);
                
                if (onQRCallback) {
                    onQRCallback(deviceId, qrBase64);
                }
                
                return { qrCode: qrBase64, connected: false };
            }
            
            if (connection === 'close') {
                const reason = new Boom(lastDisconnect?.error)?.output?.statusCode;
                console.log(`[WhatsApp] Connexion fermée pour ${deviceId}, raison: ${reason}`);
                
                if (reason === DisconnectReason.loggedOut) {
                    // Session déconnectée, supprimer les credentials
                    if (fs.existsSync(sessionPath)) {
                        fs.rmSync(sessionPath, { recursive: true });
                    }
                    activeConnections.delete(deviceId);
                    
                    if (onConnectionCallback) {
                        onConnectionCallback(deviceId, 'disconnected', null);
                    }
                } else {
                    // Reconnecter automatiquement
                    setTimeout(() => startSession(deviceId), 5000);
                }
            } else if (connection === 'open') {
                console.log(`[WhatsApp] Connecté pour ${deviceId}: ${sock.user?.id}`);
                
                if (onConnectionCallback) {
                    onConnectionCallback(deviceId, 'connected', sock.user?.id);
                }
            }
        });
        
        // Sauvegarder les credentials
        sock.ev.on('creds.update', saveCreds);
        
        // Écouter les messages
        sock.ev.on('messages.upsert', async ({ messages, type }) => {
            if (type !== 'notify') return;
            
            for (const msg of messages) {
                await handleMessage(deviceId, msg);
            }
        });
        
        // Écouter les messages envoyés
        sock.ev.on('messages.update', async (updates) => {
            for (const update of updates) {
                if (update.update?.status === 3) { // Message envoyé
                    // Le message a été envoyé avec succès
                }
            }
        });
        
        return { qrCode: null, connected: !!sock.user };
        
    } catch (error) {
        console.error(`[WhatsApp] Erreur pour ${deviceId}:`, error);
        return { qrCode: null, connected: false, error: error.message };
    }
}

/**
 * Traite un message WhatsApp reçu
 */
async function handleMessage(deviceId, msg) {
    try {
        // Ignorer les messages de statut et les réactions
        if (msg.key.remoteJid === 'status@broadcast') return;
        if (msg.message?.reactionMessage) return;
        
        const isFromMe = msg.key.fromMe;
        const remoteJid = msg.key.remoteJid;
        const isGroup = remoteJid.endsWith('@g.us');
        
        // Extraire le contenu du message
        let messageContent = '';
        let messageType = 'text';
        let mediaUrl = null;
        
        if (msg.message?.conversation) {
            messageContent = msg.message.conversation;
        } else if (msg.message?.extendedTextMessage?.text) {
            messageContent = msg.message.extendedTextMessage.text;
        } else if (msg.message?.imageMessage) {
            messageType = 'image';
            messageContent = msg.message.imageMessage.caption || '📷 Photo';
        } else if (msg.message?.videoMessage) {
            messageType = 'video';
            messageContent = msg.message.videoMessage.caption || '🎬 Vidéo';
        } else if (msg.message?.audioMessage) {
            messageType = 'audio';
            messageContent = msg.message.audioMessage.ptt ? '🎤 Message vocal' : '🎵 Audio';
        } else if (msg.message?.documentMessage) {
            messageType = 'document';
            messageContent = `📄 ${msg.message.documentMessage.fileName || 'Document'}`;
        } else if (msg.message?.stickerMessage) {
            messageType = 'sticker';
            messageContent = '🎨 Sticker';
        } else if (msg.message?.locationMessage) {
            messageType = 'location';
            const loc = msg.message.locationMessage;
            messageContent = `📍 Position: ${loc.degreesLatitude}, ${loc.degreesLongitude}`;
        } else if (msg.message?.contactMessage) {
            messageType = 'contact';
            messageContent = `👤 Contact: ${msg.message.contactMessage.displayName}`;
        } else {
            // Type de message non géré
            return;
        }
        
        // Extraire le numéro/nom du contact
        const sender = isFromMe ? 'Moi' : (msg.pushName || remoteJid.split('@')[0]);
        const phone = remoteJid.split('@')[0];
        
        // Créer l'objet message
        const messageData = {
            deviceId,
            app: 'WhatsApp',
            sender,
            phone,
            message: messageContent,
            messageType,
            isOutgoing: isFromMe,
            isGroup,
            groupName: isGroup ? (msg.key.participant ? 'Groupe' : null) : null,
            messageId: msg.key.id,
            timestamp: new Date(msg.messageTimestamp * 1000).toISOString(),
            capturedAt: new Date().toISOString(),
            source: 'whatsapp_web',
        };
        
        console.log(`[WhatsApp] Message capturé: ${sender} -> ${messageContent.substring(0, 50)}...`);
        
        // Envoyer au callback
        if (onMessageCallback) {
            onMessageCallback(messageData);
        }
        
    } catch (error) {
        console.error('[WhatsApp] Erreur traitement message:', error);
    }
}

/**
 * Déconnecte une session WhatsApp
 */
async function disconnectSession(deviceId) {
    if (activeConnections.has(deviceId)) {
        const sock = activeConnections.get(deviceId);
        await sock.logout();
        activeConnections.delete(deviceId);
        
        const sessionPath = path.join(SESSIONS_DIR, deviceId);
        if (fs.existsSync(sessionPath)) {
            fs.rmSync(sessionPath, { recursive: true });
        }
        
        return true;
    }
    return false;
}

/**
 * Vérifie si une session est connectée
 */
function isConnected(deviceId) {
    if (activeConnections.has(deviceId)) {
        const sock = activeConnections.get(deviceId);
        return !!sock.user;
    }
    return false;
}

/**
 * Récupère toutes les sessions actives
 */
function getActiveSessions() {
    const sessions = [];
    for (const [deviceId, sock] of activeConnections) {
        sessions.push({
            deviceId,
            connected: !!sock.user,
            phone: sock.user?.id || null,
        });
    }
    return sessions;
}

/**
 * Restaure les sessions existantes au démarrage
 */
async function restoreSessions() {
    if (!fs.existsSync(SESSIONS_DIR)) return;
    
    const dirs = fs.readdirSync(SESSIONS_DIR);
    for (const deviceId of dirs) {
        const sessionPath = path.join(SESSIONS_DIR, deviceId);
        if (fs.statSync(sessionPath).isDirectory()) {
            console.log(`[WhatsApp] Restauration session: ${deviceId}`);
            await startSession(deviceId);
        }
    }
}

module.exports = {
    setCallbacks,
    startSession,
    disconnectSession,
    isConnected,
    getActiveSessions,
    restoreSessions,
};
