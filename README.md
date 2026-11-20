# Local Chat — prototype Android

Application de messagerie locale en développement, écrite en Kotlin avec Jetpack Compose et Google Nearby Connections. Elle permet à des appareils Android proches d’échanger sans connexion Internet, via un téléphone hôte qui relaie les messages.

## Utilisation prévue

1. Installer l’application sur des téléphones Android compatibles.
2. Autoriser les permissions Bluetooth et appareils à proximité (la localisation peut être demandée sur d’anciennes versions d’Android).
3. Sur un téléphone, enregistrer un pseudo et choisir un nom de salon, puis créer la connexion.
4. Sur un autre, saisir le même nom de salon, rechercher la connexion, la rejoindre et accepter la demande sur l’hôte.
5. L’hôte doit rester à proximité et garder la connexion active.

Une organisation peut être renseignée dans le profil, mais elle est facultative. Le nom du salon est libre.

## État réel et limites

- Prototype en développement : il n’est ni publié ni prêt pour un déploiement sensible.
- La CI compile les variantes debug et release. Cela ne remplace pas des essais sur de vrais téléphones ; aucun test appareil n’est revendiqué.
- La release compilée par la CI n’est pas signée pour distribution.
- La connexion Nearby et la reconnexion automatique n’ont pas encore été validées en usage réel.
- La reconnexion reconnaît actuellement l’hôte par son nom affiché, qui peut être dupliqué ou imité.
- L’acceptation de la demande n’est pas une authentification forte.
- Le code facultatif à quatre chiffres est vérifié par défi-réponse HMAC avant l’admission d’un participant et le relais de ses messages. Après un code erroné, l’application permet de réessayer ; trois erreurs ferment la connexion. La reconnexion redemande le code.
- Le code est conservé dans les préférences locales de l’application. Quatre chiffres sont faciles à deviner ; le mécanisme est une barrière informelle, pas une sécurité forte ni une protection contre un appareil compromis. La fonction est expérimentale et n’a pas été testée sur plusieurs téléphones.
- L’historique est temporaire et n’est pas sauvegardé durablement.
- Les messages privés, vocaux et les appels restent à développer.
- Nearby Connections chiffre les transferts selon la documentation Google, mais cela ne remplace pas l’authentification, les tests de sécurité ni une architecture de production.
- L’application requiert des services Google Play compatibles.

## Projet

- Identifiant d’application : `cd.localchat.app`.
- Kotlin/Jetpack Compose ; Android minimum : API 24.
- Dépendance Nearby Connections : `com.google.android.gms:play-services-nearby:19.3.0` (version déclarée dans le projet).
- Interface en cours de localisation en français et en anglais selon la langue de l’appareil.

Documentation Google : https://developers.google.com/nearby/connections/overview

## Branche expérimentale hybride (en cours)

Cette branche prépare une future option en ligne sans remplacer le transport Nearby existant. Le transport en ligne reste **désactivé** dans l’application : Firebase Authentication n’est pas intégré, aucune lecture/écriture Firestore n’est effectuée par le client et aucun message n’est envoyé vers le cloud. L’historique Nearby reste temporaire et séparé.

Firebase Authentication a été initialisé dans le projet, sans méthode de connexion activée ; il reste à intégrer au client.

Un fichier `firestore.rules` de référence bloque toute lecture et écriture (`allow read, write: if false`). Il est uniquement dans le dépôt : il n’a pas été déployé sur Firebase. Le projet Firebase `local-chat-hybride` conserve ses règles deny-all vérifiées. Il ne faut pas les remplacer avant d’implémenter une identité fiable, une appartenance explicite à chaque salon, la validation côté serveur des données et des tests positifs/négatifs avec l’émulateur Firebase Rules.

Ne pas déposer de clé de compte de service, de jeton ou de secret dans le dépôt. Un fichier de configuration Android Firebase ne constitue pas un contrôle d’accès : les règles Firestore doivent protéger chaque document. Aucun abonnement payant ni chiffrement de bout en bout n’est déclaré ou supposé.

État de l’expérimentation : seule une frontière d’architecture hors ligne/en ligne et une référence de règles fermées sont ajoutées ; l’authentification, la gestion des salons en ligne et la persistance cloud restent à réaliser et tester avant d’activer un mode en ligne.

