// Shared UI: element helper, the two springs, theme, haptics, toast, sheet, dialog.
export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const root = document.documentElement;

/** h('div.cls#id', {attrs, on:{click}}, ...children) */
export function h(sel, attrs, ...kids) {
  if (attrs == null || typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs)) { if (attrs != null) kids.unshift(attrs); attrs = {}; }
  const m = /^([a-z0-9-]+)?((?:[.#][\w-]+)*)$/i.exec(sel);
  const el = document.createElement(m[1] || 'div');
  for (const p of m[2].match(/[.#][\w-]+/g) || []) { if (p[0] === '.') el.classList.add(p.slice(1)); else el.id = p.slice(1); }
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'on') for (const [ev, f] of Object.entries(v)) el.addEventListener(ev, f);
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return el;
}
export const icon = (name, fill = false) => h('i', { class: `${fill ? 'ph-fill' : 'ph'} ph-${name}`, 'aria-hidden': 'true' });

// ------------------------------------------------------------------ motion: two springs only
export const SPRINGS = { touch: { k: 520, c: 25 }, glide: { k: 230, c: 30 } };
export const EASE = {};
function curve({ k, c }) {
  let x = 0, v = 0, t = 0;
  const dt = 1 / 600, pts = [];
  while (t < 3) { v += (-k * (x - 1) - c * v) * dt; x += v * dt; t += dt; pts.push(x); if (Math.abs(x - 1) < 0.001 && Math.abs(v) < 0.02) break; }
  const N = 60, out = [];
  for (let i = 0; i <= N; i++) out.push(+pts[Math.min(pts.length - 1, Math.round((i / N) * (pts.length - 1)))].toFixed(4));
  out[0] = 0; out[N] = 1;
  return { easing: `linear(${out.join(', ')})`, dur: Math.round(t * 1000) };
}
export function applyMotion() {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  for (const [name, s] of Object.entries(SPRINGS)) {
    const c = curve(s);
    EASE[name] = { easing: c.easing, dur: reduce ? 1 : c.dur };
    root.style.setProperty(`--ease-${name}`, c.easing);
    root.style.setProperty(`--dur-${name}`, EASE[name].dur + 'ms');
  }
}
/** Web Animations with one of the springs; interruptible (a new animation on the same element takes over). */
export function animate(el, frames, spring = 'glide', extra = {}) {
  const e = EASE[spring];
  return el.animate(frames, { duration: e.dur, easing: e.easing, fill: 'both', ...extra });
}

export function haptic(kind = 'light') {
  if (!navigator.vibrate || settings.get('haptics') === false) return;
  try { navigator.vibrate({ tick: 4, light: 8, medium: 14, confirm: [8, 40, 12], reject: [14, 30, 14] }[kind] || 8); } catch { /* */ }
}

// ------------------------------------------------------------------ settings (this browser only)
export const settings = {
  get(k, d) { try { const v = localStorage.getItem('noted.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('noted.' + k, JSON.stringify(v)); } catch { /* */ } },
};
const darkMQ = matchMedia('(prefers-color-scheme: dark)');
export function applyTheme() {
  const t = settings.get('theme', 'system');
  root.dataset.theme = t === 'system' ? (darkMQ.matches ? 'dark' : 'light') : t;
  root.dataset.accent = settings.get('accent', 'fire');
  root.dataset.font = settings.get('font', 'soft');
  document.querySelector('meta[name=theme-color]')?.setAttribute('content', getComputedStyle(root).getPropertyValue('--bg').trim());
}
darkMQ.addEventListener('change', applyTheme);

// ------------------------------------------------------------------ toast
let toastTimer = 0;
export function toast(msg, { icon: ic = null, action = null, onAction = null, ms = 3200 } = {}) {
  const host = $('#toast');
  host.replaceChildren(h('div.toast-in',
    ic && icon(ic, true),
    h('span.toast-msg', msg),
    action && h('button.toast-act.press', { on: { click: () => { haptic('light'); onAction?.(); hide(); } } }, action),
    h('i.toast-bar', { style: { animationDuration: ms + 'ms' } }),
  ));
  host.classList.add('on');
  animate(host, [{ transform: 'translate(-50%, 140%)', opacity: 0 }, { transform: 'translate(-50%, 0)', opacity: 1 }], 'touch');
  clearTimeout(toastTimer);
  const hide = () => { clearTimeout(toastTimer); animate(host, [{ transform: 'translate(-50%, 0)', opacity: 1 }, { transform: 'translate(-50%, 140%)', opacity: 0 }], 'glide').finished.then(() => host.classList.remove('on')).catch(() => {}); };
  toastTimer = setTimeout(hide, ms);
}

// ------------------------------------------------------------------ sheet (bottom on phones, centred card on wide screens)
const stack = [];
export function sheet(title, body, { wide = false, onClose = null } = {}) {
  const scrim = h('div.scrim');
  const card = h(`div.sheet${wide ? '.wide' : ''}`, { role: 'dialog', 'aria-modal': 'true', 'aria-label': title || '' },
    h('div.sheet-grip'),
    title ? h('div.sheet-head', h('h2.sheet-title', title), h('button.icon-btn.press', { 'aria-label': 'סגירה', on: { click: () => close() } }, icon('x'))) : null,
    h('div.sheet-body', body),
  );
  const layer = h('div.layer', scrim, card);
  $('#overlay').append(layer);
  const narrow = matchMedia('(max-width: 720px)').matches;
  animate(scrim, [{ opacity: 0 }, { opacity: 1 }], 'glide');
  animate(card, narrow ? [{ transform: 'translateY(100%)' }, { transform: 'translateY(0)' }] : [{ transform: 'translateY(24px) scale(.96)', opacity: 0 }, { transform: 'none', opacity: 1 }], narrow ? 'glide' : 'touch');
  let closed = false;
  function close() {
    if (closed) return; closed = true;
    stack.splice(stack.indexOf(close), 1);
    animate(scrim, [{ opacity: 1 }, { opacity: 0 }], 'glide');
    animate(card, narrow ? [{ transform: 'translateY(0)' }, { transform: 'translateY(105%)' }] : [{ transform: 'none', opacity: 1 }, { transform: 'translateY(16px) scale(.97)', opacity: 0 }], 'glide')
      .finished.then(() => layer.remove()).catch(() => layer.remove());
    onClose?.();
  }
  scrim.addEventListener('click', close);
  dragToClose(card, close);
  stack.push(close);
  setTimeout(() => card.querySelector('[autofocus]')?.focus(), 60);
  return { close, card };
}
export const closeTop = () => { const c = stack[stack.length - 1]; if (c) { c(); return true; } return false; };
addEventListener('keydown', (e) => { if (e.key === 'Escape' && closeTop()) { e.preventDefault(); e.stopImmediatePropagation(); } }, true);

/** Pull a sheet down by its grip to close it (follows the finger, springs back if not far enough). */
function dragToClose(card, close) {
  const grip = card.querySelector('.sheet-grip');
  let y0 = null, dy = 0;
  grip.addEventListener('pointerdown', (e) => { y0 = e.clientY; dy = 0; grip.setPointerCapture(e.pointerId); card.getAnimations().forEach((a) => a.cancel()); });
  grip.addEventListener('pointermove', (e) => { if (y0 == null) return; dy = Math.max(0, e.clientY - y0); card.style.transform = `translateY(${dy}px)`; });
  const up = () => {
    if (y0 == null) return; y0 = null;
    if (dy > 110) { card.style.transform = ''; close(); return; }
    animate(card, [{ transform: `translateY(${dy}px)` }, { transform: 'translateY(0)' }], 'touch').finished.then(() => { card.style.transform = ''; }).catch(() => {});
  };
  grip.addEventListener('pointerup', up); grip.addEventListener('pointercancel', up);
}

/** Confirm dialog → Promise<boolean>. */
export function confirmDialog({ title, text = '', ok = 'אישור', cancel = 'ביטול', danger = false, ic = 'info' }) {
  return new Promise((res) => {
    let done = false;
    const finish = (v) => { if (done) return; done = true; s.close(); res(v); };
    const body = h('div.dialog',
      h(`div.dialog-ico${danger ? '.danger' : ''}`, icon(ic, true)),
      h('h3.dialog-title', title),
      text && h('p.dialog-text', text),
      h('div.dialog-btns',
        h(`button.btn${danger ? '.danger' : '.dark'}.press`, { on: { click: () => { haptic(danger ? 'medium' : 'confirm'); finish(true); } } }, ok),
        h('button.btn.quiet.press', { on: { click: () => finish(false) } }, cancel),
      ),
    );
    const s = sheet(null, body, { onClose: () => { if (!done) { done = true; res(false); } } });
    s.card.classList.add('dialog-card');
  });
}

/** One-line text prompt in a sheet → Promise<string|null>. */
export function promptDialog({ title, value = '', placeholder = '', ok = 'שמירה' }) {
  return new Promise((res) => {
    let done = false;
    const input = h('input.field', { value, placeholder, autofocus: true, maxlength: 80 });
    const go = () => { if (!input.value.trim()) { haptic('reject'); input.focus(); return; } done = true; s.close(); res(input.value.trim()); };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
    const s = sheet(title, h('div.stack-12', input, h('button.btn.dark.press.full', { on: { click: go } }, ok)), { onClose: () => { if (!done) res(null); } });
  });
}

// press feedback for anything with .press (scale on the touch spring)
document.addEventListener('pointerdown', (e) => {
  const el = e.target.closest('.press');
  if (!el || el.disabled) return;
  const s = el.dataset.scale || '.95';
  animate(el, [{ transform: 'scale(1)' }, { transform: `scale(${s})` }], 'touch', { duration: 160 });
  const up = () => { animate(el, [{ transform: `scale(${s})` }, { transform: 'scale(1)' }], 'touch'); removeEventListener('pointerup', up); removeEventListener('pointercancel', up); };
  addEventListener('pointerup', up); addEventListener('pointercancel', up);
}, { passive: true });

// ------------------------------------------------------------------ dates
export const DAYS = ['א׳', 'ב׳', 'ג׳', 'ד׳', 'ה׳', 'ו׳', 'ש׳'];
export const DAYS_FULL = ['ראשון', 'שני', 'שלישי', 'רביעי', 'חמישי', 'שישי', 'שבת'];
export const MONTHS = ['ינואר', 'פברואר', 'מרץ', 'אפריל', 'מאי', 'יוני', 'יולי', 'אוגוסט', 'ספטמבר', 'אוקטובר', 'נובמבר', 'דצמבר'];
const pad = (n) => String(n).padStart(2, '0');
export const hm = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const sod = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
export const dayDiff = (a, b) => Math.round((sod(a) - sod(b)) / 864e5);
export function fmtRel(ms) {
  const d = new Date(ms), now = new Date();
  const dd = dayDiff(d, now);
  if (dd === 0) return `היום ${hm(d)}`;
  if (dd === 1) return `מחר ${hm(d)}`;
  if (dd === -1) return `אתמול ${hm(d)}`;
  if (dd > 1 && dd < 7) return `${DAYS[d.getDay()]} ${hm(d)}`;
  return `${d.getDate()}/${d.getMonth() + 1} ${hm(d)}`;
}
export const fmtShort = (d) => `${DAYS[d.getDay()]} ${d.getDate()}/${d.getMonth() + 1} ${hm(d)}`;
