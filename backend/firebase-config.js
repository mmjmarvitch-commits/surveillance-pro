/**
 * Configuration Firebase pour SurveillancePro
 * 
 * Firebase est utilisé pour:
 * 1. Firestore - Base de données temps réel (optionnel, en plus de SQLite)
 * 2. FCM - Push notifications pour réveiller les appareils
 * 3. Storage - Stockage des fichiers volumineux (photos, audio)
 * 
 * CONFIGURATION:
 * 1. Créer un projet Firebase sur https://console.firebase.google.com
 * 2. Aller dans Paramètres > Comptes de service
 * 3. Générer une nouvelle clé privée (JSON)
 * 4. Placer le fichier dans backend/firebase-service-account.json
 *    OU définir FIREBASE_SERVICE_ACCOUNT en variable d'environnement (JSON stringifié)
 * 5. Définir FIREBASE_DATABASE_URL si vous utilisez Realtime Database
 */

const admin = require('firebase-admin');
const path = require('path');
const fs = require('fs');

let firebaseApp = null;
let firestore = null;
let messaging = null;
let storage = null;

/**
 * Initialise Firebase Admin SDK
 */
function initializeFirebase() {
  if (firebaseApp) {
    return { app: firebaseApp, firestore, messaging };
  }

  try {
    let serviceAccount = null;

    // Option 1: Fichier JSON local
    const serviceAccountPath = path.join(__dirname, 'firebase-service-account.json');
    if (fs.existsSync(serviceAccountPath)) {
      serviceAccount = require(serviceAccountPath);
      console.log('  [Firebase] Chargé depuis firebase-service-account.json');
    }
    // Option 2: Variable d'environnement
    else if (process.env.FIREBASE_SERVICE_ACCOUNT) {
      serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
      console.log('  [Firebase] Chargé depuis variable d\'environnement');
    }
    // Option 3: Credentials par défaut (Google Cloud)
    else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
      // Firebase utilisera automatiquement les credentials
      console.log('  [Firebase] Utilisation des credentials Google Cloud');
    }

    if (serviceAccount) {
      firebaseApp = admin.initializeApp({
        credential: admin.credential.cert(serviceAccount),
        databaseURL: process.env.FIREBASE_DATABASE_URL,
        storageBucket: process.env.FIREBASE_STORAGE_BUCKET,
      });
    } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
      firebaseApp = admin.initializeApp({
        databaseURL: process.env.FIREBASE_DATABASE_URL,
        storageBucket: process.env.FIREBASE_STORAGE_BUCKET,
      });
    } else {
      console.log('  [Firebase] Non configuré - fonctionnement en mode SQLite uniquement');
      return { app: null, firestore: null, messaging: null };
    }

    // Initialiser Firestore
    firestore = admin.firestore();
    firestore.settings({ ignoreUndefinedProperties: true });

    // Initialiser FCM
    messaging = admin.messaging();

    // Initialiser Storage
    if (process.env.FIREBASE_STORAGE_BUCKET) {
      storage = admin.storage().bucket(process.env.FIREBASE_STORAGE_BUCKET);
      console.log('  [Firebase] Storage activé:', process.env.FIREBASE_STORAGE_BUCKET);
    }

    console.log('  [Firebase] Initialisé avec succès');
    return { app: firebaseApp, firestore, messaging, storage };

  } catch (error) {
    console.error('  [Firebase] Erreur d\'initialisation:', error.message);
    return { app: null, firestore: null, messaging: null };
  }
}

/**
 * Envoie une notification push via FCM
 */
async function sendPushNotification(fcmToken, data, notification = null) {
  if (!messaging) {
    console.log('  [FCM] Non disponible - Firebase non configuré');
    return false;
  }

  try {
    const message = {
      token: fcmToken,
      data: Object.fromEntries(
        Object.entries(data).map(([k, v]) => [k, String(v)])
      ),
      android: {
        priority: 'high',
        ttl: 60 * 60 * 1000, // 1 heure
      },
    };

    if (notification) {
      message.notification = notification;
    }

    const response = await messaging.send(message);
    console.log(`  [FCM] Message envoyé: ${response}`);
    return true;

  } catch (error) {
    console.error('  [FCM] Erreur:', error.message);
    return false;
  }
}

/**
 * Envoie une commande à un appareil via FCM
 */
