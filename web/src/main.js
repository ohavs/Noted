import { boot, onUser, signIn, logOut } from './fb.js';
import { S, start, stop, changed, COLORS, createNote, setMeta, toggleCheck, writeBlocks, deleteForever, createCategory, remId, deleteAccount } from './model.js';
import { h, $, icon, applyMotion, applyTheme, toast, sheet, confirmDialog, promptDialog, haptic, settings, animate, fmtRel } from './ui.js';
import { toHTML, clean, esc } from './rich.js';
import { openEditor } from './editor.js';
import { openReminder, startRinging, agenda, reminderChip, placeChip } from './reminders.js';
import { openScreens, connectScreen, addToScreen } from './screens.js';

export const CAT_ICONS = {
  folder: 'folder-simple', home: 'house', work: 'briefcase', ideas: 'lightbulb', heart: 'heart', trips: 'map-trifold', cart: 'shopping-cart',
  book: 'book-open', plane: 'airplane', sport: 'barbell', pets: 'paw-print', kids: 'baby', study: 'graduation-cap', code: 'code', art: 'palette',
  plant: 'plant', coffee: 'coffee', music: 'music-notes', film: 'film-slate', games: 'game-controller', health: 'first-aid', money: 'wallet',
  gift: 'gift', people: 'users', car: 'car', star: 'star', tag: 'tag', leaf: 'leaf',
};

export const ui = { view: 'all', query: '' };

// ------------------------------------------------------------------ boot
applyMotion();
applyTheme();
const app = $('#app');

(async () => {
  try { await boot(); } catch (e) {
    $('#gate').replaceChildren(gateCard('האתר לא הצליח להתחבר לענן', 'רעננו את הדף בעוד רגע.', null));
    return;
  }
  onUser((u) => {
    if (u) { start(u); showApp(); } else { stop(); showGate(); }
  });
  S.listeners.add(render);
  addEventListener('popstate', route);
})();

function gateCard(title, text, action) {
  return h('div.gate-card',
    h('div.gate-logo', 'noted'),
    h('h1.gate-title', title),
    h('p.gate-text', text),
    action,
    h('div.gate-links', h('a', { href: '/privacy' }, 'מדיניות פרטיות'), h('span', '·'), h('a', { href: 'https://github.com/ohavs/Noted/releases/latest' }, 'האפליקציה לאנדרואיד')),
  );
}

function showGate() {
  app.hidden = true;
  const btn = h('button.btn.google.press', { on: { click: async () => { haptic(); try { await signIn(); } catch (e) { toast('ההתחברות נכשלה — נסו שוב', { icon: 'warning-circle' }); } } } }, icon('google-logo', true), 'התחברות עם Google');
  const del = location.pathname.startsWith('/delete-account');
  $('#gate').replaceChildren(gateCard(
    del ? 'מחיקת החשבון' : 'הפתקים שלך, גם כאן',
    del ? 'מתחברים עם אותו חשבון Google שבאפליקציה, ואז אפשר למחוק את החשבון ואת כל מה שבענן.' : 'אותם פתקים, קטגוריות ותזכורות כמו בטלפון — מסונכרנים בזמן אמת. תזכורת שנקבעת כאן מצלצלת גם בטלפון ובשעון.',
    btn,
  ));
  $('#gate').hidden = false;
}

function showApp() {
  $('#gate').hidden = true;
  app.hidden = false;
  buildShell();
  route();
  startRinging();
}

function route() {
  const p = location.pathname;
  const m = /^\/screen\/([A-Z0-9-]{8,12})/i.exec(p);
  if (m) { history.replaceState(null, '', '/'); setTimeout(() => connectScreen(m[1].replace(/-/g, '').toUpperCase()), 300); }
  if (p.startsWith('/delete-account')) { ui.view = 'settings'; setTimeout(askDeleteAccount, 300); }
  if (p.startsWith('/settings')) ui.view = 'settings';
  render();
}

export function go(view) {
  if (ui.view === view) return;
  ui.view = view;
  haptic('tick');
  const main = $('#view');
  render();
  animate(main, [{ opacity: 0, transform: 'translateY(10px)' }, { opacity: 1, transform: 'none' }], 'glide');
  $('#main').scrollTo({ top: 0 });
  closeDrawer();
}

