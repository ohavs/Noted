'use strict';
/* noted — interactive design prototype. Plain JS, no build step. */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const device = $('#device');
const root = document.documentElement;
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const lerp = (a, b, t) => a + (b - a) * t;
const pad = n => String(n).padStart(2, '0');

/* ================================================================ storage + settings */
const store = {
  get(k, d) { try { const v = localStorage.getItem('noted.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('noted.' + k, JSON.stringify(v)); } catch {} },
};
const settings = {
  theme: store.get('theme', 'system'),
  accent: store.get('accent', 'fire'),
  font: store.get('font', 'soft'),
  haptics: store.get('haptics', true),
  slow: store.get('slow', 1),
};
const saveSetting = (k, v) => { settings[k] = v; store.set(k, v); };

/* ================================================================ motion tokens
   Two springs are the only source of motion. CSS easings are generated from them
   (linear()), JS-driven gestures integrate the same physics. */
const MOTION = {
  touch: { stiffness: 520, damping: 25, mass: 1 }, // ζ≈0.55 — lively, for touch feedback
  glide: { stiffness: 230, damping: 30, mass: 1 }, // ζ≈1   — smooth, for layout and screens
};
const EASE = {};
function springCurve({ stiffness: k, damping: c, mass: m }) {
  let x = 0, v = 0, t = 0;
  const dt = 1 / 600, pts = [];
  while (t < 3) {
    const a = (-k * (x - 1) - c * v) / m;
    v += a * dt; x += v * dt; t += dt;
    pts.push(x);
    if (Math.abs(x - 1) < 0.001 && Math.abs(v) < 0.02) break;
  }
  const N = 60, out = [];
  for (let i = 0; i <= N; i++) out.push(+pts[Math.min(pts.length - 1, Math.round(i / N * (pts.length - 1)))].toFixed(4));
  out[0] = 0; out[N] = 1;
  return { easing: `linear(${out.join(', ')})`, dur: Math.round(t * 1000) };
}
function applyMotion() {
  for (const k of Object.keys(MOTION)) {
    const c = springCurve(MOTION[k]);
    EASE[k] = { easing: c.easing, dur: c.dur * settings.slow };
    root.style.setProperty(`--ease-${k}`, c.easing);
    root.style.setProperty(`--dur-${k}`, EASE[k].dur + 'ms');
  }
  root.style.setProperty('--dur-fast', 150 * settings.slow + 'ms');
}

class Spring {
  constructor(v, onUpdate) { this.v = v; this.vel = 0; this.target = v; this.onUpdate = onUpdate; this.raf = 0; this.res = null; }
  set(v) { this.stop(); this.v = v; this.vel = 0; this.target = v; this.onUpdate(v); }
  stop() { cancelAnimationFrame(this.raf); this.raf = 0; if (this.res) { const r = this.res; this.res = null; r(false); } }
  to(target, cfg = MOTION.glide, vel) {
    this.target = target; this.cfg = cfg;
    if (vel != null) this.vel = vel;
    if (this.res) { const r = this.res; this.res = null; r(false); }
    return new Promise(res => {
      this.res = res;
      if (!this.raf) { this.last = performance.now(); this.raf = requestAnimationFrame(this.tick); }
    });
  }
  tick = now => {
    const dt = Math.min(0.05, (now - this.last) / 1000) / settings.slow;
    this.last = now;
    const { stiffness: k, damping: c, mass: m = 1 } = this.cfg;
    const steps = Math.max(1, Math.ceil(dt * 480)), h = dt / steps;
    for (let i = 0; i < steps; i++) {
      const a = (-k * (this.v - this.target) - c * this.vel) / m;
      this.vel += a * h; this.v += this.vel * h;
    }
    if (Math.abs(this.v - this.target) < 0.0008 && Math.abs(this.vel) < 0.01) {
      this.v = this.target; this.vel = 0; this.raf = 0;
      this.onUpdate(this.v);
      const r = this.res; this.res = null; r && r(true);
      return;
    }
    this.onUpdate(this.v);
    this.raf = requestAnimationFrame(this.tick);
  };
}

/* velocity tracker for flings */
function tracker() {
  const pts = [];
  return {
    add(y) { const t = performance.now(); pts.push([t, y]); while (pts.length > 2 && t - pts[0][0] > 90) pts.shift(); },
    vel() { if (pts.length < 2) return 0; const [t0, y0] = pts[0], [t1, y1] = pts[pts.length - 1]; return t1 === t0 ? 0 : (y1 - y0) / ((t1 - t0) / 1000); },
  };
}

/* ================================================================ haptics */
function haptic(kind = 'light') {
  if (!settings.haptics || !navigator.vibrate) return;
  try { navigator.vibrate({ tick: 4, light: 9, medium: 15, heavy: 24 }[kind] || 9); } catch {}
}

/* ================================================================ theme */
const darkMQ = matchMedia('(prefers-color-scheme: dark)');
const effectiveTheme = () => settings.theme === 'system' ? (darkMQ.matches ? 'dark' : 'light') : settings.theme;
function applyTheme() {
  root.dataset.theme = effectiveTheme();
  root.dataset.accent = settings.accent;
  root.dataset.font = settings.font;
  const meta = $('meta[name="theme-color"]');
  if (meta) meta.content = getComputedStyle(root).getPropertyValue('--bg').trim();
}
function setTheme(mode, x, y) {
  const before = effectiveTheme();
  saveSetting('theme', mode);
  if (effectiveTheme() === before || !document.startViewTransition || x == null) { applyTheme(); return; }
  const t = document.startViewTransition(() => applyTheme());
  t.ready.then(() => {
    const r = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
    root.animate({ clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
      { duration: EASE.glide.dur * 1.6, easing: EASE.glide.easing, pseudoElement: '::view-transition-new(root)' });
  }).catch(() => {});
}
darkMQ.addEventListener('change', () => settings.theme === 'system' && applyTheme());

/* ================================================================ dates (local time) */
const DAYS = ['א׳', 'ב׳', 'ג׳', 'ד׳', 'ה׳', 'ו׳', 'ש׳'];
const DAYS_FULL = ['ראשון', 'שני', 'שלישי', 'רביעי', 'חמישי', 'שישי', 'שבת'];
const MONTHS = ['ינואר', 'פברואר', 'מרץ', 'אפריל', 'מאי', 'יוני', 'יולי', 'אוגוסט', 'ספטמבר', 'אוקטובר', 'נובמבר', 'דצמבר'];
const startOfDay = d => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n, d.getHours(), d.getMinutes());
const dayDiff = (a, b) => Math.round((startOfDay(a) - startOfDay(b)) / 864e5);
const hm = d => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const fmtShort = d => `${DAYS[d.getDay()]} ${d.getDate()}/${d.getMonth() + 1} ${hm(d)}`;
function fmtRel(d) {
  const dd = dayDiff(d, new Date());
  if (dd === 0) return `היום ${hm(d)}`;
  if (dd === 1) return `מחר ${hm(d)}`;
  if (dd > 1 && dd < 7) return `יום ${DAYS[d.getDay()]} ${hm(d)}`;
  return fmtShort(d);
}
function fmtIn(d) {
  const min = Math.round((d - new Date()) / 6e4);
  if (min < 0) return 'הזמן הזה כבר עבר';
  if (min < 1) return 'עכשיו';
  if (min < 60) return min === 1 ? 'בעוד דקה' : `בעוד ${min} דק׳`;
  const h = Math.round(min / 60);
  if (h < 24) return h === 1 ? 'בעוד שעה' : h === 2 ? 'בעוד שעתיים' : `בעוד ${h} שעות`;
  const days = Math.round(min / 1440);
  return days === 1 ? 'בעוד יום' : days === 2 ? 'בעוד יומיים' : `בעוד ${days} ימים`;
}
const at = (base, dayOff, h, m = 0) => { const d = addDays(startOfDay(base), dayOff); d.setHours(h, m, 0, 0); return d; };
const nextWeekday = (wd, h, m = 0) => { const now = new Date(); let off = (wd - now.getDay() + 7) % 7; const d = at(now, off, h, m); return d < now ? at(now, off + 7, h, m) : d; };

/* ================================================================ data */
const COLORS = ['butter', 'peach', 'rose', 'lilac', 'sky', 'mint', 'sage', 'sand'];
const COLOR_NAMES = { butter: 'חמאה', peach: 'אפרסק', rose: 'ורוד', lilac: 'לילך', sky: 'שמיים', mint: 'מנטה', sage: 'מרווה', sand: 'חול' };
const CATS = [
  { id: 'all', name: 'כל הפתקים' },
  { id: 'home', name: 'בית' },
  { id: 'work', name: 'עבודה' },
  { id: 'me', name: 'אישי' },
  { id: 'trips', name: 'טיולים' },
  { id: 'ideas', name: 'רעיונות' },
];
const TEXTY = new Set(['p', 'h1', 'h2', 'h3', 'quote', 'bullet', 'num', 'check']);
let uid = 1;
const bid = () => 'b' + (uid++);
const nid = () => 'n' + (uid++);
const P = html => ({ id: bid(), type: 'p', html });
const C = (html, done = false) => ({ id: bid(), type: 'check', html, done });
const B = html => ({ id: bid(), type: 'bullet', html });

const notes = [
  { id: nid(), cat: 'home', color: 'butter', title: 'קניות לשבת', pinned: true, reminder: nextWeekday(5, 10),
    blocks: [C('חלה ×2', true), C('עגבניות שרי', true), C('גבינה בולגרית', true), C('יין אדום'), C('פרחים לשולחן'), C('נרות'), C('קפה טחון')] },
  { id: nid(), cat: 'trips', color: 'mint', title: 'טיול בגליל העליון',
    blocks: [P('שלושה ימים בסוף החודש. לבדוק צימר עם ג׳קוזי ונוף לחרמון.'),
      { id: bid(), type: 'images', imgs: ['galil1', 'galil2', 'galil3', 'galil4'] },
      { id: bid(), type: 'h3', html: 'מסלולים' }, B('נחל עיון — מפל התנור'), B('הר מירון בזריחה'), C('להזמין צימר')] },
  { id: nid(), cat: 'work', color: 'sky', title: 'ישיבת צוות — יום ד׳', reminder: nextWeekday(3, 9, 30),
    blocks: [P('להציג את האב-טיפוס ולסגור לוח זמנים לרבעון.'), C('לשלוח סיכום לכולם', true), C('לתאם הדגמה עם דנה'), C('לעדכן את התקציב')] },
  { id: nid(), cat: 'me', color: 'lilac', title: 'רעיון למנגינה',
    blocks: [P('הקלטתי בדרך הביתה. הפזמון מתחיל בשנייה ה-20.'), { id: bid(), type: 'voice', dur: 47 }] },
  { id: nid(), cat: 'home', color: 'peach', title: 'שקשוקה של סבתא',
    blocks: [P('שש עגבניות בשלות, פלפל אדום, שום, כמון ופפריקה מעושנת. לבשל לאט עשרים דקות לפני הביצים.'),
      { id: bid(), type: 'quote', html: 'הסוד הוא סבלנות — לא לערבב אחרי הביצים.' }] },
  { id: nid(), cat: 'me', color: 'rose', title: 'ספרים לחורף', fav: true,
    blocks: [C('סיפור על אהבה וחושך', true), C('מאה שנים של בדידות', true), C('הנסיך הקטן (שוב)'), C('קפקא על החוף'), C('הזקן והים')] },
  { id: nid(), cat: 'work', color: 'sage', title: 'רעיונות לאפליקציה',
    blocks: [P('ערימת פתקים שנפרשת כמו קלפים, כותרת ענקית שמתחלפת בהחלקה.'), B('רטט עדין בכל פעולה'), B('בורר זמן עם ספרות ענק')] },
  { id: nid(), cat: 'home', color: 'sand', title: 'קוד לשער החניה', locked: true,
    blocks: [P('החל מינואר: 4471 ואז כוכבית.')] },
  { id: nid(), cat: 'home', color: 'sky', title: 'מתנה ליום הולדת לנועה', reminder: at(new Date(), 0, 20),
    blocks: [P('היא אמרה שהיא רוצה מצלמת פילם ישנה.'), C('לבדוק מחירים ביד שנייה'), C('להזמין עוגה')] },
];
let order = notes.map(n => n.id);
const byId = id => notes.find(n => n.id === id);
let trash = [];

const state = {
  tab: 'home', cat: 0, expanded: null,
  selecting: false, selected: new Set(),
  searching: false, query: '',
};

function visibleNotes() {
  let list = order.map(byId).filter(n => n && !n.archived && !n.trashed);
  if (state.searching) {
    const q = state.query.trim();
    if (!q) return [];
    return list.filter(n => (n.title + ' ' + n.blocks.map(b => plain(b.html || '')).join(' ')).includes(q));
  }
  const cat = CATS[state.cat].id;
  if (cat !== 'all') list = list.filter(n => n.cat === cat);
  return [...list.filter(n => n.pinned), ...list.filter(n => !n.pinned)];
}
const countIn = cid => notes.filter(n => !n.archived && !n.trashed && (cid === 'all' || n.cat === cid)).length;

/* ================================================================ html helpers */
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const tmp = document.createElement('div');
const plain = html => { tmp.innerHTML = html; return tmp.textContent; };
const noteVar = c => `var(--n-${c})`;
const CHECK_SVG = '<svg viewBox="0 0 16 16"><path d="M3.5 8.5l3 3 6-7"/></svg>';
const imgURL = seed => `https://picsum.photos/seed/${seed}/360/360`;

function progressOf(n) {
  const c = n.blocks.filter(b => b.type === 'check');
  return c.length ? { done: c.filter(b => b.done).length, total: c.length } : null;
}
function ringHTML(p) {
  const C = 2 * Math.PI * 18;
  return `<div class="ring"><svg viewBox="0 0 46 46"><circle class="track" cx="23" cy="23" r="18"/><circle class="bar" cx="23" cy="23" r="18" stroke-dasharray="${C}" stroke-dashoffset="${C * (1 - p.done / p.total)}"/></svg><b>${p.done}/${p.total}</b></div>`;
}
function waveBars(seed, n = 34) {
  let s = [...seed].reduce((a, c) => a + c.charCodeAt(0), 7), out = '';
  for (let i = 0; i < n; i++) { s = (s * 9301 + 49297) % 233280; const h = 18 + (s / 233280) * 82 * (0.55 + 0.45 * Math.sin(i / 3)); out += `<i style="height:${clamp(h, 14, 100)}%"></i>`; }
  return out;
}
const fmtDur = s => `${Math.floor(s / 60)}:${pad(s % 60)}`;
const voiceHTML = b => `<div class="voice" data-bid="${b.id}" data-dur="${b.dur}"><button class="voice-play press" data-act="play" aria-label="נגן"><i class="ph-fill ph-play"></i></button><div class="wave">${waveBars(b.id)}</div><span class="voice-time">${fmtDur(b.dur)}</span></div>`;
const imgsHTML = b => `<div class="img-strip">${b.imgs.map(s => `<div class="ph-img" style="background-image:url(${imgURL(s)})"></div>`).join('')}</div>`;
const linkHTML = b => { let host = b.url; try { host = new URL(b.url).hostname.replace(/^www\./, ''); } catch {} return `<a class="link-card" href="${esc(b.url)}" target="_blank" rel="noopener"><span class="lc-ico">${esc(host[0] || '•').toUpperCase()}</span><span class="lc-txt"><b>${esc(b.title || host)}</b><small>${esc(host)}</small></span><i class="ph ph-arrow-square-out"></i></a>`; };
const checkRowHTML = (b, editable) => `<div class="check-row ${b.done ? 'done' : ''}" data-bid="${b.id}"><button class="chk" tabindex="-1" data-act="check" aria-label="סימון">${CHECK_SVG}</button><div class="check-text${editable ? ' txt' : ''}"${editable ? ' contenteditable="true" data-ph="פריט"' : ''}>${b.html}</div></div>`;

/* ================================================================ cards */
const STEP = 118, HC = 196, GAP = 14, COMPRESS = 16;
const stackEl = $('#stack');
const homeScroll = $('#home-scroll');
const cardEls = new Map();

function snippetOf(n) {
  const b = n.blocks.find(b => TEXTY.has(b.type) && plain(b.html).trim());
  return b ? plain(b.html) : '';
}
function metaHTML(n) {
  const out = [];
  if (n.reminder) out.push(`<span><i class="ph ph-bell"></i>${fmtRel(n.reminder)}</span>`);
  const im = n.blocks.filter(b => b.type === 'images').reduce((a, b) => a + b.imgs.length, 0);
  if (im) out.push(`<span><i class="ph ph-image"></i>${im}</span>`);
  const v = n.blocks.find(b => b.type === 'voice');
  if (v) out.push(`<span><i class="ph ph-waveform"></i>${fmtDur(v.dur)}</span>`);
  if (n.blocks.some(b => b.type === 'draw')) out.push(`<span><i class="ph ph-scribble-loop"></i></span>`);
  return out.join('');
}
function fullHTML(n) {
  if (n.locked && !n.unlocked) {
    return `<div class="lock-panel"><button class="lock-btn press" data-act="unlock" aria-label="פתיחה בטביעת אצבע"><i class="ph ph-fingerprint"></i></button><span>הפתק נעול — נגיעה לפתיחה</span></div>`;
  }
  let h = '';
  let num = 0;
  for (const b of n.blocks) {
    if (b.type !== 'num') num = 0;
    switch (b.type) {
      case 'p': if (plain(b.html).trim()) h += `<p class="c-full-text">${b.html}</p>`; break;
      case 'h1': case 'h2': case 'h3': h += `<div class="c-full-h">${b.html}</div>`; break;
      case 'quote': h += `<div class="c-full-quote">${b.html}</div>`; break;
      case 'bullet': h += `<div class="c-full-li">${b.html}</div>`; break;
      case 'num': h += `<div class="c-full-li num" data-n="${++num}">${b.html}</div>`; break;
      case 'check': h += checkRowHTML(b, false); break;
      case 'images': h += imgsHTML(b); break;
      case 'voice': h += voiceHTML(b); break;
      case 'divider': h += '<hr class="c-hr">'; break;
      case 'draw': if (b.data) h += `<img class="c-draw" src="${b.data}" alt="">`; break;
      case 'link': h += linkHTML(b); break;
    }
  }
  if (n.reminder) h += `<button class="reminder-chip press" data-act="reminder"><i class="ph ph-bell-ringing"></i>${fmtRel(n.reminder)}${n.repeat && n.repeat !== 'none' ? ' · ' + REPEAT_NAMES[n.repeat] : ''}</button>`;
  h += `<div class="expand-hint" data-act="pull"><span class="grab"></span>משיכה למעלה לעריכה</div>`;
  return h;
}
function cardInner(n) {
  const p = progressOf(n);
  const locked = n.locked && !n.unlocked;
  const badges = [n.pinned && '<i class="ph-fill ph-push-pin"></i>', n.fav && '<i class="ph-fill ph-star"></i>', n.locked && `<i class="ph${locked ? '-fill' : ''} ph-lock${locked ? '' : '-simple-open'}"></i>`].filter(Boolean).join('');
  return `<div class="card-surface sq"><div class="c-inner">
    <div class="c-head"><div class="c-title">${esc(n.title || 'ללא כותרת')}</div><div class="c-badges">${badges}</div>${p && !locked ? ringHTML(p) : ''}</div>
    <div class="c-body">
      <div class="c-sum"><div class="c-snippet">${locked ? '••••••  ••••' : esc(snippetOf(n)) || '&nbsp;'}</div><div class="c-meta">${metaHTML(n)}</div></div>
      <div class="c-full">${fullHTML(n)}</div>
    </div></div><span class="sel-badge"><i class="ph-fill ph-check" style="font-size:15px"></i></span></div>`;
}
function makeCard(n) {
  const el = document.createElement('div');
  el.className = 'card';
  el.dataset.id = n.id;
  el.style.setProperty('--note', noteVar(n.color));
  el.innerHTML = cardInner(n);
  return el;
}
function refreshCard(n) {
  const el = cardEls.get(n.id);
  if (!el) return;
  el.style.setProperty('--note', noteVar(n.color));
  el.innerHTML = cardInner(n);
  el.classList.toggle('selected', state.selected.has(n.id));
}

let lastTops = [];
function measureFull(el) { return $('.c-inner', el).offsetHeight; }

function layout({ instant = false } = {}) {
  const list = visibleNotes();
  if (instant) stackEl.classList.add('no-anim');
  const ei = list.findIndex(n => n.id === state.expanded);
  let y = 0;
  const tops = [], heights = [];
  list.forEach((n, i) => {
    const el = cardEls.get(n.id);
    const h = i === ei ? Math.max(HC, measureFull(el)) : HC;
    tops.push(y); heights.push(h);
    if (ei >= 0 && i < ei) y += COMPRESS;
    else if (i === ei) y += h + GAP;
    else y += STEP;
  });
  const last = list.length - 1;
  const total = list.length ? tops[last] + heights[last] : 0;
  stackEl.style.height = Math.max(total + 150, list.length ? 0 : 420) + 'px';
  list.forEach((n, i) => {
    const el = cardEls.get(n.id);
    el.style.height = heights[i] + 'px';
    el.style.zIndex = i + 1;
    el.dataset.top = tops[i];
    el.dataset.depth = ei >= 0 && i < ei ? ei - i : 0;
    el.classList.toggle('expanded', i === ei);
  });
  lastTops = tops;
  applyPile();
  if (instant) { stackEl.offsetHeight; stackEl.classList.remove('no-anim'); }
}

/* cards that scroll past the top pile up behind the next one */
function applyPile() {
  const S = homeScroll.scrollTop - stackEl.offsetTop;
  const pileY = S + 18;
  for (const el of cardEls.values()) {
    if (!el.isConnected || el.dataset.top == null) continue;
    let d = +el.dataset.top;
    const depthAbove = +el.dataset.depth || 0;
    let s = depthAbove ? Math.max(.86, 1 - depthAbove * .035) : 1;
    let piled = false, op = 1;
    if (d < pileY) {
      const over = (pileY - d) / STEP;
      const k = Math.min(over, 3);
      d = pileY - k * 6;
      s = Math.min(s, 1 - k * .035);
      op = over > 3.5 ? Math.max(0, 1 - (over - 3.5)) : 1;
      piled = over > 0.02;
    }
    el.classList.toggle('piled', piled);
    el.style.transform = `translate3d(0, ${d}px, 0) scale(${s})`;
    el.style.opacity = op;
  }
}

function renderStack({ enter = false } = {}) {
  const list = visibleNotes();
  const ids = new Set(list.map(n => n.id));
  for (const [id, el] of cardEls) if (!ids.has(id)) { el.remove(); cardEls.delete(id); }
  list.forEach(n => {
    if (!cardEls.has(n.id)) { const el = makeCard(n); cardEls.set(n.id, el); stackEl.appendChild(el); }
  });
  stackEl.classList.toggle('selecting', state.selecting);
  renderEmpty(list.length === 0);
  layout({ instant: true });
  if (enter) {
    list.forEach((n, i) => {
      $('.card-surface', cardEls.get(n.id)).animate(
        [{ opacity: 0, transform: 'translateY(46px) scale(.96)' }, { opacity: 1, transform: 'none' }],
        { duration: EASE.glide.dur, easing: EASE.glide.easing, delay: i * 45 * settings.slow, fill: 'backwards' });
    });
  }
}

function renderEmpty(show) {
  let e = $('.empty', stackEl);
  if (!show) { e && e.remove(); return; }
  if (e) e.remove();
  const searching = state.searching;
  e = document.createElement('div');
  e.className = 'empty';
  e.innerHTML = searching
    ? `<svg viewBox="0 0 168 140" aria-hidden="true"><circle cx="74" cy="62" r="36" fill="none" stroke="var(--ink-3)" stroke-width="9"/><path d="M101 89l26 26" stroke="var(--ink-3)" stroke-width="11" stroke-linecap="round"/><path d="M60 62h28" stroke="var(--accent)" stroke-width="7" stroke-linecap="round"/></svg>
       <h3>${state.query.trim() ? 'לא מצאנו' : 'מה מחפשים?'}</h3><p>${state.query.trim() ? 'אין פתק שמכיל את המילים האלה. אולי בכתיב אחר?' : 'חיפוש בכותרות, בתוכן, בתמלולים ובטקסט מסריקות.'}</p>`
    : `<svg viewBox="0 0 168 140" aria-hidden="true">
        <rect x="30" y="58" width="108" height="66" rx="24" fill="var(--n-sand)" transform="rotate(-6 84 91)"/>
        <rect x="26" y="40" width="116" height="70" rx="26" fill="var(--n-mint)" transform="rotate(4 84 75)"/>
        <rect x="22" y="22" width="124" height="74" rx="28" fill="var(--n-butter)"/>
        <rect x="42" y="42" width="58" height="9" rx="4.5" fill="var(--note-ink)" opacity=".28"/>
        <rect x="42" y="60" width="36" height="9" rx="4.5" fill="var(--note-ink)" opacity=".14"/>
        <circle cx="122" cy="96" r="20" fill="var(--accent)"/><path d="M114 96h16M122 88v16" stroke="var(--accent-ink)" stroke-width="3.5" stroke-linecap="round"/></svg>
       <h3>ריק כאן, בינתיים</h3><p>פתק חדש ב״${esc(CATS[state.cat].name)}״ יופיע בראש הערימה.</p>
       <button class="btn btn-accent press sq" data-act="new"><i class="ph ph-plus"></i> פתק חדש</button>`;
  e.style.top = '10px';
  stackEl.appendChild(e);
  e.animate([{ opacity: 0, transform: 'translateY(20px)' }, { opacity: 1, transform: 'none' }], { duration: EASE.glide.dur, easing: EASE.glide.easing });
}

function expand(id) {
  const prev = state.expanded;
  state.expanded = prev === id ? null : id;
  haptic('light');
  layout();
  if (state.expanded) {
    // keep the expanded card in view, but never scroll the pile out of sight
    const el = cardEls.get(id);
    const top = stackEl.offsetTop + (+el.dataset.top);
    const bottom = top + el.offsetHeight;
    const viewB = homeScroll.scrollTop + homeScroll.clientHeight - 120;
    let target = homeScroll.scrollTop;
    if (bottom > viewB) target += bottom - viewB;
    target = Math.min(target, stackEl.offsetTop + lastTops[0] - 14 + (+el.dataset.top));
    if (top < homeScroll.scrollTop + 20) target = top - 70;
    if (Math.abs(target - homeScroll.scrollTop) > 2) homeScroll.scrollTo({ top: Math.max(0, target), behavior: 'smooth' });
  }
}

/* ================================================================ category title */
const track = $('#cat-track');
function renderCats() {
  track.innerHTML = CATS.map((c, i) => `<button class="cat-item${i === state.cat ? ' current' : ''}" data-i="${i}">${esc(c.name)}<span class="cat-count">${countIn(c.id)}</span></button>`).join('');
  renderCatSub();
}
function renderCatSub() {
  const list = visibleNotes();
  const pend = list.reduce((a, n) => { const p = progressOf(n); return a + (p ? p.total - p.done : 0); }, 0);
  const rem = list.filter(n => n.reminder && n.reminder > new Date()).length;
  const parts = [`${list.length} פתקים`];
  if (pend) parts.push(`${pend} פריטים פתוחים`);
  if (rem) parts.push(`${rem} תזכורות`);
  $('#cat-sub').innerHTML = parts.map(esc).join('<span class="dot"></span>');
}
function updateCounts() { $$('.cat-item', track).forEach((el, i) => $('.cat-count', el).textContent = countIn(CATS[i].id)); renderCatSub(); }
function nearestCat() {
  const tr = track.getBoundingClientRect();
  let best = 0, bd = Infinity;
  $$('.cat-item', track).forEach((el, i) => { const d = Math.abs(el.getBoundingClientRect().right - (tr.right - 16)); if (d < bd) { bd = d; best = i; } });
  return best;
}
let catTimer = 0;
track.addEventListener('scroll', () => {
  const i = nearestCat();
  $$('.cat-item', track).forEach((el, j) => el.classList.toggle('current', j === i));
  clearTimeout(catTimer);
  catTimer = setTimeout(() => { if (i !== state.cat) setCat(i); }, 90);
}, { passive: true });
track.addEventListener('click', e => {
  const it = e.target.closest('.cat-item'); if (!it) return;
  goCat(+it.dataset.i);
});
function goCat(i) {
  i = clamp(i, 0, CATS.length - 1);
  const el = $$('.cat-item', track)[i];
  const tr = track.getBoundingClientRect();
  track.scrollBy({ left: el.getBoundingClientRect().right - (tr.right - 16), behavior: 'smooth' });
}
let catVersion = 0;
function setCat(i) {
  if (i === state.cat) return;
  const v = ++catVersion;
  const dir = i > state.cat ? 1 : -1;
  state.cat = i; state.expanded = null;
  haptic('tick');
  const old = [...cardEls.values()];
  old.forEach((el, k) => $('.card-surface', el).animate(
    [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: `translateX(${-dir * 40}px) scale(.96)` }],
    { duration: EASE.glide.dur * .45, easing: 'ease-in', delay: Math.min(k, 5) * 18 * settings.slow, fill: 'forwards' }));
  const e = $('.empty', stackEl); e && e.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 150 * settings.slow, fill: 'forwards' });
  setTimeout(() => {
    if (v !== catVersion) return;
    for (const el of cardEls.values()) el.remove();
    cardEls.clear();
    renderStack({ enter: true });
    renderCatSub();
  }, (EASE.glide.dur * .3 + 60) * 1);
}

