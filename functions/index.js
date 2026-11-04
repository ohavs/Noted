/**
 * noted — server side of sharing.
 *
 *  - Shared folders: a note whose `folder` names a `folders/{id}` document gets that folder's
 *    members on it (tracked in `via`, so the people shared on the note directly stay apart).
 *  - Notifications (FCM, data messages the app draws itself): content changes are collected
 *    per note in `pending/{note}` and sent once, 2 minutes after the first change; joins go
 *    out right away. Ticking a line off doesn't notify.
 */
const { onDocumentWritten } = require('firebase-functions/v2/firestore');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { setGlobalOptions, logger } = require('firebase-functions/v2');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue, Timestamp } = require('firebase-admin/firestore');
const { getMessaging } = require('firebase-admin/messaging');

// same place as the database (Tel Aviv)
setGlobalOptions({ region: 'me-west1', maxInstances: 3, memory: '256MiB' });
initializeApp();
const db = getFirestore();

const BATCH_MS = 2 * 60 * 1000;
const ZW = /[​‌‍﻿]/g;

// ------------------------------------------------------------------ helpers

const sameSet = (a = [], b = []) => a.length === b.length && a.every((x) => b.includes(x));
const sameMap = (a = {}, b = {}) => {
  const ka = Object.keys(a);
  return ka.length === Object.keys(b).length && ka.every((k) => a[k] === b[k]);
};
const samePerson = (a, b) => (a?.n ?? '') === (b?.n ?? '') && (a?.p ?? '') === (b?.p ?? '');
const first = (name) => (name || '').trim().split(/\s+/)[0] || 'מישהו';
const clean = (s) => (s || '').replace(ZW, '').replace(/\s+/g, ' ').trim();

/** May [uid] put notes in [folder]? */
const canAdd = (folder, uid) => !!folder && ['owner', 'editor'].includes((folder.members || {})[uid]);

/** The note's members without the ones a folder brought in. */
function direct(note) {
  const via = new Set(note.via || []);
  const m = {};
  for (const [u, r] of Object.entries(note.members || {})) if (!via.has(u)) m[u] = r;
  m[note.owner] = 'owner';
  return m;
}

/**
 * Puts [folder]'s members on the note (or takes them off when [folder] is null), keeping the
 * direct members as they are. [extra] is merged into the same write.
 */
async function applyFolder(ref, note, folder, extra = {}) {
  const members = direct(note);
  const via = [];
  const people = {};
  if (folder) {
    for (const u of folder.memberIds || []) {
      if (u in members) continue;
      members[u] = folder.members?.[u] === 'viewer' ? 'viewer' : 'editor';
      via.push(u);
      if (folder.people?.[u]) people[u] = folder.people[u];
    }
  }
  const ids = Object.keys(members);
  const upd = { ...extra };
  if (!sameSet(note.memberIds, ids) || !sameMap(note.members, members) || !sameSet(note.via, via)) {
    upd.memberIds = ids;
    upd.members = members;
    upd.via = via;
  }
  for (const [u, p] of Object.entries(people)) if (!samePerson(note.people?.[u], p)) upd[`people.${u}`] = p;
  for (const u of note.memberIds || []) {
    if (!(u in members)) {
      upd[`people.${u}`] = FieldValue.delete();
      upd[`u.${u}`] = FieldValue.delete();
    }
  }
  if (Object.keys(upd).length) await ref.update(upd);
}

/** Lines whose content changed (not ticks, not moves), by who changed them. */
function changes(before, after) {
  const out = {};
  const add = (uid, key) => { if (uid) (out[uid] ||= new Set()).add(key); };
  if ((before.title || '') !== (after.title || '')) add(after.by, '#t');
  const a = after.blocks || {};
  const b = before.blocks || {};
  const sig = (x) => JSON.stringify([x.type, x.text, x.indent, x.x, x.spans]);
  for (const [id, blk] of Object.entries(a)) {
    if (!b[id] || sig(b[id]) !== sig(blk)) {
      // a fresh empty line isn't news yet
      if (!b[id] && !clean(blk.text) && !blk.x) continue;
      add(blk.by || after.by, id);
    }
  }
  for (const id of Object.keys(b)) if (!a[id] && (clean(b[id].text) || b[id].x)) add(after.by, id);
  return out;
}

