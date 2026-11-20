#!/usr/bin/env python3
"""Static smoke checks for the standalone web page; not an end-to-end Firebase test."""
from html.parser import HTMLParser
from pathlib import Path

class Page(HTMLParser):
    def __init__(self):
        super().__init__(); self.ids=set(); self.lang=None; self.inputs=[]; self.buttons=[]; self.scripts=0
    def handle_starttag(self, tag, attrs):
        a=dict(attrs)
        if tag=='html': self.lang=a.get('lang')
        if 'id' in a: self.ids.add(a['id'])
        if tag=='input': self.inputs.append(a)
        if tag=='button': self.buttons.append(a)
        if tag=='script': self.scripts += 1

html=Path(__file__).with_name('index.html').read_text(encoding='utf-8')
p=Page(); p.feed(html)
assert p.lang=='fr', 'French document language missing'
for anchor in ['accueil','fonctionnement','etat','messagerie','questions']:
    assert f'id="{anchor}"' in html, f'missing section {anchor}'
assert 'LOCAL_CHAT_FIREBASE_CONFIG' in html and 'if(!configReady)return' in html, 'auth must remain disabled without config'
assert 'firestore.googleapis.com' not in html and 'firebase/firestore' not in html, 'web page must not access Firestore'
assert 'createUserWithEmailAndPassword' in html and 'signInWithEmailAndPassword' in html and 'sendPasswordResetEmail' in html
assert 'authEmulator' in html, 'Auth Emulator test hook missing'
assert 'deny-all' in Path(__file__).with_name('MESSAGERIE_WEB.md').read_text(encoding='utf-8').lower()
assert 'aucun message envoyé' in html.lower() or 'aucun message web' in html.lower()
assert 'viewport' in html and '@media' in html, 'responsive layout missing'
assert not any('firestore' in str(b).lower() for b in p.buttons), 'unexpected firestore control'
print('PASS: HTML parsed; French, sections, responsive styles, emulator-ready email Auth hooks, explicit disabled backend; no Firestore SDK/access present.')
