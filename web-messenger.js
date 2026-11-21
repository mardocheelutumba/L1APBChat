// Local-only web client. It refuses to initialize unless BOTH Firebase Auth and
// Firestore point at localhost emulators. This intentionally cannot reach production.
const cfg = window.LOCAL_CHAT_DEV_FIREBASE_CONFIG;
const localHost = (value) => {
  try { const parsed = new URL(value); return parsed.protocol === 'http:' && ['localhost', '127.0.0.1', '::1'].includes(parsed.hostname); }
  catch { return false; }
};
const authEndpoint = cfg?.authEmulator;
const firestoreEndpoint = cfg?.firestoreEmulator;
const safeConfig = cfg && cfg.apiKey && cfg.authDomain && cfg.projectId && cfg.appId
  && localHost(authEndpoint) && localHost(firestoreEndpoint);
const $ = (id) => document.getElementById(id);
const status = $('backend-status');
const form = $('auth-form');
const notice = $('auth-notice');
function say(text, error=false) {
  notice.textContent = text; notice.hidden = false; notice.classList.toggle('error', error);
}
function statusText(title, body) {
  status.replaceChildren(); const icon=document.createElement('span'); icon.className='lock'; icon.textContent='◷';
  const content=document.createElement('div'); const strong=document.createElement('strong'); strong.textContent=title;
  content.append(strong, document.createTextNode(body)); status.append(icon, content);
}
function disableForm() { form?.querySelectorAll('input,button').forEach((x)=>x.disabled=true); }
if (!safeConfig) {
  disableForm();
  statusText('Connexion désactivée pour sécurité', 'Aucun Firebase de production n’est utilisé. Configurez les deux émulateurs locaux pour lancer le banc d’essai.');
  say('Espace de test local uniquement. Le site publié ne peut ni créer de compte réel ni lire/écrire des messages.');
} else {
  statusText('Banc d’essai local', 'Les données restent dans les émulateurs localhost; aucun compte ni message réel ne sera utilisé.');
  import('https://www.gstatic.com/firebasejs/11.0.0/firebase-app.js').then(async (appSdk) => {
    const authSdk=await import('https://www.gstatic.com/firebasejs/11.0.0/firebase-auth.js');
    const dbSdk=await import('https://www.gstatic.com/firebasejs/11.0.0/firebase-firestore.js');
    const app=appSdk.initializeApp(cfg);
    const auth=authSdk.getAuth(app);
    const db=dbSdk.getFirestore(app);
    authSdk.connectAuthEmulator(auth, authEndpoint, {disableWarnings:true});
    const firestoreUrl=new URL(firestoreEndpoint);
    dbSdk.connectFirestoreEmulator(db, firestoreUrl.hostname, Number(firestoreUrl.port || 8080));
    form.querySelectorAll('input,button').forEach((x)=>x.disabled=false);
    const state={ user:null, activeRoom:null, cleanups:[] };
    const clearWatchers=()=>{state.cleanups.splice(0).forEach((off)=>off());};
    const h=(tag,attrs={},text='')=>{const el=document.createElement(tag);Object.assign(el,attrs);el.textContent=text;return el;};
    let workspace=$('chat-workspace');
    if(!workspace){ workspace=h('div',{id:'chat-workspace'}); workspace.className='notice'; form.parentElement.append(workspace); }
    function renderShell() {
      workspace.replaceChildren();
      if(!state.user){ workspace.hidden=true; return; }
      workspace.hidden=false;
      const line=h('p',{},`Connecté : ${state.user.email || state.user.uid}`);
      const logout=h('button',{type:'button',className:'button ghost'},'Se déconnecter');
      logout.addEventListener('click',()=>authSdk.signOut(auth));
      const roomBox=h('section'); roomBox.className='chat-tools';
      roomBox.append(h('h3',{},'Créer une salle'));
      const title=h('input',{type:'text',maxLength:80,placeholder:'Nom de la salle','aria-label':'Nom de la salle'});
      const nickname=h('input',{type:'text',maxLength:40,placeholder:'Votre nom affiché','aria-label':'Votre nom affiché'});
      const create=h('button',{type:'button',className:'button'},'Créer et devenir hôte');
      create.addEventListener('click',()=>createRoom(title.value.trim(),nickname.value.trim()).catch(showError));
      roomBox.append(title,nickname,create);
      const join=h('section'); join.className='chat-tools'; join.append(h('h3',{},'Demander l’accès à une salle'));
      const code=h('input',{type:'text',maxLength:100,placeholder:'Code de salle fourni par l’hôte','aria-label':'Code de salle'});
      const joinName=h('input',{type:'text',maxLength:40,placeholder:'Votre nom affiché','aria-label':'Nom affiché pour la demande'});
      const ask=h('button',{type:'button',className:'button ghost'},'Envoyer une demande');
      ask.addEventListener('click',()=>requestJoin(code.value.trim(),joinName.value.trim()).catch(showError)); join.append(code,joinName,ask);
      const rooms=h('section'); rooms.id='my-rooms'; rooms.append(h('h3',{},'Mes salles'));
      workspace.append(line,logout,roomBox,join,rooms);
      state.roomsNode=rooms;
      listenRooms();
    }
    function showError(error){say(friendly(error),true);}
    function friendly(error){
      const code=error?.code||'';
      if(code.includes('permission-denied')) return 'Accès refusé par les règles Firestore (demande en attente, adhésion révoquée ou données invalides).';
      if(code==='auth/email-already-in-use') return 'Cette adresse e-mail possède déjà un compte de test.';
      if(code==='auth/invalid-credential') return 'Adresse ou mot de passe incorrect.';
      if(code==='auth/weak-password') return 'Choisissez un mot de passe d’au moins 6 caractères.';
      if(code==='auth/invalid-email') return 'Adresse e-mail invalide.';
      return 'Action refusée ou émulateur indisponible. Aucun service réel n’a été appelé.';
    }
    async function createRoom(title,displayName){
      if(!title||!displayName) throw new Error('Complétez le nom de la salle et le nom affiché.');
      const uid=state.user.uid; const roomId=crypto.randomUUID();
      const batch=dbSdk.writeBatch(db);
      batch.set(dbSdk.doc(db,'rooms',roomId),{title,createdBy:uid,createdAt:dbSdk.serverTimestamp(),state:'open',schemaVersion:1});
      batch.set(dbSdk.doc(db,'rooms',roomId,'members',uid),{uid,role:'host',state:'active',joinedAt:dbSdk.serverTimestamp(),displayName});
      await batch.commit(); say(`Salle créée. Code à partager : ${roomId}. Ce code ne suffit pas à entrer : l’hôte doit approuver les demandes.`);
    }
    async function requestJoin(roomId,displayName){
      if(!roomId||!displayName) throw new Error('Saisissez le code de salle et un nom affiché.');
      const uid=state.user.uid;
      await dbSdk.setDoc(dbSdk.doc(db,'rooms',roomId,'joinRequests',uid),{uid,displayName,state:'pending',createdAt:dbSdk.serverTimestamp(),updatedAt:dbSdk.serverTimestamp()});
      localStorage.setItem(`lc-pending-${uid}-${roomId}`, 'pending');
      say('Demande transmise. Le code n’accorde pas l’accès; attendez la décision de l’hôte.');
      watchRequest(roomId);
    }
    function listenRooms(){
      clearWatchers();
      const q=dbSdk.query(dbSdk.collectionGroup(db,'members'),dbSdk.where('uid','==',state.user.uid));
      const off=dbSdk.onSnapshot(q,(snap)=>{
        const docs=snap.docs.filter((d)=>d.data().state==='active');
        state.roomsNode.replaceChildren(h('h3',{},'Mes salles'));
        if(!docs.length) state.roomsNode.append(h('p',{},'Aucune salle approuvée sur cet appareil. Vous pouvez créer une salle ou demander un accès.'));
        docs.forEach((m)=>{
          const parts=m.ref.path.split('/');const roomId=parts[1];
          const button=h('button',{type:'button',className:'button ghost'},`Ouvrir : ${roomId}`);
          button.addEventListener('click',()=>openRoom(roomId,m.data()).catch(showError));state.roomsNode.append(button);
        });
      },showError); state.cleanups.push(off);
    }
    function watchRequest(roomId){
      const ref=dbSdk.doc(db,'rooms',roomId,'joinRequests',state.user.uid);
      const off=dbSdk.onSnapshot(ref,(snap)=>{
        if(!snap.exists())return;
        const v=snap.data();
        if(v.state==='approved'){localStorage.removeItem(`lc-pending-${state.user.uid}-${roomId}`);say('Demande acceptée. La salle apparaît dans « Mes salles ».');}
        else if(v.state==='rejected'){say('Demande refusée par l’hôte.',true);}
      },showError);state.cleanups.push(off);
    }
    async function openRoom(roomId,membership){
      const roomSnap=await dbSdk.getDoc(dbSdk.doc(db,'rooms',roomId));
      if(!roomSnap.exists())throw new Error('Salle introuvable.');
      clearWatchers(); state.activeRoom=roomId;
      const panel=h('section');panel.className='chat-tools';panel.append(h('h3',{},roomSnap.data().title));
      const codeLine=h('p',{},`Code : ${roomId} · rôle : ${membership.role}`);
      const messages=h('div');messages.className='chat-messages';messages.setAttribute('aria-live','polite');
      const input=h('input',{type:'text',maxLength:4000,placeholder:'Écrire un message…','aria-label':'Message'});
      const send=h('button',{type:'button',className:'button'},'Envoyer');
      const formLine=h('div');formLine.append(input,send);
      const back=h('button',{type:'button',className:'button ghost'},'Retour à mes salles');back.addEventListener('click',renderShell);
      panel.append(codeLine,back,messages,formLine);
      if(membership.role==='host'){
        const pending=h('div');pending.append(h('h4',{},'Demandes d’accès'));
        const q=dbSdk.query(dbSdk.collection(db,`rooms/${roomId}/joinRequests`),dbSdk.where('state','==','pending'));
        const offReq=dbSdk.onSnapshot(q,(snap)=>{
          pending.replaceChildren(h('h4',{},'Demandes d’accès'));
          if(!snap.size)pending.append(h('p',{},'Aucune demande en attente.'));
          snap.forEach((item)=>{const wrap=h('div');const label=h('span',{},`${item.data().displayName} · UID ${item.data().uid} `);const yes=h('button',{type:'button',className:'button'},'Accepter');const no=h('button',{type:'button',className:'button ghost'},'Refuser');
            yes.addEventListener('click',()=>decide(item.id,true,item.data().displayName).catch(showError));no.addEventListener('click',()=>decide(item.id,false,item.data().displayName).catch(showError));wrap.append(label,yes,no);pending.append(wrap);});
        },showError);state.cleanups.push(offReq);panel.append(pending);
        const activeMembers=h('div');activeMembers.append(h('h4',{},'Membres actifs'));
        const membersQuery=dbSdk.query(dbSdk.collection(db,`rooms/${roomId}/members`),dbSdk.where('role','==','member'),dbSdk.where('state','==','active'));
        const offMembers=dbSdk.onSnapshot(membersQuery,(snap)=>{
          activeMembers.replaceChildren(h('h4',{},'Membres actifs'));
          if(!snap.size)activeMembers.append(h('p',{},'Aucun membre invité pour le moment.'));
          snap.forEach((item)=>{const row=h('div');const name=item.data().displayName;row.append(h('span',{},`${name} `));const revoke=h('button',{type:'button',className:'button ghost'},`Révoquer ${name}`);revoke.addEventListener('click',()=>dbSdk.updateDoc(item.ref,{state:'revoked'}).catch(showError));row.append(revoke);activeMembers.append(row);});
        },showError);state.cleanups.push(offMembers);panel.append(activeMembers);
        async function decide(uid,accept,displayName){const batch=dbSdk.writeBatch(db);const req=dbSdk.doc(db,`rooms/${roomId}/joinRequests/${uid}`);batch.update(req,{state:accept?'approved':'rejected',updatedAt:dbSdk.serverTimestamp()});if(accept)batch.set(dbSdk.doc(db,`rooms/${roomId}/members/${uid}`),{uid,role:'member',state:'active',joinedAt:dbSdk.serverTimestamp(),displayName});await batch.commit();}
      }
      async function sendMessage(){const body=input.value.trim();if(!body)return;const ref=dbSdk.doc(db,`rooms/${roomId}/messages`,crypto.randomUUID());await dbSdk.setDoc(ref,{senderUid:state.user.uid,senderName:membership.displayName,body,createdAt:dbSdk.serverTimestamp(),schemaVersion:1});input.value='';}
      send.addEventListener('click',()=>sendMessage().catch(showError));input.addEventListener('keydown',(event)=>{if(event.key==='Enter'){event.preventDefault();sendMessage().catch(showError);}});
      const q=dbSdk.query(dbSdk.collection(db,`rooms/${roomId}/messages`),dbSdk.orderBy('createdAt','asc'),dbSdk.limit(100));
      const off=dbSdk.onSnapshot(q,(snap)=>{messages.replaceChildren();snap.forEach((m)=>{const row=h('article');row.className=m.data().senderUid===state.user.uid?'bubble mine':'bubble';row.append(h('strong',{},m.data().senderName),h('div',{},m.data().body),h('small',{},`${m.data().createdAt?.toDate?.().toLocaleString?.()||'à l’instant'}`));messages.append(row);});messages.scrollTop=messages.scrollHeight;},showError);state.cleanups.push(off);
      workspace.replaceChildren(panel);
    }
    $('auth-form').addEventListener('submit',async(event)=>{
      event.preventDefault();const data=new FormData(form);const email=String(data.get('email')||'').trim();const password=String(data.get('password')||'');const mode=event.submitter?.value||'login';
      try{if(mode==='register')await authSdk.createUserWithEmailAndPassword(auth,email,password);else await authSdk.signInWithEmailAndPassword(auth,email,password);say('Compte de test connecté à l’émulateur local.');}
      catch(error){showError(error);}
    });
    $('reset').addEventListener('click',async()=>{const email=$('email').value.trim();if(!email){say('Saisissez une adresse e-mail.',true);return;}try{await authSdk.sendPasswordResetEmail(auth,email);say('Réinitialisation demandée dans Auth Emulator (aucun e-mail réel envoyé).');}catch(error){showError(error);}});
    authSdk.onAuthStateChanged(auth,(user)=>{clearWatchers();state.user=user;renderShell();});
  }).catch((error)=>{statusText('Émulateurs indisponibles', 'Le banc d’essai est resté désactivé.');say('Impossible de charger Firebase en local. Aucun accès de production n’est tenté.',true);});
}
