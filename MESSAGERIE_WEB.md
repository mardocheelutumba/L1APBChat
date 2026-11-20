# Site vitrine et espace web — état expérimental

Branche `web-messagerie`, créée depuis `hybride-experimental` à partir du commit `ee70fc676de89a068d1f3128734af1c6b9c36eae`. Aucun fichier Android n’a été modifié. `index.html` est un site autonome et responsive; la conversation dessinée est une illustration fictive.

## En place

- Vitrine en français avec navigation mobile et présentation explicite des limites.
- Authentification e-mail (création, connexion, réinitialisation) préparée avec le SDK Firebase Auth modulaire. Elle ne s’active que si `window.LOCAL_CHAT_FIREBASE_CONFIG` contient `apiKey`, `authDomain`, `projectId` et `appId`; sans cela, Firebase n’est pas chargé et les champs restent désactivés.
- `authEmulator` peut pointer vers Firebase Auth Emulator pour des essais sans vrais comptes.
- Aucun accès Firestore, aucun schéma ni envoi de messages, aucune synchronisation ou salon web. Firestore deny-all reste inchangé et n’a pas été déployé. Nearby reste réservé à l’application Android.

## Authentification et tests

Aucune configuration Firebase ou clé privée n’est dans le dépôt. Les valeurs de configuration web Firebase identifient un projet; elles ne remplacent jamais une clé Admin. L’authentification n’a pas été validée avec un projet ni avec l’émulateur dans cette livraison. Pour la tester, injecter la configuration avant le script, démarrer Auth Emulator et fournir `authEmulator: 'http://127.0.0.1:9099'`. Utiliser uniquement des comptes fictifs dans l’émulateur.

`python3 test_web_smoke.py` vérifie statiquement le HTML, les liens de sections, les styles responsive, les fonctions Auth préparées, la garde sans configuration et l’absence du SDK Firestore. Ce n’est pas un test d’authentification ou de messages de bout en bout.

## Avant d’activer un chat

Définir les rôles et le modèle d’accès; ajouter des tests positifs et négatifs Firestore Rules à Emulator Suite; faire examiner les règles; demander l’approbation explicite avant toute règle permissive ou tout déploiement. Ne pas activer SMS, facturation ou service payant.

## Publication et limites

Le projet Vercel `local-chat` est publié à <https://local-chat-ochre-eta.vercel.app/>. C’est un déploiement statique préliminaire du seul `index.html`, téléversé manuellement; il n’est pas relié à Git et ne se met pas à jour automatiquement depuis cette branche. Le HTML publié a été vérifié en HTTP 200 et correspond octet pour octet à `index.html`.

La publication n’est pas une messagerie : pas de compte Firebase validé, de messages, salons, historique, notifications, pièces jointes ou liaison Android-web. La page publiée le dit clairement.
