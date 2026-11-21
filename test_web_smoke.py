#!/usr/bin/env python3
"""Static sanity checks only; emulator tests are separate and do not call production Firebase."""
from html.parser import HTMLParser
from pathlib import Path

ROOT=Path(__file__).parent
class Page(HTMLParser):
    def __init__(self):
        super().__init__(); self.lang=None; self.ids=set(); self.scripts=[]
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=='html': self.lang=a.get('lang')
        if 'id' in a:self.ids.add(a['id'])
        if tag=='script':self.scripts.append(a)

html=(ROOT/'index.html').read_text(encoding='utf-8')
client=(ROOT/'web-messenger.js').read_text(encoding='utf-8')
rules=(ROOT/'firestore.rules').read_text(encoding='utf-8')
parser=Page();parser.feed(html)
assert parser.lang=='fr'
for anchor in ['accueil','fonctionnement','etat','messagerie','questions']:
    assert anchor in parser.ids, f'missing section {anchor}'
assert any(s.get('type')=='module' and s.get('src')=='./web-messenger.js' for s in parser.scripts)
assert 'LOCAL_CHAT_DEV_FIREBASE_CONFIG' in client
assert "parsed.protocol === 'http:'" in client and "'localhost', '127.0.0.1', '::1'" in client
assert 'connectAuthEmulator' in client and 'connectFirestoreEmulator' in client
assert 'createUserWithEmailAndPassword' in client and 'signInWithEmailAndPassword' in client
for operation in ['createRoom','requestJoin','approve','sendMessage','listenRooms']:
    assert operation in client, f'missing client flow {operation}'
assert 'allow read, write: if false' not in rules, 'candidate rules must include tested authorized flows'
assert 'request.auth.uid' in rules and 'getAfter' in rules and 'hasOnly' in rules
assert 'resource.data.uid == request.auth.uid' in rules
assert 'resource.data.resource.data.uid' not in rules, 'reject invalid nested resource reference'
assert 'match /{path=**}/members/{uid}' in rules, 'collection-group discovery requires recursive match'
assert 'allow update, delete: if false' in rules
assert 'Auth Emulator' in (ROOT/'MESSAGERIE_WEB.md').read_text(encoding='utf-8')
assert 'production' in (ROOT/'MESSAGERIE_WEB.md').read_text(encoding='utf-8').lower()
assert 'local-config.js' in html and (ROOT/'.gitignore').read_text().find('web/local-config.js')>=0
# The bounded pilot is a proposal for local emulators, not production approval.
pilot=(ROOT/'MESSAGERIE_WEB.md').read_text(encoding='utf-8').lower()
spec=(ROOT/'FIRESTORE_MEMBERSHIP_SPEC.md').read_text(encoding='utf-8').lower()
for phrase in ['proposition de pilote fermé', 'émulateurs locaux seulement', 'quatre identités fictives', 'cinquante messages', 'cinq jours', 'aucun pilote cloud', 'n’approuve ni le déploiement']:
    assert phrase in pilot, f'missing pilot boundary in MESSAGERIE_WEB.md: {phrase}'
for phrase in ['protocole fermé', 'local uniquement', 'quatre identités fictives', 'cinquante messages', 'suppression', 'aucun essai cloud n’est autorisé']:
    assert phrase in spec, f'missing safety condition in FIRESTORE_MEMBERSHIP_SPEC.md: {phrase}'
assert 'firebase deploy' in (ROOT/'MESSAGERIE_WEB.md').read_text(encoding='utf-8')
assert "branches: [web-messagerie]" in (ROOT/'.github/workflows/web-tests.yml').read_text(encoding='utf-8')
print('PASS: HTML/client static checks and written local-pilot guardrails; no browser or production runtime test claimed.')
