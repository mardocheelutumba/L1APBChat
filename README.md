# Local Chat — prototype Android et banc d’essai web

Application Android de messagerie locale en développement, écrite en Kotlin avec Jetpack Compose et Google Nearby Connections. Des appareils Android proches échangent sans connexion Internet via un téléphone hôte qui relaie les messages. L’option web expérimentale est séparée et ne change pas le transport Nearby.

## Utilisation Android prévue

1. Installer l’application sur des téléphones Android compatibles.
2. Autoriser les permissions Bluetooth et appareils à proximité (la localisation peut être demandée sur d’anciennes versions d’Android).
3. Sur un téléphone, enregistrer un pseudo, choisir un nom de salon et créer la connexion.
4. Sur l’autre, saisir le même nom de salon, rechercher la connexion, la rejoindre et accepter la demande sur l’hôte.
5. L’hôte doit rester à proximité et garder la connexion active.

Une organisation peut être renseignée dans le profil, mais elle est facultative. Le nom du salon est libre.

## État de l’application Android

- Prototype en développement, non publié et non prêt pour un déploiement sensible.
- La CI compile les variantes debug et release. Cela ne remplace pas des essais sur de vrais téléphones; aucun test appareil n’est revendiqué ici.
- La release CI n’est pas signée pour distribution.
- La connexion Nearby et la reconnexion automatique n’ont pas encore été validées en usage réel.
- L’acceptation d’une demande n’est pas une authentification forte.
- Le code facultatif à quatre chiffres est vérifié par défi-réponse HMAC avant admission/relais, mais reste une barrière informelle et n’a pas été testé sur plusieurs téléphones.
- L’historique est temporaire; messages privés, vocaux et appels restent à développer.
- Nearby chiffre les transferts selon la documentation Google, sans que cela remplace authentification et tests de sécurité.
- L’application nécessite des services Google Play compatibles.

## Projet Android

- Identifiant : `cd.localchat.app`.
- Kotlin/Jetpack Compose; Android minimum API 24.
- Nearby Connections `com.google.android.gms:play-services-nearby:19.3.0`.
- Interface en cours de localisation français/anglais.

Documentation : https://developers.google.com/nearby/connections/overview

## Expérience web — uniquement émulateurs locaux

`index.html` et `web-messenger.js` forment un prototype web de test, sans lien avec l’application Android. Il prépare l’inscription/connexion Firebase Auth e-mail/mot de passe, la création atomique salle+hôte, les demandes d’accès avec approbation par l’hôte et les messages texte Firestore. Le client refuse de s’initialiser tant que **Auth et Firestore ne sont pas tous deux explicitement configurés sur localhost**. Les identifiants de l’exemple sont fictifs; des comptes de test doivent être utilisés dans les émulateurs. Le site public Vercel reste la version statique antérieure, non reliée automatiquement à cette branche.

### Modèle de règles en attente de revue

`firestore.rules` contient maintenant une proposition candidate pour l’émulateur, avec authentification obligatoire, appariement atomique du salon et de son hôte, adhésion vérifiée, approbation d’accès, schéma à champs limités, messages immuables et révocation. Les tests positifs/négatifs sont dans `tests/firestore.rules.test.js` et s’exécutent avec Firestore Rules Emulator. Les contrôles statiques seuls ne constituent pas un test d’émulateur, navigateur ou production.

**Important : les règles candidates ne sont pas déployées.** L’application Firebase distante n’a pas été changée; aucun compte, message ou donnée réelle n’a été créé. Le consentement au stockage Firebase ne vaut pas approbation de règles permissives précises. Ne pas déployer avant revue et accord explicite de l’utilisateur. Le projet doit rester Spark : ne pas activer de facturation, SMS ou service payant.

Limites connues avant toute publication publique : pas de contrôle robuste de débit/quota, de modération, de confirmation e-mail, de suppression complète des données ou d’audit indépendant; Firebase Spark peut subir une consommation abusive. Pas de pièces jointes, de chiffrement de bout en bout ou de synchronisation avec Android. Voir `MESSAGERIE_WEB.md` et `FIRESTORE_MEMBERSHIP_SPEC.md` pour les instructions locales, garanties et limites.

Ne jamais déposer de clé de compte de service, jeton, mot de passe ou secret dans le dépôt. Une configuration Web publique ne remplace jamais les règles Firestore ni une sécurité serveur.
