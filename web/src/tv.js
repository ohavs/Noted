// The TV screen (/tv): shows a code + QR until linked, then the chosen notes, big and calm.
// Reads only screens/{code}; no sign-in on the TV.
import { initializeApp } from 'firebase/app';
import { getFirestore, doc, onSnapshot } from 'firebase/firestore';
import qrcode from 'qrcode-generator';
import { toHTML, clean, esc } from './rich.js';

const ALPHA = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const $ = (s) => document.querySelector(s);
const root = document.documentElement;

function code() {
  let c = null;
  try { c = localStorage.getItem('noted.tv.code'); } catch { /* */ }
  if (new URLSearchParams(location.search).has('new') || !c || c.length !== 8) {
    const r = crypto.getRandomValues(new Uint32Array(8));
    c = [...r].map((x) => ALPHA[x % ALPHA.length]).join('');
    try { localStorage.setItem('noted.tv.code', c); } catch { /* */ }
  }
  return c;
}

const CODE = code();
const fmt = (c) => `${c.slice(0, 4)}-${c.slice(4)}`;
const darkMQ = matchMedia('(prefers-color-scheme: dark)');
let theme = 'auto';
const applyTheme = () => { root.dataset.theme = theme === 'auto' ? (darkMQ.matches ? 'dark' : 'light') : theme; };
darkMQ.addEventListener('change', applyTheme);

function pairing() {
  const url = `${location.origin}/screen/${CODE}`;
  const qr = qrcode(0, 'M'); qr.addData(url); qr.make();
  $('#tv').innerHTML = `
    <div class="pair">
      <div class="pair-brand">noted</div>
      <h1>מחברים את המסך</h1>
      <p>בטלפון או במחשב: <b class="ltr">noted-ce451.web.app</b> ← מסך תצוגה ← חיבור מסך, ומקלידים:</p>
      <div class="pair-code ltr">${fmt(CODE)}</div>
      <div class="pair-or">או סורקים בטלפון</div>
      <div class="pair-qr">${qr.createSvgTag({ cellSize: 6, margin: 0, scalable: true })}</div>
    </div>`;
}

function blockHTML(b) {
  const html = toHTML(clean(b.text), b.spans);
  const ind = `style="padding-inline-start:${(b.indent || 0) * 1.2}em"`;
  switch (b.type) {
    case 'H1': case 'H2': case 'H3': return `<div class="tb-h">${html}</div>`;
    case 'Quote': return `<div class="tb-q">${html}</div>`;
    case 'Bullet': return `<div class="tb-li" ${ind}><i class="dot"></i><span>${html}</span></div>`;
    case 'Num': return `<div class="tb-li" ${ind}><span>${html}</span></div>`;
    case 'Check': return `<div class="tb-c${b.done ? ' done' : ''}" ${ind}><i class="box">${b.done ? '<svg viewBox="0 0 16 16"><path d="M3.5 8.5l3 3 6-7"/></svg>' : ''}</i><span>${html}</span></div>`;
    case 'Divider': return '<hr>';
    default: return clean(b.text).trim() ? `<div class="tb-p">${html}</div>` : '';
  }
}

function show(sc) {
  theme = sc.theme || 'auto'; applyTheme();
  const ids = (sc.noteIds || []).filter((id) => sc.notes?.[id]);
  if (!ids.length) { $('#tv').innerHTML = `<div class="pair"><div class="pair-brand">noted</div><h1>${esc(sc.name || 'מסך')}</h1><p>עוד לא נבחרו פתקים להצגה.</p></div>`; return; }
  const cards = ids.map((id) => {
    const n = sc.notes[id];
    const open = n.b.filter((b) => !(b.type === 'Check' && b.done));
    const done = n.b.length - open.length;
    const checks = n.b.filter((b) => b.type === 'Check');
    return `<article class="tv-card" style="background:var(--n-${String(n.c).toLowerCase()})">
      <header>${n.t ? `<h2>${esc(n.t)}</h2>` : ''}${checks.length ? `<span class="tv-count">${checks.filter((b) => b.done).length}/${checks.length}</span>` : ''}</header>
      <div class="tv-body">${open.map(blockHTML).join('')}${done && checks.length ? `<div class="tv-done">${done} סומנו</div>` : ''}</div>
    </article>`;
  }).join('');
  $('#tv').innerHTML = `<header class="tv-top"><span class="pair-brand sm">noted</span><b>${esc(sc.name || '')}</b><span id="clock"></span></header><main class="tv-grid n${Math.min(ids.length, 6)}">${cards}</main>`;
  tickClock();
}

function tickClock() {
  const el = $('#clock'); if (!el) return;
  const d = new Date();
  el.textContent = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
setInterval(tickClock, 15000);

// keep the screen on while it shows notes
async function wake() { try { await navigator.wakeLock?.request('screen'); } catch { /* */ } }
document.addEventListener('visibilitychange', () => { if (!document.hidden) wake(); });

(async () => {
  applyTheme();
  pairing();
  wake();
  const cfg = await (await fetch('/__/firebase/init.json')).json();
  const db = getFirestore(initializeApp(cfg));
  onSnapshot(doc(db, 'screens', CODE), (s) => { if (s.exists()) show(s.data()); else { theme = 'auto'; applyTheme(); pairing(); } }, () => setTimeout(() => location.reload(), 30000));
})();