// ------------------------------------------------------------------ shell
function buildShell() {
  const u = S.user;
  app.replaceChildren(
    h('aside#side',
      h('div.brand', h('span.brand-name', 'noted'), h('button.icon-btn.press.only-narrow', { 'aria-label': 'סגירה', on: { click: closeDrawer } }, icon('x'))),
      h('nav#nav'),
      h('div.side-foot',
        h('button.me.press', { on: { click: () => go('settings') } },
          u.photoURL ? h('img.avatar', { src: u.photoURL, alt: '', referrerpolicy: 'no-referrer' }) : h('span.avatar', (u.displayName || u.email || '?')[0]),
          h('span.me-name', u.displayName || u.email),
          icon('gear'),
        ),
      ),
    ),
    h('div#drawer-scrim', { on: { click: closeDrawer } }),
    h('main#main',
      h('header.top',
        h('button.icon-btn.press.only-narrow', { 'aria-label': 'תפריט', on: { click: openDrawer } }, icon('list')),
        h('h1#title.giant'),
        h('label.search', icon('magnifying-glass'),
          h('input#q', { placeholder: 'חיפוש בפתקים', type: 'search', on: { input: (e) => { ui.query = e.target.value; renderView(); } } })),
        h('button#new.btn.accent.press', { on: { click: newNote } }, icon('plus'), h('span.hide-narrow', 'פתק חדש')),
      ),
      h('div#strip'),
      h('section#view'),
    ),
    h('button#fab.press', { 'aria-label': 'פתק חדש', on: { click: newNote } }, icon('plus')),
  );
  addEventListener('keydown', (e) => {
    if (e.target.closest('input, textarea, [contenteditable=true]')) return;
    if (e.key === 'n' || e.key === 'מ') { e.preventDefault(); newNote(); }
    if (e.key === '/') { e.preventDefault(); $('#q')?.focus(); }
  });
}
const openDrawer = () => { document.body.classList.add('drawer'); haptic(); };
const closeDrawer = () => document.body.classList.remove('drawer');

async function newNote() {
  haptic('medium');
  const cat = ui.view.startsWith('cat:') ? ui.view.slice(4) : '';
  const id = await createNote({ categoryId: cat });
  openEditor(id, $('#new')?.getBoundingClientRect(), { fresh: true });
}

// ------------------------------------------------------------------ data views
const live = () => [...S.notes.values()].filter((n) => !n.trashedAt && !n.archived);
const kids = (id) => S.cats.filter((c) => c.parent === id).map((c) => c.id);
function inView(n) {
  const v = ui.view;
  if (v.startsWith('cat:')) { const id = v.slice(4); return n.categoryId === id || kids(id).includes(n.categoryId); }
  if (v === 'shared') return n.shared;
  return true;
}
const byOrder = (a, b) => (b.pinned - a.pinned) || (a.position - b.position);
function matches(n, q) {
  if (!q) return true;
  q = q.trim().toLowerCase();
  return n.title.toLowerCase().includes(q) || n.blocks.some((b) => b.text.toLowerCase().includes(q));
}

function render(what) {
  if (app.hidden) return;
  if (what?.error) { toast('אין הרשאה לענן — נסו להתחבר מחדש', { icon: 'warning-circle' }); return; }
  renderNav();
  renderView();
}

function renderNav() {
  const nav = $('#nav');
  if (!nav) return;
  const count = (id) => live().filter((n) => n.categoryId === id || kids(id).includes(n.categoryId)).length;
  const item = (view, ic, label, n, sub = false) => h(`button.nav-item.press${ui.view === view ? '.on' : ''}${sub ? '.sub' : ''}`, { 'data-scale': '.97', on: { click: () => go(view) } },
    icon(ic, ui.view === view), h('span.nav-label', label), n != null && n > 0 ? h('span.nav-count', n) : null);
  const tops = S.cats.filter((c) => !c.parent);
  const remCount = [...S.reminders.values()].filter((r) => !r.fired || r.snoozeAt).length;
  nav.replaceChildren(
    item('all', 'notepad', 'כל הפתקים', live().length),
    h('div.nav-sec', 'קטגוריות', h('button.icon-btn.sm.press', { 'aria-label': 'קטגוריה חדשה', on: { click: newCategory } }, icon('plus'))),
    ...tops.flatMap((c) => [item('cat:' + c.id, CAT_ICONS[c.icon] || 'folder-simple', c.name, count(c.id)),
      ...S.cats.filter((k) => k.parent === c.id).map((k) => item('cat:' + k.id, CAT_ICONS[k.icon] || 'folder-simple', k.name, count(k.id), true))]),
    h('div.nav-sec', 'עוד'),
    item('reminders', 'bell-ringing', 'תזכורות', remCount),
    item('shared', 'users-three', 'משותף', live().filter((n) => n.shared).length),
    item('screens', 'television-simple', 'מסך תצוגה', S.screens.length),
    item('archive', 'archive', 'ארכיון', null),
    item('trash', 'trash', 'סל מחזור', null),
  );
  // the phone layout: a row of giant category names, like the app's header
  const strip = $('#strip');
  if (strip) {
    const chips = [['all', 'הכל'], ...tops.map((c) => ['cat:' + c.id, c.name])];
    strip.replaceChildren(...chips.map(([v, l]) => h(`button.strip-item.press${ui.view === v ? '.on' : ''}`, { on: { click: () => go(v) } }, l)));
  }
}

