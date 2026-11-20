const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const { initializeTestEnvironment, assertFails } = require('@firebase/rules-unit-testing');
const { collection, deleteDoc, doc, getDoc, getDocs, setDoc, updateDoc } = require('firebase/firestore');
const PROJECT_ID = 'demo-local-chat-hybride-rules';
let testEnv;
test.before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8') },
  });
});
test.after(async () => { await testEnv?.cleanup(); });
test('unauthenticated clients are denied reads and writes', async (t) => {
  const db = testEnv.unauthenticatedContext().firestore();
  const room = doc(db, 'rooms', 'room_example_0123456789');
  const message = doc(db, 'rooms', 'room_example_0123456789', 'messages', 'message-1');
  const membership = doc(db, 'rooms', 'room_example_0123456789', 'members', 'user-1');
  await t.test('cannot read a room or list rooms', async () => {
    await assertFails(getDoc(room));
    await assertFails(getDocs(collection(db, 'rooms')));
  });
  await t.test('cannot create, edit, or delete a room', async () => {
    await assertFails(setDoc(room, { ownerUid: 'user-1' }));
    await assertFails(updateDoc(room, { ownerUid: 'user-2' }));
    await assertFails(deleteDoc(room));
  });
  await t.test('cannot read or create membership', async () => {
    await assertFails(getDoc(membership));
    await assertFails(setDoc(membership, { role: 'member' }));
  });
  await t.test('cannot read or write messages', async () => {
    await assertFails(getDoc(message));
    await assertFails(setDoc(message, { senderUid: 'user-1', body: 'hello' }));
  });
});
test('authenticated clients are also denied until room authorization is designed', async (t) => {
  const db = testEnv.authenticatedContext('user-1').firestore();
  const room = doc(db, 'rooms', 'room_example_0123456789');
  const message = doc(db, 'rooms', 'room_example_0123456789', 'messages', 'message-1');
  const membership = doc(db, 'rooms', 'room_example_0123456789', 'members', 'user-1');
  await t.test('cannot create rooms or self-assign membership', async () => {
    await assertFails(setDoc(room, { ownerUid: 'user-1' }));
    await assertFails(setDoc(membership, { role: 'owner' }));
  });
  await t.test('cannot read or write room messages', async () => {
    await assertFails(getDoc(room));
    await assertFails(getDoc(message));
    await assertFails(setDoc(message, { senderUid: 'user-1', body: 'hello' }));
    await assertFails(deleteDoc(message));
  });
});