/* ================================================================ stack gestures */
let press = null;
const LONG = 430;
stackEl.addEventListener('contextmenu', e => e.preventDefault());
stackEl.addEventListener('pointerdown', e => {
  if (e.button > 0) return;
  const card = e.target.closest('.card');
  const act = e.target.closest('[data-act]');
  if (act && act.dataset.act === 'new') return;
  press = { x: e.clientX, y: e.clientY, t: performance.now(), card, act, id: card && card.dataset.id, moved: false, mode: null, pid: e.pointerId, inStrip: !!e.target.closest('.img-strip') };
  if (!card) return;
  if (act && act.dataset.act === 'pull' && card.classList.contains('expanded')) {
    press.mode = 'pull';
    card.setPointerCapture(e.pointerId);
    return;
  }
  if (!act) card.classList.add('pressing');
  if (!state.selecting) press.timer = setTimeout(() => startLift(press), LONG * settings.slow);
});
stackEl.addEventListener('pointermove', e => {
  if (!press || e.pointerId !== press.pid) return;
  const dx = e.clientX - press.x, dy = e.clientY - press.y;
  if (press.mode === 'pull') { pullMove(dy, e); return; }
  if (press.mode === 'lift' || press.mode === 'drag') { liftMove(e); return; }
  if (press.mode === 'swipe') { swipeMove(dx); return; }
  if (Math.hypot(dx, dy) > 9) {
    clearTimeout(press.timer);
    press.card && press.card.classList.remove('pressing');
    press.moved = true;
    if (!press.inStrip && Math.abs(dx) > Math.abs(dy) * 1.3 && !state.searching) {
      press.mode = 'swipe';
      stackEl.setPointerCapture(e.pointerId);
    }
  }
});
const endPress = e => {
  if (!press || e.pointerId !== press.pid) return;
  const p = press; press = null;
  clearTimeout(p.timer);
  p.card && p.card.classList.remove('pressing');
  if (p.mode === 'pull') return pullEnd(e.type === 'pointercancel');
  if (p.mode === 'lift' || p.mode === 'drag') return liftEnd(p);
  if (p.mode === 'swipe') return swipeEnd(e.clientX - p.x);
  if (e.type === 'pointercancel' || p.moved) return;
  if (p.act && p.act.dataset.act === 'new') return;
  tapCard(p, e);
};
stackEl.addEventListener('pointerup', endPress);
stackEl.addEventListener('pointercancel', endPress);
stackEl.addEventListener('click', e => { const a = e.target.closest('[data-act="new"]'); if (a) newNote(a); });

