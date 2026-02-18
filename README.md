# Surveillance Pro – Supervision des iPhones professionnels

Application d'entreprise pour la supervision des activités sur les **iPhones** professionnels, dans le respect des règles de transparence et de conformité (voir [RÈGLES.md](./RÈGLES.md)).

---

## Structure du projet

```
surveillance_pro/
│
├── SurveillancePro/          ← APP iPHONE (Xcode)
│   ├── SurveillancePro.xcodeproj/
│   ├── SurveillancePro/           Code Swift (SwiftUI)
│   │   ├── SurveillanceProApp.swift    Point d'entrée
│   │   ├── ContentView.swift           Consentement → Supervision
│   │   ├── AcceptanceView.swift        Écran d'information
│   │   ├── MainSupervisionView.swift   Vue « Téléphone surveillé »
│   │   ├── SupervisionService.swift    Appels API
│   │   ├── DeviceStorage.swift         Stockage local
│   │   ├── Config/APIConfig.swift      URL du serveur
│   │   ├── Info.plist                  Autorisations (ATS, réseau local)
│   │   └── Assets.xcassets/
│   ├── SurveillanceProTests/
│   └── SurveillanceProUITests/
│
├── backend/                  ← SERVEUR (API Node.js)
│   ├── server.js                  API Express + WebSocket
│   ├── package.json
│   ├── mdm.js                     Script MDM
│   ├── extension/                 Extension Chrome
│   └── data.json
│
├── dashboard/                ← SITE WEB (Interface admin)
│   ├── index.html                 Dashboard admin
│   ├── style.css                  Styles
│   ├── app.js                     Logique front-end
│   ├── employee.html              Portail employé
│   ├── employee.js
│   └── install.html               Page d'installation Chrome
│
├── Dockerfile                Déploiement Docker (serveur)
├── railway.json              Config Railway (hébergement)
├── .env.example              Variables d'environnement
├── RÈGLES.md                 Règles du projet (visibilité, conformité)
└── README.md                 Ce fichier
```

### Deux espaces distincts

| Espace | Dossier | Outil | Description |
|--------|---------|-------|-------------|
| **App iPhone** | `SurveillancePro/` | Xcode | Application iOS installée sur les téléphones pro |
| **Site Web / Serveur** | `backend/` + `dashboard/` | Node.js | API + dashboard admin (ordinateur / navigateur) |

---

## Démarrage rapide

### 1. Serveur + site web (ordinateur)

```bash
cd backend && npm install && npm run dev
```

Ouvrir **http://localhost:3000** dans le navigateur :
- **Dashboard admin** : gestion des appareils, événements, alertes, photos, conformité
- **Portail employé** : http://localhost:3000/employee.html
- **Identifiants par défaut** : `admin` / `admin` (à changer)

### 2. App iPhone (Xcode)

1. Ouvrir `SurveillancePro/SurveillancePro.xcodeproj` dans Xcode
2. Vérifier l'URL dans `Config/APIConfig.swift` (ex. `http://localhost:3000`)
3. Lancer sur simulateur iPhone ou appareil réel

Au premier lancement : écran d'information → « J'accepte » → vue « Téléphone professionnel surveillé »

---

## Règles principales

- **App visible** : icône sur l'écran d'accueil + visible dans Réglages
- **Information préalable** : l'utilisateur est informé avant installation
- **Pas de mode caché** : nom explicite, transparence totale

Voir [RÈGLES.md](./RÈGLES.md) pour le détail complet.
