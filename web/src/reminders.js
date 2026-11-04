// Time reminders on the web: the picker (same model as the app), the agenda, and ringing while
// the site is open. Every change goes to users/{uid}/reminders — the phone and watch ring from it.
import { S, setReminder, removeReminder, doneReminder, advanceReminder, patchReminder, remId } from './model.js';
import { h, $, $$, icon, sheet, toast, haptic, animate, fmtRel, fmtShort, hm, DAYS, DAYS_FULL, MONTHS, dayDiff, settings } from './ui.js';
import { parseRule, repeatText, dueAt, occAt, fromLocal } from './repeat.js';
import { clean } from './rich.js';

export function reminderChip(r, small = false) {
  const due = dueAt(r);
  const late = due != null && due < Date.now();
  const rep = parseRule(r.rule, r.until);
  return h(`span.rem-chip${small ? '.sm' : ''}${late ? '.late' : ''}`, icon(r.sticky ? 'notification' : 'bell-ringing', true),
    due != null ? fmtRel(due) : 'צלצלה', rep.kind !== 'none' ? icon('repeat') : null);
}

// ------------------------------------------------------------------ picker

export function openReminder(noteId, blockId = '', line = '') {
  const id = remId(noteId, blockId);
  const ex = S.reminders.get(id);
  const now = new Date();
  const def = () => { const d = new Date(); d.setMinutes(0, 0, 0); d.setHours(d.getHours() + 1); if (d.getHours() < 8) d.setHours(9); return d; };
  const exAt = ex ? occAt(ex) : 0;
  const st = {
    at: ex && exAt > Date.now() ? new Date(exAt) : def(),
    rep: ex ? parseRule(ex.rule, ex.until) : { kind: 'none' },
    sticky: ex?.sticky || false,
  };
  const n = S.notes.get(noteId);
  const head = blockId ? clean(line).trim() || 'שורה' : clean(n?.title).trim() || 'הפתק';

  const body = h('div.picker');
  const s = sheet(blockId ? 'תזכורת לשורה' : 'תזכורת', body, { wide: false });

  function quick() {
    const at = (dOff, H, M = 0) => { const d = new Date(); d.setDate(d.getDate() + dOff); d.setHours(H, M, 0, 0); return d; };
    const inH = (k) => { const d = new Date(Date.now() + k * 3600e3); d.setSeconds(0, 0); d.setMinutes(Math.ceil(d.getMinutes() / 5) * 5); return d; };
    const evening = now.getHours() < 19 ? at(0, 20) : at(1, 20);
    const list = [['בעוד שעה', inH(1)], [now.getHours() < 19 ? 'הערב' : 'מחר בערב', evening], ['מחר בבוקר', at(1, 9)], ['בעוד שבוע', at(7, 9)]];
    return h('div.quick', ...list.map(([l, d]) => h('button.quick-item.press', { on: { click: () => { st.at = d; haptic('tick'); draw(); } } }, h('b', l), h('small', fmtShort(d)))));
  }

  function days() {
    const strip = h('div.days');
    for (let i = 0; i < 21; i++) {
      const d = new Date(); d.setDate(d.getDate() + i);
      const on = dayDiff(d, st.at) === 0;
      strip.append(h(`button.day.press${on ? '.on' : ''}`, { 'data-scale': '.9', on: { click: () => { st.at = new Date(d.getFullYear(), d.getMonth(), d.getDate(), st.at.getHours(), st.at.getMinutes()); haptic('tick'); draw(); } } },
        h('small', i === 0 ? 'היום' : i === 1 ? 'מחר' : DAYS[d.getDay()]), h('b', d.getDate()), d.getDate() === 1 || i === 0 ? h('i', MONTHS[d.getMonth()]) : null));
    }
    return strip;
  }

  function clock() {
    const unit = (val, max, stepv, set, label) => {
      const el = h('div.wheel', { tabindex: 0, 'aria-label': label },
        h('button.wheel-btn.press', { 'aria-label': 'למעלה', on: { click: () => set((val + stepv) % max) } }, icon('caret-up')),
        h('span.wheel-val', String(val).padStart(2, '0')),
        h('button.wheel-btn.press', { 'aria-label': 'למטה', on: { click: () => set((val - stepv + max) % max) } }, icon('caret-down')));
      el.addEventListener('wheel', (e) => { e.preventDefault(); set((val + (e.deltaY < 0 ? stepv : -stepv) + max) % max); }, { passive: false });
      el.addEventListener('keydown', (e) => { if (e.key === 'ArrowUp') { e.preventDefault(); set((val + stepv) % max); } if (e.key === 'ArrowDown') { e.preventDefault(); set((val - stepv + max) % max); } });
      // drag up/down: one step per 14px, a tick each
      let y0 = null, v0 = 0;
      el.addEventListener('pointerdown', (e) => { if (e.target.closest('button')) return; y0 = e.clientY; v0 = val; el.setPointerCapture(e.pointerId); });
      el.addEventListener('pointermove', (e) => { if (y0 == null) return; const k = Math.round((y0 - e.clientY) / 14); const nv = ((v0 + k * stepv) % max + max) % max; if (nv !== val) set(nv, true); });
      el.addEventListener('pointerup', () => { y0 = null; });
      return el;
    };
    return h('div.clock',
      unit(st.at.getHours(), 24, 1, (v, drag) => { st.at = new Date(st.at); st.at.setHours(v); haptic('tick'); draw(drag); }, 'שעה'),
      h('span.clock-colon', ':'),
      unit(st.at.getMinutes(), 60, 5, (v, drag) => { st.at = new Date(st.at); st.at.setMinutes(v); haptic('tick'); draw(drag); }, 'דקות'),
    );
  }

  function repeatRow() {
    const opts = [['none', 'פעם אחת'], ['daily', 'כל יום'], ['days', 'ימים בשבוע'], ['monthly', 'כל חודש']];
    const k = st.rep.kind === 'custom' ? 'none' : st.rep.kind;
    return h('div.stack-8',
      h('div.seg', ...opts.map(([v, l]) => h(`button.seg-item.press${k === v ? '.on' : ''}`, { on: { click: () => {
        st.rep = v === 'days' ? { kind: 'days', days: [st.at.getDay() === 0 ? 7 : st.at.getDay()] } : { kind: v };
        haptic('tick'); draw();
      } } }, l))),
      st.rep.kind === 'days' ? h('div.weekdays', ...[7, 1, 2, 3, 4, 5, 6].map((iso) => h(`button.wd.press${st.rep.days.includes(iso) ? '.on' : ''}`, { on: { click: () => {
        const d = new Set(st.rep.days); d.has(iso) ? d.delete(iso) : d.add(iso); if (!d.size) { haptic('reject'); return; }
        st.rep = { ...st.rep, days: [...d] }; haptic('tick'); draw();
      } } }, DAYS[iso % 7]))) : null,
    );
  }

  function stickyRow() {
    return h(`button.sticky-row.press${st.sticky ? '.on' : ''}`, { 'data-scale': '.98', on: { click: () => { st.sticky = !st.sticky; haptic('tick'); draw(); } } },
      icon(st.sticky ? 'notification' : 'notification', st.sticky),
      h('span.sr-txt', h('b', 'להשאיר בהתראות'), h('small', 'בטלפון: אחרי שהיא מצלצלת — נשארת עד שמסירים')),
      h(`span.switch${st.sticky ? '.on' : ''}`, h('i')));
  }

  function draw(fromDrag = false) {
    const past = st.at <= new Date();
    if (fromDrag) { // keep the wheels under the finger, just refresh the text
      $$('.wheel-val', body)[0].textContent = String(st.at.getHours()).padStart(2, '0');
      $$('.wheel-val', body)[1].textContent = String(st.at.getMinutes()).padStart(2, '0');
      $('.set-btn', body).textContent = `קביעת תזכורת · ${fmtShort(st.at)}`;
      return;
    }
    body.replaceChildren(
      h('p.picker-for', icon('note-pencil'), head),
      quick(), days(), clock(), repeatRow(), stickyRow(),
      h('p.picker-sum', past ? 'הזמן הזה כבר עבר' : `${untilText(st.at)}${repeatText(st.rep) ? ' · ' + repeatText(st.rep) : ''}`),
      h(`button.btn.dark.full.press.set-btn${past ? '.off' : ''}`, { on: { click: async (e) => {
        if (st.at <= new Date()) { haptic('reject'); animate(e.currentTarget, [{ transform: 'translateX(0)' }, { transform: 'translateX(8px)' }, { transform: 'translateX(0)' }], 'touch'); return; }
        haptic('confirm');
        s.close();
        await setReminder(noteId, blockId, st.at, st.rep, st.sticky);
        toast(`תזכורת נקבעה · ${fmtRel(st.at.getTime())}`, { icon: 'bell-ringing' });
        askNotify();
      } } }, `קביעת תזכורת · ${fmtShort(st.at)}`),
      ex ? h('button.btn.quiet.full.press', { on: { click: async () => {
        s.close(); await removeReminder(id);
        toast('התזכורת הוסרה', { icon: 'bell-slash', action: 'ביטול', onAction: () => patchReminder(ex, { deleted: false }) });
      } } }, icon('bell-slash'), 'הסרת התזכורת') : null,
    );
    const on = $('.day.on', body); if (on) on.scrollIntoView({ inline: 'center', block: 'nearest' });
  }
  draw();
}

