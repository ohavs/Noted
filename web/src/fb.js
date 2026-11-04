// Firebase for the web app. The config comes from Hosting's reserved /__/firebase/init.json
// (the project's web app), so nothing about the project is baked into the code.
import { initializeApp } from 'firebase/app';
import { getAuth, GoogleAuthProvider, signInWithPopup, signInWithRedirect, onAuthStateChanged, signOut } from 'firebase/auth';
import { initializeFirestore, persistentLocalCache, persistentMultipleTabManager, memoryLocalCache } from 'firebase/firestore';
import { getStorage } from 'firebase/storage';
import { getFunctions } from 'firebase/functions';

export const fb = { app: null, auth: null, db: null, storage: null, fns: null };

export async function boot() {
  const cfg = await (await fetch('/__/firebase/init.json')).json();
  fb.app = initializeApp(cfg);
  fb.auth = getAuth(fb.app);
  let cache;
  try { cache = persistentLocalCache({ tabManager: persistentMultipleTabManager() }); } catch { cache = memoryLocalCache(); }
  fb.db = initializeFirestore(fb.app, { localCache: cache, ignoreUndefinedProperties: true });
  try { fb.storage = getStorage(fb.app); } catch { fb.storage = null; }
  fb.fns = getFunctions(fb.app, 'me-west1');
  return fb;
}

export function onUser(cb) { return onAuthStateChanged(fb.auth, cb); }

export async function signIn() {
  const p = new GoogleAuthProvider();
  p.setCustomParameters({ prompt: 'select_account' });
  try { await signInWithPopup(fb.auth, p); } catch (e) {
    if (String(e?.code).includes('popup-blocked')) await signInWithRedirect(fb.auth, p);
    else if (!String(e?.code).includes('popup-closed')) throw e;
  }
}

export const logOut = () => signOut(fb.auth);
