// Time reminders, the same model as the app (data/Reminders.kt): local date-times + IANA zone,
// rule "" | d | w:1,3 (ISO days) | m | n:N:d|w|m, `until` a local date.
export const pad = (n) => String(n).padStart(2, '0');

/** "2026-11-04T20:00" (LocalDateTime.toString with zero seconds) ↔ Date (local). */
export const toLocal = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
export function fromLocal(s, zone) {
  const m = /^(\d+)-(\d+)-(\d+)T(\d+):(\d+)(?::(\d+))?/.exec(s || '');
  if (!m) return null;
  const [y, mo, d, h, mi, se] = m.slice(1).map((x) => Number(x || 0));
  const here = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const naive = new Date(y, mo - 1, d, h, mi, se);
  if (!zone || zone === here) return naive;
  // same wall time in another zone: shift by the offset difference
  const asUTC = Date.UTC(y, mo - 1, d, h, mi, se);
  const offset = (z, t) => {
    const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: z, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
      .formatToParts(new Date(t)).map((x) => [x.type, x.value]));
    return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - t;
  };
  try { return new Date(asUTC - offset(zone, asUTC)); } catch { return naive; }
}

export function parseRule(rule, until) {
  const p = String(rule || '').split(':');
  const u = until ? new Date(until + 'T00:00') : null;
  switch (p[0]) {
    case 'd': return { kind: 'daily', until: u };
    case 'm': return { kind: 'monthly', until: u };
    case 'w': return { kind: 'days', days: (p[1] || '').split(',').map(Number).filter(Boolean), until: u };
    case 'n': return { kind: 'custom', every: Number(p[1]) || 1, unit: p[2] || 'd', until: u };
    default: return { kind: 'none' };
  }
}
export function ruleOf(r) {
  switch (r.kind) {
    case 'daily': return 'd';
    case 'monthly': return 'm';
    case 'days': return 'w:' + [...r.days].sort().join(',');
    case 'custom': return `n:${r.every}:${r.unit}`;
    default: return '';
  }
}

const isoDay = (d) => (d.getDay() === 0 ? 7 : d.getDay());
const addMonths = (d, k) => { const x = new Date(d); const day = x.getDate(); x.setDate(1); x.setMonth(x.getMonth() + k); x.setDate(Math.min(day, new Date(x.getFullYear(), x.getMonth() + 1, 0).getDate())); return x; };
const addDays = (d, k) => { const x = new Date(d); x.setDate(x.getDate() + k); return x; };

/** First occurrence strictly after [after] (both local Dates), or null when the series is over. */
export function next(rep, anchor, after) {
  if (rep.kind === 'none') return null;
  const last = rep.until ? addDays(rep.until, 1) : null;
  let t = null;
  const step = (f) => { let k = 0; while (f(k) <= after && k < 100000) k++; return f(k); };
  if (rep.kind === 'daily') t = step((k) => addDays(anchor, k));
  else if (rep.kind === 'monthly') t = step((k) => addMonths(anchor, k));
  else if (rep.kind === 'custom') {
    const e = Math.max(1, rep.every);
    t = step((k) => (rep.unit === 'm' ? addMonths(anchor, e * k) : addDays(anchor, (rep.unit === 'w' ? 7 : 1) * e * k)));
  } else if (rep.kind === 'days' && rep.days.length) {
    let d = new Date(Math.max(anchor, after));
    d.setHours(anchor.getHours(), anchor.getMinutes(), 0, 0);
    for (let i = 0; i < 9 && !t; i++) {
      if (rep.days.includes(isoDay(d)) && d > after && d >= anchor) t = new Date(d);
      d = addDays(d, 1);
    }
  }
  return t && (!last || t < last) ? t : null;
}

/** A reminder document → its view: when it rings next, epoch ms (null = nothing pending). */
export function dueAt(r) {
  const occ = fromLocal(r.occ, r.zone)?.getTime();
  return [r.snoozeAt || null, r.fired ? null : occ].filter((x) => x != null).reduce((a, b) => (a == null ? b : Math.min(a, b)), null);
}
export const occAt = (r) => fromLocal(r.occ, r.zone)?.getTime() ?? 0;

const DAYS = ['א׳', 'ב׳', 'ג׳', 'ד׳', 'ה׳', 'ו׳', 'ש׳'];
export function repeatText(rep) {
  switch (rep.kind) {
    case 'daily': return 'כל יום';
    case 'monthly': return 'כל חודש';
    case 'days': return rep.days.length === 7 ? 'כל יום' : 'בימי ' + rep.days.map((d) => DAYS[d % 7]).join(' ');
    case 'custom': return `כל ${rep.every} ${{ d: 'ימים', w: 'שבועות', m: 'חודשים' }[rep.unit]}`;
    default: return '';
  }
}