const TITLES = { all: 'כל הפתקים', reminders: 'תזכורות', shared: 'משותף', archive: 'ארכיון', trash: 'סל מחזור', screens: 'מסך תצוגה', settings: 'הגדרות' };
function renderView() {
  const view = $('#view');
  if (!view) return;
  const v = ui.view;
  const cat = v.startsWith('cat:') ? S.cats.find((c) => c.id === v.slice(4)) : null;
  $('#title').textContent = cat ? cat.name : (TITLES[v] || 'noted');
  document.title = `${$('#title').textContent} · noted`;
  if (!S.ready.notes) { view.replaceChildren(skeleton()); return; }
  if (v === 'reminders') return view.replaceChildren(agenda());
  if (v === 'screens') return view.replaceChildren(openScreens());
  if (v === 'settings') return view.replaceChildren(settingsView());
  let list;
  if (v === 'archive') list = [...S.notes.values()].filter((n) => n.archived && !n.trashedAt).sort((a, b) => b.updatedAt - a.updatedAt);
  else if (v === 'trash') list = [...S.notes.values()].filter((n) => n.trashedAt).sort((a, b) => b.trashedAt - a.trashedAt);
  else list = live().filter(inView).sort(byOrder);
  list = list.filter((n) => matches(n, ui.query));
  if (!list.length) return view.replaceChildren(empty(v));
  const pinned = v === 'all' || v.startsWith('cat:') ? list.filter((n) => n.pinned) : [];
  const rest = list.filter((n) => !pinned.includes(n));
  view.replaceChildren(...[
    v === 'trash' ? h('p.view-note', icon('info'), 'פתקים בסל נמחקים לגמרי אחרי 30 יום.') : null,
    pinned.length ? h('div.group-label', icon('push-pin', true), 'נעוצים') : null,
    pinned.length ? grid(pinned) : null,
    pinned.length && rest.length ? h('div.group-label', 'השאר') : null,
    rest.length ? grid(rest) : null,
  ].filter(Boolean));
}

function skeleton() { return h('div.grid', ...[0, 1, 2, 3, 4, 5].map((i) => h('div.card.skel', { style: { height: 140 + (i % 3) * 60 + 'px' } }))); }

function empty(v) {
  const t = {
    archive: ['הארכיון ריק', 'פתקים שהעברת לארכיון יחכו כאן.', 'archive'],
    trash: ['הסל ריק', 'פתקים שנמחקו נשמרים כאן 30 יום.', 'trash'],
    shared: ['אין עדיין פתקים משותפים', 'משתפים פתק מהאפליקציה בטלפון — והוא יופיע גם כאן.', 'users-three'],
  }[v] || (ui.query ? ['לא נמצא', `אין פתק עם „${ui.query}”.`, 'magnifying-glass'] : ['עוד אין כאן פתקים', 'פתק חדש — מקש N או הכפתור למעלה.', 'note-pencil']);
  return h('div.empty', h('div.empty-ico', icon(t[2])), h('h3', t[0]), h('p', t[1]));
}

// ------------------------------------------------------------------ cards
function grid(list) { return h('div.grid', ...list.map(card)); }

