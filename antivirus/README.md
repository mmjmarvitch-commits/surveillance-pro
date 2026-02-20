# Security Pro - Antivirus Android

Application de sécurité Android légère et complète, faisant partie de l'écosystème Supervision Pro.

## Fonctionnalités

### 🔍 Scan d'applications
- **Scan rapide** : Analyse les applications installées récemment (7 derniers jours)
- **Scan complet** : Analyse toutes les applications installées
- Détection de malwares, spywares, adwares connus
- Analyse des signatures suspectes

### 🛡️ Protection temps réel
- Surveillance des nouvelles installations
- Alertes instantanées en cas de menace
- Démarrage automatique au boot

### ⚠️ Analyse des permissions
- Détection des permissions dangereuses
- Identification des combinaisons de permissions suspectes
- Score de risque par application

### 🔒 Vérification système
- Détection de root
- Vérification ADB
- État des sources inconnues
- Options développeur
- Verrouillage d'écran
- Niveau de patch sécurité

## Structure du projet

```
antivirus/
├── app/
│   ├── src/main/
│   │   ├── java/com/securitypro/android/
│   │   │   ├── MainActivity.kt          # UI Compose
│   │   │   ├── data/
│   │   │   │   └── ThreatModels.kt       # Modèles de données
│   │   │   ├── scanner/
│   │   │   │   ├── AppScanner.kt         # Moteur de scan
│   │   │   │   └── MalwareDatabase.kt    # Base de signatures
│   │   │   ├── services/
│   │   │   │   ├── ScanService.kt        # Service de scan
│   │   │   │   └── RealTimeProtectionService.kt
│   │   │   ├── receivers/
│   │   │   │   ├── BootReceiver.kt
│   │   │   │   └── PackageReceiver.kt
│   │   │   └── ui/
│   │   │       └── Theme.kt              # Thème Material 3
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

## Compilation

### Prérequis
- Android Studio Hedgehog ou supérieur
- JDK 17
- Android SDK 34

### Build Debug
```bash
cd antivirus
./gradlew assembleDebug
```

### Build Release
```bash
cd antivirus
./gradlew assembleRelease
```

L'APK sera généré dans `app/build/outputs/apk/`

## Déploiement

1. Compiler l'APK release
2. Copier l'APK dans `backend/downloads/SecurityPro.apk`
3. L'APK sera disponible sur `/download/securitypro`
4. Page de téléchargement : `/securitypro.html`

## Technologies

- **Kotlin** 1.9.22
- **Jetpack Compose** avec Material 3
- **Coroutines** pour les opérations asynchrones
- **Foreground Services** pour la protection temps réel

## Permissions requises

| Permission | Usage |
|------------|-------|
| `INTERNET` | Mises à jour signatures (futur) |
| `QUERY_ALL_PACKAGES` | Lister les apps installées |
| `POST_NOTIFICATIONS` | Alertes de menaces |
| `FOREGROUND_SERVICE` | Protection temps réel |
| `RECEIVE_BOOT_COMPLETED` | Démarrage auto |

## Licence

Propriétaire - Tous droits réservés
