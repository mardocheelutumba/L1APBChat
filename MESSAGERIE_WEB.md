# Messagerie web — banc d’essai local uniquement

**Statut : implémentation expérimentale non publiée et non autorisée en production.** Le site public Vercel n’est pas relié automatiquement à cette branche et reste la page statique antérieure. Aucun déploiement Vercel, aucune règle Firestore distante, aucune activation Auth réelle et aucun compte/donnée de test réel n’ont été effectués pour cette branche.

## Fonctionnalités préparées

- Auth Firebase e-mail/mot de passe : inscription, connexion, déconnexion et demande de réinitialisation, via **Firebase Auth Emulator uniquement**.
- Création d’une salle et de l’adhésion hôte dans une même écriture atomique.
- Code de salle UUID à transmettre comme moyen de retrouver la salle. Le code n’accorde aucun accès : l’autre compte doit soumettre une demande, puis un hôte actif l’approuve ou la refuse.
- Découverte des salles par requête de groupe des adhésions propres à l’utilisateur; lecture du salon et des messages réservée aux membres actifs.
- Chat texte avec horodatage serveur, limite de 4 000 caractères, lecture temps réel des 100 messages les plus récents et messages immuables.
- Révocation côté règles : une adhésion révoquée n’autorise plus les lectures/écritures suivantes. Une copie précédemment téléchargée ne peut pas être effacée par les règles.

`web-messenger.js` refuse de démarrer si les deux points de terminaison ne sont pas explicitement des URLs HTTP localhost. Il n’existe aucun chemin de repli vers les services Firebase de production. Le HTML servi sans `web/local-config.js` garde les champs d’authentification désactivés.

## Lancer seulement avec des émulateurs

1. Installer Node 22, Java 21, puis `npm install`.
2. Copier `web/local-config.example.js` vers `web/local-config.js` (ignoré par Git; les paramètres fournis sont factices).
3. Dans un terminal, lancer `firebase emulators:start --only auth,firestore --project demo-local-chat-hybride-rules`.
4. Dans un autre, servir le dépôt par HTTP local (`python3 -m http.server 8000`), puis ouvrir `http://localhost:8000`.
5. Utiliser uniquement des adresses et mots de passe fictifs dans Auth Emulator. Sa réinitialisation de mot de passe n’envoie pas de vrai e-mail.

Ne pas remplacer les endpoints par ceux du projet Firebase réel. Une configuration Web publique n’est pas une clé Admin, mais elle ne doit pas servir à contourner les garde-fous de cette version.

## Tests et limites de validation

- `npm run test:rules` lance Firestore Rules Emulator et des tests `@firebase/rules-unit-testing` : création atomique salle/hôte, demande et approbation, lecture/écriture, découverte des propres adhésions via collection-group, et refus (non authentifié, non-membre, code seul, énumération, auto-promotion/usurpation, création ou approbation partielle, messages invalides/édités, révocation).
- `npm run test:web` exécute des contrôles statiques HTML/JS et un contrôle anti-régression sur la référence `resource.data.uid`. Ces tests ne prouvent ni le fonctionnement interactif du navigateur ni l’intégration de bout en bout.
- Le workflow GitHub Actions `Vérification du prototype web et des règles candidates` a réussi sur la branche `web-messagerie` : vérifications statiques et suite Firestore Rules Emulator sur le projet de démonstration `demo-local-chat-hybride-rules`.
- Cette réussite CI ne remplace pas un essai manuel du parcours web dans un navigateur : aucun test interactif de navigateur ni test de production n’est revendiqué.
- Il n’existe pas de limitation de débit, quota de création de salons/demandes/messages, signalement, modération ni serveur de confiance. Les règles seules ne donnent pas de limitation robuste anti-abus. Un projet Spark gratuit reste exposé à une consommation abusive de ses quotas; la mise en ligne publique doit attendre un contrôle anti-abus et une revue.
- Pas de confirmation d’e-mail, de politique de conservation, de suppression de compte/données, de gestion d’appareil ou de récupération testée. Pas de synchronisation avec l’application Android, de pièce jointe, d’appel ou de notifications.

## Règles : décision en attente

`firestore.rules` est une **proposition candidate** incluse pour exécution et tests locaux; sa présence dans Git ne vaut pas déploiement. L’application Firebase distante conserve les règles qui y sont actuellement installées. Ne pas exécuter `firebase deploy --only firestore:rules` et ne pas connecter l’interface au projet réel. L’approbation du stockage Firebase ne constitue pas l’approbation de ces règles précises; l’utilisateur doit examiner cette proposition et approuver explicitement avant tout déploiement.

Voir `FIRESTORE_MEMBERSHIP_SPEC.md` pour le modèle, les garanties, les limites et les points d’approbation.
