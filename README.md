# Docker Dashboard — application Android

Une WebView plein écran autour de [DashboardDocker](https://github.com/Hitman47/DashboardDocker) :
l'application affiche le dashboard tel qu'il est servi par le NAS, donc elle suit
automatiquement chaque mise à jour du conteneur sans réinstallation.

## Ce qu'elle fait

- Premier lancement : l'adresse du dashboard (`192.168.1.30:3000`), bouton **Tester**
  qui vérifie qu'un Docker Dashboard répond et affiche son nom.
- **Plusieurs dashboards** (un par NAS) : chaque fiche a un nom (facultatif, sinon celui
  que le dashboard s'est donné), l'app retient le dernier ouvert. Retour → **Mes dashboards**
  pour passer de l'un à l'autre, ajouter, modifier ou supprimer. Chaque dashboard garde sa
  propre session (cookies et stockage séparés par adresse).
- Session conservée entre les lancements (stockage web + cookies), WebSocket « Live » actif.
- Téléphone comme tablette : c'est le dashboard lui-même qui est responsive ; la taille
  du texte se règle dans ses Réglages › Général.
- Les liens vers d'autres services (↗ WebUI, blocs) s'ouvrent dans le navigateur ;
  le dashboard reste dans l'app.
- NAS éteint / réseau coupé : écran d'erreur avec nouvel essai automatique toutes les 5 s.
- **Retour** : historique de la page, puis menu (recharger, mes dashboards, modifier ce dashboard, quitter).
- Envoi de fichiers (fonds d'écran, import de config) et téléchargements (export de
  config, ICS) vers *Téléchargements*.
- HTTP en clair autorisé (LAN / VPN) ; l'écran d'adresse prévient si l'URL http://
  n'est pas locale.
- **Mises à jour** : le dashboard (≥ 4.3.18, jeton GitHub `repo` renseigné dans Registres) signale
  une nouvelle version au chargement ; bandeau « Installer » → l'APK est téléchargé via le NAS,
  la notification ouvre l'installateur. Le dépôt reste privé, aucun jeton dans l'app.
- **Verrou à l'ouverture** (option) : empreinte, visage ou code de l'appareil au lancement
  et après plus de 30 s passées ailleurs ; un aller-retour rapide vers Chrome (lien ↗) ne
  redemande rien. Se règle dans la fiche d'un dashboard (Retour → Modifier ce dashboard).

## Télécharger l'APK

Les versions sont dans les **Releases** GitHub, signées avec la clé de l'auteur. Elles
sont produites sur le poste de développement par `tools\publier.ps1` (compilation propre,
vérification de la version et de la signature lues dans l'APK, tag, Release) : **aucun
secret n'est stocké sur GitHub**, le CI ne fait qu'un contrôle de compilation et dépose
un APK debug en artefact dans l'onglet Actions.

## Compiler

Windows, Android Studio SDK installé (`local.properties` → `sdk.dir`), Java 17+ :

```powershell
.\gradlew.bat assembleDebug
```

APK : `app\build\outputs\apk\debug\app-debug.apk`. Pour une release signée, générer
une clé une fois avec `signing\new-keystore.ps1` (le keystore et
`keystore.properties` restent hors du dépôt) puis `.\gradlew.bat assembleRelease`.

Socle identique à Portainer Remote : AGP 8.13, Kotlin 2.3, Compose BOM 2025.10,
`minSdk 26`, `targetSdk 36`.
