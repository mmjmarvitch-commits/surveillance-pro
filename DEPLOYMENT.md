# 🚀 Guide de Déploiement Production - Surveillance Pro

## Prérequis

- Node.js 18+ 
- Un serveur (Railway, Render, VPS, etc.)
- Domaine avec HTTPS (obligatoire en production)

---

## 1. Configuration des Variables d'Environnement

Créez un fichier `.env` à partir de `.env.example` :

```bash
cd backend
cp ../.env.example .env
```

### Variables OBLIGATOIRES en production :

```bash
# Générer les clés secrètes :
node -e "console.log('JWT_SECRET=' + require('crypto').randomBytes(64).toString('hex'))"
node -e "console.log('DEVICE_ENCRYPTION_KEY=' + require('crypto').randomBytes(32).toString('hex'))"
node -e "console.log('DATA_ENCRYPTION_KEY=' + require('crypto').randomBytes(32).toString('hex'))"
```

Copiez les valeurs générées dans votre `.env` :

```env
NODE_ENV=production
PORT=3000
JWT_SECRET=<valeur_générée>
DEVICE_ENCRYPTION_KEY=<valeur_générée>
DATA_ENCRYPTION_KEY=<valeur_générée>
ALLOWED_ORIGINS=https://votre-domaine.com
```

⚠️ **Le serveur refusera de démarrer si ces variables ne sont pas définies en production.**

---

## 2. Déploiement sur Railway (Recommandé)

### Étape 1 : Créer le projet
```bash
# Installer Railway CLI
npm install -g @railway/cli
railway login
railway init
```

### Étape 2 : Configurer les variables
```bash
railway variables set NODE_ENV=production
railway variables set JWT_SECRET=<votre_secret>
railway variables set DEVICE_ENCRYPTION_KEY=<votre_clé>
railway variables set DATA_ENCRYPTION_KEY=<votre_clé>
```

### Étape 3 : Déployer
```bash
railway up
```

---

## 3. Déploiement sur Render

1. Connectez votre repo GitHub à Render
2. Créez un nouveau "Web Service"
3. Configurez :
   - **Build Command**: `cd backend && npm install`
   - **Start Command**: `cd backend && npm start`
4. Ajoutez les variables d'environnement dans l'onglet "Environment"

---

## 4. Premier Démarrage

Au premier démarrage, le serveur affichera les identifiants admin :

```
════════════════════════════════════════════════════════════
🔐 PREMIER DÉMARRAGE - IDENTIFIANTS ADMIN
════════════════════════════════════════════════════════════
Utilisateur: admin
Mot de passe: <mot_de_passe_généré>
════════════════════════════════════════════════════════════
⚠️  CHANGEZ CE MOT DE PASSE IMMÉDIATEMENT APRÈS CONNEXION
════════════════════════════════════════════════════════════
```

**IMPORTANT** : Notez ce mot de passe et changez-le immédiatement dans Sécurité > Changer le mot de passe.

---

## 5. Configuration HTTPS

### Avec Railway/Render
HTTPS est automatique avec le domaine fourni.

### Avec un VPS (Nginx + Let's Encrypt)
```nginx
server {
    listen 443 ssl http2;
    server_name votre-domaine.com;
    
    ssl_certificate /etc/letsencrypt/live/votre-domaine.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/votre-domaine.com/privkey.pem;
    
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

## 6. Configuration des Apps Mobiles

### Android
1. Modifiez `android/app/src/main/java/.../data/DeviceStorage.kt`
2. Changez `serverURL` vers votre domaine HTTPS
3. Recompilez l'APK

### iOS
1. Modifiez `SurveillancePro/Config/APIConfig.swift`
2. Changez l'URL du serveur
3. Recompilez via Xcode

### Extension Chrome
1. Lors de la configuration, entrez l'URL de votre serveur

---

## 7. Checklist Pré-Production

- [ ] Variables d'environnement configurées
- [ ] HTTPS activé
- [ ] Mot de passe admin changé
- [ ] 2FA activé pour l'admin
- [ ] Backup de la base de données configuré
- [ ] URLs des apps mobiles mises à jour
- [ ] Test de connexion depuis un appareil

---

## 8. Backup de la Base de Données

### SQLite local
```bash
# Backup quotidien (ajoutez au cron)
cp backend/surveillance.db backup/surveillance_$(date +%Y%m%d).db
```

### Turso Cloud (recommandé pour production)
Configurez `TURSO_URL` et `TURSO_TOKEN` pour une base de données cloud avec réplication automatique.

---

## 9. Monitoring

Surveillez les logs pour détecter les problèmes :
```bash
# Railway
railway logs

# Render
# Voir dans le dashboard

# VPS
pm2 logs surveillance-pro
```

---

## Support

En cas de problème :
1. Vérifiez les logs du serveur
2. Vérifiez que les variables d'environnement sont correctes
3. Testez la connexion depuis le navigateur
