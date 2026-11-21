import { readFile } from 'node:fs/promises';
import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  collection, collectionGroup, doc, getDoc, getDocs, query, serverTimestamp,
  setDoc, updateDoc, writeBatch, where,
} from 'firebase/firestore';

const projectId = 'demo-local-chat-hybride-rules';
let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId,
    firestore: { rules: await readFile(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const dbFor = (uid) => env.authenticatedContext(uid).firestore();
const roomRef = (db, id='room-alpha') => doc(db, 'rooms', id);
const memberRef = (db, room='room-alpha', uid='host') => doc(db, 'rooms', room, 'members', uid);
const requestRef = (db, room='room-alpha', uid='guest') => doc(db, 'rooms', room, 'joinRequests', uid);
const messageRef = (db, room='room-alpha', id='msg-1') => doc(db, 'rooms', room, 'messages', id);

async function createRoom(uid='host', room='room-alpha') {
  const db = dbFor(uid);
  const batch = writeBatch(db);
  batch.set(doc(db, 'rooms', room), {
    title: 'Salle de test', createdBy: uid, createdAt: serverTimestamp(), state: 'open', schemaVersion: 1,
  });
  batch.set(doc(db, 'rooms', room, 'members', uid), {
    uid, role: 'host', state: 'active', joinedAt: serverTimestamp(), displayName: 'Hôte',
  });
  await assertSucceeds(batch.commit());
  return db;
}
async function makeJoinRequest(uid='guest', room='room-alpha') {
  const db = dbFor(uid);
  await assertSucceeds(setDoc(requestRef(db, room, uid), {
    uid, state: 'pending', createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
  }));
  return db;
}
async function approve(uid='guest', room='room-alpha') {
  const db = dbFor('host');
  const batch = writeBatch(db);
  batch.update(requestRef(db, room, uid), { state: 'approved', updatedAt: serverTimestamp() });
  batch.set(memberRef(db, room, uid), {
    uid, role: 'member', state: 'active', joinedAt: serverTimestamp(), displayName: 'Invité',
  });
  await assertSucceeds(batch.commit());
}

// Positive path: atomic room+host creation and own-membership discovery.
test('host creates room atomically and discovers it through own membership', async () => {
  const db = await createRoom();
  const room = await assertSucceeds(getDoc(roomRef(db)));
  assert.equal(room.data().createdBy, 'host');
  const mine = await assertSucceeds(getDocs(query(collectionGroup(db, 'members'), where('uid', '==', 'host'))));
  assert.equal(mine.size, 1);
});

test('approved member can read room and exchange immutable messages', async () => {
  await createRoom(); await makeJoinRequest(); await approve();
  const db = dbFor('guest');
  assert.equal((await assertSucceeds(getDoc(roomRef(db)))).data().title, 'Salle de test');
  const own = await assertSucceeds(getDoc(memberRef(db, 'room-alpha', 'guest')));
  assert.equal(own.data().role, 'member');
  await assertSucceeds(setDoc(messageRef(db), {
    senderUid: 'guest', body: 'Bonjour', createdAt: serverTimestamp(), schemaVersion: 1,
  }));
  const messages = await assertSucceeds(getDocs(collection(db, 'rooms/room-alpha/messages')));
  assert.equal(messages.size, 1);
});

test('unauthenticated users cannot read or write room data', async () => {
  await createRoom();
  const db = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(roomRef(db)));
  await assertFails(getDocs(collection(db, 'rooms/room-alpha/messages')));
  await assertFails(setDoc(messageRef(db), { senderUid: 'guest', body: 'x', createdAt: serverTimestamp(), schemaVersion: 1 }));
  await assertFails(setDoc(requestRef(db), { uid: 'guest', state: 'pending', createdAt: serverTimestamp(), updatedAt: serverTimestamp() }));
});

test('knowing a room ID only permits a request; it does not reveal room or messages', async () => {
  await createRoom();
  const db = await makeJoinRequest('stranger');
  await assertFails(getDoc(roomRef(db)));
  await assertFails(getDocs(collection(db, 'rooms/room-alpha/messages')));
  await assertFails(getDocs(collection(db, 'rooms/room-alpha/members')));
  const ownRequest = await assertSucceeds(getDoc(requestRef(db, 'room-alpha', 'stranger')));
  assert.equal(ownRequest.data().state, 'pending');
  await assertFails(getDoc(requestRef(db, 'room-alpha', 'guest')));
});

test('only host can approve and approval must atomically create a member record', async () => {
  await createRoom(); await makeJoinRequest();
  const guest = dbFor('guest');
  await assertFails(updateDoc(requestRef(guest), { state: 'approved', updatedAt: serverTimestamp() }));
  const host = dbFor('host');
  await assertFails(setDoc(memberRef(host, 'room-alpha', 'guest'), {
    uid: 'guest', role: 'member', state: 'active', joinedAt: serverTimestamp(), displayName: 'Invité',
  })); // request still pending, no atomic approval
  await approve();
  assert.equal((await assertSucceeds(getDoc(roomRef(guest)))).data().state, 'open');
});

test('cannot self-promote, impersonate another user, or create a room without paired host membership', async () => {
  await createRoom();
  const attacker = dbFor('attacker');
  await assertFails(setDoc(memberRef(attacker, 'room-alpha', 'attacker'), {
    uid: 'attacker', role: 'host', state: 'active', joinedAt: serverTimestamp(), displayName: 'Attacker',
  }));
  await assertFails(setDoc(memberRef(attacker, 'room-alpha', 'host'), {
    uid: 'host', role: 'member', state: 'active', joinedAt: serverTimestamp(), displayName: 'Spoof',
  }));
  await assertFails(setDoc(roomRef(attacker, 'orphan'), {
    title: 'Orphan', createdBy: 'attacker', createdAt: serverTimestamp(), state: 'open', schemaVersion: 1,
  }));
  const bad = writeBatch(attacker);
  bad.set(doc(attacker, 'rooms', 'fake'), {
    title: 'Fake', createdBy: 'host', createdAt: serverTimestamp(), state: 'open', schemaVersion: 1,
  });
  bad.set(doc(attacker, 'rooms', 'fake', 'members', 'attacker'), {
    uid: 'attacker', role: 'host', state: 'active', joinedAt: serverTimestamp(), displayName: 'Attacker',
  });
  await assertFails(bad.commit());
});

test('messages reject spoofed senders, oversized payloads, edits and deletes', async () => {
  await createRoom();
  const host = dbFor('host');
  await assertFails(setDoc(messageRef(host), {
    senderUid: 'other-user', body: 'spoof', createdAt: serverTimestamp(), schemaVersion: 1,
  }));
  await assertFails(setDoc(messageRef(host), {
    senderUid: 'host', body: 'x'.repeat(4001), createdAt: serverTimestamp(), schemaVersion: 1,
  }));
  await assertFails(setDoc(messageRef(host), {
    senderUid: 'host', body: 'secret', createdAt: serverTimestamp(), schemaVersion: 1, extra: 'no',
  }));
  await assertSucceeds(setDoc(messageRef(host), {
    senderUid: 'host', body: 'valid', createdAt: serverTimestamp(), schemaVersion: 1,
  }));
  await assertFails(updateDoc(messageRef(host), { body: 'changed' }));
  await assertFails((await import('firebase/firestore')).deleteDoc(messageRef(host)));
});

test('host can revoke a member; revoked member loses room and message access', async () => {
  await createRoom(); await makeJoinRequest(); await approve();
  const host = dbFor('host');
  await assertSucceeds(updateDoc(memberRef(host, 'room-alpha', 'guest'), { state: 'revoked' }));
  const guest = dbFor('guest');
  await assertFails(getDoc(roomRef(guest)));
  await assertFails(getDocs(collection(guest, 'rooms/room-alpha/messages')));
  await assertFails(setDoc(messageRef(guest, 'room-alpha', 'after-revoke'), {
    senderUid: 'guest', body: 'still here?', createdAt: serverTimestamp(), schemaVersion: 1,
  }));
  await assertFails(updateDoc(memberRef(guest, 'room-alpha', 'guest'), { state: 'active' }));
});

test('nonmembers cannot enumerate rooms or messages, and clients cannot alter room ownership', async () => {
  await createRoom();
  const outsider = dbFor('outsider');
  await assertFails(getDocs(collection(outsider, 'rooms')));
  await assertFails(getDocs(query(collectionGroup(outsider, 'messages'))));
  const host = dbFor('host');
  await assertFails(updateDoc(roomRef(host), { createdBy: 'outsider' }));
  await assertFails(updateDoc(roomRef(host), { title: 'changed' }));
});
