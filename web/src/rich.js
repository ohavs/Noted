// Inline styles as the app keeps them: spans [{s, e, t}] over a block's text.
// t: b i u s (bold, italic, underline, strike), c:<Ink> text colour, h:<Ink> marker.
export const INKS = ['Red', 'Orange', 'Green', 'Blue', 'Purple'];
export const INK_NAMES = { Red: 'אדום', Orange: 'כתום', Green: 'ירוק', Blue: 'כחול', Purple: 'סגול' };

export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ZW = /[​‌‍﻿]/g;
export const clean = (s) => String(s ?? '').replace(ZW, '');

/** Text + spans → HTML (b/i/u/s tags, ink spans, marks). */
export function toHTML(text, spans = []) {
  text = String(text ?? '');
  if (!spans?.length) return esc(text);
  const cuts = new Set([0, text.length]);
  for (const sp of spans) { cuts.add(Math.max(0, Math.min(text.length, sp.s))); cuts.add(Math.max(0, Math.min(text.length, sp.e))); }
  const pts = [...cuts].sort((a, b) => a - b);
  let out = '';
  for (let i = 0; i < pts.length - 1; i++) {
    const a = pts[i], b = pts[i + 1];
    if (a >= b) continue;
    const on = spans.filter((sp) => sp.s <= a && sp.e >= b).map((sp) => sp.t);
    let h = esc(text.slice(a, b));
    if (on.includes('s')) h = `<s>${h}</s>`;
    if (on.includes('u')) h = `<u>${h}</u>`;
    if (on.includes('i')) h = `<i>${h}</i>`;
    if (on.includes('b')) h = `<b>${h}</b>`;
    const c = on.find((t) => t.startsWith('c:'));
    if (c) h = `<span class="ink" data-c="${esc(c.slice(2))}">${h}</span>`;
    const m = on.find((t) => t.startsWith('h:'));
    if (m) h = `<mark data-h="${esc(m.slice(2))}">${h}</mark>`;
    out += h;
  }
  return out;
}

/** A contenteditable element → { text, spans } (the inverse of toHTML; tolerant of browser markup). */
export function fromDOM(el) {
  let text = '';
  const runs = []; // [start, end, styles[]]
  const walk = (node, styles) => {
    if (node.nodeType === 3) {
      const t = node.nodeValue;
      if (t) { runs.push([text.length, text.length + t.length, styles]); text += t; }
      return;
    }
    if (node.nodeType !== 1) return;
    if (node.tagName === 'BR') { if (node.nextSibling || node.previousSibling) { text += '\n'; } return; }
    const s = [...styles];
    const tag = node.tagName;
    const st = node.style || {};
    if (tag === 'B' || tag === 'STRONG' || Number(st.fontWeight) >= 600 || st.fontWeight === 'bold') s.push('b');
    if (tag === 'I' || tag === 'EM' || st.fontStyle === 'italic') s.push('i');
    if (tag === 'U' || String(st.textDecoration || st.textDecorationLine).includes('underline')) s.push('u');
    if (tag === 'S' || tag === 'STRIKE' || tag === 'DEL' || String(st.textDecoration || st.textDecorationLine).includes('line-through')) s.push('s');
    if (node.dataset?.c) s.push('c:' + node.dataset.c);
    if (node.dataset?.h) s.push('h:' + node.dataset.h);
    const block = tag === 'DIV' || tag === 'P';
    if (block && text.length && !text.endsWith('\n')) text += '\n';
    node.childNodes.forEach((ch) => walk(ch, s));
  };
  el.childNodes.forEach((ch) => walk(ch, []));
  // one span per style per contiguous stretch
  const spans = [];
  const kinds = new Set(runs.flatMap((r) => r[2]));
  for (const k of kinds) {
    let cur = null;
    for (const [a, b, st] of runs) {
      if (st.includes(k)) {
        if (cur && cur.e === a) cur.e = b; else { cur = { s: a, e: b, t: k }; spans.push(cur); }
      } else cur = null;
    }
  }
  if (text.endsWith('\n')) text = text.slice(0, -1);
  return { text, spans: spans.filter((x) => x.e > x.s && x.e <= text.length + 1).map((x) => ({ ...x, e: Math.min(x.e, text.length) })) };
}

export const sameSpans = (a = [], b = []) => JSON.stringify([...a].sort(cmp)) === JSON.stringify([...b].sort(cmp));
const cmp = (x, y) => x.s - y.s || x.e - y.e || (x.t < y.t ? -1 : 1);