async function queue(noteId, uid, { lines = [], added = null }) {
  const ref = db.doc(`pending/${noteId}`);
  await db.runTransaction(async (tx) => {
    const s = await tx.get(ref);
    const upd = {};
    if (!s.exists) upd.due = Timestamp.fromMillis(Date.now() + BATCH_MS);
    if (lines.length) upd.a = { [uid]: FieldValue.arrayUnion(...lines) };
    if (added) upd.add = { by: uid, folder: added };
    tx.set(ref, upd, { merge: true });
  });
}

/** Sends a data message to every device of [uid]; drops tokens that went stale. */
async function send(uid, data) {
  const devs = await db.collection(`users/${uid}/devices`).get();
  const docs = devs.docs.filter((d) => d.get('token'));
  if (!docs.length) return;
  const res = await getMessaging().sendEachForMulticast({
    tokens: docs.map((d) => d.get('token')),
    data,
    android: { priority: 'high', ttl: 24 * 3600 * 1000, collapseKey: data.note || data.folder || 'noted' },
  });
  await Promise.all(res.responses.map((r, i) => {
    const code = r.error?.code || '';
    if (!r.success && (code.includes('registration-token-not-registered') || code.includes('invalid-registration-token') || code.includes('invalid-argument'))) {
      return docs[i].ref.delete();
    }
    return null;
  }));
}

function who(names) {
  if (names.length === 1) return names[0];
  if (names.length === 2) return `${names[0]} ו${names[1]}`;
  return `${names[0]} ועוד ${names.length - 1}`;
}

// ------------------------------------------------------------------ notes

exports.noteWritten = onDocumentWritten('notes/{id}', async (ev) => {
  const id = ev.params.id;
  const before = ev.data.before.exists ? ev.data.before.data() : null;
  const after = ev.data.after.exists ? ev.data.after.data() : null;
  if (!after) {
    await db.doc(`pending/${id}`).delete().catch(() => {});
    return;
  }
  const ref = ev.data.after.ref;

  // in or out of a shared folder
  const bf = before?.folder || '';
  const af = after.folder || '';
  if (af !== bf) {
    let folder = null;
    if (af) {
      const f = await db.doc(`folders/${af}`).get();
      folder = f.exists ? f.data() : null;
      if (!canAdd(folder, after.owner)) folder = null;
    }
    await applyFolder(ref, after, folder, af && !folder ? { folder: FieldValue.delete() } : {});
    if (folder && (folder.memberIds || []).length > 1) await queue(id, after.owner, { added: af });
  }

  if (!before) return;
  const members = after.memberIds || [];
  if (members.length > 1 && !after.trashedAt) {
    for (const [uid, keys] of Object.entries(changes(before, after))) await queue(id, uid, { lines: [...keys] });
  }

  // someone joined by link (not brought in by a folder)
  const via = after.via || [];
  const joined = members.filter((u) => !(before.memberIds || []).includes(u) && !via.includes(u));
  for (const j of joined) {
    const title = clean(after.title) || 'פתק משותף';
    const body = `${first(after.people?.[j]?.n)} · הצטרפות חדשה לפתק`;
    await Promise.all((before.memberIds || []).map((r) => send(r, { kind: 'join', note: id, folder: '', title, body })));
  }
});

// ------------------------------------------------------------------ folders

exports.folderWritten = onDocumentWritten('folders/{id}', async (ev) => {
  const id = ev.params.id;
  const before = ev.data.before.exists ? ev.data.before.data() : null;
  const after = ev.data.after.exists ? ev.data.after.data() : null;

  const peopleMoved = () => {
    const a = after?.people || {};
    const b = before?.people || {};
    return Object.keys({ ...a, ...b }).some((k) => !samePerson(a[k], b[k]));
  };
  const moved = !before || !after || !sameSet(before.memberIds, after.memberIds) || !sameMap(before.members, after.members) || peopleMoved();
  if (moved) {
    const notes = await db.collection('notes').where('folder', '==', id).get();
    for (const d of notes.docs) {
      const n = d.data();
      if (after && canAdd(after, n.owner)) await applyFolder(d.ref, n, after);
      // folder gone, or the note's owner left it: the note goes back to its direct members
      else await applyFolder(d.ref, n, null, { folder: FieldValue.delete() });
    }
  }

  if (before && after) {
    const joined = (after.memberIds || []).filter((u) => !(before.memberIds || []).includes(u));
    for (const j of joined) {
      const title = clean(after.name) || 'תיקייה משותפת';
      const body = `${first(after.people?.[j]?.n)} · הצטרפות חדשה לתיקייה`;
      await Promise.all((before.memberIds || []).map((r) => send(r, { kind: 'join', note: '', folder: id, title, body })));
    }
  }
});