function tapCard(p, e) {
  if (!p.card) { if (state.expanded) expand(state.expanded); return; }
  const n = byId(p.id);
  if (state.selecting) { toggleSelect(n.id); return; }
  const expanded = p.card.classList.contains('expanded');
  if (expanded && p.act) {
    const a = p.act.dataset.act;
    if (a === 'check') { const row = p.act.closest('.check-row'); toggleCheck(n, row.dataset.bid, row); return; }
    if (a === 'play') { togglePlay(p.act.closest('.voice')); return; }
    if (a === 'reminder') { openTimePicker(n); return; }
    if (a === 'unlock') { unlock(n); return; }
    if (a === 'pull') { openEditor(n, { fromEl: p.card }); return; }
  }
  if (p.inStrip && expanded) return;
  if (expanded) {
    if (n.locked && !n.unlocked) { unlock(n); return; }
    openEditor(n, { fromEl: p.card });
  } else expand(n.id);
}

function toggleCheck(n, blockId, row) {
  const b = n.blocks.find(b => b.id === blockId);
  b.done = !b.done;
  row.classList.toggle('done', b.done);
  const chk = $('.chk', row);
  chk.classList.remove('breathe'); chk.offsetWidth; chk.classList.add('breathe');
  haptic(b.done ? 'medium' : 'light');
  const card = cardEls.get(n.id);
  const p = progressOf(n);
  const ring = card && $('.ring', card);
  if (ring && p) {
    const C = 2 * Math.PI * 18;
    $('.bar', ring).style.strokeDashoffset = C * (1 - p.done / p.total);
    $('b', ring).textContent = `${p.done}/${p.total}`;
    if (p.done === p.total) { ring.animate([{ transform: 'scale(1)' }, { transform: 'scale(1.18)' }, { transform: 'scale(1)' }], { duration: EASE.touch.dur, easing: EASE.touch.easing }); toast('כל הרשימה סומנה. יפה!', { icon: 'check' }); }
  }
  renderCatSub();
}

function unlock(n) {
  haptic('medium');
  const card = cardEls.get(n.id);
  const btn = card && $('.lock-btn', card);
  if (btn) btn.animate([{ transform: 'scale(1)' }, { transform: 'scale(.85)' }, { transform: 'scale(1)' }], { duration: EASE.touch.dur, easing: EASE.touch.easing });
  setTimeout(() => { n.unlocked = true; refreshCard(n); if (state.expanded !== n.id) state.expanded = n.id; layout(); haptic('light'); }, 380 * settings.slow);
}

/* voice playback (simulated) */
let playing = null;
function togglePlay(v) {
  if (!v) return;
  if (playing && playing.el === v) { stopPlay(); return; }
  stopPlay();
  const dur = +v.dataset.dur, bars = $$('.wave i', v), time = $('.voice-time', v);
  $('.voice-play i', v).className = 'ph-fill ph-pause';
  const t0 = performance.now();
  haptic('light');
  const tick = () => {
    const el = (performance.now() - t0) / 1000;
    const f = Math.min(1, el / dur);
    const on = Math.round(f * bars.length);
    bars.forEach((b, i) => b.classList.toggle('on', i < on));
    time.textContent = fmtDur(Math.floor(el));
    if (f >= 1) { stopPlay(); return; }
    playing.raf = requestAnimationFrame(tick);
  };
  playing = { el: v, raf: requestAnimationFrame(tick) };
}
function stopPlay() {
  if (!playing) return;
  cancelAnimationFrame(playing.raf);
  const v = playing.el; playing = null;
  $('.voice-play i', v).className = 'ph-fill ph-play';
  $$('.wave i', v).forEach(b => b.classList.remove('on'));
  $('.voice-time', v).textContent = fmtDur(+v.dataset.dur);
}

/* horizontal swipe on the stack = next / previous category */
function swipeMove(dx) {
  const max = 90;
  const t = Math.sign(dx) * max * (1 - Math.exp(-Math.abs(dx) / max));
  for (const el of cardEls.values()) $('.card-surface', el).style.transform = `translateX(${t}px)`;
}
function swipeEnd(dx) {
  for (const el of cardEls.values()) {
    const s = $('.card-surface', el);
    s.style.transform = '';
  }
  // RTL: next category sits to the left, so dragging right brings it in
  if (dx > 60) goCat(state.cat + 1);
  else if (dx < -60) goCat(state.cat - 1);
}

/* ================================================================ long press: lift, menu, reorder */
const scrim = $('#scrim');
const menu = $('#menu');
let lift = null;

function devRect(el) {
  const d = device.getBoundingClientRect(), r = el.getBoundingClientRect();
  return { top: r.top - d.top, left: r.left - d.left, width: r.width, height: r.height, right: r.right - d.left, bottom: r.bottom - d.top };
}

function startLift(p) {
  if (!p || !p.card || press !== p) return;
  p.mode = 'lift';
  p.card.classList.remove('pressing');
  const card = p.card;
  const r = devRect(card);
  const clone = document.createElement('div');
  clone.className = 'lift';
  clone.style.cssText = `top:${r.top}px;left:${r.left}px;width:${r.width}px;height:${r.height}px;`;
  clone.style.setProperty('--note', card.style.getPropertyValue('--note'));
  clone.innerHTML = card.innerHTML;
  if (card.classList.contains('expanded')) clone.classList.add('lift-exp');
  clone.style.position = 'absolute';
  clone.style.transform = '';
  device.appendChild(clone);
  card.classList.add('ghosted');
  lift = { clone, card, id: p.id, r0: r, y0: p.y, spring: null, scale: 1, dy: 0 };
  lift.spring = new Spring(0, v => { lift.scale = 1 + .045 * v; drawLift(); });
  lift.spring.to(1, MOTION.touch);
  scrim.classList.add('show', 'blur');
  haptic('heavy');
  openMenu(lift);
  try { stackEl.setPointerCapture(p.pid); } catch {}
}
function drawLift() { if (lift) lift.clone.style.transform = `translate3d(0, ${lift.dy}px, 0) scale(${lift.scale})`; }

function liftMove(e) {
  const p = press;
  const dy = e.clientY - p.y;
  if (p.mode === 'lift' && Math.abs(dy) > 10) {
    p.mode = 'drag';
    closeMenu();
    scrim.classList.remove('blur');
    if (state.expanded) { state.expanded = null; layout(); }
    lift.startTop = +lift.card.dataset.top;
    lift.ids = visibleNotes().map(n => n.id);
    lift.idx = lift.ids.indexOf(lift.id);
    // the clone was taken from an expanded card: shrink it to stack height
    lift.clone.classList.remove('lift-exp');
    lift.clone.style.transition = `height ${EASE.glide.dur}ms ${EASE.glide.easing}`;
    lift.clone.style.height = HC + 'px';
    lift.y0 = e.clientY;
  }
  if (p.mode !== 'drag') return;
  lift.dy = e.clientY - lift.y0;
  drawLift();
  const pos = lift.startTop + lift.dy;
  const to = clamp(Math.round(pos / STEP), 0, lift.ids.length - 1);
  if (to !== lift.idx) {
    lift.ids.splice(lift.idx, 1); lift.ids.splice(to, 0, lift.id); lift.idx = to;
    applyVisibleOrder(lift.ids);
    layout();
    haptic('tick');
  }
  autoScroll(e.clientY);
}
let asRaf = 0;
function autoScroll(y) {
  cancelAnimationFrame(asRaf);
  const r = homeScroll.getBoundingClientRect();
  const v = y > r.bottom - 110 ? 8 : y < r.top + 90 ? -8 : 0;
  if (!v || !lift) return;
  const step = () => {
    if (!lift || !press) return;
    homeScroll.scrollTop += v;
    lift.startTop += v; // keep finger-to-slot mapping as content moves under it
    lift.y0 -= 0;
    asRaf = requestAnimationFrame(step);
  };
  asRaf = requestAnimationFrame(step);
}
function applyVisibleOrder(ids) {
  // write the new relative order of visible ids back into the global order
  const set = new Set(ids);
  const slots = order.map((id, i) => set.has(id) ? i : -1).filter(i => i >= 0);
  slots.forEach((slot, k) => order[slot] = ids[k]);
}
function liftEnd(p) {
  cancelAnimationFrame(asRaf);
  if (p.mode === 'lift') return; // menu stays open; scrim tap closes
  settleLift();
}
function settleLift() {
  if (!lift) return;
  const L = lift; lift = null;
  const target = devRect(L.card);
  L.spring.stop();
  const cur = L.clone.getBoundingClientRect();
  const dev = device.getBoundingClientRect();
  L.clone.style.transition = '';
  const anim = L.clone.animate([
    { transform: `translate3d(0, ${L.dy}px, 0) scale(${L.scale})`, top: L.clone.style.top, height: L.clone.style.height },
    { transform: 'none', top: target.top + 'px', height: target.height + 'px' }],
    { duration: EASE.glide.dur, easing: EASE.glide.easing, fill: 'forwards' });
  void cur; void dev;
  scrim.classList.remove('show', 'blur');
  closeMenu();
  anim.onfinish = () => { L.card.classList.remove('ghosted'); L.clone.remove(); };
  haptic('light');
}
scrim.addEventListener('click', () => { if ($('#coach').classList.contains('show')) { $('#coach-ok').click(); return; } settleLift(); });

function openMenu(L) {
  const n = byId(L.id);
  $('#menu-swatches').innerHTML = COLORS.map(c => `<button class="swatch${c === n.color ? ' on' : ''}" style="--c:var(--n-${c})" data-c="${c}" aria-label="${COLOR_NAMES[c]}"></button>`).join('');
  const acts = [
    ['move', 'ph-folder-simple', 'קטגוריה'], ['remind', 'ph-bell', 'תזכורת'], ['share', 'ph-share-network', 'שיתוף'], ['pin', 'ph-push-pin', n.pinned ? 'שחרור' : 'נעיצה', n.pinned],
    ['fav', 'ph-star', 'מועדף', n.fav], ['lock', 'ph-lock', n.locked ? 'ביטול נעילה' : 'נעילה', n.locked], ['archive', 'ph-archive', 'ארכיון'], ['delete', 'ph-trash', 'מחיקה', false, 'danger'],
  ];
  $('#menu-grid').innerHTML = acts.map(([a, ic, l, on, cls]) => `<button class="menu-act press ${on ? 'on' : ''} ${cls || ''}" data-a="${a}"><span class="t sq"><i class="${on ? 'ph-fill' : 'ph'} ${ic}"></i></span>${l}</button>`).join('');
  menu.classList.remove('up');
  menu.style.top = menu.style.bottom = '';
  const mh = menu.offsetHeight || 330, H = device.clientHeight;
  const r = L.r0;
  if (r.bottom + 14 + mh < H - 10) menu.style.top = r.bottom + 14 + 'px';
  else if (r.top - 14 - mh > 10) { menu.style.top = r.top - 14 - mh + 'px'; menu.classList.add('up'); }
  else { menu.style.top = H - mh - 20 + 'px'; menu.classList.add('up'); }
  requestAnimationFrame(() => menu.classList.add('show'));
  menu.dataset.id = L.id;
}
function closeMenu() { menu.classList.remove('show'); }
$('#menu-swatches').addEventListener('click', e => {
  const s = e.target.closest('.swatch'); if (!s) return;
  const n = byId(menu.dataset.id);
  n.color = s.dataset.c;
  $$('.swatch', menu).forEach(x => x.classList.toggle('on', x === s));
  if (lift) lift.clone.style.setProperty('--note', noteVar(n.color));
  refreshCard(n);
  haptic('tick');
});
$('#menu-grid').addEventListener('click', e => {
  const b = e.target.closest('.menu-act'); if (!b) return;
  const n = byId(menu.dataset.id);
  const a = b.dataset.a;
  haptic('light');
  if (a === 'pin' || a === 'fav' || a === 'lock') {
    const key = a === 'pin' ? 'pinned' : a === 'fav' ? 'fav' : 'locked';
    n[key] = !n[key];
    if (a === 'lock') n.unlocked = false;
    settleLift(); refreshCard(n); layout();
    toast({ pinned: n.pinned ? 'ננעץ בראש הערימה' : 'השחרור בוצע', fav: n.fav ? 'נוסף למועדפים' : 'הוסר מהמועדפים', locked: n.locked ? 'הפתק ננעל' : 'הנעילה בוטלה' }[key], { icon: 'check' });
    return;
  }
  if (a === 'remind') { settleLift(); openTimePicker(n); return; }
  if (a === 'move') { settleLift(); openMoveSheet([n.id]); return; }
  if (a === 'share') { settleLift(); toast('לינק לשיתוף הועתק', { icon: 'ph-link' }); return; }
  if (a === 'archive') { settleLift(); removeNotes([n.id], 'archived'); return; }
  if (a === 'delete') { settleLift(); confirmDelete([n.id]); return; }
});
$('#menu-select').addEventListener('click', () => { const id = menu.dataset.id; settleLift(); startSelecting(id); });

