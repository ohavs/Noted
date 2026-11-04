// TV / wall screens: a TV opens noted-ce451.web.app/tv and shows a code; here (or on the phone's
// browser, by scanning its QR) the code is linked to chosen notes. The TV reads only screens/{code},
// a copy a function keeps fresh on every note change.
import { S, saveScreen, deleteScreen } from './model.js';
import { h, $, $$, icon, sheet, toast, haptic, confirmDialog } from './ui.js';
import { clean } from './rich.js';

const ALPHA = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
export const fmtCode = (c) => `${c.slice(0, 4)}-${c.slice(4)}`;

export function openScreens() {
  const list = S.screens;
  return h('div.screens',
    h('section.scr-hero',
      h('div.scr-tv', icon('television-simple')),
      h('div',
        h('h2', 'פתקים על המסך הגדול'),
        h('p', 'פותחים בדפדפן של הטלוויזיה (או טאבלט על המקרר) את ', h('b.ltr', 'noted-ce451.web.app/tv'), ' — יופיע קוד. מקלידים אותו כאן, או סורקים את הברקוד בטלפון, ובוחרים פתקים. המסך מתעדכן לבד.'),
        h('button.btn.accent.press', { on: { click: () => connectScreen('') } }, icon('plus'), 'חיבור מסך'),
      ),
    ),
    list.length ? h('div.scr-list', ...list.map((sc) => h('article.scr-card',
      h('div.scr-card-top', icon('television-simple', true), h('b', sc.name || 'מסך'), h('span.scr-code.ltr', fmtCode(sc.id))),
      h('div.scr-notes', ...(sc.noteIds || []).map((id) => S.notes.get(id)).filter(Boolean).map((n) => h('span.scr-note', { style: { background: `var(--n-${n.color.toLowerCase()})` } }, clean(n.title) || 'פתק'))),
      h('div.scr-actions',
        h('button.btn.quiet.press', { on: { click: () => editScreen(sc.id, sc) } }, icon('pencil-simple'), 'עריכה'),
        h('button.btn.quiet.danger.press', { on: { click: async () => {
          if (await confirmDialog({ title: 'לנתק את המסך?', text: 'הוא יחזור להציג קוד חיבור.', ok: 'ניתוק', danger: true, ic: 'television-simple' })) { await deleteScreen(sc.id); toast('המסך נותק', { icon: 'television-simple' }); }
        } } }, icon('link-break'), 'ניתוק'),
      ),
    ))) : null,
  );
}

/** Asks for the TV's code (prefilled when opened from its QR), then which notes. */
export function connectScreen(code) {
  if (code && S.screens.some((s) => s.id === code)) return editScreen(code, S.screens.find((s) => s.id === code));
  const input = h('input.field.code-field.ltr', { value: code ? fmtCode(code) : '', placeholder: 'XXXX-XXXX', maxlength: 9, autofocus: true, autocomplete: 'off', spellcheck: 'false' });
  input.addEventListener('input', () => {
    const raw = input.value.toUpperCase().replace(/[^A-Z0-9]/g, '').split('').filter((c) => ALPHA.includes(c)).join('').slice(0, 8);
    input.value = raw.length > 4 ? fmtCode(raw) : raw;
  });
  const go = () => {
    const raw = input.value.replace(/-/g, '');
    if (raw.length !== 8) { haptic('reject'); input.focus(); return; }
    s.close();
    setTimeout(() => editScreen(raw, null), 150);
  };
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
  const s = sheet('חיבור מסך', h('div.stack-12',
    h('p.set-text', 'הקוד שמופיע על המסך:'), input,
    h('button.btn.dark.full.press', { on: { click: go } }, 'המשך'),
  ));
}

function editScreen(code, existing) {
  const chosen = new Set(existing?.noteIds || []);
  let name = existing?.name || 'סלון';
  let theme = existing?.theme || 'auto';
  const notes = [...S.notes.values()].filter((n) => !n.trashedAt && !n.archived).sort((a, b) => (b.pinned - a.pinned) || (a.position - b.position));
  const body = h('div.stack-12');
  const s = sheet(existing ? 'עריכת מסך' : 'מה יופיע על המסך', body, { wide: true });
  const draw = () => {
    body.replaceChildren(
      h('div.row-2',
        h('input.field', { value: name, placeholder: 'שם המסך', on: { input: (e) => { name = e.target.value; } } }),
        h('div.seg', ...[['auto', 'אוטומטי'], ['dark', 'כהה'], ['light', 'בהיר']].map(([v, l]) => h(`button.seg-item.press${theme === v ? '.on' : ''}`, { on: { click: () => { theme = v; haptic('tick'); draw(); } } }, l))),
      ),
      h('div.pick-notes', ...notes.map((n) => h(`button.pick-note.press${chosen.has(n.id) ? '.on' : ''}`, { 'data-scale': '.97', style: { background: `var(--n-${n.color.toLowerCase()})` }, on: { click: () => { chosen.has(n.id) ? chosen.delete(n.id) : chosen.add(n.id); haptic('tick'); draw(); } } },
        h('span.pn-check', icon('check')), h('b', clean(n.title) || 'פתק'), h('small', n.blocks.filter((b) => b.text).slice(0, 2).map((b) => clean(b.text)).join(' · '))))),
      h('button.btn.dark.full.press', { on: { click: async () => {
        if (!chosen.size) { haptic('reject'); toast('בוחרים לפחות פתק אחד', { icon: 'hand-tap' }); return; }
        try {
          await saveScreen(code, { name: name.trim() || 'מסך', noteIds: [...chosen], theme });
          haptic('confirm'); s.close(); toast(existing ? 'המסך עודכן' : 'המסך מחובר', { icon: 'television-simple' });
        } catch (e) { toast(String(e.message) === 'taken' ? 'הקוד הזה כבר בשימוש — רעננו את המסך לקוד חדש' : 'לא הצליח — בדקו את הקוד', { icon: 'warning-circle' }); }
      } } }, `${existing ? 'שמירה' : 'חיבור'} · ${chosen.size} פתקים`),
    );
  };
  draw();
}

/** From a note's menu: onto one of my screens. */
export function addToScreen(n) {
  if (!S.screens.length) { connectScreen(''); return; }
  const s = sheet('הצגה במסך', h('div.menu', ...S.screens.map((sc) => {
    const on = (sc.noteIds || []).includes(n.id);
    return h(`button.menu-row.press${on ? '.on' : ''}`, { on: { click: async () => {
      s.close();
      const ids = on ? sc.noteIds.filter((x) => x !== n.id) : [...(sc.noteIds || []), n.id];
      await saveScreen(sc.id, { name: sc.name, noteIds: ids, theme: sc.theme });
      toast(on ? `הוסר מ${sc.name}` : `מוצג ב${sc.name}`, { icon: 'television-simple' });
    } } }, icon('television-simple'), h('span', sc.name || 'מסך'), on ? icon('check') : null);
  }), h('button.menu-row.press', { on: { click: () => { s.close(); connectScreen(''); } } }, icon('plus'), h('span', 'מסך חדש'))));
}