function untilText(d) {
  const m = Math.round((d - Date.now()) / 60000);
  if (m < 60) return `בעוד ${m} דק׳`;
  const hrs = Math.round(m / 60);
  if (hrs < 24) return `בעוד ${hrs} שע׳`;
  const dd = dayDiff(d, new Date());
  return dd === 1 ? 'מחר' : `בעוד ${dd} ימים`;
}

function askNotify() {
  if (!('Notification' in window) || Notification.permission !== 'default' || settings.get('askedNotify')) return;
  settings.set('askedNotify', true);
  toast('שהתזכורת תצלצל גם בדפדפן?', { icon: 'bell', action: 'לאשר', ms: 7000, onAction: () => Notification.requestPermission() });
}

// ------------------------------------------------------------------ agenda

export function agenda() {
  const list = [...S.reminders.values()].map((r) => ({ r, due: dueAt(r) })).filter((x) => x.due != null && S.notes.get(x.r.noteId) && !S.notes.get(x.r.noteId).trashedAt).sort((a, b) => a.due - b.due);
  if (!list.length) return h('div.empty', h('div.empty-ico', icon('bell-ringing')), h('h3', 'אין תזכורות קרובות'), h('p', 'קובעים תזכורת מתוך פתק — כאן או בטלפון.'));
  const groups = new Map();
  for (const x of list) {
    const d = new Date(x.due), dd = dayDiff(d, new Date());
    const key = dd < 0 ? 'עבר' : dd === 0 ? 'היום' : dd === 1 ? 'מחר' : dd < 7 ? `יום ${DAYS_FULL[d.getDay()]}` : `${d.getDate()} ב${MONTHS[d.getMonth()]}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(x);
  }
  return h('div.agenda', ...[...groups].map(([k, xs]) => h('section.ag-group', h('h3.ag-day', k), ...xs.map(({ r, due }) => {
    const n = S.notes.get(r.noteId);
    const line = r.blockId ? n.blocks.find((b) => b.id === r.blockId) : null;
    const rep = parseRule(r.rule, r.until);
    const el = h('div.ag-item', { style: { '--c': `var(--n-${n.color.toLowerCase()})` } },
      h('span.ag-time', hm(new Date(due))),
      h('button.ag-main.press', { 'data-scale': '.98', on: { click: () => import('./editor.js').then((m) => m.openEditor(n.id, el.getBoundingClientRect())) } },
        h('b', clean(line?.text || n.title).trim() || 'פתק'), h('small', [line ? clean(n.title) : '', repeatText(rep)].filter(Boolean).join(' · ') || ' ')),
      r.sticky ? h('span.ag-flag', { title: 'נשאר בהתראות' }, icon('notification', true)) : null,
      h('button.icon-btn.press', { 'aria-label': 'בוצע', title: 'בוצע', on: { click: async () => { haptic('confirm'); await doneReminder(r); toast('סומן כבוצע', { icon: 'check-circle' }); } } }, icon('check-circle')),
      h('button.icon-btn.press', { 'aria-label': 'עריכה', title: 'עריכה', on: { click: () => openReminder(r.noteId, r.blockId, line?.text || '') } }, icon('pencil-simple')),
    );
    return el;
  }))));
}

// ------------------------------------------------------------------ ringing (while the site is open)

const rung = new Set();
let audio = null;
const TONES = { system: 'tone_bell', bell: 'tone_bell', glass: 'tone_glass', soft: 'tone_soft', marimba: 'tone_marimba', drop: 'tone_drop', pop: 'tone_pop' };

export function startRinging() {
  setInterval(check, 15000);
  setTimeout(check, 2500);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) check(); });
}

async function check() {
  if (!S.user) return;
  const now = Date.now();
  for (const r of S.reminders.values()) {
    const due = dueAt(r);
    if (due == null || due > now + 1000) continue;
    const key = `${r.id}@${due}`;
    if (rung.has(key)) continue;
    rung.add(key);
    // long overdue (the site was closed): just move it on, the phone already rang
    if (now - due < 10 * 60e3) ring(r);
    advanceReminder(r, now).catch(() => {});
  }
}

function ring(r) {
  const n = S.notes.get(r.noteId);
  if (!n || n.trashedAt) return;
  const line = r.blockId ? n.blocks.find((b) => b.id === r.blockId) : null;
  if (line?.done && parseRule(r.rule).kind === 'none') return;
  const title = clean(line?.text || n.title).trim() || 'תזכורת';
  try { audio = audio || new Audio(); audio.src = `/tones/${TONES[settings.get('tone', 'bell')] || 'tone_bell'}.ogg`; audio.play().catch(() => {}); } catch { /* */ }
  if ('Notification' in window && Notification.permission === 'granted' && document.hidden) {
    const nt = new Notification(title, { body: line ? clean(n.title) : '', tag: r.id, icon: '/icon-192.png', requireInteraction: !!r.sticky });
    nt.onclick = () => { focus(); import('./editor.js').then((m) => m.openEditor(n.id)); nt.close(); };
  }
  toast(title, { icon: 'bell-ringing', action: 'בוצע', ms: r.sticky ? 600000 : 12000, onAction: () => doneReminder(S.reminders.get(r.id) || r) });
}
