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
- Le code actuel n’implémente pas de confirmation d’e-mail, de rétention ou de suppression de compte/données, de gestion d’appareil ni de récupération testée. La proposition de conservation ci-dessous n’est pas implémentée. Pas de synchronisation avec l’application Android, de pièce jointe, d’appel ou de notifications.

## Règles : décision en attente

`firestore.rules` est une **proposition candidate** incluse pour exécution et tests locaux; sa présence dans Git ne vaut pas déploiement. L’application Firebase distante conserve les règles qui y sont actuellement installées. Ne pas exécuter `firebase deploy --only firestore:rules` et ne pas connecter l’interface au projet réel. L’approbation du stockage Firebase ne constitue pas l’approbation de ces règles précises; l’utilisateur doit examiner cette proposition et approuver explicitement avant tout déploiement.

Voir `FIRESTORE_MEMBERSHIP_SPEC.md` pour le modèle, les garanties, les limites et les points d’approbation.


## Proposition de pilote fermé — à approuver, non activée

Le « Oui » reçu autorise à préparer et examiner un protocole limité. Il **n’approuve ni le déploiement des règles Firestore, ni l’accès au Firebase réel, ni la mise en production**. Les plafonds et durées ci-dessous sont des propositions de travail, pas des choix déjà approuvés par l’utilisateur.

### Périmètre recommandé : émulateurs locaux seulement

Le premier essai doit rester dans Auth Emulator et Firestore Emulator sur la machine du testeur, avec le projet fictif `demo-local-chat-hybride-rules`. Ne pas saisir de véritable adresse, nom, mot de passe, message personnel ni donnée scolaire; utiliser des identités fictives sous `example.invalid`. La page publique reste une présentation statique : aucun compte réel, aucune conversation réelle et aucun branchement vers Firebase distant. Le client actuel refuse les endpoints non locaux.

**Plan proposé, sur cinq jours au maximum :**

1. **Jour 1 — préparation et sécurité :** lancer les émulateurs, exécuter `npm test`, vérifier les refus et confirmer que les endpoints affichés sont uniquement localhost. Quatre identités fictives : un hôte, deux membres et un compte extérieur non membre.
2. **Jours 2–3 — parcours fonctionnel :** dans un navigateur local, essayer création/approbation, conversation et révocation avec les mêmes quatre identités. Deux salles au plus; dix messages par identité au plus et cinquante messages au total pour tout l’essai.
3. **Jour 4 — abus et signalement :** rejouer les refus des tests de règles; simuler manuellement un signalement et la fermeture/révocation. Ne pas solliciter de vrais étudiants ni inviter d’autres comptes.
4. **Jour 5 — clôture :** vérifier qu’un membre révoqué n’a plus accès, puis effacer tout l’état des émulateurs et consigner seulement un bilan agrégé sans contenu de message ni identité. Arrêter l’essai au plus tard à la fin de cette journée.

Ces nombres sont des **plafonds manuels proposés pour un essai local**, pas des limites appliquées par l’application ou les règles. Les tests d’émulateur et les vérifications statiques ne prouvent pas qu’un navigateur fonctionne correctement; un parcours interactif doit être observé et rapporté séparément.

### Conservation, suppression et signalements — proposition

- **Émulateur local :** ne pas exporter ni conserver de sauvegarde; à la clôture, supprimer l’état Auth/Firestore de l’émulateur (ou recréer son espace de données), arrêter les processus et vérifier qu’aucun export n’a été laissé. Durée maximale proposée : cinq jours, fin de test comprise.
- **Essai cloud futur :** non autorisé par ce plan. Si une demande séparée est examinée, proposer au préalable une durée maximale de sept jours, puis une suppression sous 24 h après clôture de tous les comptes d’essai et de leurs documents, avec vérification manuelle dans Auth et Firestore. Cette voie n’est pas fournie par l’application : les suppressions Firestore ne sont pas automatiquement récursives et aucun parcours de suppression de compte/données n’est implémenté. Ne pas créer de données cloud avant qu’une méthode complète et vérifiable ait été approuvée.
- **Signalement/modération :** il n’y a pas de bouton de signalement ni d’outil de modération dans l’application. Dans ce pilote local, les testeurs remettraient un signalement directement au responsable de l’essai, hors application; celui-ci arrêterait le test concerné, fermerait la salle ou révoquerait l’adhésion, consignerait uniquement la catégorie et le résultat sans copier le message, puis supprimerait les données de test. En cas de contenu inquiétant ou d’accès non autorisé, arrêter l’ensemble du pilote et ne pas le rouvrir avant revue.

### Pourquoi aucun pilote cloud n’est prêt

Les plafonds proposés ne sont pas imposés techniquement : les règles candidates ne fournissent pas une limitation robuste de fréquence ou de volume, et aucun backend de confiance n’est prévu. Quatre comptes ou une durée courte ne peuvent donc pas être garantis par le client contre un abus ou une erreur. Le projet Spark et ses quotas ne donnent pas une garantie d’absence d’interruption ou de consommation abusive. En outre, la confirmation d’adresse, la suppression autonome/complète, le contrôle de débit et la modération font défaut. Il n’est donc pas possible de présenter un essai Firebase réel comme sûr et maîtrisé gratuitement à ce stade.

Pas de Cloud Functions, de serveur payant, de SMS, de facturation, de secrets/API supplémentaires ou de déploiement. Un passage cloud demanderait une approbation séparée précisant au minimum le projet et les règles exactes, les comptes de test, les limites techniquement appliquées, le calendrier, les coûts/quotas acceptés, la suppression vérifiable et le responsable de modération. Jusqu’à cette approbation, rester en local.
