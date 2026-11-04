// The cloud as the app keeps it (see app/.../sync/Sync.kt): notes/{id} with a `blocks` map,
// my categories, my time reminders, my TV screens. Live listeners feed `S`; writes follow the
// app's shapes exactly so the phone merges them like any other device.
import {
  collection, doc, query, where, onSnapshot, setDoc, updateDoc, deleteDoc, serverTimestamp,
  deleteField, FieldPath, getDoc,
} from 'firebase/firestore';
import { ref as sref, getDownloadURL } from 'firebase/storage';
import { httpsCallable } from 'firebase/functions';
import { fb } from './fb.js';
import { sameSpans } from './rich.js';
import { toLocal, parseRule, next, fromLocal } from './repeat.js';

export const S = {
  user: null,
  notes: new Map(),
  cats: [],
  reminders: new Map(),
  places: new Map(),
  placeRems: new Map(),
  screens: [],
  ready: { notes: false, cats: false },
  listeners: new Set(),
};
export const changed = (what) => S.listeners.forEach((f) => f(what));

export const COLORS = ['Butter', 'Peach', 'Rose', 'Lilac', 'Sky', 'Mint', 'Sage', 'Sand'];
export const COLOR_NAMES = { Butter: 'חמאה', Peach: 'אפרסק', Rose: 'ורד', Lilac: 'לילך', Sky: 'שמיים', Mint: 'מנטה', Sage: 'מרווה', Sand: 'חול' };
export const TEXT_TYPES = new Set(['P', 'H1', 'H2', 'H3', 'Quote', 'Bullet', 'Num', 'Check']);
const PERSONAL = new Set(['categoryId', 'pinned', 'favorite', 'archived', 'trashedAt', 'position']);
export const uuid = () => crypto.randomUUID();

let unsub = [];

export function start(user) {
  stop();
  S.user = user;
  const me = user.uid;
  const db = fb.db;
  unsub.push(onSnapshot(query(collection(db, 'notes'), where('memberIds', 'array-contains', me)), (s) => {
    for (const ch of s.docChanges()) {
      if (ch.type === 'removed') S.notes.delete(ch.doc.id);
      else { const n = parse(ch.doc, me); if (n) S.notes.set(n.id, n); else S.notes.delete(ch.doc.id); }
    }
    S.ready.notes = true;
    changed('notes');
  }, (e) => changed({ error: e })));
  unsub.push(onSnapshot(collection(db, 'users', me, 'categories'), (s) => {
    S.cats = s.docs.map((d) => ({ id: d.id, ...d.data() })).filter((c) => !c.deleted)
      .map((c) => ({ id: c.id, name: c.name || '', icon: c.icon || 'folder', position: c.position ?? 0, parent: c.parent || '' }))
      .sort((a, b) => a.position - b.position);
    S.ready.cats = true;
    changed('cats');
  }));
  unsub.push(onSnapshot(collection(db, 'users', me, 'reminders'), (s) => {
    S.reminders = new Map(s.docs.filter((d) => !d.get('deleted')).map((d) => [d.id, { id: d.id, ...d.data() }]));
    changed('reminders');
  }));
  // place reminders are set on the phone (geofences); the site shows them and can remove them
  unsub.push(onSnapshot(collection(db, 'users', me, 'places'), (s) => {
    S.places = new Map(s.docs.filter((d) => !d.get('deleted')).map((d) => [d.id, { id: d.id, ...d.data() }]));
    changed('reminders');
  }));
  unsub.push(onSnapshot(collection(db, 'users', me, 'placeReminders'), (s) => {
    S.placeRems = new Map(s.docs.filter((d) => !d.get('deleted')).map((d) => [d.id, { id: d.id, ...d.data() }]));
    changed('reminders');
  }));
  unsub.push(onSnapshot(query(collection(db, 'screens'), where('owner', '==', me)), (s) => {
    S.screens = s.docs.map((d) => ({ id: d.id, ...d.data() }));
    changed('screens');
  }));
  setDoc(doc(db, 'users', me), { name: user.displayName || '', email: user.email || '', photo: user.photoURL || null, seenAt: serverTimestamp() }, { merge: true }).catch(() => {});
}

export function stop() {
  unsub.forEach((u) => u());
  unsub = [];
  S.notes.clear(); S.cats = []; S.reminders.clear(); S.places.clear(); S.placeRems.clear(); S.screens = [];
  S.ready = { notes: false, cats: false };
}

