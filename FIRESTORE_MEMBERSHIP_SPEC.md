# Future Firestore room membership and messaging — proposal

**Not implemented.** Current `firestore.rules` denies all client reads and writes. Current emulator tests verify only this deny-all behavior. This document does not authorize changing or deploying rules. Firebase remains closed; Nearby remains the only usable transport. Never commit Firebase secrets or private configuration.

## Identity and trust boundaries

- Require Firebase Authentication for every future cloud operation. Derive identity exclusively from `request.auth.uid`; never trust a client-supplied UID or display name.
- Room IDs and the existing optional four-digit local PIN are not authorization secrets. Knowledge of either must not grant cloud access.
- Use Firestore Rules as a client access boundary. Any trusted server using Admin SDK must separately authorize, validate and rate-limit, since Admin SDK bypasses Rules.
- Keep deny-all rules until the model is implemented, reviewed, emulator-tested and explicitly approved.

## Proposed documents

- `rooms/{roomId}`: immutable `createdBy`, `createdAt`, schema/security version, bounded title and status. Only a validated creation flow may create it; clients cannot alter ownership or security fields.
- `rooms/{roomId}/members/{uid}`: document ID and immutable UID equal Auth UID; role `host`/`member`, state `active`/`revoked`, joined timestamp, optional bounded display name. Users cannot self-create elevated roles, edit roles, or revive revoked membership. Host creation/admission must be atomic and protected.
- `rooms/{roomId}/joinRequests/{uid}`: own request only, bounded status and strict transitions; pending request grants no room/message access. Host approval must create membership safely.
- `rooms/{roomId}/messages/{messageId}`: immutable authenticated `senderUid`, server-consistent timestamp, bounded body and schema version. Active members may read/create; sender must equal `request.auth.uid`; client updates/deletes denied.
- Invitation secrets must not be readable from room/member documents. If added, a trusted service should generate ≥128-bit random tokens, store only their digest server-side, bind expiry/use limits/revocation/scope, consume atomically, prevent replay/races, throttle guesses and never log raw tokens. A short PIN or hash of one is not a substitute.

## Admission and abuse protections

1. Room creation requires signed-in identity, bounded fields and quotas; atomically create room and initial host membership, preferably through a trusted service where Rules cannot safely guarantee invariants.
2. Joining requires authentication plus a validated invite or host-approved request; create only caller's `member` record. Room ID alone is insufficient.
3. Active membership is required for every room/message read and message write. Revoked members lose subsequent access; already downloaded/cached data cannot be erased by Rules.
4. Enforce strict field allowlists/types/enums, no arbitrary extra fields, bounded payloads and immutable ownership. Proposed caps for review: title 80 Unicode characters, display name 40, body 4,000 UTF-8 bytes. Avoid unbounded maps/arrays and arbitrary attachment URLs.
5. Add server-side quotas, invite-attempt throttling, message frequency limits, abuse reporting, moderation, room closure and safe audit logs. Rules alone are not a robust general rate limiter. Review query behavior and Rules access-call limits.

## Future emulator acceptance matrix (not current tests)

| Case | Expected |
|---|---|
| Anonymous read/list/write | Deny |
| Authenticated non-member reads room/messages | Deny |
| Active member reads room/messages | Allow |
| Active member sends valid message with sender UID = auth UID | Allow |
| Spoof sender, edit/delete message, extra fields or oversize body | Deny |
| Client claims another creator, omits initial host, or self-assigns host | Deny |
| User writes/edits another UID's membership or revives revoked state | Deny |
| Valid invitation via trusted atomic flow | Allow only caller as member |
| Expired/revoked/used/replayed/guessed invitation, race beyond use limit | Deny; no excess membership |
| Room ID or local PIN alone presented as proof | Deny |
| User creates own pending join request | Allow within quota, without room/message access |
| Requester reads/edits another request or self-approves | Deny |
| Authorized host approves/revokes; revoked user subsequently reads/writes | Allow host operation; deny revoked access |
| Non-host revokes another member or closes room | Deny |
| Spam exceeds backend quota | Server throttles/rejects; do not claim Rules alone solve it |

The matrix is future acceptance criteria, not a claim of passing tests. Present tests must continue to check deny-all until a separately approved implementation replaces them; retain regression tests for anonymous and non-member denial.

## Current status

No Auth method is integrated into Android; no cloud membership, Firestore client transport or cloud persistence exists. Online repository remains disabled. No permissive Rules, Firebase deployment or device test has been performed.