// ------------------------------------------------------------------ batched delivery

async function deliver(noteId, p) {
  const s = await db.doc(`notes/${noteId}`).get();
  if (!s.exists) return;
  const note = s.data();
  if (note.trashedAt) return;
  const blocks = note.blocks || {};
  const title = clean(note.title);
  const lines = Object.values(blocks).filter((b) => clean(b.text) || b.x);
  const byAuthor = p.a || {};
  const added = p.add?.by ? p.add : null;
  if (added && !title && !lines.length) return; // an empty note someone opened and left

  let folderName = '';
  if (added) {
    const f = await db.doc(`folders/${added.folder}`).get();
    folderName = clean(f.get('name'));
  }
  const name = (u) => first(note.people?.[u]?.n);

  await Promise.all((note.memberIds || []).map((r) => {
    if (added && added.by !== r) {
      return send(r, {
        kind: 'add', note: noteId, folder: added.folder,
        title: folderName || 'תיקייה משותפת',
        body: `פתק חדש מ${name(added.by)}: ${title || 'ללא כותרת'}`,
      });
    }
    const others = Object.keys(byAuthor).filter((u) => u !== r);
    if (!others.length) return null;
    const keys = new Set(others.flatMap((u) => byAuthor[u] || []));
    const ids = [...keys].filter((k) => k !== '#t' && blocks[k]);
    const gone = [...keys].filter((k) => k !== '#t' && !blocks[k]).length;
    const n = ids.length + gone;
    const w = who(others.map(name));
    let body;
    if (n === 0) body = `${w} · הכותרת השתנתה`;
    else if (n === 1 && ids.length === 1 && clean(blocks[ids[0]].text)) body = `${w}: ${clean(blocks[ids[0]].text).slice(0, 90)}`;
    else if (n === 1) body = `${w} · שורה אחת השתנתה`;
    else body = `${w} · ${n} שורות השתנו`;
    return send(r, { kind: 'edit', note: noteId, folder: note.folder || '', title: title || 'פתק משותף', body, who: w });
  }));
}

exports.flush = onSchedule({ schedule: 'every 1 minutes', timeZone: 'Asia/Jerusalem', retryCount: 0 }, async () => {
  const due = await db.collection('pending').where('due', '<=', Timestamp.now()).limit(100).get();
  for (const d of due.docs) {
    // taken only if nothing joined it since it was read; otherwise the next tick sends it whole
    try { await d.ref.delete({ lastUpdateTime: d.updateTime }); } catch { continue; }
    try { await deliver(d.id, d.data()); } catch (e) { logger.error('deliver', d.id, e); }
  }
});

// ------------------------------------------------------------------ places search + kill switch
Object.assign(exports, require('./places'));

// ------------------------------------------------------------------ reminders

/**
 * A time reminder set or changed anywhere (the website, a phone) reaches every device of its
 * owner as a data message, so a phone whose app is asleep still arms the alarm for it.
 */
exports.reminderWritten = onDocumentWritten('users/{uid}/reminders/{id}', async (ev) => {
  const after = ev.data?.after;
  if (!after?.exists) return;
  const r = after.data();
  const data = { kind: 'rem', id: ev.params.id };
  for (const k of ['noteId', 'blockId', 'anchor', 'occ', 'zone', 'rule', 'until']) data[k] = r[k] == null ? '' : String(r[k]);
  for (const k of ['at', 'snoozeAt']) data[k] = r[k] == null ? '' : String(r[k]);
  for (const k of ['fired', 'sticky', 'deleted']) data[k] = r[k] ? '1' : '0';
  try {
    await send(ev.params.uid, { ...data, folder: '', note: `rem:${ev.params.id}` });
  } catch (e) {
    logger.warn('reminder push failed', e);
  }
});
Object.assign(exports, require('./account'));
