import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { chromium } from 'playwright';

const baseUrl = 'http://127.0.0.1:4173';
const server = spawn('python3', ['-m', 'http.server', '4173', '--bind', '127.0.0.1'], {
  stdio: 'ignore',
});
let browser;

async function waitForServer() {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      const response = await fetch(baseUrl);
      if (response.ok) return;
    } catch {}
    await delay(200);
  }
  throw new Error('Static web server did not start on 127.0.0.1:4173');
}

async function register(page, email, displayName) {
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => {
    const emailInput = document.querySelector('#email');
    return emailInput && !emailInput.disabled;
  }, undefined, { timeout: 30000 });
  await page.getByLabel('Adresse e-mail').fill(email);
  await page.getByLabel('Mot de passe').fill('test-password-123');
  await page.getByRole('button', { name: 'Créer un compte' }).click();
  await page.getByText(`Connecté : ${email}`, { exact: true }).waitFor({ timeout: 30000 });
  assert.match(await page.locator('#backend-status').innerText(), /Banc d’essai local/);
  return displayName;
}

async function assertEmulatorDeniesRoomRead(email, roomCode) {
  const tokenResponse = await fetch('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-api-key', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email, password: 'test-password-123', returnSecureToken: true }),
  });
  assert.equal(tokenResponse.status, 200, 'outsider must authenticate against the local Auth Emulator');
  const { idToken } = await tokenResponse.json();
  const response = await fetch(`http://127.0.0.1:8080/v1/projects/demo-local-chat-hybride-rules/databases/(default)/documents/rooms/${roomCode}`, {
    headers: { authorization: `Bearer ${idToken}` },
  });
  assert.equal(response.status, 403, 'unauthorized room read must be rejected by Firestore Emulator rules');
}

async function fillAndClick(page, values, buttonName) {
  for (const [label, value] of Object.entries(values)) await page.getByLabel(label, { exact: true }).fill(value);
  await page.getByRole('button', { name: buttonName, exact: true }).click();
}

try {
  await waitForServer();
  browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const hostContext = await browser.newContext();
  const outsiderContext = await browser.newContext();
  const guestContext = await browser.newContext();
  const host = await hostContext.newPage();
  const outsider = await outsiderContext.newPage();
  const guest = await guestContext.newPage();

  await register(host, 'host@example.test', 'Hôte');
  await fillAndClick(host, { 'Nom de la salle': 'Salon e2e', 'Votre nom affiché': 'Hôte' }, 'Créer et devenir hôte');
  const notice = host.locator('#auth-notice');
  await notice.getByText(/Salle créée\./).waitFor({ timeout: 30000 });
  const roomCode = (await notice.innerText()).match(/Code à partager\s*:\s*([\w-]+)/)?.[1];
  assert.ok(roomCode, 'The room creation notice must expose a share code');
  await host.getByRole('button', { name: `Ouvrir : ${roomCode}`, exact: true }).waitFor({ timeout: 30000 });
  await host.getByRole('button', { name: `Ouvrir : ${roomCode}`, exact: true }).click();
  await host.getByText('Demandes d’accès', { exact: true }).waitFor({ timeout: 30000 });

  // An outsider knows the code but must not enter; the host explicitly rejects the request.
  await register(outsider, 'outsider@example.test', 'Intrus');
  await assertEmulatorDeniesRoomRead('outsider@example.test', roomCode);
  await fillAndClick(outsider, {
    'Code de salle': roomCode,
    'Nom affiché pour la demande': 'Intrus',
  }, 'Envoyer une demande');
  await outsider.getByText(/Demande transmise/).waitFor({ timeout: 30000 });
  await host.getByRole('button', { name: 'Refuser', exact: true }).waitFor({ timeout: 30000 });
  await host.getByRole('button', { name: 'Refuser', exact: true }).click();
  await outsider.getByText(/Demande refusée par l’hôte/).waitFor({ timeout: 30000 });
  await assert.rejects(outsider.getByRole('button', { name: /^Ouvrir :/ }).waitFor({ timeout: 1000 }));

  // A separate fake account requests access, is approved, and joins the same room.
  await register(guest, 'guest@example.test', 'Invité');
  await fillAndClick(guest, {
    'Code de salle': roomCode,
    'Nom affiché pour la demande': 'Invité',
  }, 'Envoyer une demande');
  await guest.getByText(/Demande transmise/).waitFor({ timeout: 30000 });
  await host.getByRole('button', { name: 'Accepter', exact: true }).waitFor({ timeout: 30000 });
  await host.getByRole('button', { name: 'Accepter', exact: true }).click();
  await guest.getByText(/Demande acceptée/).waitFor({ timeout: 30000 });
  await guest.getByRole('button', { name: `Ouvrir : ${roomCode}`, exact: true }).waitFor({ timeout: 30000 });
  await guest.getByRole('button', { name: `Ouvrir : ${roomCode}`, exact: true }).click();

  // Real-time two-way message exchange through the Firestore Emulator.
  await host.getByLabel('Message').fill('Message du hôte vers invité');
  await host.getByRole('button', { name: 'Envoyer', exact: true }).click();
  await guest.getByText('Message du hôte vers invité', { exact: true }).waitFor({ timeout: 30000 });
  await guest.getByLabel('Message').fill('Réponse de l’invité au hôte');
  await guest.getByRole('button', { name: 'Envoyer', exact: true }).click();
  await host.getByText('Réponse de l’invité au hôte', { exact: true }).waitFor({ timeout: 30000 });

  // The host revokes the guest. Subsequent message and renewed-access writes must be denied by rules.
  await host.getByRole('button', { name: 'Révoquer Invité', exact: true }).waitFor({ timeout: 30000 });
  await host.getByRole('button', { name: 'Révoquer Invité', exact: true }).click();
  await host.getByText('Aucun membre invité pour le moment.', { exact: true }).waitFor({ timeout: 30000 });
  await guest.getByLabel('Message').fill('Ce message doit être refusé');
  await guest.getByRole('button', { name: 'Envoyer', exact: true }).click();
  await guest.getByText(/Accès refusé par les règles Firestore/).waitFor({ timeout: 30000 });
  await guest.getByRole('button', { name: 'Retour à mes salles', exact: true }).click();
  await guest.getByLabel('Code de salle').fill(roomCode);
  await guest.getByLabel('Nom affiché pour la demande', { exact: true }).fill('Invité');
  await guest.getByRole('button', { name: 'Envoyer une demande', exact: true }).click();
  await guest.getByText(/Accès refusé par les règles Firestore/).waitFor({ timeout: 30000 });

  console.log('PASS: fake accounts registered; outsider rejected; guest approved; messages exchanged both ways; revoked guest denied message and renewed join request.');
  await Promise.all([hostContext.close(), outsiderContext.close(), guestContext.close()]);
} catch (error) {
  console.error('FAIL: browser/emulator end-to-end test did not complete.');
  throw error;
} finally {
  if (browser) await browser.close();
  server.kill('SIGTERM');
}