function card(n) {
  const rem = S.reminders.get(remId(n.id));
  const prem = S.placeRems.get(remId(n.id));
  const shown = n.blocks.filter((b) => b.type !== 'Check' || !b.done).slice(0, 9);
  const doneCount = n.blocks.filter((b) => b.type === 'Check' && b.done).length;
  const checks = n.blocks.filter((b) => b.type === 'Check');
  const el = h(`article.card.press`, { 'data-scale': '.985', tabindex: 0, style: { background: `var(--n-${n.color.toLowerCase()})` }, dataset: { id: n.id } },
    h('div.card-top',
      n.title ? h('h3.card-title', clean(n.title)) : null,
      h('div.card-flags',
        n.pinned ? icon('push-pin', true) : null,
        n.shared ? icon('users-three', true) : null,
        checks.length ? ring(checks.filter((b) => b.done).length / checks.length) : null,
      ),
    ),
    h('div.card-body', ...shown.map((b) => cardBlock(n, b))),
    doneCount ? h('div.card-more', `${doneCount} סומנו`) : null,
    rem || prem ? h('div.card-rems', rem ? reminderChip(rem) : null, prem ? placeChip(prem) : null) : null,
    n.shared ? avatars(n) : null,
  );
  el.addEventListener('click', (e) => { if (e.target.closest('.chk, a')) return; openEditor(n.id, el.getBoundingClientRect()); });
  el.addEventListener('keydown', (e) => { if (e.key === 'Enter') openEditor(n.id, el.getBoundingClientRect()); });
  el.addEventListener('contextmenu', (e) => { e.preventDefault(); noteMenu(n); });
  return el;
}

function ring(p) {
  const r = 9, c = 2 * Math.PI * r;
  const s = h('span.ring', { html: `<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="${r}" class="rb"/><circle cx="12" cy="12" r="${r}" class="rf" stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - p)}"/></svg>` });
  return s;
}

function avatars(n) {
  return h('div.avatars', ...Object.keys(n.members).slice(0, 4).map((uid) => {
    const p = n.people[uid] || {};
    return p.p ? h('img.av', { src: p.p, alt: p.n || '', title: p.n || '', referrerpolicy: 'no-referrer' }) : h('span.av', { title: p.n || '' }, (p.n || '?')[0]);
  }));
}

export const CHECK_SVG = '<svg viewBox="0 0 16 16"><path d="M3.5 8.5l3 3 6-7"/></svg>';

function cardBlock(n, b) {
  const html = toHTML(clean(b.text), b.spans);
  switch (b.type) {
    case 'H1': case 'H2': case 'H3': return h('div.cb-h', { html });
    case 'Quote': return h('div.cb-quote', { html });
    case 'Bullet': return h('div.cb-li', { style: { paddingInlineStart: 14 + b.indent * 16 + 'px' }, html: `<i class="dot"></i>${html}` });
    case 'Num': {
      let k = 1; const i = n.blocks.indexOf(b);
      for (let j = i - 1; j >= 0 && n.blocks[j].type === 'Num'; j--) if ((n.blocks[j].indent || 0) === (b.indent || 0)) k++;
      return h('div.cb-li.num', { style: { paddingInlineStart: b.indent * 16 + 'px' }, html: `<b class="n">${k}.</b> ${html}` });
    }
    case 'Check': {
      const row = h(`div.cb-check${b.done ? '.done' : ''}`, { style: { paddingInlineStart: b.indent * 18 + 'px' } },
        h('button.chk.press', { 'aria-label': 'סימון', 'data-scale': '.8', html: CHECK_SVG }),
        h('span', { html }),
        n.role === 'viewer' ? null : h('button.done-x.press', { 'aria-label': 'מחיקת השורה', 'data-scale': '.8', on: { click: (e) => { e.stopPropagation(); haptic('medium'); deleteLine(n, b); } } }, icon('x')));
      const chk = row.querySelector('.chk');
      longPress(chk, () => deleteLine(n, b), () => {
        if (n.role === 'viewer') { toast('יש לך הרשאת צפייה בפתק הזה', { icon: 'eye' }); return; }
        haptic(b.done ? 'light' : 'confirm');
        row.classList.toggle('done');
        toggleCheck(n, b.id).catch(() => toast('לא נשמר — אין חיבור?', { icon: 'warning-circle' }));
      });
      return row;
    }
    case 'Divider': return h('hr.cb-hr');
    case 'Images': return h('div.cb-media', icon('image'), 'תמונות');
    case 'Voice': return h('div.cb-media', icon('waveform'), 'הקלטה');
    case 'Draw': return h('div.cb-media', icon('pen-nib'), 'ציור');
    case 'Link': { let u = ''; try { u = new URL(JSON.parse(b.x).url).hostname.replace(/^www\./, ''); } catch { /* */ } return h('div.cb-media', icon('link'), u || 'קישור'); }
    case 'Place': { let nm = ''; try { nm = JSON.parse(b.x).name; } catch { /* */ } return h('div.cb-media', icon('map-pin'), nm || 'מקום'); }
    default: return clean(b.text).trim() ? h('div.cb-p', { html }) : null;
  }
}

