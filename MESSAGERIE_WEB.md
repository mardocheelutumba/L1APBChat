# Site vitrine et espace web de messagerie — état expérimental

Branche de travail : `web-messagerie`, créée depuis `hybride-experimental` au commit `ee70fc676de89a068d1f3128734af1c6b9c36eae`. Le site n’altère pas le code Android. La page `index.html` est un site responsive autonome (sans dépendances CSS/JS de tiers). L’illustration de conversation est explicitement fictive.

## Ce qui est en place

- Vitrine en français, responsive, navigation par ancres et état des lieux lisible sur mobile.
- Interface d’authentification e-mail (inscription, connexion, réinitialisation) câblée au SDK Firebase Auth modulaire quand une configuration web publique complète est injectée dans `window.LOCAL_CHAT_FIREBASE_CONFIG` avant le script de la page.
- L’interface ne charge pas Firebase en l’absence de configuration; champs et boutons restent désactivés.
- Option `authEmulator` dans la configuration pour pointer Auth vers Firebase Emulator Suite et effectuer des essais sans comptes réels.
- Aucune opération Firestore, aucun schéma de message, aucun bouton d’envoi, aucune synchro: le backend de chat est volontairement désactivé. Les règles Firestore deny-all ne sont pas modifiées ni déployées.
- Le statut après inscription/connexion précise encore que les messages ne sont pas disponibles.

## Configuration et test Auth local

Aucune valeur de config ou clé n’est incluse dans le dépôt. La configuration web Firebase (`apiKey`, `authDomain`, `projectId`, `appId`) n’est pas un secret serveur mais identifie le projet : ne pas ajouter de credentials privés ou de clé admin. Avant un essai, injecter un objet `window.LOCAL_CHAT_FIREBASE_CONFIG` avant le script d’initialisation. Pour les tests uniquement, ajouter `authEmulator: 'http://127.0.0.1:9099'`, démarrer Firebase Emulator Suite et utiliser son interface pour les comptes fictifs. Auth Emulator ne modifie pas les vrais comptes si son endpoint local est correct. Ne pas utiliser un vrai compte pendant le test de l’émulateur.

Le SDK Firebase est chargé depuis le CDN officiel Google uniquement après configuration. L’authentification réelle n’a pas été validée dans cette branche (aucune config/connexion n’est disponible ici). La présence de l’interface n’est pas une preuve que le login fonctionne.

## Avant toute messagerie

1. Établir le modèle de menace, l’identité de chaque membre, les rôles et règles par conversation.
2. Ajouter des tests négatifs/positifs de règles Firestore à Emulator Suite, sans affaiblir les règles du projet.
3. Faire réviser le modèle de règles et les tests; demander une approbation explicite avant toute règle permissive ou déploiement.
4. Implémenter les services Firestore minimaux, puis prouver lecture/écriture isolées entre comptes sur émulateur.
5. Ne jamais présenter Nearby Android comme compatible navigateur; ne pas activer facturation, SMS, ou services payants.

## Limites de cette livraison

Pas de messages web, salons web, historique, notifications, pièces jointes, connexion Android-web, ni compte Firebase validé. Cette page ne constitue pas une application de messagerie prête pour production. Le déploiement Vercel n’est pas effectué par cette livraison.