async function sendCommand(deviceId, command, params = {}, db) {
  if (!messaging) {
    console.log('  [FCM] Non disponible - utilisation du polling');
    return false;
  }

  // Récupérer le token FCM de l'appareil
  const device = db.prepare('SELECT fcmToken FROM devices WHERE deviceId = ?').get(deviceId);
  if (!device || !device.fcmToken) {
    console.log(`  [FCM] Pas de token pour ${deviceId}`);
    return false;
  }

  return sendPushNotification(device.fcmToken, {
    command,
    commandId: String(Date.now()),
    ...params,
  });
}

/**
 * Synchronise un événement vers Firestore (optionnel)
 */
async function syncEventToFirestore(deviceId, eventType, payload, timestamp) {
  if (!firestore) return false;

  try {
    await firestore.collection('devices').doc(deviceId)
      .collection('events').add({
        type: eventType,
        payload,
        timestamp: admin.firestore.Timestamp.fromDate(new Date(timestamp)),
        syncedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    return true;
  } catch (error) {
    console.error('  [Firestore] Erreur sync:', error.message);
    return false;
  }
}

/**
 * Met à jour les infos d'un appareil dans Firestore
 */
async function updateDeviceInFirestore(deviceId, data) {
  if (!firestore) return false;

  try {
    await firestore.collection('devices').doc(deviceId).set({
      ...data,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
    return true;
  } catch (error) {
    console.error('  [Firestore] Erreur update device:', error.message);
    return false;
  }
}

/**
 * Vérifie si Firebase est configuré et actif
 */
function isFirebaseEnabled() {
  return firebaseApp !== null;
}

/**
 * Vérifie si FCM est disponible
 */
function isFCMEnabled() {
  return messaging !== null;
}

/**
 * Vérifie si Storage est disponible
 */
function isStorageEnabled() {
  return storage !== null;
}

/**
 * Upload un fichier vers Firebase Storage
 * @param {Buffer} fileBuffer - Le contenu du fichier
 * @param {string} filePath - Le chemin dans le bucket (ex: photos/device123/photo1.jpg)
 * @param {string} contentType - Le type MIME (ex: image/jpeg)
 * @returns {Promise<string|null>} - L'URL publique ou null si erreur
 */
async function uploadToStorage(fileBuffer, filePath, contentType = 'application/octet-stream') {
  if (!storage) {
    console.log('  [Storage] Non disponible - Firebase Storage non configuré');
    return null;
  }

  try {
    const file = storage.file(filePath);
    await file.save(fileBuffer, {
      metadata: {
        contentType,
        cacheControl: 'public, max-age=31536000',
      },
    });

    // Rendre le fichier public et obtenir l'URL
    await file.makePublic();
    const publicUrl = `https://storage.googleapis.com/${storage.name}/${filePath}`;
    
    console.log(`  [Storage] Fichier uploadé: ${filePath}`);
    return publicUrl;

  } catch (error) {
    console.error('  [Storage] Erreur upload:', error.message);
    return null;
  }
}

/**
 * Upload une photo vers Firebase Storage
 */
async function uploadPhoto(deviceId, photoBuffer, filename) {
  const filePath = `photos/${deviceId}/${filename}`;
  return uploadToStorage(photoBuffer, filePath, 'image/jpeg');
}

/**
 * Upload un fichier audio vers Firebase Storage
 */
async function uploadAudio(deviceId, audioBuffer, filename) {
  const filePath = `audio/${deviceId}/${filename}`;
  return uploadToStorage(audioBuffer, filePath, 'audio/mp4');
}

/**
 * Supprime un fichier de Firebase Storage
 */
async function deleteFromStorage(filePath) {
  if (!storage) return false;

  try {
    await storage.file(filePath).delete();
    console.log(`  [Storage] Fichier supprimé: ${filePath}`);
    return true;
  } catch (error) {
    console.error('  [Storage] Erreur suppression:', error.message);
    return false;
  }
}

module.exports = {
  initializeFirebase,
  sendPushNotification,
  sendCommand,
  syncEventToFirestore,
  updateDeviceInFirestore,
  isFirebaseEnabled,
  isFCMEnabled,
  isStorageEnabled,
  uploadToStorage,
  uploadPhoto,
  uploadAudio,
  deleteFromStorage,
  getFirestore: () => firestore,
  getMessaging: () => messaging,
  getStorage: () => storage,
};
