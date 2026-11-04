/**
 * Account deletion (Google Play requires it in the app and on the web): everything the caller
 * owns goes — their notes, folders, categories, templates, reminders, devices, invites, media —
 * they are taken off notes and folders others shared with them, and the sign-in itself is deleted.
 */
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions/v2');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { getAuth } = require('firebase-admin/auth');
const { getStorage } = require('firebase-admin/storage');

async function dropAll(q) {
  const db = getFirestore();
  for (;;) {
    const s = await q.limit(400).get();
    if (s.empty) return;
    const b = db.batch();
    s.docs.forEach((d) => b.delete(d.ref));
    await b.commit();
  }
}

/** Takes [uid] off a shared document (note or folder) someone else owns. */
function leave(uid) {
  return {
    memberIds: FieldValue.arrayRemove(uid),
    [`members.${uid}`]: FieldValue.delete(),
    [`people.${uid}`]: FieldValue.delete(),
    [`u.${uid}`]: FieldValue.delete(),
  };
}

exports.deleteAccount = onCall({ timeoutSeconds: 300, maxInstances: 2 }, async (req) => {
  const uid = req.auth?.uid;
  if (!uid) throw new HttpsError('unauthenticated', 'sign in first');
  const db = getFirestore();
  for (const col of ['notes', 'folders']) {
    const s = await db.collection(col).where('memberIds', 'array-contains', uid).get();
    for (const d of s.docs) {
      if (d.get('owner') === uid) await d.ref.delete();
      else await d.ref.update(leave(uid));
    }
  }
  await dropAll(db.collection('invites').where('by', '==', uid));
  await db.recursiveDelete(db.doc(`users/${uid}`));
  try {
    await getStorage().bucket().deleteFiles({ prefix: `users/${uid}/` });
  } catch (e) {
    logger.warn('media cleanup', e.message);
  }
  await getAuth().deleteUser(uid);
  logger.info('account deleted', uid);
  return { ok: true };
});
