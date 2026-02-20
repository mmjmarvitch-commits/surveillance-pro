# 🔥 Configuration Firebase pour SurveillancePro

## Pourquoi Firebase ?

Firebase offre plusieurs avantages:
- **FCM (Firebase Cloud Messaging)** - Réveiller l'appareil à distance instantanément
- **Firestore** - Base de données temps réel (optionnel, en plus de SQLite)
- **Storage** - Stockage des fichiers volumineux (photos, audio)
- **Analytics** - Statistiques d'utilisation

---

## 📱 Étape 1: Créer un projet Firebase

1. Aller sur https://console.firebase.google.com
2. Cliquer sur **"Ajouter un projet"**
3. Nom du projet: `SurveillancePro` (ou autre)
4. Désactiver Google Analytics (optionnel)
5. Cliquer sur **"Créer le projet"**

---

## 📲 Étape 2: Ajouter l'app Android

1. Dans la console Firebase, cliquer sur **"Ajouter une application"** → Android
2. Remplir les informations:
   - **Nom du package**: `com.surveillancepro.android`
   - **Nom de l'application**: `Supervision Pro`
   - **Certificat SHA-1**: (optionnel pour commencer)

3. Télécharger le fichier `google-services.json`

4. **IMPORTANT**: Placer le fichier dans:
   ```
   android/app/google-services.json
   ```

---

## 🖥️ Étape 3: Configurer le Backend

### Option A: Fichier JSON (recommandé pour développement)

1. Dans Firebase Console → **Paramètres du projet** → **Comptes de service**
2. Cliquer sur **"Générer une nouvelle clé privée"**
3. Télécharger le fichier JSON
4. Renommer en `firebase-service-account.json`
5. Placer dans:
   ```
   backend/firebase-service-account.json
   ```

### Option B: Variables d'environnement (recommandé pour production)

Ajouter dans `.env`:
```env
# Contenu du fichier JSON sur une seule ligne
FIREBASE_SERVICE_ACCOUNT={"type":"service_account","project_id":"...","private_key":"..."}

# URL de la base de données (si vous utilisez Realtime Database)
FIREBASE_DATABASE_URL=https://votre-projet.firebaseio.com

# Bucket de stockage (si vous utilisez Storage)
FIREBASE_STORAGE_BUCKET=votre-projet.appspot.com
```

---

## 🔑 Étape 4: Obtenir la clé serveur FCM (pour les push)

1. Firebase Console → **Paramètres du projet** → **Cloud Messaging**
2. Copier la **"Clé du serveur"** (Server key)
3. Ajouter dans `.env`:
   ```env
   FCM_SERVER_KEY=AAAA...votre_clé...
   ```

---

## ✅ Étape 5: Vérifier l'installation

### Backend
```bash
cd backend
npm install
npm run dev
```

Vous devriez voir:
```
  [Firebase] Chargé depuis firebase-service-account.json
  [Firebase] Initialisé avec succès
  ...
  ║  ✅ Firebase/FCM actif                                      ║
```

### Android
1. Ouvrir le projet dans Android Studio
2. Sync Gradle
3. Builder l'APK

---

## 📁 Structure des fichiers

```
surveillance_pro/
├── backend/
│   ├── firebase-config.js          ← Configuration Firebase
│   ├── firebase-service-account.json  ← Clé privée (NE PAS COMMIT!)
│   └── server.js
│
├── android/
│   └── app/
│       ├── google-services.json    ← Config Android (NE PAS COMMIT!)
│       └── build.gradle.kts
│
└── .env                            ← Variables d'environnement
```

---

## 🔒 Sécurité

**IMPORTANT**: Ne jamais commit ces fichiers sur Git!

Ajouter dans `.gitignore`:
```gitignore
# Firebase
backend/firebase-service-account.json
android/app/google-services.json
.env
```

---

## 🧪 Tester FCM

### Depuis le dashboard
1. Aller sur la page d'un appareil
2. Cliquer sur "Envoyer commande"
3. Choisir une commande (ex: `sync`)
4. L'appareil devrait répondre immédiatement

### Depuis la console Firebase
1. Firebase Console → **Cloud Messaging** → **Envoyer le premier message**
2. Créer une notification de test
3. Cibler l'app Android
4. Envoyer

---

## 🔧 Dépannage

### "Firebase non configuré"
- Vérifier que `firebase-service-account.json` existe dans `backend/`
- Vérifier que le fichier JSON est valide

### "FCM token non trouvé"
- L'appareil doit avoir `google-services.json` configuré
- L'appareil doit être connecté à Internet
- Vérifier les logs Android pour les erreurs FCM

### "Push non reçu"
- Vérifier que l'app n'est pas en mode économie de batterie
- Vérifier que les notifications sont autorisées
- Vérifier le token FCM dans la base de données

---

## 📊 Base de données

### Architecture actuelle (hybride)

```
┌─────────────────────────────────────────────────────────────┐
│                        BACKEND                               │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   SQLite (local)          Firebase (optionnel)              │
│   ┌─────────────┐         ┌─────────────┐                   │
│   │ devices     │ ──sync──│ Firestore   │                   │
│   │ events      │         │ (temps réel)│                   │
│   │ photos      │         └─────────────┘                   │
│   │ alerts      │                                           │
│   └─────────────┘         ┌─────────────┐                   │
│         │                 │ FCM         │                   │
│         │                 │ (push)      │                   │
│         ▼                 └─────────────┘                   │
│   ┌─────────────┐               │                           │
│   │ Dashboard   │◄──────────────┘                           │
│   │ (WebSocket) │                                           │
│   └─────────────┘                                           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Pourquoi garder SQLite + Firebase ?

| SQLite | Firebase |
|--------|----------|
| Fonctionne hors ligne | Temps réel |
| Pas de coût | Coût selon usage |
| Données locales | Push notifications |
| Backup facile | Scalable |

**Recommandation**: Utiliser SQLite comme base principale + Firebase pour FCM (push).

---

*Guide créé le 20 février 2026*
