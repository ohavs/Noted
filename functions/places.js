/**
 * Google Places search for the app, from the server.
 *
 * The app used to call Places with the Maps key baked into the APK — anyone can unzip an APK,
 * read the key and fake the package/certificate headers it's locked to. Now the APK key is
 * Maps SDK only, and Places goes through here with a server key (Secret Manager, never shipped):
 * only signed-in users, a daily cap per person and for everyone together (Asia/Jerusalem days).
 *
 * Kill switch: the "noted places kill switch" budget publishes to `budget-alerts`; once the
 * month's cost reaches the budget, `budgetGuard` turns the Places API off for the project
 * (billing stays on, so Firebase keeps working). The app falls back to the phone's geocoder.
 * Turning it back on is a deliberate act (console / CI), never automatic.
 *
 * Media cap: the "noted media cap" budget (higher) turns Firebase Storage off the same way —
 * the only cost a signed-in stranger could run up (uploads to their own folder, downloads of
 * them). Notes keep syncing through Firestore; media waits until Storage is turned back on.
 */
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onMessagePublished } = require('firebase-functions/v2/pubsub');
const { defineSecret } = require('firebase-functions/params');
const { logger } = require('firebase-functions/v2');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');

const PLACES_KEY = defineSecret('PLACES_KEY');
const BASE = 'https://places.googleapis.com/v1';
const PROJECT = 'noted-ce451';
// budget display name → the API it turns off
const GUARDED = {
  'noted places kill switch': 'places.googleapis.com',
  'noted media cap': 'firebasestorage.googleapis.com',
};

// Autocomplete that ends in a details lookup is free; details are 10K free a month.
// 150 details/day for everyone stays under that; abandoned typing sessions are the only cost.
const CAP = {
  auto: { user: 200, all: 600 },
  details: { user: 40, all: 150 },
};

const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Jerusalem' });

/** Counts one call; false once this person or everyone together reached today's cap. */
async function spend(uid, op) {
  const db = getFirestore();
  const ref = db.doc(`usage/places-${today()}`);
  return db.runTransaction(async (tx) => {
    const d = (await tx.get(ref)).data() || {};
    const all = d[op] || 0;
    const mine = (d.u && d.u[uid] && d.u[uid][op]) || 0;
    if (all >= CAP[op].all || mine >= CAP[op].user) return false;
    tx.set(ref, { [op]: FieldValue.increment(1), u: { [uid]: { [op]: FieldValue.increment(1) } } }, { merge: true });
    return true;
  });
}

async function google(path, { body, fields } = {}) {
  const headers = { 'X-Goog-Api-Key': PLACES_KEY.value() };
  if (fields) headers['X-Goog-FieldMask'] = fields;
  if (body) headers['Content-Type'] = 'application/json; charset=utf-8';
  const res = await fetch(BASE + path, {
    method: body ? 'POST' : 'GET',
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(8000),
  });
  const text = await res.text();
  if (!res.ok) {
    logger.warn('places', res.status, text.slice(0, 300));
    throw new HttpsError('unavailable', `places ${res.status}`);
  }
  return JSON.parse(text);
}

const str = (v, max) => (typeof v === 'string' && v.length > 0 && v.length <= max ? v : null);
const num = (v, lo, hi) => (typeof v === 'number' && Number.isFinite(v) && v >= lo && v <= hi ? v : null);

exports.places = onCall({ secrets: [PLACES_KEY], timeoutSeconds: 20, maxInstances: 3 }, async (req) => {
  const uid = req.auth?.uid;
  if (!uid) throw new HttpsError('unauthenticated', 'sign in first');
  const d = req.data || {};
  const op = d.op === 'details' ? 'details' : d.op === 'auto' ? 'auto' : null;
  const session = str(d.session, 64);
  if (!op || !session) throw new HttpsError('invalid-argument', 'op/session');
  if (!(await spend(uid, op))) throw new HttpsError('resource-exhausted', 'daily cap');

  if (op === 'auto') {
    const input = str(d.input, 120);
    if (!input) throw new HttpsError('invalid-argument', 'input');
    const body = { input, languageCode: 'he', regionCode: 'il', sessionToken: session };
    const lat = num(d.lat, -90, 90);
    const lng = num(d.lng, -180, 180);
    if (lat != null && lng != null) {
      const center = { latitude: lat, longitude: lng };
      body.locationBias = { circle: { center, radius: 30000 } };
      body.origin = center;
    }
    const res = await google('/places:autocomplete', { body });
    const out = [];
    for (const s of res.suggestions || []) {
      const p = s.placePrediction;
      if (!p || !p.placeId) continue;
      const f = p.structuredFormat || {};
      const whole = p.text?.text || '';
      const name = f.mainText?.text || whole.split(',')[0].trim();
      if (!name) continue;
      out.push({
        id: p.placeId,
        name,
        address: f.secondaryText?.text || whole,
        meters: typeof p.distanceMeters === 'number' ? p.distanceMeters : null,
      });
    }
    return { suggestions: out };
  }

  const id = typeof d.id === 'string' && /^[A-Za-z0-9_-]{1,400}$/.test(d.id) ? d.id : null;
  if (!id) throw new HttpsError('invalid-argument', 'id');
  const q = `languageCode=he&regionCode=il&sessionToken=${encodeURIComponent(session)}`;
  const res = await google(`/places/${id}?${q}`, { fields: 'id,location,formattedAddress,types' });
  if (!res.location) throw new HttpsError('not-found', 'no location');
  return {
    address: res.formattedAddress || '',
    lat: res.location.latitude,
    lng: res.location.longitude,
    types: res.types || [],
  };
});

/** Budget notification → the guarded API off once the month's cost reaches that budget. */
exports.budgetGuard = onMessagePublished({ topic: 'budget-alerts', retry: false }, async (ev) => {
  const m = ev.data.message.json || {};
  const api = GUARDED[m.budgetDisplayName];
  if (!api) return;
  if (!(Number(m.costAmount) >= Number(m.budgetAmount))) return;
  const meta = await fetch('http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token', {
    headers: { 'Metadata-Flavor': 'Google' },
  }).then((r) => r.json());
  const res = await fetch(`https://serviceusage.googleapis.com/v1/projects/${PROJECT}/services/${api}:disable`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${meta.access_token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ disableDependentServices: false }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`disable ${api} ${res.status}: ${text.slice(0, 300)}`);
  logger.error(`KILL SWITCH: ${api} turned off`, { budget: m.budgetDisplayName, cost: m.costAmount, amount: m.budgetAmount, currency: m.currencyCode });
});
