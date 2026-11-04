// The note editor: a panel in the note's colour that opens out of its card. Blocks are edited in
// place (contenteditable per text block); every change is written per block, like the app does,
// and changes from other devices flow in live without touching the line being typed.
import { S, canEdit, setMeta, writeBlocks, sameBlock, uuid, between, sinkChecked, reorder, TEXT_TYPES, deleteForever, mediaURL, remId } from './model.js';
import { h, $, $$, icon, animate, toast, haptic, sheet, fmtRel } from './ui.js';
import { toHTML, fromDOM, clean, esc, INKS, INK_NAMES } from './rich.js';
import { noteMenu, CHECK_SVG, longPress } from './main.js';
import { openReminder, reminderChip } from './reminders.js';

let ed = null;

addEventListener('keydown', (e) => {
  // Esc closes the editor once no sheet is open above it (sheets handle their own Esc first)
  if (e.key === 'Escape' && ed && !document.querySelector('#overlay .layer:not(.ed-layer)')) { e.preventDefault(); closeEditor(); }
});

export function openEditor(id, from, { fresh = false } = {}) {
  if (ed) closeEditor();
  const n = S.notes.get(id);
  if (!n && !fresh) return;
  const readOnly = n ? !canEdit(n) : false;
  const panel = h('div.editor', { role: 'dialog', 'aria-modal': 'true' });
  const scrim = h('div.scrim.ed-scrim', { on: { click: () => closeEditor() } });
  const layer = h('div.layer.ed-layer', scrim, panel);
  $('#overlay').append(layer);
  ed = { id, panel, layer, scrim, from, fresh, readOnly, blocks: [], saved: new Map(), dirty: new Set(), removed: new Set(), title: '', titleDirty: false, timer: 0, focusId: null };
  const wait = () => {
    const nn = S.notes.get(id);
    if (!nn) { setTimeout(wait, 60); return; }
    load(nn);
    build();
    openAnim();
    if (fresh) setTimeout(() => $('.ed-title', panel)?.focus(), 80);
  };
  wait();
  S.listeners.add(onRemote);
  history.pushState({ editor: id }, '', location.pathname);
  addEventListener('popstate', onPop);
}

function onPop() { if (ed) closeEditor({ fromHistory: true }); }

function load(n) {
  ed.n = n;
  ed.blocks = n.blocks.map((b) => ({ ...b }));
  if (!ed.blocks.some((b) => TEXT_TYPES.has(b.type))) ed.blocks.push({ id: uuid(), type: 'P', text: '', done: false, indent: 0, spans: [], x: '', o: (ed.blocks.at(-1)?.o ?? 0) + 1, _new: true });
  ed.saved = new Map(n.blocks.map((b) => [b.id, { ...b }]));
  ed.title = n.title;
}