/** A note document as [me] sees it (my personal fields if it's someone else's). */
function parse(d, me) {
  const x = d.data();
  if (!x.owner) return null;
  const mine = x.owner === me;
  const my = mine ? null : (x.u?.[me] || {});
  const pick = (k, def) => (mine ? (x[k] ?? def) : (my[k] ?? def));
  if (!mine && x.trashedAt) return null; // the owner binned it
  const blocks = Object.entries(x.blocks || {}).map(([id, b]) => ({
    id, o: Number(b.o) || 0, type: b.type || 'P', text: b.text || '', done: !!b.done, indent: b.indent || 0,
    spans: (b.spans || []).map((s) => ({ s: s.s, e: s.e, t: s.t })), x: b.x || '', by: b.by || '',
  })).sort((a, b) => a.o - b.o || (a.id < b.id ? -1 : 1));
  const members = x.members || { [x.owner]: 'owner' };
  return {
    id: d.id, owner: x.owner, mine, role: members[me] || (mine ? 'owner' : 'viewer'),
    title: x.title || '', color: x.color || 'Butter',
    categoryId: pick('categoryId', x.folder || ''), pinned: !!pick('pinned', false), favorite: !!pick('favorite', false),
    archived: !!pick('archived', false), trashedAt: pick('trashedAt', null), position: Number(pick('position', 0)) || 0,
    createdAt: x.createdAt || 0, updatedAt: x.updatedAt || 0,
    members, people: x.people || {}, folder: x.folder || '', blocks,
    shared: Object.keys(members).length > 1,
  };
}

export const canEdit = (n) => n.role !== 'viewer';
const me = () => S.user.uid;
const person = () => ({ n: S.user.displayName || (S.user.email || '').split('@')[0], p: S.user.photoURL || null });
const blockMap = (b) => ({ o: b.o, type: b.type, text: b.text, done: !!b.done, indent: b.indent || 0, spans: b.spans || [], x: b.x || '', by: me() });

/** Personal / shared meta fields (category, pin, …, title, colour). */
export async function setMeta(n, fields) {
  const args = [];
  let content = false;
  for (const [k, v] of Object.entries(fields)) {
    if (PERSONAL.has(k)) args.push(n.mine ? new FieldPath(k) : new FieldPath('u', me(), k), v);
    else if (canEdit(n)) { args.push(new FieldPath(k), v); content = true; }
  }
  if (content || n.mine) args.push(new FieldPath('updatedAt'), Date.now(), new FieldPath('by'), me(), new FieldPath('at'), serverTimestamp());
  if (args.length) await updateDoc(doc(fb.db, 'notes', n.id), ...args);
}

/** Changed blocks (and removed ids) of one note, in one write. */
export async function writeBlocks(n, changedBlocks, removedIds = []) {
  if (!canEdit(n) || (!changedBlocks.length && !removedIds.length)) return;
  const args = [];
  for (const b of changedBlocks) args.push(new FieldPath('blocks', b.id), blockMap(b));
  for (const id of removedIds) args.push(new FieldPath('blocks', id), deleteField());
  args.push(new FieldPath('updatedAt'), Date.now(), new FieldPath('by'), me(), new FieldPath('at'), serverTimestamp());
  await updateDoc(doc(fb.db, 'notes', n.id), ...args);
}

export const sameBlock = (a, b) => a.type === b.type && a.text === b.text && !!a.done === !!b.done && (a.indent || 0) === (b.indent || 0) && sameSpans(a.spans, b.spans) && (a.x || '') === (b.x || '') && a.o === b.o;

export async function createNote({ title = '', blocks = null, color = null, categoryId = '' } = {}) {
  const id = uuid();
  const now = Date.now();
  const list = blocks || [{ id: uuid(), type: 'P', text: '', done: false, indent: 0, spans: [], x: '' }];
  list.forEach((b, i) => { b.o = i + 1; });
  const minPos = Math.min(0, ...[...S.notes.values()].map((x) => x.position));
  const cat = S.cats.find((c) => c.id === categoryId);
  await setDoc(doc(fb.db, 'notes', id), {
    title, color: color || COLORS[Math.floor(Math.random() * COLORS.length)], categoryId: categoryId || S.cats[0]?.id || '',
    pinned: false, favorite: false, archived: false, trashedAt: null, position: minPos - 1, updatedAt: now,
    owner: me(), memberIds: [me()], members: { [me()]: 'owner' }, people: { [me()]: person() }, createdAt: now,
    blocks: Object.fromEntries(list.map((b) => [b.id, blockMap(b)])), by: me(), at: serverTimestamp(),
    ...(cat && !cat.parent && isFolder(cat.id) ? { folder: cat.id } : {}),
  });
  return id;
}
const isFolder = () => false; // shared folders are set up from the phone app