/* ================================================================ remove / delete */
function removeNotes(ids, key) {
  const els = ids.map(id => cardEls.get(id)).filter(Boolean);
  els.forEach(el => $('.card-surface', el).animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(.9) translateY(-10px)' }], { duration: EASE.glide.dur * .6, easing: EASE.glide.easing, fill: 'forwards' }));
  ids.forEach(id => { const n = byId(id); n[key] = true; if (state.expanded === id) state.expanded = null; });
  setTimeout(() => { renderStack(); updateCounts(); }, EASE.glide.dur * .45);
  const label = key === 'archived' ? (ids.length > 1 ? `${ids.length} פתקים הועברו לארכיון` : 'הועבר לארכיון') : (ids.length > 1 ? `${ids.length} פתקים הועברו לסל` : 'הפתק הועבר לסל המחזור');
  toast(label, {
    icon: key === 'archived' ? 'ph-archive' : 'ph-trash', action: 'ביטול', ms: 5000,
    onAction: () => { ids.forEach(id => { byId(id)[key] = false; }); renderStack({ enter: true }); updateCounts(); haptic('light'); },
  });
}
async function confirmDelete(ids) {
  const one = ids.length === 1 ? byId(ids[0]) : null;
  const ok = await showDialog({
    icon: 'ph-trash', title: one ? 'להעביר לסל המחזור?' : `להעביר ${ids.length} פתקים לסל?`,
    text: 'אפשר לשחזר מהסל במשך 30 יום. אחר כך הם נמחקים לתמיד.',
    note: one, confirm: 'העבר לסל', cancel: 'השאר',
  });
  if (!ok) return;
  if (state.selecting) stopSelecting();
  removeNotes(ids, 'trashed');
}

/* ================================================================ multi-select */
function startSelecting(firstId) {
  state.selecting = true; state.expanded = null;
  state.selected = new Set(firstId ? [firstId] : []);
  for (const n of visibleNotes()) refreshCard(n);
  stackEl.classList.add('selecting');
  layout();
  $('#sel-header').classList.add('show');
  $('#selbar').classList.add('show');
  $('#nav').classList.add('away');
  $('#fab').style.opacity = 0; $('#fab').style.pointerEvents = 'none';
  updateSelCount();
  haptic('medium');
}
function stopSelecting() {
  state.selecting = false; state.selected.clear();
  stackEl.classList.remove('selecting');
  for (const el of cardEls.values()) el.classList.remove('selected');
  $('#sel-header').classList.remove('show');
  $('#selbar').classList.remove('show');
  $('#nav').classList.remove('away');
  $('#fab').style.opacity = ''; $('#fab').style.pointerEvents = '';
}
function toggleSelect(id) {
  state.selected.has(id) ? state.selected.delete(id) : state.selected.add(id);
  cardEls.get(id).classList.toggle('selected', state.selected.has(id));
  haptic('tick');
  updateSelCount();
}
function updateSelCount() { const k = state.selected.size; $('#sel-count').textContent = k === 0 ? 'בחירת פתקים' : k === 1 ? 'פתק אחד נבחר' : `${k} פתקים נבחרו`; }
$('#sel-close').addEventListener('click', stopSelecting);
$('#sel-all').addEventListener('click', () => { visibleNotes().forEach(n => { state.selected.add(n.id); cardEls.get(n.id).classList.add('selected'); }); updateSelCount(); haptic('light'); });
$('#selbar').addEventListener('click', e => {
  const b = e.target.closest('[data-sel]'); if (!b) return;
  const ids = [...state.selected];
  if (!ids.length) { toast('קודם בוחרים פתקים', { icon: 'ph-hand-tap' }); return; }
  const a = b.dataset.sel;
  if (a === 'delete') confirmDelete(ids);
  if (a === 'move') openMoveSheet(ids);
  if (a === 'color') openColorSheet(ids);
  if (a === 'pin') { const all = ids.every(id => byId(id).pinned); ids.forEach(id => byId(id).pinned = !all); stopSelecting(); ids.forEach(id => refreshCard(byId(id))); layout(); toast(all ? 'השחרור בוצע' : `${ids.length} נעוצים למעלה`, { icon: 'check' }); }
});

/* ================================================================ toast */
function toast(msg, { icon = 'check', action, onAction, ms = 2600 } = {}) {
  const box = $('#toasts');
  while (box.children.length > 1) box.firstChild.remove();
  const t = document.createElement('div');
  t.className = 'toast sq';
  const ico = icon === 'check' ? `<span class="ti">${CHECK_SVG}</span>` : `<span class="ti"><i class="ph ${icon}"></i></span>`;
  t.innerHTML = `${ico}<span class="msg">${esc(msg)}</span>${action ? `<button class="act press">${esc(action)}</button>` : ''}${action ? `<span class="timer" style="animation-duration:${ms * settings.slow}ms"></span>` : ''}`;
  box.appendChild(t);
  let gone = false;
  const kill = () => { if (gone) return; gone = true; t.classList.add('out'); setTimeout(() => t.remove(), EASE.glide.dur); };
  if (action) $('.act', t).addEventListener('click', () => { onAction && onAction(); kill(); });
  setTimeout(kill, ms * settings.slow);
  // flick away
  let sx = null;
  t.addEventListener('pointerdown', e => { sx = e.clientX; t.setPointerCapture(e.pointerId); t.style.transition = 'none'; });
  t.addEventListener('pointermove', e => { if (sx == null) return; t.style.transform = `translateX(${e.clientX - sx}px)`; t.style.opacity = 1 - Math.abs(e.clientX - sx) / 260; });
  t.addEventListener('pointerup', e => { if (sx == null) return; const dx = e.clientX - sx; sx = null; t.style.transition = ''; if (Math.abs(dx) > 80) { t.style.transform = `translateX(${Math.sign(dx) * 420}px)`; t.style.opacity = 0; gone = true; setTimeout(() => t.remove(), EASE.glide.dur); } else { t.style.transform = ''; t.style.opacity = ''; } });
  haptic('tick');
  return t;
}

/* ================================================================ dialog */
const dlg = $('#dialog'), dlgScrim = $('#dlg-scrim');
function showDialog({ icon = 'ph-question', danger = false, title, text, note, confirm = 'אישור', cancel = 'ביטול', input }) {
  return new Promise(res => {
    const art = `<div class="dlg-art sq ${danger ? 'danger' : ''}"><span class="lid"><i class="ph ${icon}"></i></span></div>`;
    const nb = note ? `<div class="dlg-note sq" style="--note:${noteVar(note.color)}"><i class="ph ph-note-blank"></i>${esc(note.title || 'ללא כותרת')}</div>` : '';
    const inp = input ? `<label class="dlg-input sq"><i class="ph ${input.icon || 'ph-link'}"></i><input type="${input.type || 'text'}" placeholder="${esc(input.placeholder || '')}" value="${esc(input.value || '')}" dir="auto"></label>` : '';
    dlg.innerHTML = `${art}<h3>${esc(title)}</h3>${text ? `<p>${esc(text)}</p>` : ''}${nb}${inp}<div class="dlg-actions"><button class="btn btn-block press sq ${danger ? 'btn-danger' : 'btn-dark'}" data-r="1">${esc(confirm)}</button><button class="btn btn-block btn-quiet press" data-r="0">${esc(cancel)}</button></div>`;
    dlgScrim.classList.add('show');
    requestAnimationFrame(() => dlg.classList.add('show'));
    haptic(danger ? 'medium' : 'light');
    const field = $('input', dlg);
    if (field) setTimeout(() => field.focus(), 120);
    const done = v => { dlg.classList.remove('show'); dlgScrim.classList.remove('show'); dlg.onclick = null; dlgScrim.onclick = null; res(v); };
    dlg.onclick = e => { const b = e.target.closest('[data-r]'); if (!b) return; done(b.dataset.r === '1' ? (field ? field.value.trim() : true) : false); };
    dlgScrim.onclick = () => done(false);
    if (field) field.onkeydown = e => { if (e.key === 'Enter') done(field.value.trim()); };
  });
}

/* ================================================================ sheets */
const sheetScrim = $('#sheet-scrim');
sheetScrim.style.transition = 'none';
const sheets = {};
function makeSheet(name, { onBeforeClose } = {}) {
  const el = $('#sheet-' + name);
  const s = { el, name, open: false, onBeforeClose };
  s.spring = new Spring(0, p => {
    const h = el.offsetHeight + 30;
    el.style.transform = `translateY(${(1 - p) * h}px)`;
    sheetScrim.style.opacity = clamp(p, 0, 1);
  });
  // drag to dismiss from the grabber and title
  let start = null, tr;
  const down = e => {
    if (e.target.closest('button,input,.days,.shortcuts,.clock,.cal')) return;
    start = { y: e.clientY, p: s.spring.v }; tr = tracker(); tr.add(e.clientY);
    el.setPointerCapture(e.pointerId); s.spring.stop();
  };
  const move = e => {
    if (!start) return;
    const dy = e.clientY - start.y; tr.add(e.clientY);
    const h = el.offsetHeight;
    let p = start.p - dy / h;
    if (p > 1) p = 1 + (p - 1) * .15;
    s.spring.set(p);
  };
  const up = async () => {
    if (!start) return;
    start = null;
    const v = tr.vel();
    const h = el.offsetHeight;
    if (v > 700 || s.spring.v < .6) await closeSheet(name, { vel: -v / h });
    else s.spring.to(1, MOTION.glide, -v / h);
  };
  $('.sheet-grab', el).addEventListener('pointerdown', down);
  $('.sheet-title', el) && $('.sheet-title', el).addEventListener('pointerdown', down);
  el.addEventListener('pointermove', move);
  el.addEventListener('pointerup', up);
  el.addEventListener('pointercancel', up);
  sheets[name] = s;
  s.spring.set(0);
  return s;
}
let topSheet = null;
function openSheet(name) {
  const s = sheets[name];
  if (topSheet && topSheet !== s) closeSheet(topSheet.name, { force: true });
  s.open = true; topSheet = s;
  if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
  hideSmartbar();
  sheetScrim.classList.add('show');
  s.el.style.visibility = '';
  haptic('light');
  return s.spring.to(1, MOTION.glide);
}
async function closeSheet(name, { vel, force } = {}) {
  const s = sheets[name];
  if (!force && s.onBeforeClose) {
    const ok = await s.onBeforeClose();
    if (!ok) { s.spring.to(1, MOTION.glide); return false; }
  }
  s.open = false;
  if (topSheet === s) topSheet = null;
  sheetScrim.classList.remove('show');
  await s.spring.to(0, MOTION.glide, vel);
  return true;
}
sheetScrim.addEventListener('click', () => topSheet && closeSheet(topSheet.name));

/* move-to-category + colour sheets share one sheet */
function openMoveSheet(ids) {
  $('#sheet-move .sheet-title').textContent = ids.length > 1 ? `להעביר ${ids.length} פתקים ל…` : 'להעביר ל…';
  $('#move-list').innerHTML = CATS.filter(c => c.id !== 'all').map(c => `<button class="row press" data-c="${c.id}"><i class="ph ${CAT_ICONS[c.id] || 'ph-folder-simple'}"></i>${esc(c.name)}<span class="end">${countIn(c.id)}</span></button>`).join('')
    + `<button class="row press" data-c="__new"><i class="ph ph-plus"></i>קטגוריה חדשה</button>`;
  $('#move-list').onclick = e => {
    const r = e.target.closest('[data-c]'); if (!r) return;
    if (r.dataset.c === '__new') { toast('יצירת קטגוריות — בשלב 3', { icon: 'ph-folder-simple' }); return; }
    ids.forEach(id => byId(id).cat = r.dataset.c);
    const name = CATS.find(c => c.id === r.dataset.c).name;
    closeSheet('move');
    if (state.selecting) stopSelecting();
    renderStack(); updateCounts();
    toast(ids.length > 1 ? `${ids.length} פתקים עברו ל״${name}״` : `עבר ל״${name}״`, { icon: 'ph-folder-simple' });
  };
  openSheet('move');
}
const CAT_ICONS = { home: 'ph-house', work: 'ph-briefcase', me: 'ph-heart', trips: 'ph-map-trifold', ideas: 'ph-lightbulb' };
function openColorSheet(ids) {
  $('#sheet-move .sheet-title').textContent = 'צבע לכל הנבחרים';
  $('#move-list').innerHTML = `<div class="color-grid">${COLORS.map(c => `<button class="color-tile press sq" style="--c:var(--n-${c})" data-col="${c}"><span></span>${COLOR_NAMES[c]}</button>`).join('')}</div>`;
  $('#move-list').onclick = e => {
    const r = e.target.closest('[data-col]'); if (!r) return;
    ids.forEach(id => { byId(id).color = r.dataset.col; refreshCard(byId(id)); });
    haptic('light');
    closeSheet('move'); stopSelecting();
  };
  openSheet('move');
}

/* ================================================================ time picker */
const REPEAT_NAMES = { none: 'ללא', day: 'כל יום', week: 'כל שבוע', month: 'כל חודש' };
const tp = { d: null, repeat: 'none', note: null, initial: '', calOpen: false, calMonth: null };
function defaultTime() {
  const now = new Date();
  return now.getHours() < 19 ? at(now, 0, 20) : at(now, 1, 8);
}
function shortcutsList() {
  const now = new Date();
  const inHour = new Date(Math.ceil((now.getTime() + 36e5) / 3e5) * 3e5);
  const tonight = now.getHours() < 20 ? at(now, 0, 20) : at(now, 1, 20);
  const nextSun = at(now, ((7 - now.getDay()) % 7) || 7, 9);
  return [
    { k: 'hour', ico: 'ph-clock-countdown', name: 'בעוד שעה', d: inHour, sub: hm(inHour) },
    { k: 'eve', ico: 'ph-moon-stars', name: now.getHours() < 20 ? 'הערב' : 'מחר בערב', d: tonight, sub: hm(tonight) },
    { k: 'morn', ico: 'ph-sun-horizon', name: 'מחר בבוקר', d: at(now, 1, 8), sub: '08:00' },
    { k: 'week', ico: 'ph-calendar-dots', name: 'בשבוע הבא', d: nextSun, sub: `${DAYS[0]} ${hm(nextSun)}` },
  ];
}
function openTimePicker(note) {
  tp.note = note;
  tp.d = note.reminder ? new Date(note.reminder) : defaultTime();
  tp.repeat = note.repeat || 'none';
  tp.initial = tp.d.getTime() + tp.repeat;
  tp.calOpen = false;
  $('#tp-cal').style.height = '0px'; $('#tp-cal').style.opacity = 0;
  $('#tp-days').style.display = '';
  $('#tp-month').classList.remove('open');
  renderShortcuts(); renderDays(); renderClock(false); renderRepeat(); updateTP();
  openSheet('time').then(() => scrollDayIntoView(false));
  requestAnimationFrame(() => scrollDayIntoView(false));
}
function renderShortcuts() {
  $('#tp-shorts').innerHTML = shortcutsList().map(s => `<button class="short press sq" data-k="${s.k}"><i class="ph ${s.ico}"></i><span><b>${s.name}</b><small>${s.sub}</small></span></button>`).join('');
}
$('#tp-shorts').addEventListener('click', e => {
  const b = e.target.closest('.short'); if (!b) return;
  const s = shortcutsList().find(x => x.k === b.dataset.k);
  setTP(s.d);
  haptic('medium');
});
function setTP(d, { roll = true } = {}) {
  const old = tp.d;
  tp.d = new Date(d);
  renderClock(roll, old);
  markDay(); scrollDayIntoView(true);
  if (tp.calOpen) renderCal();
  updateTP();
}
const DAYS_AHEAD = 120;
function renderDays() {
  const today = startOfDay(new Date());
  let h = '';
  for (let i = 0; i < DAYS_AHEAD; i++) {
    const d = addDays(today, i);
    h += `<button class="day press sq${i === 0 ? ' today' : ''}" data-off="${i}" data-m="${d.getMonth()}" data-y="${d.getFullYear()}"><small>${i === 0 ? 'היום' : DAYS[d.getDay()]}</small><b>${d.getDate()}</b></button>`;
  }
  $('#tp-days').innerHTML = h;
  markDay();
}
function markDay() {
  const off = dayDiff(tp.d, new Date());
  $$('.day', $('#tp-days')).forEach(el => el.classList.toggle('on', +el.dataset.off === off));
  updateMonthLabel();
}
function scrollDayIntoView(smooth) {
  const el = $('.day.on', $('#tp-days'));
  const strip = $('#tp-days');
  if (!el) return;
  const sr = strip.getBoundingClientRect(), r = el.getBoundingClientRect();
  if (r.left < sr.left + 20 || r.right > sr.right - 20) strip.scrollBy({ left: r.right - (sr.right - 80), behavior: smooth ? 'smooth' : 'auto' });
}
function updateMonthLabel() {
  let d = tp.d;
  if (!tp.calOpen) {
    const strip = $('#tp-days'), sr = strip.getBoundingClientRect();
    const first = $$('.day', strip).find(el => el.getBoundingClientRect().left < sr.right - 30);
    if (first) d = new Date(+first.dataset.y, +first.dataset.m, 1);
  } else d = tp.calMonth;
  $('#tp-month span').textContent = `${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}
$('#tp-days').addEventListener('scroll', () => requestAnimationFrame(updateMonthLabel), { passive: true });
$('#tp-days').addEventListener('click', e => {
  const b = e.target.closest('.day'); if (!b) return;
  const d = addDays(startOfDay(new Date()), +b.dataset.off);
  d.setHours(tp.d.getHours(), tp.d.getMinutes());
  tp.d = d; markDay(); updateTP(); haptic('tick');
});
$('#tp-month').addEventListener('click', () => {
  tp.calOpen = !tp.calOpen;
  const cal = $('#tp-cal');
  $('#tp-month').classList.toggle('open', tp.calOpen);
  haptic('light');
  if (tp.calOpen) {
    tp.calMonth = new Date(tp.d.getFullYear(), tp.d.getMonth(), 1);
    renderCal();
    $('#tp-days').style.display = 'none';
    cal.style.height = cal.scrollHeight + 'px'; cal.style.opacity = 1;
  } else {
    cal.style.height = '0px'; cal.style.opacity = 0;
    $('#tp-days').style.display = '';
    scrollDayIntoView(false);
  }
  updateMonthLabel();
});
function renderCal() {
  const m = tp.calMonth, today = startOfDay(new Date());
  const first = new Date(m.getFullYear(), m.getMonth(), 1);
  const days = new Date(m.getFullYear(), m.getMonth() + 1, 0).getDate();
  let h = `<div class="cal-nav"><button class="icon-btn press" data-nav="-1" aria-label="חודש קודם"><i class="ph ph-caret-right"></i></button><span>${MONTHS[m.getMonth()]} ${m.getFullYear()}</span><button class="icon-btn press" data-nav="1" aria-label="חודש הבא"><i class="ph ph-caret-left"></i></button></div>`;
  h += DAYS.map(d => `<div class="cal-head">${d}</div>`).join('');
  for (let i = 0; i < first.getDay(); i++) h += '<span></span>';
  for (let i = 1; i <= days; i++) {
    const d = new Date(m.getFullYear(), m.getMonth(), i);
    const cls = [d < today && 'past', +d === +today && 'today', +d === +startOfDay(tp.d) && 'on'].filter(Boolean).join(' ');
    h += `<button class="cal-day press ${cls}" data-d="${i}" ${d < today ? 'disabled' : ''}>${i}</button>`;
  }
  const cal = $('#tp-cal');
  cal.innerHTML = h;
  if (tp.calOpen) cal.style.height = cal.scrollHeight + 'px';
  updateMonthLabel();
}
$('#tp-cal').addEventListener('click', e => {
  const nav = e.target.closest('[data-nav]');
  if (nav) { tp.calMonth = new Date(tp.calMonth.getFullYear(), tp.calMonth.getMonth() + +nav.dataset.nav, 1); renderCal(); haptic('tick'); return; }
  const b = e.target.closest('.cal-day'); if (!b || b.disabled) return;
  const d = new Date(tp.calMonth.getFullYear(), tp.calMonth.getMonth(), +b.dataset.d, tp.d.getHours(), tp.d.getMinutes());
  tp.d = d; renderCal(); markDay(); updateTP(); haptic('tick');
});

/* giant digits: drag up/down, a tick per minute */
function rollDigits(el, text, dir) {
  const spans = $$('span', el);
  spans.slice(0, -1).forEach(s => s.remove());
  const old = spans[spans.length - 1];
  if (old && old.textContent === text) return;
  const nu = document.createElement('span');
  nu.textContent = text;
  el.appendChild(nu);
  if (!dir || !old) { old && old.remove(); return; }
  const o = { duration: EASE.touch.dur * .7, easing: EASE.touch.easing };
  nu.animate([{ transform: `translateY(${dir * 70}%)`, opacity: 0 }, { transform: 'none', opacity: 1 }], o);
  const a = old.animate([{ transform: 'none', opacity: 1 }, { transform: `translateY(${-dir * 70}%)`, opacity: 0 }], { ...o, fill: 'forwards' });
  a.onfinish = () => old.remove();
}
function renderClock(roll, old) {
  const dirH = roll && old ? Math.sign(tp.d.getHours() - old.getHours()) : 0;
  const dirM = roll && old ? Math.sign(tp.d.getMinutes() - old.getMinutes()) : 0;
  rollDigits($('#tp-h'), pad(tp.d.getHours()), dirH);
  rollDigits($('#tp-m'), pad(tp.d.getMinutes()), dirM);
}
function bump(unit, delta) {
  const old = new Date(tp.d);
  if (unit === 'h') tp.d.setHours((tp.d.getHours() + delta + 24) % 24);
  else tp.d.setMinutes((tp.d.getMinutes() + delta + 60) % 60);
  rollDigits($(unit === 'h' ? '#tp-h' : '#tp-m'), pad(unit === 'h' ? tp.d.getHours() : tp.d.getMinutes()), Math.sign(delta));
  void old;
  haptic('tick');
  updateTP();
}
function bindDigits(el, unit) {
  let sy = null, last = 0, moved = false;
  const px = unit === 'h' ? 18 : 9;
  el.addEventListener('pointerdown', e => { sy = e.clientY; last = 0; moved = false; el.setPointerCapture(e.pointerId); el.classList.add('active'); });
  el.addEventListener('pointermove', e => {
    if (sy == null) return;
    const steps = Math.trunc((sy - e.clientY) / px);
    if (steps !== last) { const d = steps - last; last = steps; moved = true; for (let i = 0; i < Math.abs(d); i++) bump(unit, Math.sign(d)); }
  });
  const up = e => {
    if (sy == null) return;
    el.classList.remove('active');
    if (!moved && e.type === 'pointerup') { const r = el.getBoundingClientRect(); bump(unit, e.clientY < r.top + r.height / 2 ? 1 : -1); }
    sy = null;
  };
  el.addEventListener('pointerup', up);
  el.addEventListener('pointercancel', up);
  el.addEventListener('wheel', e => { e.preventDefault(); bump(unit, e.deltaY < 0 ? 1 : -1); }, { passive: false });
}
bindDigits($('#tp-h'), 'h');
bindDigits($('#tp-m'), 'm');

function renderRepeat() {
  $$('#tp-repeat button').forEach(b => b.classList.toggle('on', b.dataset.r === tp.repeat));
  requestAnimationFrame(moveUline);
}
function moveUline() {
  const on = $('#tp-repeat button.on'), u = $('#tp-repeat .uline'), box = $('#tp-repeat');
  if (!on) return;
  const br = box.getBoundingClientRect(), r = on.getBoundingClientRect();
  u.style.left = '0px';
  u.style.width = (r.width - 20) + 'px';
  u.style.transform = `translateX(${r.left - br.left + 10}px)`;
}
$('#tp-repeat').addEventListener('click', e => {
  const b = e.target.closest('button'); if (!b) return;
  tp.repeat = b.dataset.r; renderRepeat(); updateTP(); haptic('tick');
});
function updateTP() {
  const past = tp.d < new Date();
  $('#tp-when').textContent = fmtShort(tp.d);
  const sub = $('#tp-sub');
  sub.textContent = fmtIn(tp.d) + (tp.repeat !== 'none' ? ` · ${REPEAT_NAMES[tp.repeat]}` : '');
  sub.style.color = past ? 'var(--danger)' : '';
  $('#tp-set').style.opacity = past ? .45 : 1;
  const sc = shortcutsList();
  $$('.short', $('#tp-shorts')).forEach(b => b.classList.toggle('on', +sc.find(s => s.k === b.dataset.k).d === +tp.d));
}
$('#tp-set').addEventListener('click', () => {
  if (tp.d < new Date()) { haptic('heavy'); $('#tp-set').animate([{ transform: 'translateX(0)' }, { transform: 'translateX(-8px)' }, { transform: 'translateX(8px)' }, { transform: 'translateX(0)' }], { duration: 300 }); toast('בוחרים זמן עתידי', { icon: 'ph-warning' }); return; }
  const n = tp.note;
  n.reminder = new Date(tp.d); n.repeat = tp.repeat;
  tp.initial = tp.d.getTime() + tp.repeat;
  closeSheet('time', { force: true });
  refreshCard(n); layout(); renderCatSub();
  if (ed.note === n) renderEdMeta();
  haptic('medium');
  toast(`תזכורת נקבעה · ${fmtRel(n.reminder)}`, { icon: 'ph-bell-ringing' });
});
makeSheet('time', {
  onBeforeClose: async () => {
    if (tp.d.getTime() + tp.repeat === tp.initial) return true;
    return showDialog({ icon: 'ph-bell-slash', title: 'לצאת בלי לקבוע?', text: 'הזמן שבחרת לא יישמר.', confirm: 'צא בלי לשמור', cancel: 'המשך לבחור' });
  },
});
makeSheet('settings');
makeSheet('move');

/* ================================================================ editor */
const editor = $('#editor');
const edTitle = $('#ed-title');
const edBlocks = $('#ed-blocks');
const edScroll = $('#ed-scroll');
const ed = { note: null, isNew: false, from: null, spring: null, p: 0, hist: [], hi: 0, original: '', flip: null };

ed.spring = new Spring(0, p => setMorph(p));

function edBg(color) { return `color-mix(in oklab, var(--n-${color}) 46%, var(--bg))`; }

function blockHTML(b) {
  const ph = { p: 'כותבים, או + להוספה', h1: 'כותרת', h2: 'כותרת משנה', h3: 'כותרת קטנה', quote: 'ציטוט', bullet: 'פריט', num: 'פריט' }[b.type];
  let body;
  switch (b.type) {
    case 'check': body = checkRowHTML(b, true); break;
    case 'images': body = imgsHTML(b); break;
    case 'voice': body = voiceHTML(b); break;
    case 'divider': body = '<hr>'; break;
    case 'link': body = linkHTML(b); break;
    case 'draw': body = `<div class="draw-wrap sq"><canvas class="draw-pad"></canvas><button class="draw-clear press" aria-label="ניקוי"><i class="ph ph-eraser"></i></button><span class="draw-hint">ציירו כאן באצבע</span></div>`; break;
    default: body = `<div class="txt" contenteditable="true" data-ph="${ph}">${b.html || ''}</div>`;
  }
  return `<div class="blk blk-${b.type}" data-id="${b.id}" data-type="${b.type}" data-indent="${b.indent || 0}"><span class="blk-handle" aria-label="גרירה"><i class="ph ph-dots-six-vertical"></i></span><div class="blk-body">${body}</div></div>`;
}
function renderBlocks() {
  edBlocks.innerHTML = ed.blocks.map(blockHTML).join('');
  $$('.blk-draw', edBlocks).forEach(initDraw);
}
function snapshot() { return JSON.stringify({ t: edTitle.textContent, c: ed.color, b: ed.blocks }); }
function restore(s) {
  const o = JSON.parse(s);
  edTitle.textContent = o.t; ed.blocks = o.b;
  if (o.c && o.c !== ed.color) setEdColor(o.c);
  renderBlocks();
}
function readDOM() {
  const old = new Map(ed.blocks.map(b => [b.id, b]));
  ed.blocks = $$('.blk', edBlocks).map(el => {
    const b = { ...(old.get(el.dataset.id) || { id: el.dataset.id }) };
    b.type = el.dataset.type;
    b.indent = +el.dataset.indent || 0;
    const t = $('.txt', el);
    if (t) { let h = t.innerHTML; if (h === '<br>') h = ''; b.html = h; }
    if (b.type === 'check') b.done = $('.check-row', el).classList.contains('done');
    return b;
  });
}
function renderEdMeta() {
  const n = ed.note;
  const cat = CATS.find(c => c.id === n.cat);
  const parts = [`<span>${esc(cat ? cat.name : '')}</span>`];
  if (n.reminder) parts.push(`<button class="ed-rem press" data-act="remind"><i class="ph ph-bell-ringing"></i>${fmtRel(n.reminder)}</button>`);
  parts.push(`<span class="saved-mark" id="saved-mark"><svg viewBox="0 0 16 16"><path d="M3 8.5l3 3 7-7.5"/></svg>נשמר</span>`);
  $('#ed-meta').innerHTML = parts.join('<span class="dot-sep"></span>');
  $('#ed-bell').innerHTML = `<i class="${n.reminder ? 'ph-fill' : 'ph'} ph-bell"></i>`;
}
$('#ed-meta').addEventListener('click', e => { if (e.target.closest('[data-act="remind"]')) { readDOM(); openTimePicker(ed.note); } });

let dirtyTimer = 0;
function markDirty() {
  clearTimeout(dirtyTimer);
  const m = $('#saved-mark'); m && m.classList.remove('show');
  dirtyTimer = setTimeout(() => {
    readDOM();
    const s = snapshot();
    if (s !== ed.hist[ed.hi]) { ed.hist = ed.hist.slice(0, ed.hi + 1); ed.hist.push(s); ed.hi = ed.hist.length - 1; }
    updateUndo();
    const m2 = $('#saved-mark'); m2 && m2.classList.add('show');
  }, 450);
}
function commitNow() { clearTimeout(dirtyTimer); readDOM(); const s = snapshot(); if (s !== ed.hist[ed.hi]) { ed.hist = ed.hist.slice(0, ed.hi + 1); ed.hist.push(s); ed.hi = ed.hist.length - 1; } updateUndo(); }
function updateUndo() { $('#ed-undo').disabled = ed.hi <= 0; $('#ed-redo').disabled = ed.hi >= ed.hist.length - 1; }
$('#ed-undo').addEventListener('click', () => { commitNow(); if (ed.hi > 0) { ed.hi--; restore(ed.hist[ed.hi]); updateUndo(); haptic('tick'); } });
$('#ed-redo').addEventListener('click', () => { if (ed.hi < ed.hist.length - 1) { ed.hi++; restore(ed.hist[ed.hi]); updateUndo(); haptic('tick'); } });

function setEdColor(c) {
  ed.color = c;
  editor.style.setProperty('--note', noteVar(c));
  editor.style.setProperty('--ed-bg', edBg(c));
}

function openEditor(n, { fromEl, isNew = false, interactive = false } = {}) {
  stopPlay();
  closeMenu();
  ed.note = n; ed.isNew = isNew; ed.fromEl = fromEl;
  ed.blocks = JSON.parse(JSON.stringify(n.blocks));
  if (!ed.blocks.length) ed.blocks.push(P(''));
  setEdColor(n.color);
  edTitle.textContent = n.title || '';
  renderBlocks();
  renderEdMeta();
  ed.hist = [snapshot()]; ed.hi = 0; ed.original = ed.hist[0];
  updateUndo();
  editor.classList.add('open');
  editor.style.pointerEvents = 'none';
  edScroll.scrollTop = 0;
  // flood starts as the colour of what we morph from
  const fromCard = fromEl && fromEl.classList.contains('card');
  $('.ed-flood', editor).style.background = fromCard ? noteVar(n.color) : 'var(--accent)';
  ed.from = devRect(fromEl);
  ed.fromRadius = parseFloat(getComputedStyle(fromCard ? $('.card-surface', fromEl) : fromEl).borderTopLeftRadius) || 24;
  computeFlip(fromCard ? $('.c-title', fromEl) : null);
  if (fromCard) fromEl.classList.add('ghosted');
  $('#nav').classList.add('away'); $('#fab').classList.add('low');
  haptic('medium');
  ed.spring.set(0);
  if (interactive) return;
  ed.spring.to(1, MOTION.glide).then(done => { if (done) afterOpen(); });
}
function afterOpen() {
  editor.style.clipPath = 'none';
  editor.style.pointerEvents = '';
  if (ed.isNew) { edTitle.focus(); }
}
function computeFlip(titleEl) {
  edTitle.style.transform = '';
  if (!titleEl) { ed.flip = null; return; }
  const a = devRect(titleEl), b = devRect(edTitle);
  const s = a.height / b.height;
  ed.flip = { dx: a.right - b.right, dy: a.top - b.top, s: clamp(s, .4, 1.2) };
}
function setMorph(p) {
  ed.p = p;
  const W = device.clientWidth, H = device.clientHeight, r = ed.from;
  if (!r) return;
  const q = 1 - p;
  const rad = lerp(ed.fromRadius, 0, p);
  editor.style.clipPath = `inset(${r.top * q}px ${(W - r.right) * q}px ${(H - r.bottom) * q}px ${r.left * q}px round ${rad}px)`;
  const flood = $('.ed-flood', editor);
  flood.style.opacity = clamp(1 - p * 1.25, 0, 1);
  const c = clamp((p - .35) / .65, 0, 1);
  $('#ed-top').style.opacity = c;
  const rest = [$('#ed-meta'), edBlocks];
  rest.forEach(el => { el.style.opacity = c; el.style.transform = `translateY(${q * 40}px)`; });
  if (ed.flip) {
    const f = ed.flip;
    edTitle.style.transform = `translate(${f.dx * q}px, ${f.dy * q}px) scale(${lerp(1, f.s, q)})`;
    edTitle.style.opacity = 1;
  } else {
    edTitle.style.transform = `translateY(${q * 40}px)`;
    edTitle.style.opacity = c;
  }
}

function isEmptyNote() {
  return !edTitle.textContent.trim() && ed.blocks.every(b => TEXTY.has(b.type) ? !plain(b.html || '').trim() : false);
}
async function closeEditor({ vel = 0, then } = {}) {
  if (!ed.note) return;
  hideSmartbar(); hideBubble(); closePop();
  if (document.activeElement && editor.contains(document.activeElement)) document.activeElement.blur();
  commitNow();
  const n = ed.note;
  const empty = isEmptyNote();
  // write back
  n.title = edTitle.textContent.trim();
  n.blocks = ed.blocks.filter(b => !(b.type === 'p' && !plain(b.html || '').trim() && ed.blocks.length > 1));
  n.color = ed.color;
  let target = ed.fromEl;
  if (ed.isNew) {
    if (empty) { n.trashed = true; }
    else {
      notes.push(n);
      order.unshift(n.id);
      const cat = CATS[state.cat].id;
      n.cat = cat === 'all' ? 'home' : cat;
      if (state.searching) closeSearch();
      homeScroll.scrollTop = 0;
      renderStack(); updateCounts();
      target = cardEls.get(n.id);
    }
  } else {
    refreshCard(n);
    layout({ instant: true });
    updateCounts();
  }
  if (target && target.classList.contains('card')) {
    target.classList.add('ghosted');
    ed.from = devRect(target);
    ed.fromRadius = parseFloat(getComputedStyle($('.card-surface', target)).borderTopLeftRadius) || 24;
    $('.ed-flood', editor).style.background = noteVar(n.color);
    edTitle.style.transform = '';
    computeFlip($('.c-title', target));
  } else if (target) {
    ed.from = devRect(target);
  }
  $('#nav').classList.remove('away'); $('#fab').classList.remove('low');
  const done = await ed.spring.to(0, MOTION.glide, vel);
  if (!done) return;
  editor.classList.remove('open');
  editor.style.clipPath = '';
  if (target && target.classList) target.classList.remove('ghosted');
  const wasNew = ed.isNew;
  ed.note = null;
  if (!empty) { haptic('light'); toast(wasNew ? 'פתק חדש נשמר' : 'נשמר', { icon: 'check', ms: 1500 }); }
  if (then) then(n);
}
$('#ed-back').addEventListener('click', () => closeEditor());

/* drag the editor down from its top bar to fold it back into the card */
(() => {
  const top = $('#ed-top');
  let s = null, tr;
  top.addEventListener('pointerdown', e => {
    if (e.target.closest('button')) return;
    s = { y: e.clientY, started: false }; tr = tracker(); tr.add(e.clientY);
    top.setPointerCapture(e.pointerId);
  });
  top.addEventListener('pointermove', e => {
    if (!s) return;
    const dy = e.clientY - s.y; tr.add(e.clientY);
    if (!s.started && dy > 8) {
      s.started = true;
      hideSmartbar(); hideBubble();
      if (document.activeElement) document.activeElement.blur();
      ed.spring.stop();
    }
    if (s.started) ed.spring.set(clamp(1 - dy / (device.clientHeight * .75), 0, 1));
  });
  const up = () => {
    if (!s) return;
    const st = s.started; s = null;
    if (!st) return;
    const v = tr.vel() / (device.clientHeight * .75);
    if (v > .9 || ed.p < .7) closeEditor({ vel: -v });
    else ed.spring.to(1, MOTION.glide, -v).then(d => d && afterOpen());
  };
  top.addEventListener('pointerup', up);
  top.addEventListener('pointercancel', up);
})();

/* pull an expanded card up into the editor */
let pull = null;
function pullMove(dy, e) {
  if (!pull) {
    if (dy > -6) return;
    const n = byId(press.id);
    pull = { tr: tracker() };
    openEditor(n, { fromEl: press.card, interactive: true });
  }
  pull.tr.add(e.clientY);
  ed.spring.set(clamp(-dy / 320, 0, 1));
}
function pullEnd(cancel) {
  if (!pull) { return; }
  const v = -pull.tr.vel() / 320; pull = null;
  if (!cancel && (ed.p > .3 || v > 1)) ed.spring.to(1, MOTION.glide, v).then(d => d && afterOpen());
  else closeEditor({ vel: v });
}

/* ---------------- editor: focus, keys, markdown shortcuts */
let focusedBlk = null;
edBlocks.addEventListener('focusin', e => {
  const blk = e.target.closest('.blk');
  setFocused(blk);
});
edBlocks.addEventListener('focusout', () => setTimeout(() => {
  if (!edBlocks.contains(document.activeElement)) { setFocused(null); }
}, 0));
edTitle.addEventListener('focus', () => { setFocused(null); showSmartbar('insert'); });
edTitle.addEventListener('blur', () => setTimeout(() => { if (!editor.contains(document.activeElement)) hideSmartbar(); }, 0));
edTitle.addEventListener('keydown', e => {
  if (e.key === 'Enter') { e.preventDefault(); const t = $('.txt', edBlocks); if (t) caret(t, false); }
});
edTitle.addEventListener('input', markDirty);

function setFocused(blk) {
  if (focusedBlk && focusedBlk !== blk) { focusedBlk.classList.remove('focused'); removePlus(); }
  focusedBlk = blk;
  if (blk) { blk.classList.add('focused'); refreshContext(); }
  else if (document.activeElement !== edTitle) hideSmartbar();
  updatePlus();
}
function caret(el, atEnd = true) {
  el.focus();
  const r = document.createRange();
  r.selectNodeContents(el); r.collapse(!atEnd ? true : false);
  const s = getSelection(); s.removeAllRanges(); s.addRange(r);
}
const typeOf = blk => blk && blk.dataset.type;
function insertBlockAfter(blk, b) {
  const wrap = document.createElement('div');
  wrap.innerHTML = blockHTML(b);
  const el = wrap.firstChild;
  if (blk) blk.after(el); else edBlocks.appendChild(el);
  if (b.type === 'draw') initDraw(el);
  return el;
}
function changeType(blk, type) {
  readDOM();
  const b = ed.blocks.find(x => x.id === blk.dataset.id);
  b.type = type;
  if (type === 'check' && b.done == null) b.done = false;
  const wrap = document.createElement('div');
  wrap.innerHTML = blockHTML(b);
  const el = wrap.firstChild;
  blk.replaceWith(el);
  const t = $('.txt', el);
  if (t) caret(t, true);
  markDirty();
  return el;
}
edBlocks.addEventListener('keydown', e => {
  const txt = e.target.closest('.txt'); if (!txt) return;
  const blk = txt.closest('.blk');
  const type = typeOf(blk);
  const sel = getSelection();
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    const isList = type === 'check' || type === 'bullet' || type === 'num';
    if (isList && !txt.textContent.trim()) { changeType(blk, 'p'); blk.dataset.indent = 0; return; }
    const r = sel.getRangeAt(0); r.deleteContents();
    const tail = document.createRange(); tail.selectNodeContents(txt); tail.setStart(r.startContainer, r.startOffset);
    const frag = tail.extractContents();
    const d = document.createElement('div'); d.appendChild(frag);
    let html = d.innerHTML; if (html === '<br>') html = '';
    const nb = { id: bid(), type: isList ? type : 'p', html, indent: isList ? +blk.dataset.indent : 0 };
    if (nb.type === 'check') nb.done = false;
    const el = insertBlockAfter(blk, nb);
    caret($('.txt', el), false);
    markDirty(); haptic('tick');
    return;
  }
  if (e.key === 'Backspace' && sel.isCollapsed) {
    const r = sel.getRangeAt(0);
    const pre = document.createRange(); pre.selectNodeContents(txt); pre.setEnd(r.startContainer, r.startOffset);
    if (pre.toString().length > 0) return;
    if (pre.cloneContents().querySelector && pre.cloneContents().querySelector('img')) return;
    e.preventDefault();
    if (type !== 'p') { changeType(blk, 'p'); return; }
    const prev = blk.previousElementSibling;
    const pt = prev && $('.txt', prev);
    if (pt) {
      const html = txt.innerHTML === '<br>' ? '' : txt.innerHTML;
      caret(pt, true);
      const mark = document.createElement('span'); mark.id = 'caret-mark';
      pt.appendChild(mark);
      if (html) mark.insertAdjacentHTML('afterend', html);
      const rr = document.createRange(); rr.setStartBefore(mark); rr.collapse(true);
      mark.remove();
      sel.removeAllRanges(); sel.addRange(rr);
      blk.remove();
      markDirty();
    } else if (prev && !txt.textContent) { blk.remove(); markDirty(); }
    return;
  }
  if (e.key === 'Tab') {
    e.preventDefault();
    indentBlk(blk, e.shiftKey ? -1 : 1);
  }
});
edBlocks.addEventListener('input', e => {
  const txt = e.target.closest('.txt'); if (!txt) return;
  const blk = txt.closest('.blk');
  const t = txt.textContent;
  if (typeOf(blk) === 'p') {
    const rules = [[/^[-*•]\s/, 'bullet'], [/^\[\s?\]\s/, 'check'], [/^1[.)]\s/, 'num'], [/^###\s/, 'h3'], [/^##\s/, 'h2'], [/^#\s/, 'h1'], [/^>\s/, 'quote']];
    for (const [re, type] of rules) {
      if (re.test(t)) {
        txt.textContent = t.replace(re, '');
        changeType(blk, type); haptic('tick');
        return;
      }
    }
    if (t === '---') { txt.textContent = ''; const nb = changeType(blk, 'divider'); const p = insertBlockAfter(nb, P('')); caret($('.txt', p)); return; }
  }
  detectTime(t);
  updatePlus();
  markDirty();
});
edBlocks.addEventListener('click', e => {
  const chk = e.target.closest('.chk');
  if (chk) {
    const row = chk.closest('.check-row');
    row.classList.toggle('done');
    chk.classList.remove('breathe'); chk.offsetWidth; chk.classList.add('breathe');
    haptic(row.classList.contains('done') ? 'medium' : 'light');
    markDirty();
    return;
  }
  const play = e.target.closest('[data-act="play"]');
  if (play) { togglePlay(play.closest('.voice')); return; }
});

/* "מחר ב-5" in the text → offer a reminder */
let suggested = '';
function detectTime(t) {
  const m = t.match(/(היום|מחר|מחרתיים)\s*(?:ב-?|בשעה\s*)(\d{1,2})(?::(\d{2}))?/);
  if (!m || m[0] === suggested) return;
  suggested = m[0];
  const note = ed.note;
  let h = +m[2]; const mi = +(m[3] || 0);
  if (h < 8 && !m[3]) h += 12;
  const d = at(new Date(), { 'היום': 0, 'מחר': 1, 'מחרתיים': 2 }[m[1]], h % 24, mi);
  toast(`להזכיר ${fmtRel(d)}?`, { icon: 'ph-sparkle', action: 'כן', ms: 5000, onAction: () => { const n = note; if (ed.note === n) readDOM(); n.reminder = d; if (ed.note === n) renderEdMeta(); refreshCard(n); layout(); haptic('medium'); toast(`תזכורת נקבעה · ${fmtRel(d)}`, { icon: 'ph-bell-ringing' }); } });
}

/* ---------------- + on an empty line */
function updatePlus() {
  removePlus();
  const blk = focusedBlk;
  if (!blk || typeOf(blk) !== 'p') return;
  const t = $('.txt', blk);
  if (t.textContent.length) return;
  const w = document.createElement('div');
  w.className = 'plus-wrap';
  w.innerHTML = `<button class="plus-btn" aria-label="הוספה"><i class="ph ph-plus"></i></button><div class="fan">
    <button data-ins="image" aria-label="תמונה"><i class="ph ph-image"></i></button>
    <button data-ins="draw" aria-label="ציור"><i class="ph ph-scribble-loop"></i></button>
    <button data-ins="check" aria-label="רשימה"><i class="ph ph-check-square"></i></button>
    <button data-ins="voice" aria-label="הקלטה"><i class="ph ph-microphone"></i></button></div>`;
  blk.appendChild(w);
  blk.classList.add('has-plus');
}
function removePlus() { $$('.plus-wrap', edBlocks).forEach(p => { p.parentElement.classList.remove('has-plus'); p.remove(); }); }
edBlocks.addEventListener('pointerdown', e => { if (e.target.closest('.plus-wrap')) e.preventDefault(); });
edBlocks.addEventListener('click', e => {
  const pb = e.target.closest('.plus-btn');
  if (pb) { const w = pb.parentElement; w.classList.toggle('open'); haptic('light'); return; }
  const ins = e.target.closest('[data-ins]');
  if (ins) { const blk = ins.closest('.blk'); removePlus(); doInsert(ins.dataset.ins, blk); }
});

/* ---------------- inserting elements */
function doInsert(kind, blk = focusedBlk) {
  readDOM();
  const emptyP = blk && typeOf(blk) === 'p' && !$('.txt', blk).textContent.trim();
  haptic('light');
  if (kind === 'check' || kind === 'bullet' || kind === 'num' || kind === 'h1' || kind === 'h2' || kind === 'h3' || kind === 'quote' || kind === 'p') {
    if (blk && TEXTY.has(typeOf(blk)) && (emptyP || kind !== 'check' || typeOf(blk) !== 'check')) { changeType(blk, kind); return; }
    const el = insertBlockAfter(blk, { id: bid(), type: kind, html: '', done: false });
    caret($('.txt', el)); markDirty(); return;
  }
  let nb;
  if (kind === 'image') { nb = { id: bid(), type: 'images', imgs: ['x' + uid, 'y' + uid, 'z' + uid].slice(0, 2 + (uid % 2)) }; toast('נוספו תמונות מהגלריה (הדמיה)', { icon: 'ph-image' }); }
  if (kind === 'voice') { nb = { id: bid(), type: 'voice', dur: 9 + (uid * 7) % 40 }; toast('ההקלטה נשמרה · התמלול יופיע כאן', { icon: 'ph-microphone' }); }
  if (kind === 'draw') nb = { id: bid(), type: 'draw' };
  if (kind === 'divider') nb = { id: bid(), type: 'divider' };
  if (kind === 'link') { askLink(blk); return; }
  if (!nb) return;
  let el;
  if (emptyP) { const w = document.createElement('div'); w.innerHTML = blockHTML(nb); el = w.firstChild; blk.replaceWith(el); if (nb.type === 'draw') initDraw(el); }
  else el = insertBlockAfter(blk || edBlocks.lastElementChild, nb);
  el.animate([{ opacity: 0, transform: 'translateY(16px) scale(.97)' }, { opacity: 1, transform: 'none' }], { duration: EASE.touch.dur, easing: EASE.touch.easing });
  if (!el.nextElementSibling || !$('.txt', el.nextElementSibling)) { const p = insertBlockAfter(el, P('')); caret($('.txt', p)); }
  else caret($('.txt', el.nextElementSibling), false);
  markDirty();
}
async function askLink(blk) {
  const sel = getSelection();
  const hasSel = sel.rangeCount && !sel.isCollapsed && edBlocks.contains(sel.anchorNode);
  const range = hasSel ? sel.getRangeAt(0).cloneRange() : null;
  const url = await showDialog({ icon: 'ph-link', title: hasSel ? 'קישור לטקסט' : 'הוספת קישור', text: hasSel ? '' : 'נציג כרטיס עם תצוגה מקדימה.', input: { placeholder: 'https://', type: 'url', value: '' }, confirm: 'הוסף', cancel: 'ביטול' });
  if (!url) return;
  const href = /^https?:\/\//.test(url) ? url : 'https://' + url;
  if (range) {
    sel.removeAllRanges(); sel.addRange(range);
    document.execCommand('createLink', false, href);
  } else {
    const el = insertBlockAfter(blk || edBlocks.lastElementChild, { id: bid(), type: 'link', url: href, title: '' });
    el.animate([{ opacity: 0, transform: 'translateY(16px)' }, { opacity: 1, transform: 'none' }], { duration: EASE.touch.dur, easing: EASE.touch.easing });
  }
  markDirty();
}
function indentBlk(blk, d) {
  if (!blk) return;
  blk.dataset.indent = clamp((+blk.dataset.indent || 0) + d, 0, 2);
  haptic('tick'); markDirty();
}

/* ---------------- drawing block */
function initDraw(blk) {
  const cv = $('.draw-pad', blk); if (!cv) return;
  const b = ed.blocks.find(x => x.id === blk.dataset.id) || {};
  const dpr = devicePixelRatio || 1;
  const size = () => { const r = cv.getBoundingClientRect(); cv.width = r.width * dpr; cv.height = r.height * dpr; };
  requestAnimationFrame(() => {
    size();
    const ctx = cv.getContext('2d');
    if (b.data) { const im = new Image(); im.onload = () => ctx.drawImage(im, 0, 0, cv.width, cv.height); im.src = b.data; blk.classList.add('drawn'); }
  });
  let last = null;
  cv.addEventListener('pointerdown', e => {
    cv.setPointerCapture(e.pointerId);
    const r = cv.getBoundingClientRect();
    last = [(e.clientX - r.left) * dpr, (e.clientY - r.top) * dpr];
    blk.classList.add('drawn');
  });
  cv.addEventListener('pointermove', e => {
    if (!last) return;
    const ctx = cv.getContext('2d'), r = cv.getBoundingClientRect();
    const p = [(e.clientX - r.left) * dpr, (e.clientY - r.top) * dpr];
    ctx.strokeStyle = getComputedStyle(editor).getPropertyValue('--note-ink');
    ctx.lineWidth = (e.pressure && e.pointerType === 'pen' ? 2 + e.pressure * 5 : 4) * dpr;
    ctx.lineCap = ctx.lineJoin = 'round';
    ctx.beginPath(); ctx.moveTo(...last); ctx.lineTo(...p); ctx.stroke();
    last = p;
  });
  const up = () => { if (!last) return; last = null; const bb = ed.blocks.find(x => x.id === blk.dataset.id); if (bb) bb.data = cv.toDataURL(); markDirtyKeep(); };
  cv.addEventListener('pointerup', up);
  cv.addEventListener('pointercancel', up);
  $('.draw-clear', blk).addEventListener('click', () => { cv.getContext('2d').clearRect(0, 0, cv.width, cv.height); const bb = ed.blocks.find(x => x.id === blk.dataset.id); if (bb) delete bb.data; blk.classList.remove('drawn'); haptic('light'); markDirtyKeep(); });
}
function markDirtyKeep() { /* drawing data lives on the model, not the DOM */ const s = snapshot(); if (s !== ed.hist[ed.hi]) { ed.hist = ed.hist.slice(0, ed.hi + 1); ed.hist.push(s); ed.hi = ed.hist.length - 1; } updateUndo(); const m = $('#saved-mark'); m && m.classList.add('show'); }

/* ---------------- block drag (handle) */
(() => {
  let d = null;
  edBlocks.addEventListener('pointerdown', e => {
    const h = e.target.closest('.blk-handle'); if (!h) return;
    e.preventDefault();
    const blk = h.closest('.blk');
    const items = $$('.blk', edBlocks);
    d = { blk, items, from: items.indexOf(blk), to: items.indexOf(blk), y: e.clientY, tops: items.map(x => x.offsetTop), hs: items.map(x => x.offsetHeight) };
    h.setPointerCapture(e.pointerId);
    blk.classList.add('dragging');
    haptic('medium');
  });
  edBlocks.addEventListener('pointermove', e => {
    if (!d) return;
    const dy = e.clientY - d.y;
    d.blk.style.transform = `translateY(${dy}px)`;
    const center = d.tops[d.from] + dy + d.hs[d.from] / 2;
    let to = 0;
    d.items.forEach((x, i) => { if (i !== d.from && d.tops[i] + d.hs[i] / 2 < center) to++; });
    if (to !== d.to) {
      d.to = to; haptic('tick');
      d.items.forEach((x, i) => {
        if (i === d.from) return;
        let s = 0;
        if (d.from < to && i > d.from && i <= to) s = -d.hs[d.from];
        if (to < d.from && i >= to && i < d.from) s = d.hs[d.from];
        x.style.transform = s ? `translateY(${s}px)` : '';
      });
    }
  });
  const up = () => {
    if (!d) return;
    const { blk, items, from, to, hs } = d; d = null;
    let off = 0;
    if (to > from) for (let i = from + 1; i <= to; i++) off += hs[i];
    if (to < from) for (let i = to; i < from; i++) off -= hs[i];
    blk.classList.remove('dragging');
    blk.style.transform = `translateY(${off}px)`;
    setTimeout(() => {
      items.forEach(x => { x.style.transition = 'none'; x.style.transform = ''; });
      const rest = items.filter(x => x !== blk);
      if (to >= rest.length) edBlocks.appendChild(blk); else edBlocks.insertBefore(blk, rest[to]);
      edBlocks.offsetHeight;
      items.forEach(x => { x.style.transition = ''; });
      if (to !== from) { haptic('light'); markDirty(); }
    }, EASE.glide.dur * .8);
  };
  edBlocks.addEventListener('pointerup', up);
  edBlocks.addEventListener('pointercancel', up);
})();

/* ---------------- smart toolbar */
const sb = $('#smartbar'), sbMain = $('#sb-main'), sbSub = $('#sb-sub');
let sbCtx = null, sbSubKind = null;
const SB = {
  insert: [['check', 'ph-check-square'], ['image', 'ph-image'], ['voice', 'ph-microphone'], ['draw', 'ph-scribble-loop'], ['link', 'ph-link'], '|', ['styles', 'ph-text-aa'], ['remind', 'ph-bell']],
  format: [['bold', 'ph-text-b'], ['italic', 'ph-text-italic'], ['underline', 'ph-text-underline'], ['strikeThrough', 'ph-text-strikethrough'], '|', ['styles', 'ph-text-aa'], ['colors', 'ph-palette'], ['link', 'ph-link']],
  list: [['outdent', 'ph-text-outdent flip'], ['indent', 'ph-text-indent flip'], ['togglecheck', 'ph-check-square'], '|', ['styles', 'ph-text-aa'], ['image', 'ph-image'], ['remind', 'ph-bell'], ['delline', 'ph-trash']],
};
const TEXT_COLORS = ['#E8613A', '#2E9E6E', '#4C6EF5', '#A855C7', '#C2410C'];
const HL_COLORS = ['rgba(255, 205, 0, .5)', 'rgba(64, 200, 120, .38)', 'rgba(255, 105, 150, .38)', 'rgba(80, 140, 255, .38)'];
function showSmartbar(ctx) {
  sb.classList.add('show');
  if (ctx !== sbCtx) {
    sbCtx = ctx;
    sbMain.innerHTML = `<div class="sb-row">${SB[ctx].map(x => x === '|' ? '<span class="sb-sep"></span>' : `<button class="sb-btn press" data-cmd="${x[0]}"><i class="ph ${x[1]}"></i></button>`).join('')}</div>`;
    closeSub();
  }
  syncFormatState();
}
function hideSmartbar() { sb.classList.remove('show', 'sub'); sbCtx = null; sbSubKind = null; }
function closeSub() { sb.classList.remove('sub'); sbSubKind = null; $$('.sb-btn[data-cmd="styles"], .sb-btn[data-cmd="colors"]', sbMain).forEach(b => b.classList.remove('on')); }
function openSub(kind) {
  if (sbSubKind === kind) { closeSub(); return; }
  sbSubKind = kind;
  $$('.sb-btn', sbMain).forEach(b => b.classList.toggle('on', b.dataset.cmd === kind));
  if (kind === 'styles') {
    sbSub.innerHTML = `<div class="sb-row">${[['p', 'ph-text-t'], ['h1', 'ph-text-h-one'], ['h2', 'ph-text-h-two'], ['h3', 'ph-text-h-three'], ['bullet', 'ph-list-bullets'], ['num', 'ph-list-numbers'], ['quote', 'ph-quotes'], ['divider', 'ph-minus']].map(([t, i]) => `<button class="sb-btn press${focusedBlk && typeOf(focusedBlk) === t ? ' on' : ''}" data-type="${t}"><i class="ph ${i}"></i></button>`).join('')}</div>`;
  } else {
    sbSub.innerHTML = `<div class="sb-row" style="justify-content:center"><span class="lbl">טקסט</span><button class="dotc" style="--c:var(--note-ink)" data-fc="reset" aria-label="ברירת מחדל"></button>${TEXT_COLORS.map(c => `<button class="dotc" style="--c:${c}" data-fc="${c}"></button>`).join('')}<span class="sb-sep" style="margin:0 6px"></span><span class="lbl">מרקר</span>${HL_COLORS.map(c => `<button class="dotc hl" style="--c:${c}" data-hl="${c}"></button>`).join('')}</div>`;
  }
  sb.classList.add('sub');
  haptic('light');
}
sb.addEventListener('pointerdown', e => e.preventDefault()); // keep caret & selection
sb.addEventListener('click', e => {
  const t = e.target.closest('[data-type]');
  if (t) {
    if (t.dataset.type === 'divider') { doInsert('divider'); closeSub(); return; }
    if (focusedBlk) changeType(focusedBlk, t.dataset.type); else doInsert(t.dataset.type);
    closeSub(); haptic('tick'); return;
  }
  const fc = e.target.closest('[data-fc]');
  if (fc) { document.execCommand('styleWithCSS', false, true); if (fc.dataset.fc === 'reset') document.execCommand('foreColor', false, getComputedStyle(editor).getPropertyValue('--note-ink').trim()); else document.execCommand('foreColor', false, fc.dataset.fc); haptic('tick'); markDirty(); return; }
  const hl = e.target.closest('[data-hl]');
  if (hl) { document.execCommand('styleWithCSS', false, true); document.execCommand('hiliteColor', false, hl.dataset.hl); haptic('tick'); markDirty(); return; }
  const b = e.target.closest('[data-cmd]'); if (!b) return;
  const c = b.dataset.cmd;
  if (['bold', 'italic', 'underline', 'strikeThrough'].includes(c)) { document.execCommand(c); haptic('tick'); syncFormatState(); markDirty(); return; }
  if (c === 'styles' || c === 'colors') { openSub(c); return; }
  if (c === 'remind') { readDOM(); openTimePicker(ed.note); return; }
  if (c === 'indent') { indentBlk(focusedBlk, 1); return; }
  if (c === 'outdent') { indentBlk(focusedBlk, -1); return; }
  if (c === 'togglecheck') {
    if (!focusedBlk) return;
    if (typeOf(focusedBlk) === 'check') { const row = $('.check-row', focusedBlk); row.classList.toggle('done'); const chk = $('.chk', row); chk.classList.remove('breathe'); chk.offsetWidth; chk.classList.add('breathe'); haptic('medium'); markDirty(); }
    else changeType(focusedBlk, 'check');
    return;
  }
  if (c === 'delline') { const blk = focusedBlk; if (!blk) return; const prev = blk.previousElementSibling; blk.animate([{ opacity: 1 }, { opacity: 0, transform: 'translateX(-30px)' }], { duration: 180 * settings.slow }).onfinish = () => { blk.remove(); markDirty(); const t = prev && $('.txt', prev); t && caret(t); }; haptic('medium'); return; }
  doInsert(c);
});
function syncFormatState() {
  if (sbCtx !== 'format') return;
  ['bold', 'italic', 'underline', 'strikeThrough'].forEach(c => { const b = $(`[data-cmd="${c}"]`, sbMain); b && b.classList.toggle('on', document.queryCommandState(c)); });
}
function refreshContext() {
  if (!ed.note || !editor.classList.contains('open')) return;
  const s = getSelection();
  const inEd = s.rangeCount && edBlocks.contains(s.anchorNode);
  if (!inEd && document.activeElement !== edTitle) return;
  if (inEd && !s.isCollapsed) showSmartbar('format');
  else if (focusedBlk && ['check', 'bullet', 'num'].includes(typeOf(focusedBlk))) showSmartbar('list');
  else showSmartbar('insert');
}

/* ---------------- selection bubble */
const bubble = $('#bubble');
const coarse = matchMedia('(pointer: coarse)').matches;
function hideBubble() { bubble.classList.remove('show'); }
document.addEventListener('selectionchange', () => {
  if (!ed.note) return;
  refreshContext();
  const s = getSelection();
  if (!s.rangeCount || s.isCollapsed || !edBlocks.contains(s.anchorNode)) { hideBubble(); return; }
  const rr = s.getRangeAt(0).getBoundingClientRect();
  const d = device.getBoundingClientRect();
  if (!bubble.children.length) {
    bubble.innerHTML = [['bold', 'ph-text-b'], ['link', 'ph-link'], ['copy', 'ph-copy'], ['tolist', 'ph-check-square']].map(([c, i]) => `<button data-b="${c}"><i class="ph ${i}"></i></button>`).join('');
  }
  const w = 4 * 46 + 3 * 8;
  const left = clamp(rr.left - d.left + rr.width / 2 - w / 2, 12, d.width - w - 12);
  // on touch the system menu sits above the selection, so the bubble goes under it
  const top = coarse ? rr.bottom - d.top + 14 : rr.top - d.top - 58;
  bubble.style.left = left + 'px';
  bubble.style.top = clamp(top, 70, d.height - 140) + 'px';
  bubble.classList.add('show');
});
bubble.addEventListener('pointerdown', e => e.preventDefault());
bubble.addEventListener('click', async e => {
  const b = e.target.closest('[data-b]'); if (!b) return;
  const c = b.dataset.b;
  haptic('tick');
  if (c === 'bold') { document.execCommand('bold'); markDirty(); }
  if (c === 'copy') { const t = getSelection().toString(); try { await navigator.clipboard.writeText(t); } catch {} toast('הועתק', { icon: 'ph-copy' }); }
  if (c === 'link') askLink(focusedBlk);
  if (c === 'tolist' && focusedBlk) changeType(focusedBlk, 'check');
  if (c !== 'bold') hideBubble();
});

/* ---------------- editor: bell + more */
$('#ed-bell').addEventListener('click', () => { readDOM(); openTimePicker(ed.note); });
let pop = null;
function closePop() { if (pop) { const p = pop; pop = null; p.classList.remove('show'); setTimeout(() => p.remove(), 200); } }
$('#ed-more').addEventListener('click', e => {
  e.stopPropagation();
  if (pop) { closePop(); return; }
  pop = document.createElement('div');
  pop.className = 'pop sq';
  pop.innerHTML = `<div class="swatches">${COLORS.map(c => `<button class="swatch${c === ed.color ? ' on' : ''}" style="--c:var(--n-${c})" data-c="${c}" aria-label="${COLOR_NAMES[c]}"></button>`).join('')}</div>
    <button class="pop-row" data-p="share"><i class="ph ph-share-network"></i>שיתוף</button>
    <button class="pop-row" data-p="revert"><i class="ph ph-arrow-counter-clockwise"></i>ביטול כל השינויים מהפתיחה</button>
    <button class="pop-row danger" data-p="delete"><i class="ph ph-trash"></i>מחיקת הפתק</button>`;
  editor.appendChild(pop);
  requestAnimationFrame(() => pop.classList.add('show'));
  haptic('light');
  pop.addEventListener('click', async ev => {
    ev.stopPropagation();
    const sw = ev.target.closest('.swatch');
    if (sw) { setEdColor(sw.dataset.c); $$('.swatch', pop).forEach(x => x.classList.toggle('on', x === sw)); haptic('tick'); markDirtyKeep(); return; }
    const r = ev.target.closest('[data-p]'); if (!r) return;
    const a = r.dataset.p;
    closePop();
    if (a === 'share') toast('לינק לשיתוף הועתק', { icon: 'ph-link' });
    if (a === 'revert') {
      commitNow();
      if (ed.hist[ed.hi] === ed.original) { toast('אין שינויים לבטל', { icon: 'ph-info' }); return; }
      const ok = await showDialog({ icon: 'ph-arrow-counter-clockwise', title: 'לבטל את כל השינויים?', text: 'הפתק יחזור בדיוק למצב שבו פתחת אותו.', confirm: 'בטל שינויים', cancel: 'השאר' });
      if (ok) { restore(ed.original); ed.hist.push(ed.original); ed.hi = ed.hist.length - 1; updateUndo(); haptic('medium'); toast('הפתק חזר למצב המקורי', { icon: 'ph-arrow-counter-clockwise' }); }
    }
    if (a === 'delete') {
      const n = ed.note;
      if (ed.isNew) { edTitle.textContent = ''; ed.blocks = [P('')]; renderBlocks(); closeEditor(); return; }
      const ok = await showDialog({ icon: 'ph-trash', title: 'להעביר לסל המחזור?', text: 'אפשר לשחזר מהסל במשך 30 יום.', note: n, confirm: 'העבר לסל', cancel: 'השאר' });
      if (ok) closeEditor({ then: () => removeNotes([n.id], 'trashed') });
    }
  });
});
editor.addEventListener('click', () => closePop());

/* ================================================================ new note */
$('#fab').addEventListener('click', () => newNote($('#fab')));
function newNote(fromEl) {
  const cat = CATS[state.cat].id;
  const n = { id: nid(), cat: cat === 'all' ? 'home' : cat, color: COLORS[(notes.length * 3) % COLORS.length], title: '', blocks: [P('')] };
  openEditor(n, { fromEl, isNew: true });
}

/* ================================================================ search */
$('#btn-search').addEventListener('click', () => {
  state.searching = true; state.query = ''; state.expanded = null;
  $('#search-bar').classList.add('show');
  $('#search-input').value = '';
  setTimeout(() => $('#search-input').focus(), 60);
  for (const el of cardEls.values()) el.remove(); cardEls.clear();
  renderStack();
  haptic('light');
});
$('#search-input').addEventListener('input', e => { state.query = e.target.value; state.expanded = null; renderStack({ enter: false }); });
$('#search-cancel').addEventListener('click', closeSearch);
function closeSearch() {
  state.searching = false; state.query = '';
  $('#search-bar').classList.remove('show');
  $('#search-input').blur();
  for (const el of cardEls.values()) el.remove(); cardEls.clear();
  renderStack({ enter: true });
}

/* ================================================================ home scroll: pile + hide nav */
let lastST = 0;
homeScroll.addEventListener('scroll', () => {
  applyPile();
  const st = homeScroll.scrollTop, d = st - lastST;
  if (!state.selecting && !ed.note) {
    if (d > 6 && st > 60) { $('#nav').classList.add('away'); $('#fab').classList.add('low'); }
    else if (d < -6 || st < 20) { $('#nav').classList.remove('away'); $('#fab').classList.remove('low'); }
  }
  lastST = st;
}, { passive: true });

/* while a card is lifted, the page must not scroll under the finger */
document.addEventListener('touchmove', e => { if ((press && (press.mode === 'drag' || press.mode === 'lift' || press.mode === 'pull' || press.mode === 'swipe'))) e.preventDefault(); }, { passive: false });

/* ================================================================ tabs */
const SCREENS = { home: '#scr-home', shared: '#scr-shared', agenda: '#scr-agenda', places: '#scr-places' };
function moveInd() {
  const a = $('.nav-item.active'), ind = $('#nav-ind');
  ind.style.left = '0px';
  ind.style.width = (a.offsetWidth - 16) + 'px';
  ind.style.transform = `translateX(${a.offsetLeft + 8}px)`;
}
$('#nav').addEventListener('click', e => {
  const it = e.target.closest('.nav-item'); if (!it) return;
  const tab = it.dataset.tab;
  if (tab === state.tab) { if (tab === 'home') homeScroll.scrollTo({ top: 0, behavior: 'smooth' }); return; }
  $$('.nav-item').forEach(x => {
    const on = x === it;
    x.classList.toggle('active', on);
    const i = $('.ico-wrap i', x);
    i.className = (on ? 'ph-fill ' : 'ph ') + [...i.classList].find(c => c.startsWith('ph-') && c !== 'ph-fill');
  });
  moveInd();
  const from = $(SCREENS[state.tab]), to = $(SCREENS[tab]);
  const order2 = Object.keys(SCREENS);
  const dir = order2.indexOf(tab) > order2.indexOf(state.tab) ? -1 : 1;
  state.tab = tab;
  if (tab === 'agenda') renderAgenda();
  to.classList.add('active');
  to.animate([{ opacity: 0, transform: `translateX(${dir * 28}px)` }, { opacity: 1, transform: 'none' }], { duration: EASE.glide.dur, easing: EASE.glide.easing });
  from.animate([{ opacity: 1 }, { opacity: 0 }], { duration: EASE.glide.dur * .4, easing: 'ease-out' }).onfinish = () => from.classList.remove('active');
  $('#fab').style.opacity = tab === 'home' ? '' : 0;
  $('#fab').style.pointerEvents = tab === 'home' ? '' : 'none';
  $('#nav').classList.remove('away'); $('#fab').classList.remove('low');
  if (tab === 'shared') { const b = $('.badge', it); b && b.remove(); }
  haptic('tick');
});
device.addEventListener('click', e => { const t = e.target.closest('[data-toast]'); if (t) toast(t.dataset.toast, { icon: 'check' }); });

function renderAgenda() {
  const items = [];
  notes.filter(n => n.reminder && !n.trashed && !n.archived).forEach(n => items.push({ d: n.reminder, n, title: n.title, sub: n.repeat && n.repeat !== 'none' ? REPEAT_NAMES[n.repeat] : (CATS.find(c => c.id === n.cat) || {}).name }));
  const lineNote = notes.find(n => n.cat === 'trips');
  if (lineNote) items.push({ d: at(new Date(), 1, 12), n: lineNote, title: 'להזמין צימר', sub: `שורה מתוך ״${lineNote.title}״`, line: true });
  items.sort((a, b) => a.d - b.d);
  const now = new Date();
  const groups = new Map();
  items.forEach(it => { const k = +startOfDay(it.d); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(it); });
  let h = '';
  if (!items.length) h = '<p style="color:var(--ink-2)">אין תזכורות קרובות.</p>';
  for (const [k, list] of groups) {
    const d = new Date(k), dd = dayDiff(d, now);
    const label = dd === 0 ? 'היום' : dd === 1 ? 'מחר' : `יום ${DAYS_FULL[d.getDay()]}`;
    h += `<h6><b>${label}</b>${d.getDate()} ב${MONTHS[d.getMonth()]}</h6>`;
    let placedNow = dd !== 0;
    list.forEach(it => {
      if (!placedNow && it.d > now) { h += `<div class="now-line">עכשיו ${hm(now)}</div>`; placedNow = true; }
      h += `<button class="ag-item press" data-id="${it.n.id}"><span class="ag-time" style="${it.d < now ? 'color:var(--ink-3)' : ''}">${hm(it.d)}</span><span class="ag-card sq" style="--note:${noteVar(it.n.color)}"><b>${esc(it.title)}</b><small><i class="ph ${it.line ? 'ph-list-checks' : 'ph-bell'}"></i>${esc(it.sub || '')}</small></span></button>`;
    });
    if (!placedNow) h += `<div class="now-line">עכשיו ${hm(now)}</div>`;
  }
  $('#agenda').innerHTML = h;
}
$('#agenda').addEventListener('click', e => {
  const it = e.target.closest('.ag-item'); if (!it) return;
  const n = byId(it.dataset.id);
  openEditor(n, { fromEl: $('.ag-card', it) });
});

/* ================================================================ settings */
const FONT_PAIRS = [
  { id: 'soft', name: 'רך', desc: 'Fredoka + Assistant · עגול וחם', display: 'Fredoka', body: 'Assistant', w: 600 },
  { id: 'editorial', name: 'עיתונאי', desc: 'Frank Ruhl Libre + Rubik · קלאסי וחד', display: 'Frank Ruhl Libre', body: 'Rubik', w: 800 },
  { id: 'condensed', name: 'דחוס', desc: 'Karantina + Heebo · צפוף ונועז', display: 'Karantina', body: 'Heebo', w: 700, scale: 1.38 },
];
const ACCENTS = [
  { id: 'fire', name: 'אש', c: '#E0542C' }, { id: 'forest', name: 'יער', c: '#1E7552' },
  { id: 'ink', name: 'דיו', c: '#3442CF' }, { id: 'plum', name: 'שזיף', c: '#8E3AB3' },
];
function renderSettings() {
  const th = [
    ['system', 'מערכת', 'linear-gradient(90deg, #F5F1EA 50%, #12110F 50%)', ['#FAE5A0', '#4A4024']],
    ['light', 'בהיר', '#F5F1EA', ['#FAE5A0', '#C6EAD5']],
    ['dark', 'כהה', '#12110F', ['#4A4024', '#243D31']],
  ];
  $('#settings-body').innerHTML = `
    <div class="sheet-title">הגדרות <small>אב-טיפוס לאישור עיצוב</small></div>
    <div class="set-sec"><h5>מראה</h5><div class="theme-row">${th.map(([id, l, bg, cs]) => `<button class="theme-opt press sq${settings.theme === id ? ' on' : ''}" data-theme="${id}"><span class="mini" style="background:${bg}"><i style="top:10px;background:${cs[0]}"></i><i style="top:36px;background:${cs[1]}"></i></span>${l}</button>`).join('')}</div></div>
    <div class="set-sec"><h5>צבע הדגשה</h5><div class="accent-row">${ACCENTS.map(a => `<button class="accent-opt press${settings.accent === a.id ? ' on' : ''}" data-accent="${a.id}"><span class="sw sq" style="--c:${a.c}"><i class="ph-fill ph-check"></i></span>${a.name}</button>`).join('')}</div></div>
    <div class="set-sec"><h5>צמד פונטים</h5>${FONT_PAIRS.map(f => `<button class="font-opt press sq${settings.font === f.id ? ' on' : ''}" data-font="${f.id}"><span class="big" style="font-family:'${f.display}';font-weight:${f.w};font-size:${34 * (f.scale || 1)}px">אב</span><span class="meta"><b style="font-family:'${f.display}';font-weight:${f.w};font-size:${20 * (f.scale || 1)}px">${f.name} — הפתקים שלי</b><small style="font-family:'${f.body}'">${f.desc}</small></span></button>`).join('')}</div>
    <div class="set-sec"><h5>תחושה</h5>
      <button class="row" id="set-haptics"><i class="ph ph-vibrate"></i>רטט עדין בכל פעולה<span class="switch${settings.haptics ? ' on' : ''}"></span></button>
      <div class="row"><i class="ph ph-timer"></i>הילוך איטי (לבדיקת תנועה)<div class="speed">${[1, 3, 6].map(s => `<button class="press${settings.slow === s ? ' on' : ''}" data-slow="${s}">×${s === 1 ? 1 : '1/' + s}</button>`).join('')}</div></div>
    </div>
    <div class="set-sec"><h5>ארגון</h5>
      <button class="row press" data-soon="קטגוריות"><i class="ph ph-folders"></i>קטגוריות<span class="end">${CATS.length - 1}<i class="ph ph-caret-left" style="font-size:16px"></i></span></button>
      <button class="row press" data-soon="ארכיון"><i class="ph ph-archive"></i>ארכיון<span class="end">${notes.filter(n => n.archived).length}<i class="ph ph-caret-left" style="font-size:16px"></i></span></button>
      <button class="row press" id="set-trash"><i class="ph ph-trash"></i>סל המחזור<span class="end">${notes.filter(n => n.trashed && order.includes(n.id)).length}<i class="ph ph-caret-left" style="font-size:16px"></i></span></button>
      <button class="row press" id="set-coach"><i class="ph ph-hand-tap"></i>להציג שוב את ההסבר</button>
    </div>`;
}
$('#btn-profile').addEventListener('click', () => { renderSettings(); openSheet('settings'); });
$('#settings-body').addEventListener('click', async e => {
  const t = e.target.closest('[data-theme]');
  if (t) { setTheme(t.dataset.theme, e.clientX, e.clientY); $$('.theme-opt').forEach(x => x.classList.toggle('on', x === t)); haptic('light'); return; }
  const a = e.target.closest('[data-accent]');
  if (a) { saveSetting('accent', a.dataset.accent); applyTheme(); $$('.accent-opt').forEach(x => x.classList.toggle('on', x === a)); haptic('light'); return; }
  const f = e.target.closest('[data-font]');
  if (f) { saveSetting('font', f.dataset.font); applyTheme(); $$('.font-opt').forEach(x => x.classList.toggle('on', x === f)); haptic('light'); requestAnimationFrame(() => layout({ instant: true })); return; }
  if (e.target.closest('#set-haptics')) { saveSetting('haptics', !settings.haptics); $('#set-haptics .switch').classList.toggle('on', settings.haptics); haptic('medium'); return; }
  const s = e.target.closest('[data-slow]');
  if (s) { saveSetting('slow', +s.dataset.slow); applyMotion(); $$('[data-slow]').forEach(x => x.classList.toggle('on', x === s)); haptic('light'); return; }
  const soon = e.target.closest('[data-soon]');
  if (soon) { toast(`${soon.dataset.soon} — בשלב 10`, { icon: 'ph-hourglass' }); return; }
  if (e.target.closest('#set-coach')) { await closeSheet('settings'); showCoach(); return; }
  if (e.target.closest('#set-trash')) {
    const k = notes.filter(n => n.trashed && order.includes(n.id)).length;
    if (!k) { toast('הסל ריק', { icon: 'ph-trash' }); return; }
    const ok = await showDialog({ icon: 'ph-trash', danger: true, title: `למחוק ${k === 1 ? 'פתק אחד' : k + ' פתקים'} לתמיד?`, text: 'אי אפשר לבטל את זה. התמונות וההקלטות יימחקו גם הן.', confirm: 'מחק לתמיד', cancel: 'ביטול' });
    if (ok) { notes.filter(n => n.trashed).forEach(n => { order = order.filter(id => id !== n.id); }); renderSettings(); toast('הסל רוקן', { icon: 'ph-broom' }); }
  }
});

/* ================================================================ coach */
function showCoach() { $('#coach').classList.add('show'); $('#scrim').classList.add('show'); }
$('#coach-ok').addEventListener('click', () => { $('#coach').classList.remove('show'); $('#scrim').classList.remove('show'); store.set('coached', true); haptic('light'); });

/* ================================================================ boot */
applyMotion();
applyTheme();
renderCats();
renderStack({ enter: true });
requestAnimationFrame(moveInd);
addEventListener('resize', () => { moveInd(); layout({ instant: true }); });
document.fonts && document.fonts.ready.then(() => { moveInd(); layout({ instant: true }); });
if (!store.get('coached', false)) setTimeout(showCoach, 900);
window.__noted = { state, notes, layout, openTimePicker, openEditor, byId, cardEls, settings };
