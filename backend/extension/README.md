# Extension Chrome – Surveillance Pro

Extension de supervision pour les navigateurs professionnels (Chrome, Edge, Brave, Opera).

## Installation par l'employé

### Méthode 1 : Mode développeur (test / déploiement interne)

1. Ouvrir Chrome → `chrome://extensions/`
2. Activer le **Mode développeur** (en haut à droite)
3. Cliquer sur **Charger l'extension non empaquetée**
4. Sélectionner le dossier `ChromeExtension/`
5. L'extension apparaît dans la barre d'outils
6. Cliquer sur l'icône → remplir le nom + URL du serveur → **J'accepte**

### Méthode 2 : Fichier .crx signé (distribution)

1. Dans `chrome://extensions/`, cliquer sur **Empaqueter l'extension**
2. Sélectionner le dossier `ChromeExtension/`
3. Chrome génère un fichier `.crx` (l'extension empaquetée) et une clé `.pem`
4. Distribuer le `.crx` aux employés (par email, intranet, etc.)
5. L'employé ouvre le `.crx` → Chrome propose d'installer

### Méthode 3 : Chrome Web Store (public ou privé)

Pour une distribution officielle, publier sur le Chrome Web Store (compte développeur Google à 5$).
Possibilité de publier en **mode privé** (visible uniquement par les employés de l'entreprise).

## Ce que l'extension capture

| Donnée | Quand |
|--------|-------|
| **URL + titre** de chaque page | Chaque page visitée |
| **Recherches** (Google, Bing, YouTube, DuckDuckGo, Yahoo, Ecosia) | Quand une recherche est faite |
| **Formulaires soumis** (tous les champs sauf mots de passe) | Quand un formulaire est validé |
| **Textes tapés + Entrée** | Quand l'utilisateur tape et appuie Entrée |
| **Clics sur Envoyer/Valider/Confirmer** | Quand un bouton d'envoi est cliqué (capture les champs proches) |

## Consentement

Au premier clic sur l'icône de l'extension :
- L'employé voit le texte d'information (surveillance, données capturées)
- Il tape son **nom complet** (signature)
- Il entre l'**URL du serveur** de supervision
- Il clique sur **J'accepte** → enregistrement sur le serveur → supervision active

## Serveur

Les données sont envoyées au même backend que l'app iPhone : `POST /api/events`.
Le dashboard (http://localhost:3000) affiche les données Chrome avec des badges dédiés.