export async function deleteForever(n) {
  if (n.mine) await deleteDoc(doc(fb.db, 'notes', n.id));
  else await updateDoc(doc(fb.db, 'notes', n.id), new FieldPath('u', me(), 'trashedAt'), Date.now());
}

// ------------------------------------------------------------------ order + checklists

/** An order key between [a] and [b] (either may be missing). */
export function between(a, b) {
  if (a == null && b == null) return 1;
  if (a == null) return b - 1;
  if (b == null) return a + 1;
  return (a + b) / 2;
}

/** Port of sinkChecked (data/Blocks.kt): a ticked row sinks below the open ones of its list. */
export function sinkChecked(list, i) {
  const isC = (b) => b.type === 'Check';
  if (i < 0 || i >= list.length || !isC(list[i])) return list;
  const lvl = list[i].indent || 0;
  let s = i; while (s > 0 && isC(list[s - 1]) && (list[s - 1].indent || 0) >= lvl) s--;
  let e = i; while (e < list.length - 1 && isC(list[e + 1]) && (list[e + 1].indent || 0) >= lvl) e++;
  let k = s; while (k <= e && (list[k].indent || 0) > lvl) k++;
  const head = list.slice(s, k);
  const units = [];
  while (k <= e) { const st = k++; while (k <= e && (list[k].indent || 0) > lvl) k++; units.push([st, k]); }
  const mine = units.find(([a, b]) => i >= a && i < b);
  if (!mine) return list;
  const others = units.filter((u) => u !== mine);
  let at = list[i].done ? others.length : others.findIndex(([a]) => list[a].done);
  if (at < 0) at = others.length;
  const ordered = [...others]; ordered.splice(at, 0, mine);
  return [...list.slice(0, s), ...head, ...ordered.flatMap(([a, b]) => list.slice(a, b)), ...list.slice(e + 1)];
}

/** Port of Orders.assign: keeps the longest run of keys already in order, gives the rest new keys between their neighbours; returns those. */
export function reorder(list) {
  const n = list.length;
  const keys = list.map((b) => b.o);
  const tails = [], prev = new Array(n).fill(-1);
  for (let i = 0; i < n; i++) {
    let lo = 0, hi = tails.length;
    while (lo < hi) { const m = (lo + hi) >> 1; if (keys[tails[m]] < keys[i]) lo = m + 1; else hi = m; }
    if (lo > 0) prev[i] = tails[lo - 1];
    if (lo === tails.length) tails.push(i); else tails[lo] = i;
  }
  const keep = new Array(n).fill(false);
  let k = tails.length ? tails[tails.length - 1] : -1;
  while (k >= 0) { keep[k] = true; k = prev[k]; }
  const out = [];
  for (let i = 0; i < n; i++) {
    if (keep[i]) continue;
    let j = i; while (j < n && !keep[j]) j++;
    const a = i > 0 ? list[i - 1].o : null, b = j < n ? list[j].o : null, cnt = j - i;
    for (let t = 0; t < cnt; t++) {
      const o = a == null && b == null ? t + 1 : a == null ? b - (cnt - t) : b == null ? a + t + 1 : a + ((b - a) * (t + 1)) / (cnt + 1);
      list[i + t] = { ...list[i + t], o };
      out.push(list[i + t]);
    }
    i = j - 1;
  }
  return out;
}

export async function toggleCheck(n, blockId, to) {
  const list = n.blocks.map((b) => ({ ...b }));
  const i = list.findIndex((b) => b.id === blockId);
  if (i < 0) return;
  list[i].done = to ?? !list[i].done;
  const sunk = sinkChecked(list, i);
  const moved = reorder(sunk);
  const me2 = sunk.find((b) => b.id === blockId);
  const set = new Map([[me2.id, me2], ...moved.map((b) => [b.id, b])]);
  await writeBlocks(n, [...set.values()]);
}

// ------------------------------------------------------------------ categories

export async function createCategory(name, icon = 'folder', parent = '') {
  const id = uuid();
  const pos = Math.max(-1, ...S.cats.map((c) => c.position)) + 1;
  await setDoc(doc(fb.db, 'users', me(), 'categories', id), { name, icon, position: pos, parent, deleted: false });
  return id;
}

// ------------------------------------------------------------------ reminders

export const remId = (noteId, blockId = '') => (blockId ? `${noteId}:${blockId}` : noteId);