function openAnim() {
  const { panel, scrim, from } = ed;
  animate(scrim, [{ opacity: 0 }, { opacity: 1 }], 'glide');
  const r = panel.getBoundingClientRect();
  if (from && from.width) {
    const sx = from.width / r.width, sy = from.height / r.height;
    const dx = from.left + from.width / 2 - (r.left + r.width / 2), dy = from.top + from.height / 2 - (r.top + r.height / 2);
    animate(panel, [{ transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})`, borderRadius: '30px', opacity: 0.6 }, { transform: 'none', opacity: 1 }], 'glide');
    animate($('.ed-inner', panel), [{ opacity: 0 }, { opacity: 0, offset: 0.3 }, { opacity: 1 }], 'glide');
  } else animate(panel, [{ transform: 'translateY(30px) scale(.97)', opacity: 0 }, { transform: 'none', opacity: 1 }], 'touch');
}

export async function closeEditor({ fromHistory = false } = {}) {
  if (!ed) return;
  const e = ed;
  ed = null;
  S.listeners.delete(onRemote);
  removeEventListener('popstate', onPop);
  if (!fromHistory && history.state?.editor) history.back();
  await flush(e).catch(() => {});
  const n = S.notes.get(e.id);
  // a new note closed with nothing in it is gone, like in the app
  const blank = n && !clean(n.title).trim() && n.blocks.every((b) => TEXT_TYPES.has(b.type) && !clean(b.text).trim());
  if (e.fresh && blank) deleteForever(n).catch(() => {});
  const card = document.querySelector(`.card[data-id="${e.id}"]`);
  const to = card?.getBoundingClientRect();
  animate(e.scrim, [{ opacity: 1 }, { opacity: 0 }], 'glide');
  const r = e.panel.getBoundingClientRect();
  const frames = to && to.width && to.bottom > 0 && to.top < innerHeight
    ? [{ transform: 'none', opacity: 1 }, { transform: `translate(${to.left + to.width / 2 - (r.left + r.width / 2)}px, ${to.top + to.height / 2 - (r.top + r.height / 2)}px) scale(${to.width / r.width}, ${to.height / r.height})`, opacity: 0.4 }]
    : [{ transform: 'none', opacity: 1 }, { transform: 'translateY(30px) scale(.96)', opacity: 0 }];
  animate($('.ed-inner', e.panel), [{ opacity: 1 }, { opacity: 0 }], 'glide', { duration: 160 });
  animate(e.panel, frames, 'glide').finished.then(() => e.layer.remove()).catch(() => e.layer.remove());
}

// ------------------------------------------------------------------ build

function build() {
  const { panel, n, readOnly } = ed;
  panel.style.background = `var(--n-${n.color.toLowerCase()})`;
  const rem = S.reminders.get(remId(n.id));
  panel.replaceChildren(h('div.ed-inner',
    h('header.ed-head',
      h('button.icon-btn.press.ink', { 'aria-label': 'חזרה', on: { click: () => closeEditor() } }, icon('caret-right')),
      h('div.ed-head-mid', readOnly ? h('span.ed-ro', icon('eye'), 'צפייה בלבד') : null, h('span.ed-status')),
      h('button.icon-btn.press.ink', { 'aria-label': 'תזכורת', on: { click: () => openReminder(n.id) } }, icon(rem ? 'bell-ringing' : 'bell', !!rem)),
      h('button.icon-btn.press.ink', { 'aria-label': 'עוד', on: { click: () => noteMenu(S.notes.get(n.id) || n) } }, icon('dots-three-vertical')),
    ),
    h('div.ed-scroll',
      h('div.ed-title', { contenteditable: readOnly ? 'false' : 'true', 'data-ph': 'כותרת', spellcheck: 'true', on: { input: onTitle, keydown: titleKeys }, html: esc(clean(n.title)) }),
      h('div.ed-meta', rem ? reminderChip(rem) : null, peopleLine(n), h('span.ed-when', n.updatedAt ? 'נערך ' + fmtRel(n.updatedAt) : '')),
      h('div.ed-blocks', ...ed.blocks.map(row)),
      h('div.ed-tail', { on: { click: tailClick } }),
    ),
    readOnly ? null : toolbar(),
  ));
}

function peopleLine(n) {
  if (!n.shared) return null;
  const names = Object.keys(n.members).map((u) => n.people[u]?.n).filter(Boolean);
  return h('span.ed-people', icon('users-three', true), names.slice(0, 3).join(', ') + (names.length > 3 ? ` ועוד ${names.length - 3}` : ''));
}

function numberOf(b) {
  const list = ed.blocks;
  const i = list.indexOf(b);
  let k = 1;
  for (let j = i - 1; j >= 0 && list[j].type === 'Num' && (list[j].indent || 0) >= (b.indent || 0); j--) if ((list[j].indent || 0) === (b.indent || 0)) k++;
  return k;
}

function row(b) {
  const r = h(`div.ed-row.t-${b.type.toLowerCase()}${b.done ? '.done' : ''}`, { dataset: { id: b.id }, style: { paddingInlineStart: (b.indent || 0) * 22 + 'px' } });
  if (TEXT_TYPES.has(b.type)) {
    if (b.type === 'Bullet') r.append(h('span.lead.dot', { class: `lead dot${(b.indent || 0) % 2 ? ' ring' : ''}` }));
    if (b.type === 'Num') r.append(h('span.lead.num', numberOf(b) + '.'));
    if (b.type === 'Check') {
      const c = h('button.lead.chk.press', { 'aria-label': 'סימון', 'data-scale': '.8', html: CHECK_SVG });
      longPress(c, () => removeBlock(b, true), () => tick(b));
      r.append(c);
    }
    const t = h('div.ed-text', {
      contenteditable: ed.readOnly ? 'false' : 'true', spellcheck: 'true', 'data-ph': b === ed.blocks[0] && b.type === 'P' ? 'כותבים…' : '',
      html: toHTML(clean(b.text), b.spans),
      on: { input: () => onInput(b, t), keydown: (e) => keys(e, b, t), focus: () => { ed.focusId = b.id; }, blur: () => { if (ed?.focusId === b.id) ed.focusId = null; }, paste: (e) => paste(e, b, t) },
    });
    r.append(t);
    // a ticked line offers its own × (always visible, also on touch where row-tools stay hidden)
    if (b.type === 'Check' && !ed.readOnly) r.append(h('button.done-x.press', { 'aria-label': 'מחיקת השורה', 'data-scale': '.8', on: { click: () => removeBlock(b, true) } }, icon('x')));
    if (!ed.readOnly) r.append(h('div.row-tools',
      b.type === 'Check' ? h('button.row-tool.press', { 'aria-label': 'תזכורת לשורה', title: 'תזכורת לשורה', on: { click: () => openReminder(ed.n.id, b.id, b.text) } }, icon(S.reminders.get(remId(ed.n.id, b.id)) ? 'bell-ringing' : 'bell', !!S.reminders.get(remId(ed.n.id, b.id)))) : null,
      h('button.row-tool.press', { 'aria-label': 'מחיקת השורה', title: 'מחיקת השורה', on: { click: () => removeBlock(b, true) } }, icon('x')),
    ));
    const rr = S.reminders.get(remId(ed.n.id, b.id));
    if (rr) t.after(reminderChip(rr, true));
  } else {
    r.append(media(b));
    if (!ed.readOnly) r.append(h('div.row-tools', h('button.row-tool.press', { 'aria-label': 'מחיקה', on: { click: () => removeBlock(b, true) } }, icon('x'))));
  }
  return r;
}

function media(b) {
  let x = {};
  try { x = b.x ? JSON.parse(b.x) : {}; } catch { /* */ }
  const n = ed.n;
  switch (b.type) {
    case 'Divider': return h('hr.ed-hr');
    case 'Images': {
      const strip = h('div.ed-imgs');
      (x.pics || []).forEach((p) => {
        const img = h('img.ed-img', { alt: '', loading: 'lazy', style: { aspectRatio: String(p.r || 1) } });
        const box = h('button.ed-img-box.press', { 'data-scale': '.97', on: { click: () => img.src && lightbox(img.src) } }, img);
        mediaURL(n, b, p.f).then((u) => { if (u) img.src = u; else box.classList.add('missing'); });
        strip.append(box);
      });
      return strip;
    }
    case 'Voice': {
      const audio = h('audio', { preload: 'none' });
      const bars = (x.wave || []).slice(0, 48);
      const wave = h('div.wave', ...bars.map((v) => h('i', { style: { height: Math.max(12, Math.min(100, v)) + '%' } })));
      const btn = h('button.voice-play.press', { 'aria-label': 'נגינה' }, icon('play', true));
      const time = h('span.voice-time', fmtDur(x.ms || 0));
      btn.addEventListener('click', async () => {
        if (!audio.src) { const u = await mediaURL(n, b, x.f); if (!u) { toast('ההקלטה עוד לא הועלתה מהטלפון', { icon: 'cloud-slash' }); return; } audio.src = u; }
        if (audio.paused) { audio.play(); btn.replaceChildren(icon('pause', true)); } else { audio.pause(); btn.replaceChildren(icon('play', true)); }
      });
      audio.addEventListener('timeupdate', () => {
        const p = audio.currentTime / (audio.duration || 1);
        $$('i', wave).forEach((el, i) => el.classList.toggle('on', i / bars.length <= p));
        time.textContent = fmtDur(audio.currentTime * 1000);
      });
      audio.addEventListener('ended', () => { btn.replaceChildren(icon('play', true)); time.textContent = fmtDur(x.ms || 0); });
      return h('div.ed-voice', h('div.voice', btn, wave, time, audio), x.text ? h('p.voice-text', x.text) : null);
    }
    case 'Link': {
      let host = x.url || ''; try { host = new URL(x.url).hostname.replace(/^www\./, ''); } catch { /* */ }
      return h('a.ed-link.press', { href: x.url, target: '_blank', rel: 'noopener', 'data-scale': '.98' },
        h('span.lc-ico', (host[0] || '•').toUpperCase()), h('span.lc-txt', h('b', x.title || host), h('small', x.desc || host)), icon('arrow-square-out'));
    }
    case 'Place': return h('a.ed-link.press', { href: `https://www.google.com/maps/search/?api=1&query=${x.lat},${x.lng}`, target: '_blank', rel: 'noopener', 'data-scale': '.98' },
      h('span.lc-ico.place', icon('map-pin', true)), h('span.lc-txt', h('b', x.name || 'מקום'), h('small', x.address || '')), icon('arrow-square-out'));
    case 'Draw': return h('div.ed-draw', icon('pen-nib'), h('span', 'ציור — נפתח באפליקציה בטלפון'));
    default: return h('div');
  }
}
const fmtDur = (ms) => { const s = Math.round(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

function lightbox(src) {
  const s = sheet(null, h('img.lightbox', { src, alt: '' }), { wide: true });
  s.card.classList.add('lightbox-card');
}

function toolbar() {
  const t = (ic, label, f) => h('button.tool.press', { 'aria-label': label, title: label, 'data-scale': '.86', on: { mousedown: (e) => e.preventDefault(), click: f } }, icon(ic));
  return h('div.ed-tools',
    h('div.tools-group',
      t('check-square', 'רשימת סימון', () => setType('Check')),
      t('list-bullets', 'תבליטים', () => setType('Bullet')),
      t('list-numbers', 'מספור', () => setType('Num')),
      t('text-h-one', 'כותרת', () => setType('H1')),
      t('text-h-two', 'כותרת משנה', () => setType('H2')),
      t('quotes', 'ציטוט', () => setType('Quote')),
      t('minus', 'קו מפריד', addDivider),
    ),
    h('span.tools-sep'),
    h('div.tools-group',
      t('text-b', 'מודגש', () => inline('bold')),
      t('text-italic', 'נטוי', () => inline('italic')),
      t('text-underline', 'קו תחתון', () => inline('underline')),
      t('text-strikethrough', 'קו חוצה', () => inline('strikeThrough')),
      t('highlighter', 'מרקר', () => paintPick('h')),
      t('palette', 'צבע טקסט', () => paintPick('c')),
    ),
  );
}

// ------------------------------------------------------------------ editing

const textEl = (id) => ed?.panel.querySelector(`.ed-row[data-id="${id}"] .ed-text`);
const blockOf = (id) => ed.blocks.find((b) => b.id === id);
const current = () => (ed.focusId ? blockOf(ed.focusId) : null);

function onTitle(e) { ed.title = e.target.innerText.replace(/\n/g, ' '); ed.titleDirty = true; schedule(); }
function titleKeys(e) {
  if (e.key === 'Enter') { e.preventDefault(); const f = ed.blocks.find((b) => TEXT_TYPES.has(b.type)); if (f) focusBlock(f.id, 0); }
}

function onInput(b, el) {
  const { text, spans } = fromDOM(el);
  // markdown-ish starts: "- " "* " "[] " "1. " "# " "## " "> "
  if (b.type === 'P') {
    const m = /^(- |\* |\[\] |\[ \] |1\. |# |## |> )/.exec(text);
    if (m) {
      const type = { '- ': 'Bullet', '* ': 'Bullet', '[] ': 'Check', '[ ] ': 'Check', '1. ': 'Num', '# ': 'H1', '## ': 'H2', '> ': 'Quote' }[m[1]];
      Object.assign(b, { type, text: text.slice(m[1].length), spans: shift(spans, -m[1].length) });
      markDirty(b); rerow(b, 0); return;
    }
  }
  b.text = text; b.spans = spans;
  el.dataset.empty = text ? '' : '1';
  markDirty(b);
}

function paste(e, b, el) {
  const txt = e.clipboardData?.getData('text/plain');
  if (txt == null) return;
  e.preventDefault();
  const lines = txt.replace(/\r/g, '').split('\n');
  document.execCommand('insertText', false, lines[0]);
  if (lines.length > 1) {
    let after = b;
    for (const line of lines.slice(1)) after = insertAfter(after, { type: b.type === 'Check' || b.type === 'Bullet' || b.type === 'Num' ? b.type : 'P', text: line, indent: b.indent || 0 });
    focusBlock(after.id, after.text.length);
  }
}

function keys(e, b, el) {
  const off = caret(el);
  const sel = getSelection();
  const collapsed = sel.isCollapsed;
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sync(b, el);
    const list = ['Bullet', 'Num', 'Check'].includes(b.type);
    if (list && !b.text.trim()) { // empty list item: out of the list
      if ((b.indent || 0) > 0) b.indent--; else b.type = 'P';
      markDirty(b); rerow(b, 0); return;
    }
    const tail = { text: b.text.slice(off), spans: clip(b.spans, off, b.text.length, -off) };
    b.spans = clip(b.spans, 0, off, 0); b.text = b.text.slice(0, off);
    markDirty(b); rerow(b, null, true);
    const nb = insertAfter(b, { type: list ? b.type : 'P', text: tail.text, spans: tail.spans, indent: b.indent || 0 });
    focusBlock(nb.id, 0);
    return;
  }
  if (e.key === 'Backspace' && collapsed && off === 0) {
    sync(b, el);
    if (b.type !== 'P') { e.preventDefault(); b.type = 'P'; b.indent = 0; markDirty(b); rerow(b, 0); return; }
    const i = ed.blocks.indexOf(b);
    const prev = ed.blocks.slice(0, i).reverse().find((x) => TEXT_TYPES.has(x.type));
    const prevAdj = ed.blocks[i - 1];
    if (prev && prevAdj === prev) {
      e.preventDefault();
      const at = prev.text.length;
      prev.spans = [...prev.spans, ...shift(b.spans, at)];
      prev.text += b.text;
      markDirty(prev); rerow(prev);
      removeBlock(b, false);
      focusBlock(prev.id, at);
    }
    return;
  }
  if (e.key === 'Tab' && ['Bullet', 'Num', 'Check'].includes(b.type)) {
    e.preventDefault();
    b.indent = Math.max(0, Math.min(3, (b.indent || 0) + (e.shiftKey ? -1 : 1)));
    markDirty(b); rerow(b, off);
    return;
  }
  if ((e.key === 'ArrowUp' && off === 0) || (e.key === 'ArrowDown' && off === el.innerText.replace(/\n$/, '').length)) {
    const texts = ed.blocks.filter((x) => TEXT_TYPES.has(x.type));
    const k = texts.indexOf(b) + (e.key === 'ArrowUp' ? -1 : 1);
    if (texts[k]) { e.preventDefault(); focusBlock(texts[k].id, e.key === 'ArrowUp' ? texts[k].text.length : 0); }
  }
  if ((e.ctrlKey || e.metaKey) && !e.altKey) {
    const k = e.key.toLowerCase();
    if (k === 'b' || k === 'נ') { e.preventDefault(); inline('bold'); }
    if (k === 'i' || k === 'ן') { e.preventDefault(); inline('italic'); }
    if (k === 'u' || k === 'ו') { e.preventDefault(); inline('underline'); }
  }
}

function sync(b, el) { const { text, spans } = fromDOM(el); b.text = text; b.spans = spans; }
const shift = (spans, d) => spans.map((s) => ({ ...s, s: s.s + d, e: s.e + d })).filter((s) => s.e > 0);
const clip = (spans, a, z, d) => spans.map((s) => ({ ...s, s: Math.max(s.s, a) + d, e: Math.min(s.e, z) + d })).filter((s) => s.e > s.s);

function insertAfter(b, { type = 'P', text = '', spans = [], indent = 0 }) {
  const i = ed.blocks.indexOf(b);
  const nb = { id: uuid(), type, text, spans, indent, done: false, x: '', o: between(b.o, ed.blocks[i + 1]?.o) };
  ed.blocks.splice(i + 1, 0, nb);
  const r = row(nb);
  ed.panel.querySelector(`.ed-row[data-id="${b.id}"]`).after(r);
  animate(r, [{ opacity: 0, transform: 'translateY(-6px)' }, { opacity: 1, transform: 'none' }], 'glide');
  markDirty(nb);
  return nb;
}

function removeBlock(b, undoable) {
  if (ed.readOnly) { toast('יש לך הרשאת צפייה בפתק הזה', { icon: 'eye' }); return; }
  const i = ed.blocks.indexOf(b);
  if (i < 0) return;
  ed.blocks.splice(i, 1);
  if (!ed.blocks.some((x) => TEXT_TYPES.has(x.type))) insertAfter(ed.blocks.at(-1) || { o: 0 }, {});
  ed.dirty.delete(b.id);
  if (ed.saved.has(b.id)) ed.removed.add(b.id);
  const r = ed.panel.querySelector(`.ed-row[data-id="${b.id}"]`);
  if (r) animate(r, [{ opacity: 1, height: r.offsetHeight + 'px' }, { opacity: 0, height: '0px' }], 'glide').finished.then(() => r.remove()).catch(() => r.remove());
  renumber();
  schedule();
  if (undoable) {
    haptic('medium');
    toast('השורה נמחקה', { icon: 'trash', action: 'ביטול', onAction: () => {
      if (!ed) return;
      const at = Math.min(i, ed.blocks.length);
      ed.blocks.splice(at, 0, b);
      ed.removed.delete(b.id);
      markDirty(b);
      const before = ed.blocks[at - 1];
      const nr = row(b);
      if (before) ed.panel.querySelector(`.ed-row[data-id="${before.id}"]`)?.after(nr); else ed.panel.querySelector('.ed-blocks').prepend(nr);
      animate(nr, [{ opacity: 0 }, { opacity: 1 }], 'glide');
      renumber();
    } });
  }
}

function tick(b) {
  if (ed.readOnly) { haptic('reject'); toast('יש לך הרשאת צפייה בפתק הזה', { icon: 'eye' }); return; }
  b.done = !b.done;
  haptic(b.done ? 'confirm' : 'light');
  const r = ed.panel.querySelector(`.ed-row[data-id="${b.id}"]`);
  r?.classList.toggle('done', b.done);
  // sink it below the open lines of its list (after a beat, so the tick is seen)
  const i = ed.blocks.indexOf(b);
  const sunk = sinkChecked(ed.blocks, i);
  markDirty(b);
  if (sunk !== ed.blocks) setTimeout(() => {
    if (!ed) return;
    const firstTops = new Map($$('.ed-row', ed.panel).map((el) => [el.dataset.id, el.getBoundingClientRect().top]));
    ed.blocks = sunk;
    reorder(ed.blocks).forEach(markDirty);
    const box = $('.ed-blocks', ed.panel);
    ed.blocks.forEach((x) => { const el = box.querySelector(`.ed-row[data-id="${x.id}"]`); if (el) box.append(el); });
    $$('.ed-row', ed.panel).forEach((el) => { const d = (firstTops.get(el.dataset.id) ?? 0) - el.getBoundingClientRect().top; if (d) animate(el, [{ transform: `translateY(${d}px)` }, { transform: 'none' }], 'glide'); });
    renumber();
  }, 380);
}

function setType(type) {
  const b = current();
  if (!b) { const last = ed.blocks.filter((x) => TEXT_TYPES.has(x.type)).at(-1); if (last) { focusBlock(last.id, last.text.length); setTimeout(() => setType(type), 30); } return; }
  const el = textEl(b.id); sync(b, el);
  const off = caret(el);
  b.type = b.type === type ? 'P' : type;
  if (!['Bullet', 'Num', 'Check'].includes(b.type)) b.indent = 0;
  haptic('tick');
  markDirty(b); rerow(b, off);
}

function addDivider() {
  const b = current() || ed.blocks.at(-1);
  const d = insertAfter(b, { type: 'Divider' });
  const p = insertAfter(d, {});
  focusBlock(p.id, 0);
}

function inline(cmd) {
  const b = current(); if (!b) return;
  document.execCommand('styleWithCSS', false, false);
  document.execCommand(cmd, false, null);
  haptic('tick');
  onInput(b, textEl(b.id));
}

function paintPick(kind) {
  const b = current(); if (!b) { toast('קודם מסמנים טקסט', { icon: 'hand-tap' }); return; }
  const sel = getSelection();
  if (sel.isCollapsed) { toast('קודם מסמנים טקסט', { icon: 'hand-tap' }); return; }
  const range = sel.getRangeAt(0).cloneRange();
  const s = sheet(kind === 'h' ? 'מרקר' : 'צבע טקסט', h('div.inks',
    ...INKS.map((k) => h('button.ink-dot.press', { dataset: { k, kind }, 'aria-label': INK_NAMES[k], on: { click: () => { s.close(); paint(b, range, kind, k); } } }, h('span', INK_NAMES[k]))),
    h('button.ink-dot.none.press', { on: { click: () => { s.close(); paint(b, range, kind, null); } } }, h('span', 'בלי')),
  ));
}

function paint(b, range, kind, ink) {
  const el = textEl(b.id);
  if (!el) return;
  sync(b, el);
  const pre = document.createRange(); pre.selectNodeContents(el); pre.setEnd(range.startContainer, range.startOffset);
  const a = pre.toString().length, z = a + range.toString().length;
  b.spans = b.spans.flatMap((sp) => {
    if (!sp.t.startsWith(kind + ':') || sp.e <= a || sp.s >= z) return [sp];
    return [sp.s < a ? { ...sp, e: a } : null, sp.e > z ? { ...sp, s: z } : null].filter(Boolean);
  });
  if (ink) b.spans.push({ s: a, e: z, t: `${kind}:${ink}` });
  haptic('tick');
  markDirty(b); rerow(b, z);
}

/** Re-renders one row in place (type / indent / content changed); keeps the caret at [off]. */
function rerow(b, off = null, keepFocus = false) {
  const old = ed.panel.querySelector(`.ed-row[data-id="${b.id}"]`);
  if (!old) return;
  const nr = row(b);
  old.replaceWith(nr);
  renumber();
  if (off != null) focusBlock(b.id, off);
  else if (keepFocus) { /* focus moves on to the next row */ }
}

function renumber() { $$('.ed-row.t-num', ed.panel).forEach((el) => { const b = blockOf(el.dataset.id); if (b) el.querySelector('.num').textContent = numberOf(b) + '.'; }); }

function tailClick() {
  if (ed.readOnly) return;
  const last = ed.blocks.at(-1);
  if (last && TEXT_TYPES.has(last.type) && !last.text) { focusBlock(last.id, 0); return; }
  const nb = insertAfter(last, {});
  focusBlock(nb.id, 0);
}

// ------------------------------------------------------------------ caret

function caret(el) {
  const s = getSelection();
  if (!s.rangeCount || !el.contains(s.anchorNode)) return 0;
  const r = s.getRangeAt(0).cloneRange();
  const pre = document.createRange(); pre.selectNodeContents(el); pre.setEnd(r.startContainer, r.startOffset);
  return pre.toString().length;
}
function focusBlock(id, off) {
  const el = textEl(id);
  if (!el) return;
  el.focus();
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  let left = off ?? 0, node, set = false;
  const range = document.createRange();
  while ((node = walker.nextNode())) {
    if (left <= node.length) { range.setStart(node, left); set = true; break; }
    left -= node.length;
  }
  if (!set) { range.selectNodeContents(el); range.collapse(false); }
  range.collapse(true);
  const s = getSelection(); s.removeAllRanges(); s.addRange(range);
}

// ------------------------------------------------------------------ saving + live changes

function markDirty(b) { delete b._new; ed.dirty.add(b.id); ed.removed.delete(b.id); schedule(); }
function schedule() { clearTimeout(ed.timer); status('saving'); const e = ed; ed.timer = setTimeout(() => flush(e).catch(() => status('error')), 550); }
function status(s) {
  const el = ed?.panel.querySelector('.ed-status');
  if (!el) return;
  el.textContent = { saving: 'שומר…', saved: 'נשמר', error: 'לא נשמר — אין חיבור?' }[s] || '';
  el.dataset.s = s;
}

async function flush(e) {
  clearTimeout(e.timer);
  const n = S.notes.get(e.id);
  if (!n || e.readOnly) return;
  const changedBlocks = e.blocks.filter((b) => e.dirty.has(b.id) && !(e.saved.has(b.id) && sameBlock(e.saved.get(b.id), b)));
  const removed = [...e.removed];
  e.dirty.clear(); e.removed.clear();
  const tasks = [];
  if (changedBlocks.length || removed.length) {
    changedBlocks.forEach((b) => e.saved.set(b.id, { ...b }));
    removed.forEach((id) => e.saved.delete(id));
    tasks.push(writeBlocks(n, changedBlocks, removed));
  }
  if (e.titleDirty && e.title !== n.title) { e.titleDirty = false; tasks.push(setMeta(n, { title: e.title })); }
  await Promise.all(tasks);
  if (ed === e) status('saved');
}

/** Another device changed the note: take in every line not being edited here. */
function onRemote(what) {
  if (!ed || (what !== 'notes' && what !== 'reminders')) return;
  const n = S.notes.get(ed.id);
  if (!n) { closeEditor(); toast('הפתק נמחק ממכשיר אחר', { icon: 'trash' }); return; }
  const prevRole = ed.readOnly;
  ed.n = n;
  ed.readOnly = !canEdit(n);
  if (what === 'reminders' || prevRole !== ed.readOnly) { const f = ed.focusId; build(); if (f) focusBlock(f, 0); return; }
  ed.panel.style.background = `var(--n-${n.color.toLowerCase()})`;
  if (!ed.titleDirty && document.activeElement !== $('.ed-title', ed.panel) && n.title !== ed.title) { ed.title = n.title; $('.ed-title', ed.panel).innerHTML = esc(clean(n.title)); }
  const remote = new Map(n.blocks.map((b) => [b.id, b]));
  let structure = false;
  const touched = new Set();
  const next = [];
  for (const b of ed.blocks) {
    const r = remote.get(b.id);
    if (!r) {
      // gone elsewhere: drop it unless it's new here / being typed
      if (ed.saved.has(b.id) && !ed.dirty.has(b.id) && ed.focusId !== b.id) { structure = true; ed.saved.delete(b.id); continue; }
      next.push(b); continue;
    }
    if (!ed.dirty.has(b.id) && ed.focusId !== b.id && !sameBlock(r, b)) {
      const moved = r.o !== b.o;
      Object.assign(b, { ...r }); ed.saved.set(b.id, { ...r });
      if (moved) { structure = true; touched.add(b.id); }
      const el = ed.panel.querySelector(`.ed-row[data-id="${b.id}"]`);
      if (el && !moved) { const nr = row(b); el.replaceWith(nr); animate(nr, [{ backgroundColor: 'color-mix(in srgb, var(--accent) 22%, transparent)' }, { backgroundColor: 'transparent' }], 'glide', { duration: 900 }); }
    }
    next.push(b);
  }
  for (const [id, r] of remote) if (!next.some((b) => b.id === id)) { next.push({ ...r }); ed.saved.set(id, { ...r }); structure = true; }
  if (structure) {
    next.sort((a, b) => a.o - b.o || (a.id < b.id ? -1 : 1));
    ed.blocks = next;
    const box = $('.ed-blocks', ed.panel);
    const keep = new Map($$('.ed-row', box).map((el) => [el.dataset.id, el]));
    box.replaceChildren(...ed.blocks.map((b) => (b.id === ed.focusId && keep.get(b.id)) || (!touched.has(b.id) && keep.get(b.id)) || row(b)));
    renumber();
  }
}