/** Tap = [tap]; hold ~half a second = [hold] (with a firmer buzz). Used on checkboxes. */
export function longPress(el, hold, tap) {
  let t = 0, held = false;
  el.addEventListener('pointerdown', () => { held = false; t = setTimeout(() => { held = true; haptic('medium'); hold(); }, 520); });
  const cancel = () => clearTimeout(t);
  el.addEventListener('pointerup', cancel); el.addEventListener('pointerleave', cancel); el.addEventListener('pointercancel', cancel);
  el.addEventListener('click', (e) => { e.stopPropagation(); if (held) { held = false; return; } tap(); });
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}

/** Long press on a checkbox: the whole line goes (undo brings it back). */
export async function deleteLine(n, b) {
  if (n.role === 'viewer') { toast('יש לך הרשאת צפייה בפתק הזה', { icon: 'eye' }); return; }
  const fresh = S.notes.get(n.id) || n;
  await writeBlocks(fresh, [], [b.id]);
  toast('השורה נמחקה', { icon: 'trash', action: 'ביטול', onAction: () => writeBlocks(S.notes.get(n.id) || fresh, [b]) });
}

// ------------------------------------------------------------------ note menu
export function noteMenu(n) {
  const row = (ic, label, f, danger = false) => h(`button.menu-row.press${danger ? '.danger' : ''}`, { 'data-scale': '.98', on: { click: () => { s.close(); setTimeout(f, 120); } } }, icon(ic), h('span', label));
  const trashView = !!n.trashedAt;
  const s = sheet(clean(n.title) || 'פתק', h('div.menu',
    trashView ? [
      row('arrow-counter-clockwise', 'שחזור', async () => { await setMeta(n, { trashedAt: null }); toast('הפתק שוחזר', { icon: 'arrow-counter-clockwise' }); }),
      row('trash', 'מחיקה לצמיתות', async () => {
        if (await confirmDialog({ title: 'למחוק לצמיתות?', text: 'אי אפשר להחזיר את הפתק אחרי זה.', ok: 'מחיקה', danger: true, ic: 'trash' })) { await deleteForever(n); toast('נמחק', { icon: 'trash' }); }
      }, true),
    ] : [
      h('div.swatches', ...COLORS.map((c) => h(`button.swatch.press${n.color === c ? '.on' : ''}`, { 'aria-label': c, style: { background: `var(--n-${c.toLowerCase()})` }, on: { click: () => { haptic('tick'); setMeta(n, { color: c }); s.close(); } } }))),
      row(n.pinned ? 'push-pin-slash' : 'push-pin', n.pinned ? 'שחרור מהראש' : 'נעיצה בראש', () => setMeta(n, { pinned: !n.pinned })),
      row('bell', 'תזכורת', () => openReminder(n.id)),
      row('folder-simple', 'העברה לקטגוריה', () => moveNote(n)),
      row('television-simple', 'הצגה במסך', () => addToScreen(n)),
      row(n.archived ? 'tray-arrow-up' : 'archive', n.archived ? 'הוצאה מהארכיון' : 'ארכיון', () => { setMeta(n, { archived: !n.archived }); toast(n.archived ? 'הוצא מהארכיון' : 'הועבר לארכיון', { icon: 'archive', action: 'ביטול', onAction: () => setMeta(n, { archived: n.archived }) }); }),
      row('trash', 'העברה לסל', () => { setMeta(n, { trashedAt: Date.now() }); toast('הפתק הועבר לסל', { icon: 'trash', action: 'ביטול', onAction: () => setMeta(n, { trashedAt: null }) }); }, true),
    ],
  ));
}

export function moveNote(n) {
  const s = sheet('העברה ל…', h('div.menu', ...S.cats.map((c) => h(`button.menu-row.press${n.categoryId === c.id ? '.on' : ''}${c.parent ? '.sub' : ''}`, {
    on: { click: async () => { s.close(); await setMeta(n, { categoryId: c.id }); toast(`הועבר ל${c.name}`, { icon: 'folder-simple' }); } },
  }, icon(CAT_ICONS[c.icon] || 'folder-simple'), h('span', c.name), n.categoryId === c.id ? icon('check') : null))));
}