export async function setReminder(noteId, blockId, at, rep, sticky) {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  let first = at;
  if (rep.kind === 'days' && !rep.days.includes(at.getDay() === 0 ? 7 : at.getDay())) first = next(rep, at, new Date(at.getTime() - 1000)) || at;
  await setDoc(doc(fb.db, 'users', me(), 'reminders', remId(noteId, blockId)), {
    noteId, blockId, anchor: toLocal(at), occ: toLocal(first), zone, rule: ruleOfRep(rep),
    until: rep.until ? toLocal(rep.until).slice(0, 10) : null, fired: false, snoozeAt: null, sticky: !!sticky,
    at: Date.now(), deleted: false, by: 'web',
  });
}
import { ruleOf as ruleOfRep } from './repeat.js';

export async function removeReminder(id) {
  await setDoc(doc(fb.db, 'users', me(), 'reminders', id), { deleted: true, at: Date.now(), by: 'web' });
}

export async function removePlaceReminder(id) {
  await setDoc(doc(fb.db, 'users', me(), 'placeReminders', id), { deleted: true, at: Date.now(), by: 'web' });
}

export async function restorePlaceReminder(r) {
  const { id, ...rest } = r;
  await setDoc(doc(fb.db, 'users', me(), 'placeReminders', id), { ...rest, deleted: false, at: Date.now(), by: 'web' });
}

export async function patchReminder(r, fields) {
  await setDoc(doc(fb.db, 'users', me(), 'reminders', r.id), { ...strip(r), ...fields, at: Date.now(), by: 'web' });
}
const strip = (r) => { const { id, ...x } = r; return x; };

/** "Done": a one-shot goes (its line gets ticked); a series just quiets. */
export async function doneReminder(r) {
  if (parseRule(r.rule).kind !== 'none') return patchReminder(r, { snoozeAt: null });
  if (r.blockId) { const n = S.notes.get(r.noteId); if (n) await toggleCheck(n, r.blockId, true); }
  return removeReminder(r.id);
}

/** After ringing here: a series moves to its next occurrence, a one-shot is marked rung. */
export async function advanceReminder(r, now = Date.now()) {
  const rep = parseRule(r.rule, r.until);
  const fields = {};
  if (r.snoozeAt && r.snoozeAt <= now + 2000) fields.snoozeAt = null;
  const occ = fromLocal(r.occ, r.zone);
  if (!r.fired && occ && occ.getTime() <= now + 2000) {
    const anchor = fromLocal(r.anchor, r.zone) || occ;
    const nx = next(rep, anchor, new Date(Math.max(occ.getTime(), now)));
    if (nx) fields.occ = toLocal(nx); else fields.fired = true;
  }
  if (Object.keys(fields).length) await patchReminder(r, fields);
}

// ------------------------------------------------------------------ media

const urls = new Map();
/** Download URL of a media file: in the folder of whoever added it, else the owner's, else any member's. */
export async function mediaURL(n, b, name) {
  const key = name;
  if (urls.has(key)) return urls.get(key);
  if (!fb.storage) return null;
  const tries = [...new Set([b.by, n.owner, ...Object.keys(n.members)].filter(Boolean))];
  const p = (async () => {
    for (const u of tries) {
      try { return await getDownloadURL(sref(fb.storage, `users/${u}/media/${name}`)); } catch { /* next */ }
    }
    return null;
  })();
  urls.set(key, p);
  return p;
}

// ------------------------------------------------------------------ screens (TV)

export function screenCopy(n) {
  return {
    t: (n.title || '').trim(), c: n.color, u: n.updatedAt || Date.now(),
    b: n.blocks.filter((b) => TEXT_TYPES.has(b.type) || b.type === 'Divider')
      .map((b) => ({ type: b.type, text: b.text, done: b.done, indent: b.indent || 0, spans: b.spans || [] })),
  };
}

export async function saveScreen(code, { name, noteIds, theme }) {
  const notes = {};
  noteIds.forEach((id) => { const n = S.notes.get(id); if (n) notes[id] = screenCopy(n); });
  const ex = await getDoc(doc(fb.db, 'screens', code)).catch(() => null);
  if (ex?.exists() && ex.get('owner') !== me()) throw new Error('taken');
  await setDoc(doc(fb.db, 'screens', code), { owner: me(), name: name || 'מסך', noteIds, notes, theme: theme || 'auto', at: Date.now() });
}
export const deleteScreen = (code) => deleteDoc(doc(fb.db, 'screens', code));

// ------------------------------------------------------------------ account

export async function deleteAccount() {
  await httpsCallable(fb.fns, 'deleteAccount', { timeout: 300000 })();
}
