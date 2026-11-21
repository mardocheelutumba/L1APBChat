# Proposition des règles Firestore — salon web

**Décision : en attente de revue et d’approbation distincte.** Le consentement de l’utilisateur à l’usage de Firebase, Auth e-mail/mot de passe et au stockage des salons/messages dans le projet `local-chat-hybride` (région annoncée `africa-south1`, Johannesburg) ne vaut pas approbation de cette configuration de règles. Le forfait doit rester Spark; aucune facturation, aucun SMS ni service payant. La proposition est réservée à l’émulateur. Ne pas la déployer.

## Modèle d’accès proposé

### `rooms/{roomId}`

Document : `title` (1–80 caractères), `createdBy` (UID authentifié), `createdAt` (heure serveur), `state` (`open`/`closed`), `schemaVersion` (`1`). Seule la création atomique par Auth utilisateur et l’adhésion hôte associée sont autorisées. Le propriétaire et le titre ne sont ensuite pas modifiables; seul l’hôte actif peut fermer ou rouvrir le salon. Lecture directe pour adhésion active uniquement. L’énumération/listing de tous les salons est refusé.

### `rooms/{roomId}/members/{uid}`

Champs `uid`, `role` (`host`/`member`), `state` (`active`/`revoked`), `joinedAt`, `displayName` (1–40 caractères). L’ID du document et le champ UID doivent correspondre au compte. L’hôte initial est créé dans la même écriture atomique que le salon; aucun utilisateur ne peut se donner le rôle hôte. Un membre normal n’est créé qu’avec l’approbation hôte correspondante, en écriture atomique. Un hôte peut révoquer un membre actif; le membre ne peut pas se réactiver. L’hôte initial n’est pas révocable par les clients. Les adhésions sont lisibles par leur titulaire et par l’hôte; une requête groupée `uid == request.auth.uid` est prévue pour découvrir les propres salons.

### `rooms/{roomId}/joinRequests/{uid}`

Le demandeur authentifié ne peut créer que son propre document avec état `pending`; il peut lire sa propre demande. Un ID de salon connu permet de **demander** l’accès seulement, pas de lire la salle, les membres ou les messages. L’hôte actif peut lister les demandes et faire uniquement la transition `pending` vers `approved` ou `rejected`. L’approbation et la création de l’adhésion du demandeur doivent être faites dans le même batch/transaction. Une demande n’accorde jamais d’accès par elle-même. Pas de code PIN à quatre chiffres ni de nom affiché utilisé comme preuve d’accès.

### `rooms/{roomId}/messages/{messageId}`

Champs permis uniquement : `senderUid`, `body` (1–4 000 caractères), `createdAt` (heure serveur), `schemaVersion` (`1`). Seuls les membres actifs d’une salle ouverte peuvent lire et créer; l’expéditeur doit être l’UID Auth. Modification et suppression client sont refusées. Pas de pièces jointes/URL ni métadonnées.

## Cas autorisés attendus

- Hôte authentifié crée salle + rôle hôte, en une même écriture atomique.
- Membre authentifié demande l’accès à partir d’un code; ses lectures des données du salon échouent jusqu’à l’approbation.
- Hôte actif approuve ou refuse sa demande; l’approbation et le membre sont atomiques.
- Membres actifs consultent salon/messages, écrivent uniquement sous leur UID, voient leurs propres adhésions; l’hôte consulte les demandes.
- Hôte révoque un membre actif; ses lectures et écritures suivantes échouent.

## Refus obligatoires des tests

Accès non authentifié; lecture d’un salon par non-membre; énumération des salons; lecture des messages/membres par outsider; rejoindre avec seulement l’ID du salon; usurpation d’UID, auto-promotion hôte, création d’un salon sans premier hôte atomique; auto-approbation, membre créé sans approbation hôte atomique; demande/adhésion ciblant quelqu’un d’autre; membre révoqué qui se réactive ou écrit; message avec mauvais expéditeur, champs inconnus, mauvais type, limite dépassée, ou tentative d’édition/suppression; modification du créateur/titre; toute autre collection inconnue.

## Limites et risques avant un service public

- Aucun backend de confiance, invitation à durée/usage limité, confirmation d’adresse, signalement ou contrôle modérateur.
- Les IDs aléatoires sont des références de salle, pas des jetons d’autorisation. La demande hôte est la barrière d’admission. La validation en règles protège les données même si l’interface est contournée.
- Les règles ne fournissent pas de limitation fiable de débit/quota. Un attaquant peut créer de nombreuses salles, demandes ou messages et épuiser le quota Spark. Avant publication, ajouter et auditer des contrôles adaptés (backend avec limites ou mécanisme robuste et autorisé), et traiter le signalement, la suppression des comptes/données, la rétention, l’abus et l’accès des mineurs.
- Les règles ne peuvent pas effacer ce qui a déjà été téléchargé/caché après révocation. Il n’y a pas de chiffrement de bout en bout; Firebase/Auth peut avoir accès aux données selon son rôle de fournisseur.
- La règle d’énumération des adhésions nécessite tests d’émulateur et vérification de la requête `collectionGroup` avec le filtre UID. Les limites d’accès `get/getAfter`/`existsAfter` doivent rester dans les limites Firestore.
- Règles encore non revues par un auditeur indépendant. Test Emulator nécessaire avant de demander approbation.

## Référence de déploiement

Cette proposition dans le dépôt ne change aucun paramètre Firebase distant. Aucune commande de déploiement ne doit être lancée avant revue et approbation explicite, et aucun déploiement permissif n’est autorisé par le seul consentement au stockage. Tous les tests doivent utiliser `demo-local-chat-hybride-rules`, des identifiants fictifs et les émulateurs locaux.
