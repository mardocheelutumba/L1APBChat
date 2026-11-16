# L1 AP B – Tchat local (prototype 0.1)

Projet de départ Android Studio en Kotlin/Jetpack Compose. Il utilise Google Nearby Connections pour rechercher et connecter des appareils proches sans Internet. L'hôte crée un groupe en étoile ; les membres envoient leurs messages à l'hôte, qui les relaie.

## Ouvrir le projet
1. Installer Android Studio : https://developer.android.com/studio
2. Ouvrir le dossier `L1APBChat` dans Android Studio et laisser Gradle synchroniser les dépendances.
3. Connecter deux téléphones Android (ou plus) par USB, activer les options développeur et le débogage USB, puis lancer l'application sur chaque appareil.
4. Autoriser les permissions Bluetooth et appareils à proximité. Sur les versions Android plus anciennes, autoriser la localisation demandée par le système.
5. Sur un téléphone, saisir un nom et toucher « Créer le groupe ». Sur l'autre, saisir un nom, toucher « Rechercher », puis « Rejoindre » et accepter la demande sur l'hôte.

## État et limites
- Ceci est un prototype de départ, pas une application publiée ni un APK prêt à distribuer.
- Le code n'a pas été compilé ou testé sur de vrais téléphones dans cet environnement ; la synchronisation Gradle et les tests réels sont nécessaires avant usage.
- Le groupe s'arrête lorsque l'hôte quitte l'application ou se déconnecte. L'hôte doit rester proche, avec l'application ouverte.
- Les messages ne sont pas encore sauvegardés durablement ; l'historique affiché est temporaire.
- Les appels, notes vocales, notifications, ajout/suppression de membres, authentification du groupe et protection contre l'usurpation de nom restent à développer. L'acceptation de connexion actuelle n'est pas une authentification forte.
- Nearby Connections chiffre les transferts selon la documentation Google, mais cela ne remplace pas une authentification ni des tests de sécurité adaptés à une diffusion réelle.
- Nécessite les services Google Play compatibles sur les appareils.

## Bibliothèque
# Local Chat (prototype)

Application Android en Kotlin/Jetpack Compose utilisant Google Nearby Connections pour connecter des appareils proches, sans Internet. L’hôte ouvre un salon local en étoile ; les membres lui envoient leurs messages, puis l’hôte les relaie.

## Démarrer
1. Ouvrir le projet dans Android Studio et synchroniser Gradle.
2. Installer l’application sur au moins deux téléphones Android.
3. Autoriser les permissions Bluetooth et appareils à proximité. Sur les anciennes versions d’Android, autoriser aussi la localisation si demandé.
4. Sur un téléphone, créer une connexion. Sur l’autre, rechercher le salon, le rejoindre et accepter la demande sur l’hôte.

L’interface propose des ressources françaises et anglaises. Les noms, profils et salons sont neutres et personnalisables.

## État et limites
- Prototype en développement, pas une version publiée ni destinée à un déploiement sensible.
- La compilation CI ne remplace pas les essais sur de vrais téléphones ; aucun test sur appareil n’est affirmé ici.
- L’hôte doit rester proche et garder l’application ouverte pour maintenir le salon.
- L’historique n’est pas sauvegardé durablement ; les messages affichés sont temporaires.
- Les messages privés, vocaux et appels restent à développer.
- L’acceptation d’une demande par l’hôte ne constitue pas une authentification forte.
- Le code d’accès facultatif à quatre chiffres n’est pas implémenté. Il ne serait qu’une barrière contre les entrées occasionnelles : quatre chiffres sont facilement devinables et ne constituent pas une sécurité forte.
- Le chiffrement des transferts Nearby, selon la documentation Google, ne remplace pas une authentification robuste ni des tests de sécurité adaptés à une diffusion réelle.
- Requiert des services Google Play compatibles.

Dépendance : `com.google.android.gms:play-services-nearby:19.5.1`
Documentation : https://developers.google.com/nearby/connections/overview`com.google.android.gms:play-services-nearby:19.5.1` (version mentionnée dans les notes de versions officielles Google Play services au moment de la préparation).

Documentation officielle : https://developers.google.com/nearby/connections/overview
Permissions Android : https://developers.google.com/nearby/connections/android/get-started