async function newCategory() {
  const name = await promptDialog({ title: 'קטגוריה חדשה', placeholder: 'שם הקטגוריה', ok: 'יצירה' });
  if (!name) return;
  if (S.cats.some((c) => c.name.trim() === name)) { toast('כבר יש קטגוריה בשם הזה', { icon: 'info' }); return; }
  const id = await createCategory(name);
  go('cat:' + id);
}

// ------------------------------------------------------------------ settings
function settingsView() {
  const seg = (key, opts, def) => h('div.seg', ...opts.map(([v, label]) => h(`button.seg-item.press${settings.get(key, def) === v ? '.on' : ''}`, {
    on: { click: () => { settings.set(key, v); applyTheme(); haptic('tick'); renderView(); } },
  }, label)));
  const u = S.user;
  const notif = 'Notification' in window ? Notification.permission : 'unsupported';
  return h('div.settings',
    h('section.set-card',
      h('div.set-me', u.photoURL ? h('img.avatar.lg', { src: u.photoURL, alt: '', referrerpolicy: 'no-referrer' }) : h('span.avatar.lg', (u.displayName || '?')[0]),
        h('div', h('b', u.displayName || ''), h('small', u.email || ''))),
      h('button.set-row.press', { on: { click: async () => { if (await confirmDialog({ title: 'להתנתק?', text: 'הפתקים נשארים בענן ובטלפון.', ok: 'התנתקות', ic: 'sign-out' })) logOut(); } } }, icon('sign-out'), h('span', 'התנתקות')),
      h('button.set-row.press.danger', { on: { click: askDeleteAccount } }, icon('user-circle-minus'), h('span', 'מחיקת החשבון')),
    ),
    h('h3.set-sec', 'מראה'),
    h('section.set-card', seg('theme', [['system', 'לפי המערכת'], ['light', 'בהיר'], ['dark', 'כהה']], 'system')),
    h('h3.set-sec', 'צבע הדגשה'),
    h('section.set-card', h('div.accents', ...['fire', 'forest', 'ink', 'plum'].map((a) => h(`button.accent-dot.press${settings.get('accent', 'fire') === a ? '.on' : ''}`, { 'data-a': a, 'aria-label': a, on: { click: () => { settings.set('accent', a); applyTheme(); renderView(); } } })))),
    h('h3.set-sec', 'גופן'),
    h('section.set-card', seg('font', [['soft', 'רך'], ['editorial', 'עיתונאי'], ['condensed', 'צפוף']], 'soft')),
    h('h3.set-sec', 'התראות בדפדפן'),
    h('section.set-card',
      h('p.set-text', 'כשהאתר פתוח, תזכורות מצלצלות גם כאן. הטלפון והשעון מצלצלים תמיד — גם כשהאתר סגור.'),
      notif === 'granted' ? h('div.set-ok', icon('check-circle', true), 'מאושר') :
        notif === 'unsupported' ? h('div.set-text', 'הדפדפן לא תומך בהתראות') :
          h('button.btn.dark.press', { on: { click: async () => { await Notification.requestPermission(); renderView(); } } }, icon('bell'), 'אישור התראות'),
    ),
    h('div.set-links', h('a', { href: '/privacy' }, 'מדיניות פרטיות'), h('a', { href: 'https://github.com/ohavs/Noted/releases/latest' }, 'האפליקציה לאנדרואיד')),
  );
}

async function askDeleteAccount() {
  const ok = await confirmDialog({
    title: 'למחוק את החשבון?', ic: 'user-circle-minus', danger: true, ok: 'מחיקה לצמיתות',
    text: 'כל מה שבענן נמחק לצמיתות: הפתקים, הקטגוריות, התזכורות, התמונות וההקלטות, והמסכים. מפתקים ששיתפו איתך יוצאים. פתקים שכבר שמורים בטלפון נשארים בו.',
  });
  if (!ok) return;
  toast('מוחק…', { icon: 'trash', ms: 60000 });
  try { await deleteAccount(); await logOut(); toast('החשבון נמחק', { icon: 'check-circle' }); history.replaceState(null, '', '/'); } catch { toast('המחיקה לא הצליחה — נסו שוב', { icon: 'warning-circle' }); }
}

export { render };
