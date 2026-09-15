# Docker Dashboard — application Android

Une WebView plein écran autour de [DashboardDocker](https://github.com/Hitman47/DashboardDocker) :
l'application affiche le dashboard tel qu'il est servi par le NAS, donc elle suit
automatiquement chaque mise à jour du conteneur sans réinstallation.

## Ce qu'elle fait

- Premier lancement : l'adresse du dashboard (`192.168.1.30:3000`), bouton **Tester**
  qui vérifie qu'un Docker Dashboard répond et affiche son nom.
- Session conservée entre les lancements (stockage web + cookies), WebSocket « Live » actif.
- Téléphone comme tablette : c'est le dashboard lui-même qui est responsive ; la taille
  du texte se règle dans ses Réglages › Général.
- Les liens vers d'autres services (↗ WebUI, blocs) s'ouvrent dans le navigateur ;
  le dashboard reste dans l'app.
- NAS éteint / réseau coupé : écran d'erreur avec nouvel essai automatique toutes les 5 s.
- **Retour** : historique de la page, puis menu (recharger, changer d'adresse, quitter).
- Envoi de fichiers (fonds d'écran, import de config) et téléchargements (export de
  config, ICS) vers *Téléchargements*.
- HTTP en clair autorisé (LAN / VPN) ; l'écran d'adresse prévient si l'URL http://
  n'est pas locale.

## Télécharger l'APK

GitHub Actions construit l'application : chaque push sur `main` dépose un APK debug
dans l'onglet **Actions** (Artifacts), et chaque tag `v*` publie une **Release** avec
l'APK attaché (signé si les secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD` sont définis dans le dépôt, sinon APK debug).

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
