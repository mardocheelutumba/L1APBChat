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
assert 'allow update, delete: if false' in rules
assert 'Auth Emulator' in (ROOT/'MESSAGERIE_WEB.md').read_text(encoding='utf-8')
assert 'production' in (ROOT/'MESSAGERIE_WEB.md').read_text(encoding='utf-8').lower()
assert 'local-config.js' in html and (ROOT/'.gitignore').read_text().find('web/local-config.js')>=0
print('PASS: HTML and client static checks; auth+chat code is emulator-only; security rules include paired writes; no runtime test claimed.')
