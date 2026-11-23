/**
 * راه‌یاب افغانستان — Cloudflare Worker + D1
 * ضمیمهٔ ۱: سیستم جامع اطلاعات جاده‌ای (POI، تجهیزات، خدمات، گزارش‌های زنده)
 *
 * نکات حریم خصوصی و امنیت (طبق ضمیمهٔ ۱):
 *  - فقط موقعیت و مشخصات عمومی تجهیزات؛ هیچ دسترسی به تصاویر، شبکهٔ دوربین‌ها
 *    یا اطلاعات امنیتی وجود ندارد.
 *  - موقعیت گشت‌های متحرک و ایست‌های موقت تأییدنشده منتشر نمی‌شود؛ متن‌های
 *    دارای واژه‌های امنیتی حساس رد می‌شوند.
 *  - هر دستگاه (device_id) برای هر مکان فقط یک رأی دارد؛ رأی‌ها و گزارش‌های
 *    تکراری همان دستگاه بی‌اثر می‌شوند.
 *  - سرعت مجاز (speed_limit) فقط از ورودی رسمی تأییدشده پذیرفته می‌شود؛
 *    ورودی کاربران همیشه «unverified» ذخیره می‌شود.
 *
 * APIها (بخش دهم):
 *  GET  /api/v1/health
 *  GET  /api/v1/pois                 ?bbox|minLat…|category|province|status|since|limit
 *  GET  /api/v1/pois/nearby          ?lat&lon&radius&category&limit
 *  GET  /api/v1/pois/along-route     ?route=lat,lon;lat,lon…&corridor&categories&limit
 *  GET  /api/v1/pois/search          ?q&bbox&category&limit
 *  GET  /api/v1/pois/:id
 *  POST /api/v1/pois
 *  POST /api/v1/pois/:id/verify      {device_id, vote: confirm|deny}
 *  POST /api/v1/pois/:id/report      {type, description, device_id}
 *  GET  /api/v1/equipment            ?bbox|type|limit
 *  GET  /api/v1/services             ?bbox|type|limit
 *  GET  /api/v1/reports              ?bbox|type        (گزارش‌های زندهٔ ۲۴ ساعته)
 *  POST /api/v1/reports              {type, note, lat, lon, device_id, severity?, seg?, dir?, client_uuid?}
 *  POST /api/v1/reports/:id/verify   {device_id, vote: confirm|deny}   (تأیید اجتماعی گزارش زنده — بخش ۹)
 *
 * موتور ترافیک خودکار (بخش ۱۰ تا ۱۴ سند جامع) — بدون هیچ دادهٔ جعلی:
 *  POST /api/v1/traffic/samples      {device_id, samples:[{seg, speed, bearing, acc, ts?, road?, road_type?}]}
 *     ← نمونهٔ سرعت ناشناس: فقط شناسهٔ قطعهٔ شبکه‌ای + سرعت + جهت + دقت GPS.
 *        هیچ مختصاتی از راننده ذخیره نمی‌شود (حریم خصوصی بند ۲۴).
 *  GET  /api/v1/traffic/states       ?bbox|limit|include_normal
 *     ← وضعیت محاسبه‌شدهٔ هر قطعه: عادی/احتمال/متوسط/سنگین/بسیار شدید/ناکافی…
 *  GET  /api/v1/traffic/along-route  ?route=lat,lon;…&corridor&limit
 *
 * پاک‌سازی دوره‌ای با Cron (بخش ۲۵): نمونه‌های کهنه و وضعیت‌های منقضی حذف می‌شوند.
 */

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Cache-Control': 'no-store',
};

/** واژه‌های ممنوع برای انتشار — هم‌سان با برنامه (حفاظت نیروهای امنیتی) */
const SENSITIVE = [
  'چک پوست', 'چک‌پوست', 'چک پست', 'چک‌پست', 'چک پوینت', 'چک‌پوینت',
  'checkpoint', 'check post', 'checkpost', 'بازرسی', 'ایست بازرسی',
  'پوسته', 'پوسته امنیتی', 'نیروهای امنیتی', 'پایگاه', 'اردوگاه',
  'نظامی', 'زره‌پوش', 'تانک جنگی', 'سرباز', 'تسلیم‌گاه',
];

const POI_CATEGORIES = new Set([
  // دوربین‌ها و تجهیزات
  'speed_camera', 'surveillance_camera', 'avg_speed_check', 'smart_speed_sign',
  'speed_warning_display', 'vehicle_counter', 'traffic_sensor',
  // دست‌اندازها و موانع
  'bump', 'speed_bump_nonstandard', 'bump_damaged', 'bump_unmarked',
  'guardrail', 'guardrail_damaged', 'sign_warning', 'sign_speed_limit',
  'sign_danger', 'sign_oneway', 'traffic_light', 'warning_light',
  'crosswalk', 'school_line', 'roundabout', 'junction',
  // پل، تونل، زیرگذر
  'bridge', 'bridge_highway', 'bridge_pedestrian', 'tunnel', 'underpass', 'overpass',
  // پولیس و کنترول رسمی
  'police_station', 'police_district', 'traffic_police', 'accident_center',
  'emergency_center', 'vehicle_check_center', 'document_check_center',
  'weight_control', 'weighbridge', 'technical_inspection', 'traffic_service_center',
  // عوارض و مرز
  'toll_plaza', 'fare_point', 'border_crossing', 'customs', 'cargo_clearance',
  // سوخت و انرژی
  'fuel_station', 'diesel_station', 'gas_station', 'ev_charger',
  // ترمیم و نگهداری
  'car_repair', 'motorcycle_repair', 'tire_shop', 'battery_service',
  'parts_store', 'tow_truck', 'roadside_assist', 'car_wash', 'oil_change',
  // اضطراری و درمانی
  'hospital', 'clinic', 'emergency_medical', 'ambulance_station',
  'pharmacy', 'fire_station', 'rescue_center', 'first_aid',
  // رفاهی
  'parking', 'truck_parking', 'rest_area', 'guesthouse', 'hotel',
  'restaurant', 'mosque', 'public_toilet', 'market', 'bus_stop', 'terminal',
]);

const EQUIPMENT_TYPES = new Set([
  'speed_camera', 'surveillance_camera', 'avg_speed_check', 'smart_speed_sign',
  'speed_warning_display', 'vehicle_counter', 'traffic_sensor', 'tunnel_bridge_alert',
]);

const SERVICE_TYPES = new Set([
  'fuel', 'diesel', 'gas', 'ev_charging', 'repair', 'motorcycle_repair',
  'tire', 'battery', 'parts', 'tow', 'roadside_assist', 'car_wash', 'oil',
  'hospital', 'clinic', 'emergency', 'ambulance', 'pharmacy', 'fire_station',
  'rescue', 'first_aid', 'parking', 'truck_parking', 'rest_area', 'guesthouse',
  'hotel', 'restaurant', 'mosque', 'toilet', 'market', 'bus_stop', 'terminal',
]);

const LIVE_REPORT_TYPES = new Set([
  'accident', 'heavy_traffic', 'medium_traffic', 'gridlock', 'road_closed',
  'temporary_restriction', 'road_damage', 'pothole', 'landslide', 'flood',
  'snowfall', 'ice', 'fog', 'slippery', 'road_works', 'construction',
  'bridge_damage', 'broken_traffic_light', 'broken_equipment',
  'toll_congestion', 'border_congestion', 'speed_bump', 'speed_camera',
  'police', 'red_light_camera', 'narrow', 'school_zone', 'crosswalk',
  'rail_crossing', 'animals', 'broken_traffic_light_light', 'speed_limit_change',
  'steep_down', 'steep_up', 'hazard',
]);

const POI_REPORT_TYPES = new Set([
  'broken', 'removed', 'wrong_info', 'moved', 'closed', 'reopened', 'price_changed',
]);

const POI_VERIFY_THRESHOLD = 2;   // رأی تأیید لازم برای user_verified
const POI_DENY_DISABLE = 3;       // رأی رد لازم برای غیرفعال‌شدن مکان
const POI_REPORT_DISABLE = 3;     // گزارش مستقل «حذف‌شده» برای غیرفعال‌شدن

const LIVE_VERIFY_CONFIRM = 2;    // رأی تأیید لازم برای «تأییدشده» شدن گزارش زنده (بخش ۹)
const LIVE_VERIFY_DENY = 3;       // رأی رد لازم برای کنار گذاشتن گزارش زنده

// ---------- موتور ترافیک (بخش ۱۰ تا ۱۴) — مقادیر اولیهٔ قابل‌تنظیم سند ----------

// قطعه‌بندی شبکه‌ای: خانه‌های ≈۵۵۵ متر (۰٫۰۰۵ درجه) + خانهٔ جهت ۴۵ درجه؛
// شناسهٔ قطعه کاملاً پایدار است و به هیچ مسیر/بارگذاری وابسته نیست (بخش ۱۰).
const CELL_DEG = 0.005;
const TRAFFIC_ROAD_TYPES = new Set(['city', 'main', 'intercity', 'mountain', 'dirt']);
// سرعت پایهٔ اولیهٔ سند (میانهٔ بازه‌ها؛ غیررسمی و قابل‌تنظیم — بخش ۱۲):
// شهر ۲۰–۳۵ / اصلی ۳۰–۵۰ / برون‌شهری آسفالت ۴۰–۷۰ / کوهستان ۲۰–۴۰ / خاکی ۱۵–۳۵
const ROAD_BASE_KMH = { city: 27.5, main: 40, intercity: 55, mountain: 30, dirt: 25 };
// ضریب ساعت روز (اولیه؛ روز هفته فعلاً ۱٫۰ — بخش ۱۲)
function hourFactor(h) {
  if (h <= 4) return 0.85;        // شب
  if (h === 5) return 0.9;
  if (h <= 9) return 0.75;        // شلوغی صبح
  if (h <= 15) return 1.0;        // میانهٔ روز
  if (h <= 19) return 0.7;        // شلوغی عصر
  if (h <= 21) return 0.9;
  return 0.85;                    // شب
}
function baselineFor(roadType, ts) {
  const base = ROAD_BASE_KMH[roadType] || ROAD_BASE_KMH.main;
  return base * hourFactor(new Date(ts).getHours());
}
// آستانه‌های شدت (بخش ۱۳ — قابل‌تنظیم): <۶۰٪ احتمال، ~۳۵٪ سنگین، ~۲۰٪ بسیار شدید
function stateForRatio(r) {
  if (r >= 0.6) return 'normal';
  if (r >= 0.45) return 'suspected';
  if (r >= 0.35) return 'moderate';
  if (r >= 0.2) return 'severe';
  return 'extreme';
}
// اعتماد بر اساس شمار رانندهٔ مستقل (بخش ۱۳): <۳ ناکافی، ۳–۵ اولیه، >۵ بالاتر
function confidenceFor(n) {
  if (n < 3) return 'insufficient';
  if (n <= 5) return 'preliminary';
  return 'higher';
}
const SPEED_WINDOW_MS = 20 * 60 * 1000;   // پنجرهٔ «زنده» بودن وضعیت
const STATE_TTL_MS = 30 * 60 * 1000;      // پس از آن وضعیت پایان یافته است (بخش ۱۴: منقضی)
const SAMPLE_MAX_AGE_MS = 6 * 60 * 60 * 1000;   // نمونهٔ کهنهٔ آفلاین پذیرفته نمی‌شود
const SAMPLE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;  // نگهداری نمونه‌ها برای پژوهش پایه

function cellCenterOfSeg(seg) {
  const m = /^g(-?\d+)_(-?\d+)_([0-7])$/.exec(seg || '');
  if (!m) return null;
  const ix = parseInt(m[1], 10), iy = parseInt(m[2], 10);
  const latC = (iy + 0.5) * CELL_DEG;
  const lonW = CELL_DEG / Math.max(0.2, Math.cos(latC * Math.PI / 180));
  return [latC, (ix + 0.5) * lonW];
}

// ---------- ابزارها ----------

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...CORS },
  });
}

const err = (status, code, msg) => json({ ok: false, error: code, message: msg }, status);

function isSensitive(text) {
  if (!text) return false;
  const n = String(text).toLowerCase();
  return SENSITIVE.some((p) => n.includes(p.toLowerCase()));
}

function num(v, dflt = null) {
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : dflt;
}

function intLimit(v, max, dflt = 100) {
  const n = parseInt(v, 10);
  if (!Number.isFinite(n)) return dflt;
  return Math.max(1, Math.min(max, n));
}

function parseBbox(url) {
  const bbox = url.searchParams.get('bbox');
  if (bbox) {
    const p = bbox.split(',').map((x) => parseFloat(x));
    if (p.length === 4 && p.every(Number.isFinite)) return p; // minLat,minLon,maxLat,maxLon
    return null;
  }
  const minLat = num(url.searchParams.get('minLat'));
  const minLon = num(url.searchParams.get('minLon'));
  const maxLat = num(url.searchParams.get('maxLat'));
  const maxLon = num(url.searchParams.get('maxLon'));
  if ([minLat, minLon, maxLat, maxLon].every(Number.isFinite)) return [minLat, minLon, maxLat, maxLon];
  return null;
}

const R = 6371000;
function hav(lat1, lon1, lat2, lon2) {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

/** فاصلهٔ نقطه تا پاره‌خط (متر) — برای فیلتر مسیر (along-route) */
function distToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const l2 = dx * dx + dy * dy;
  if (l2 === 0) return hav(px, py, ax, ay);
  let t = ((px - ax) * dx + (py - ay) * dy) / l2;
  t = Math.max(0, Math.min(1, t));
  return hav(px, py, ax + t * dx, ay + t * dy);
}

// محدودکنندهٔ نرخ نوشتن — در حافظهٔ هر ایزولیت (سبک و بدون هزینهٔ D1)
const rateBuckets = new Map();
function rateLimited(ip) {
  const now = Date.now();
  const win = 60_000;
  const b = rateBuckets.get(ip);
  if (!b || now - b.start > win) {
    rateBuckets.set(ip, { start: now, n: 1 });
    return false;
  }
  b.n += 1;
  return b.n > 40;
}

function cleanStr(v, max = 200) {
  if (v == null) return null;
  const s = String(v).trim().slice(0, max);
  return s.length ? s : null;
}

// ---------- handler ----------

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '');
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';

    try {
      if (!path.startsWith('/api/v1/')) return err(404, 'not_found', 'Unknown path');
      if (['POST'].includes(request.method) && rateLimited(ip)) {
        return err(429, 'rate_limited', 'Too many requests. Try again in a minute.');
      }

      // GET /api/v1/health
      if (path === '/api/v1/health') {
        const t = await env.DB.prepare('SELECT COUNT(*) AS n FROM road_pois WHERE is_active=1').first();
        return json({ ok: true, service: 'rahyab', active_pois: t ? t.n : 0, time: Date.now() });
      }

      // ---------- POIها ----------
      if (path === '/api/v1/pois' && request.method === 'GET') return listPois(env, url);
      if (path === '/api/v1/pois/nearby' && request.method === 'GET') return nearbyPois(env, url);
      if (path === '/api/v1/pois/along-route' && request.method === 'GET') return alongRoutePois(env, url);
      if (path === '/api/v1/pois/search' && request.method === 'GET') return searchPois(env, url);
      const mPoi = path.match(/^\/api\/v1\/pois\/(\d+)$/);
      if (mPoi && request.method === 'GET') return getPoi(env, +mPoi[1]);
      if (path === '/api/v1/pois' && request.method === 'POST') return createPoi(env, request, url);
      const mVerify = path.match(/^\/api\/v1\/pois\/(\d+)\/verify$/);
      if (mVerify && request.method === 'POST') return verifyPoi(env, +mVerify[1], request);
      const mReport = path.match(/^\/api\/v1\/pois\/(\d+)\/report$/);
      if (mReport && request.method === 'POST') return reportPoi(env, +mReport[1], request);

      // ---------- تجهیزات ----------
      if (path === '/api/v1/equipment' && request.method === 'GET') return listEquipment(env, url);

      // ---------- خدمات ----------
      if (path === '/api/v1/services' && request.method === 'GET') return listServices(env, url);

      // ---------- گزارش‌های زنده ----------
      if (path === '/api/v1/reports' && request.method === 'GET') return listLiveReports(env, url);
      if (path === '/api/v1/reports' && request.method === 'POST') return createLiveReport(env, request);
      const mLiveVerify = path.match(/^\/api\/v1\/reports\/(\d+)\/verify$/);
      if (mLiveVerify && request.method === 'POST') return verifyLiveReport(env, +mLiveVerify[1], request);

      // ---------- موتور ترافیک (بخش ۱۰ تا ۱۴) ----------
      if (path === '/api/v1/traffic/samples' && request.method === 'POST') return postTrafficSamples(env, request);
      if (path === '/api/v1/traffic/states' && request.method === 'GET') return getTrafficStates(env, url);
      if (path === '/api/v1/traffic/along-route' && request.method === 'GET') return getTrafficAlongRoute(env, url);

      return err(404, 'not_found', 'Unknown endpoint');
    } catch (e) {
      return json({ ok: false, error: 'server_error', message: String(e && e.message || e) }, 500);
    }
  },

  // پاک‌سازی دوره‌ای — بخش ۲۵ (نگهداری بهداشت دیتابیس؛ بدون حذف دادهٔ فعال کاربران)
  async scheduled(event, env, ctx) {
    ctx.waitUntil(cleanupExpired(env));
  },
};

// ---------- POI: فهرست با فیلترها ----------

async function listPois(env, url) {
  const lim = intLimit(url.searchParams.get('limit'), parseInt(env.MAX_LIMIT || '1000'));
  const status = url.searchParams.get('status'); // unverified/user_verified/official/all
  const since = num(url.searchParams.get('since')); // updated_at >
  const bbox = parseBbox(url);
  const cat = url.searchParams.get('category');
  const prov = url.searchParams.get('province');

  const where = ['is_active = 1'];
  const bind = [];
  if (cat && POI_CATEGORIES.has(cat)) { where.push('category = ?'); bind.push(cat); }
  if (prov) { where.push('province = ?'); bind.push(cleanStr(prov, 60)); }
  if (status && status !== 'all') { where.push('verification_status = ?'); bind.push(status); }
  if (since != null) { where.push('updated_at > ?'); bind.push(since); }
  if (bbox) {
    where.push('latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?');
    bind.push(bbox[0], bbox[2], bbox[1], bbox[3]);
  }
  const { results } = await env.DB.prepare(
    `SELECT * FROM road_pois WHERE ${where.join(' AND ')} ORDER BY updated_at DESC LIMIT ${lim}`
  ).bind(...bind).all();
  return json({ ok: true, pois: results || [], count: results ? results.length : 0 });
}

async function getPoi(env, id) {
  const poi = await env.DB.prepare('SELECT * FROM road_pois WHERE id = ?').bind(id).first();
  if (!poi) return err(404, 'poi_not_found', 'No such POI');
  const votes = await env.DB.prepare(
    "SELECT vote_type, COUNT(*) AS n FROM road_poi_votes WHERE poi_id = ? GROUP BY vote_type"
  ).bind(id).all();
  const reports = await env.DB.prepare(
    "SELECT report_type, COUNT(*) AS n FROM road_poi_reports WHERE poi_id = ? AND status = 'open' GROUP BY report_type"
  ).bind(id).all();
  const confirm = votes.results?.find((v) => v.vote_type === 'confirm')?.n || 0;
  const deny = votes.results?.find((v) => v.vote_type === 'deny')?.n || 0;
  return json({ ok: true, poi, confirm_votes: confirm, deny_votes: deny, open_reports: reports.results || [] });
}

async function createPoi(env, request, url) {
  let body;
  try { body = await request.json(); } catch { return err(400, 'bad_json', 'Body must be JSON'); }
  const name = cleanStr(body.name, 120);
  const category = cleanStr(body.category, 60);
  const lat = num(body.lat ?? body.latitude);
  const lon = num(body.lon ?? body.longitude);
  if (!name) return err(400, 'missing_name', 'name is required');
  if (!category || !POI_CATEGORIES.has(category)) return err(400, 'bad_category', 'Unknown category');
  if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
    return err(400, 'bad_coords', 'Invalid coordinates');
  }
  const desc = cleanStr(body.description, 400);
  if (isSensitive(name) || isSensitive(desc)) {
    return err(403, 'sensitive_content', 'This description cannot be published');
  }
  const deviceId = cleanStr(body.device_id, 64) || 'anon';
  const now = Date.now();

  // جلوگیری از ثبت مکان تکراری: همان دسته در شعاع ۳۵ متر → همان مکان برگردانده می‌شود
  const near = await env.DB.prepare(
    `SELECT id, name, category, latitude, longitude FROM road_pois
     WHERE is_active = 1 AND category = ? AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 1`
  ).bind(category, lat - 0.0004, lat + 0.0004, lon - 0.0004, lon + 0.0004).all();
  for (const p of near.results || []) {
    if (hav(lat, lon, p.latitude, p.longitude) <= 35) {
      return json({ ok: true, duplicate: true, id: p.id, poi: p }, 200);
    }
  }

  const props = body.properties && typeof body.properties === 'object'
    ? JSON.stringify(body.properties).slice(0, 2000) : null;
  // speed_limit فقط در properties و همیشه unverified ذخیره می‌شود تا منبع رسمی تأییدش نکند
  const status = 'unverified';

  const r = await env.DB.prepare(
    `INSERT INTO road_pois
       (name, category, subcategory, latitude, longitude, province, district, road_name,
        direction, description, properties_json, verification_status, source,
        created_at, updated_at, last_verified_at, is_active)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?14,?14,1)`
  ).bind(
    name, category, cleanStr(body.subcategory, 60), lat, lon,
    cleanStr(body.province, 60), cleanStr(body.district, 60), cleanStr(body.road_name, 120),
    cleanStr(body.direction, 16), desc, props, status,
    'user', now
  ).run();

  const id = r.meta?.last_row_id;
  return json({ ok: true, id }, 201);
}

async function verifyPoi(env, id, request) {
  let body;
  try { body = await request.json(); } catch { return err(400, 'bad_json', 'Body must be JSON'); }
  const deviceId = cleanStr(body.device_id, 64);
  const vote = cleanStr(body.vote, 10);
  if (!deviceId) return err(400, 'missing_device', 'device_id is required');
  if (vote !== 'confirm' && vote !== 'deny') return err(400, 'bad_vote', 'vote must be confirm or deny');

  const poi = await env.DB.prepare('SELECT id, is_active FROM road_pois WHERE id = ?').bind(id).first();
  if (!poi) return err(404, 'poi_not_found', 'No such POI');

  // هر دستگاه برای هر مکان فقط یک رأی — رأی دوباره همان دستگاه بی‌اثر است
  const dup = await env.DB.prepare(
    'SELECT id FROM road_poi_votes WHERE poi_id = ? AND device_id = ?'
  ).bind(id, deviceId).first();
  if (dup) return json({ ok: true, already_voted: true });

  await env.DB.prepare(
    'INSERT INTO road_poi_votes (poi_id, device_id, vote_type, created_at) VALUES (?,?,?,?)'
  ).bind(id, deviceId, vote, Date.now()).run();

  const counts = await env.DB.prepare(
    "SELECT vote_type, COUNT(*) AS n FROM road_poi_votes WHERE poi_id = ? GROUP BY vote_type"
  ).bind(id).all();
  const confirm = counts.results?.find((v) => v.vote_type === 'confirm')?.n || 0;
  const deny = counts.results?.find((v) => v.vote_type === 'deny')?.n || 0;

  const now = Date.now();
  if (vote === 'confirm' && confirm >= POI_VERIFY_THRESHOLD && deny < POI_DENY_DISABLE) {
    await env.DB.prepare(
      "UPDATE road_pois SET verification_status = CASE WHEN verification_status='official' THEN 'official' ELSE 'user_verified' END, last_verified_at = ?, updated_at = ? WHERE id = ?"
    ).bind(now, now, id).run();
  }
  if (vote === 'deny' && deny >= POI_DENY_DISABLE) {
    // مکان حذف‌شده — غیرفعال می‌شود (نمایش داده نمی‌شود ولی سابقه می‌ماند)
    await env.DB.prepare(
      'UPDATE road_pois SET is_active = 0, updated_at = ? WHERE id = ?'
    ).bind(now, id).run();
  }
  return json({ ok: true, confirm_votes: confirm, deny_votes: deny });
}

async function reportPoi(env, id, request) {
  let body;
  try { body = await request.json(); } catch { return err(400, 'bad_json', 'Body must be JSON'); }
  const type = cleanStr(body.type ?? body.report_type, 30);
  const deviceId = cleanStr(body.device_id, 64);
  const desc = cleanStr(body.description, 400);
  if (!deviceId) return err(400, 'missing_device', 'device_id is required');
  if (!type || !POI_REPORT_TYPES.has(type)) return err(400, 'bad_type', 'Unknown report type');
  if (isSensitive(desc)) return err(403, 'sensitive_content', 'This description cannot be published');

  const poi = await env.DB.prepare('SELECT id, is_active FROM road_pois WHERE id = ?').bind(id).first();
  if (!poi) return err(404, 'poi_not_found', 'No such POI');

  const now = Date.now();
  const expires = now + 30 * 24 * 3600 * 1000; // عمر اعتبار گزارش مکان: ۳۰ روز
  await env.DB.prepare(
    'INSERT INTO road_poi_reports (poi_id, report_type, description, device_id, status, created_at, expires_at) VALUES (?,?,?,?,"open",?,?)'
  ).bind(id, type, desc, deviceId, now, expires).run();

  // گزارش مستقل «حذف‌شده» از دستگاه‌های مختلف → مکان غیرفعال می‌شود
  if (type === 'removed') {
    const c = await env.DB.prepare(
      "SELECT COUNT(DISTINCT device_id) AS n FROM road_poi_reports WHERE poi_id = ? AND report_type = 'removed' AND status = 'open' AND expires_at > ?"
    ).bind(id, now).first();
    if (c && c.n >= POI_REPORT_DISABLE) {
      await env.DB.prepare('UPDATE road_pois SET is_active = 0, updated_at = ? WHERE id = ?').bind(now, id).run();
    }
  }
  return json({ ok: true }, 201);
}

// ---------- nearby ----------

async function nearbyPois(env, url) {
  const lat = num(url.searchParams.get('lat'));
  const lon = num(url.searchParams.get('lon'));
  const radius = Math.min(num(url.searchParams.get('radius'), 3000), 50000);
  const lim = intLimit(url.searchParams.get('limit'), parseInt(env.NEARBY_LIMIT || '200'));
  const cat = url.searchParams.get('category');
  if (lat == null || lon == null) return err(400, 'missing_coords', 'lat and lon are required');

  const d = radius / 111320; // تقریبی درجه
  const where = ['is_active = 1', 'latitude BETWEEN ? AND ?', 'longitude BETWEEN ? AND ?'];
  const bind = [lat - d, lat + d, lon - d * Math.cos(lat * Math.PI / 180), lon + d * Math.cos(lat * Math.PI / 180)];
  if (cat && POI_CATEGORIES.has(cat)) { where.push('category = ?'); bind.push(cat); }
  const { results } = await env.DB.prepare(
    `SELECT * FROM road_pois WHERE ${where.join(' AND ')}`
  ).bind(...bind).all();
  const pois = (results || [])
    .map((p) => ({ ...p, distance_m: Math.round(hav(lat, lon, p.latitude, p.longitude)) }))
    .filter((p) => p.distance_m <= radius)
    .sort((a, b) => a.distance_m - b.distance_m)
    .slice(0, lim);
  return json({ ok: true, pois, count: pois.length });
}

// ---------- along-route ----------

async function alongRoutePois(env, url) {
  const routeRaw = url.searchParams.get('route') || '';
  const corridor = Math.min(num(url.searchParams.get('corridor'), 300), 2000);
  const lim = intLimit(url.searchParams.get('limit'), parseInt(env.NEARBY_LIMIT || '200'));
  const cats = (url.searchParams.get('categories') || '').split(',').filter((c) => POI_CATEGORIES.has(c));
  const pts = routeRaw.split(';').map((s) => {
    const [a, b] = s.split(',').map(parseFloat);
    return Number.isFinite(a) && Number.isFinite(b) ? [a, b] : null;
  }).filter(Boolean);
  if (pts.length < 2) return err(400, 'bad_route', 'route must be lat,lon;lat,lon with >= 2 points');

  const minLat = Math.min(...pts.map((p) => p[0])) - corridor / 111320;
  const maxLat = Math.max(...pts.map((p) => p[0])) + corridor / 111320;
  const lats = pts.map((p) => p[0]);
  const lons = pts.map((p) => p[1]);
  const dl = corridor / (111320 * Math.max(0.2, Math.cos((Math.min(...lats) * Math.PI) / 180)));
  const minLon = Math.min(...lons) - dl;
  const maxLon = Math.max(...lons) + dl;

  const { results } = await env.DB.prepare(
    `SELECT * FROM road_pois WHERE is_active = 1
       AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?
       ${cats.length ? `AND category IN (${cats.map(() => '?').join(',')})` : ''}`
  ).bind(minLat, maxLat, minLon, maxLon, ...cats).all();

  const pois = (results || [])
    .map((p) => {
      let best = Infinity;
      for (let i = 0; i < pts.length - 1; i++) {
        const d = distToSegment(p.latitude, p.longitude, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]);
        if (d < best) best = d;
      }
      return { ...p, route_distance_m: Math.round(best) };
    })
    .filter((p) => p.route_distance_m <= corridor)
    .sort((a, b) => a.route_distance_m - b.route_distance_m)
    .slice(0, lim);
  return json({ ok: true, pois, count: pois.length });
}

// ---------- search ----------

async function searchPois(env, url) {
  const q = cleanStr(url.searchParams.get('q'), 60);
  const lim = intLimit(url.searchParams.get('limit'), parseInt(env.SEARCH_LIMIT || '60'));
  if (!q || q.length < 2) return err(400, 'bad_query', 'q must be at least 2 characters');
  const bbox = parseBbox(url);
  const cat = url.searchParams.get('category');
  const where = ['is_active = 1', '(name LIKE ? OR description LIKE ? OR road_name LIKE ?)'];
  const like = `%${q}%`;
  const bind = [like, like, like];
  if (cat && POI_CATEGORIES.has(cat)) { where.push('category = ?'); bind.push(cat); }
  if (bbox) {
    where.push('latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?');
    bind.push(bbox[0], bbox[2], bbox[1], bbox[3]);
  }
  const { results } = await env.DB.prepare(
    `SELECT * FROM road_pois WHERE ${where.join(' AND ')} ORDER BY verification_status DESC, updated_at DESC LIMIT ${lim}`
  ).bind(...bind).all();
  return json({ ok: true, pois: results || [], count: results ? results.length : 0 });
}

// ---------- equipment / services ----------

async function listEquipment(env, url) {
  const lim = intLimit(url.searchParams.get('limit'), parseInt(env.MAX_LIMIT || '1000'));
  const bbox = parseBbox(url);
  const type = url.searchParams.get('type');
  const status = url.searchParams.get('status');
  const where = [];
  const bind = [];
  if (type && EQUIPMENT_TYPES.has(type)) { where.push('equipment_type = ?'); bind.push(type); }
  if (status) { where.push('verification_status = ?'); bind.push(status); }
  if (bbox) { where.push('latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?'); bind.push(bbox[0], bbox[2], bbox[1], bbox[3]); }
  const { results } = await env.DB.prepare(
    `SELECT * FROM road_equipment ${where.length ? 'WHERE ' + where.join(' AND ') : ''} ORDER BY last_verified_at DESC LIMIT ${lim}`
  ).bind(...bind).all();
  return json({ ok: true, equipment: results || [], count: results ? results.length : 0 });
}

async function listServices(env, url) {
  const lim = intLimit(url.searchParams.get('limit'), parseInt(env.MAX_LIMIT || '1000'));
  const bbox = parseBbox(url);
  const type = url.searchParams.get('type');
  const where = [];
  const bind = [];
  if (type && SERVICE_TYPES.has(type)) { where.push('service_type = ?'); bind.push(type); }
  if (bbox) { where.push('latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?'); bind.push(bbox[0], bbox[2], bbox[1], bbox[3]); }
  const { results } = await env.DB.prepare(
    `SELECT * FROM road_services ${where.length ? 'WHERE ' + where.join(' AND ') : ''} ORDER BY updated_at DESC LIMIT ${lim}`
  ).bind(...bind).all();
  return json({ ok: true, services: results || [], count: results ? results.length : 0 });
}

// ---------- گزارش‌های زنده (بخش پنجم) ----------

async function listLiveReports(env, url) {
  await ensureReportsSchema(env);
  const lim = intLimit(url.searchParams.get('limit'), 500, 300);
  const bbox = parseBbox(url);
  const type = url.searchParams.get('type');
  const now = Date.now();
  const where = ['expires_at > ?', "(verification IS NULL OR verification != 'rejected')"];
  const bind = [now];
  if (type && LIVE_REPORT_TYPES.has(type)) { where.push('type = ?'); bind.push(type); }
  if (bbox) { where.push('lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?'); bind.push(bbox[0], bbox[2], bbox[1], bbox[3]); }
  const { results } = await env.DB.prepare(
    `SELECT id, type, note, lat, lon, severity, seg, dir, confirm_count, deny_count, verification, created_at, expires_at
       FROM live_reports WHERE ${where.join(' AND ')} ORDER BY created_at DESC LIMIT ${lim}`
  ).bind(...bind).all();
  return json({ ok: true, reports: results || [], count: results ? results.length : 0 });
}

/**
 * مهاجرت امن خودپرداخت — بند ۱۹ سند جامع (عملیات idempotent):
 *  - ستون client_uuid برای جلوگیری از ثبت تکراری گزارش‌ها (بستهٔ اصلاح ۲)
 *  - ستون‌های بخش ۸ و ۹: severity (شدت)، seg (شناسهٔ قطعهٔ جاده)، dir (خانهٔ جهت)،
 *    confirm_count/deny_count/verification (تأیید اجتماعی رانندگان دیگر)
 * فقط «افزودن»؛ هیچ جدول یا داده‌ای حذف یا تغییر نمی‌کند.
 *  - ALTER TABLE اگر ستون از قبل موجود باشد خطا می‌دهد؛ خطا گرفته و نادیده می‌شود
 *    (رفتار idempotent خود مهاجرت).
 *  - یک‌بار در هر isolate اجرا می‌شود (schemaReady) تا هزینهٔ اضافی نداشته باشد.
 *  - بازگشت (rollback): مستقرکردن نسخهٔ قبلی Worker؛ ستون‌های nullable هستند و
 *    کد قدیمی آن‌ها را نمی‌خواند.
 */
let reportsSchemaReady = false;
async function ensureReportsSchema(env) {
  if (reportsSchemaReady) return;
  const alters = [
    'ALTER TABLE live_reports ADD COLUMN client_uuid TEXT',
    'ALTER TABLE live_reports ADD COLUMN severity TEXT',
    'ALTER TABLE live_reports ADD COLUMN seg TEXT',
    'ALTER TABLE live_reports ADD COLUMN dir INTEGER',
    'ALTER TABLE live_reports ADD COLUMN confirm_count INTEGER NOT NULL DEFAULT 0',
    'ALTER TABLE live_reports ADD COLUMN deny_count INTEGER NOT NULL DEFAULT 0',
    'ALTER TABLE live_reports ADD COLUMN verification TEXT',
  ];
  for (const sql of alters) {
    try { await env.DB.prepare(sql).run(); } catch (e) { /* ستون از قبل موجود است — مهاجرت idempotent */ }
  }
  const creates = [
    'CREATE UNIQUE INDEX IF NOT EXISTS idx_live_reports_client_uuid ON live_reports(client_uuid) WHERE client_uuid IS NOT NULL',
    `CREATE TABLE IF NOT EXISTS live_report_votes (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       report_id INTEGER NOT NULL,
       device_id TEXT NOT NULL,
       vote_type TEXT NOT NULL,
       created_at INTEGER NOT NULL,
       UNIQUE(report_id, device_id)
     )`,
    'CREATE INDEX IF NOT EXISTS idx_livereports_votes ON live_report_votes (report_id, vote_type)',
    `CREATE TABLE IF NOT EXISTS live_report_events (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       report_id INTEGER NOT NULL,
       event TEXT NOT NULL,
       created_at INTEGER NOT NULL
     )`,
    'CREATE INDEX IF NOT EXISTS idx_livereports_events ON live_report_events (report_id, created_at)',
  ];
  for (const sql of creates) {
    try { await env.DB.prepare(sql).run(); } catch (e) { /* از قبل موجود است */ }
  }
  reportsSchemaReady = true;
}

async function createLiveReport(env, request) {
  await ensureReportsSchema(env);
  let body;
  try { body = await request.json(); } catch { return err(400, 'bad_json', 'Body must be JSON'); }
  const type = cleanStr(body.type, 40);
  const note = cleanStr(body.note, 300);
  const lat = num(body.lat ?? body.latitude);
  const lon = num(body.lon ?? body.longitude);
  const deviceId = cleanStr(body.device_id, 64) || 'anon';
  const severity = cleanStr(body.severity, 10);
  const seg = cleanStr(body.seg, 40);
  const dir = num(body.dir);
  // شناسهٔ یکتای عملیات از سمت کلاینت — تکراری‌فرستانی (قطع‌شدن شبکه پس از پذیرش
  // سرور) هرگز ردیف دوباره نمی‌سازد (بند ۱۹ سند جامع)
  const clientUuid = cleanStr(body.client_uuid, 64) || null;
  if (!type || !LIVE_REPORT_TYPES.has(type)) return err(400, 'bad_type', 'Unknown report type');
  if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
    return err(400, 'bad_coords', 'Invalid coordinates');
  }
  if (severity && !['low', 'medium', 'high'].includes(severity)) return err(400, 'bad_severity', 'severity must be low/medium/high');
  if (seg && !/^g-?\d+_-?\d+_[0-7x]$/.test(seg)) return err(400, 'bad_seg', 'Invalid segment id');
  if (isSensitive(note)) return err(403, 'sensitive_content', 'This note cannot be published');
  const now = Date.now();
  const hours = type === 'road_closed' || type === 'landslide' || type === 'flood' ? 48 : 24;

  // ادغام گزارش‌های مشابه در سرور (بخش ۹): همان نوع در شعاع ۱۵۰ متر و ۴۵ دقیقه
  // یک ردیف است؛ رانندهٔ دوم همان گزارش موجود را تقویت می‌کند نه ردیف تازه.
  const near = await env.DB.prepare(
    `SELECT id, lat, lon, created_at FROM live_reports
       WHERE type = ? AND expires_at > ? AND (verification IS NULL OR verification != 'rejected')
         AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 5`
  ).bind(type, now, lat - 0.0015, lat + 0.0015, lon - 0.0015 / Math.max(0.3, Math.cos(lat * Math.PI / 180)), lon + 0.0015 / Math.max(0.3, Math.cos(lat * Math.PI / 180))).all();
  for (const r0 of near.results || []) {
    if (hav(lat, lon, r0.lat, r0.lon) <= 150 && now - (r0.created_at || 0) < 45 * 60 * 1000) {
      await env.DB.prepare(
        'INSERT OR IGNORE INTO live_report_events (report_id, event, created_at) VALUES (?,?,?)'
      ).bind(r0.id, 'merged', now).run();
      return json({ ok: true, merged: true, id: r0.id, expires_in_hours: hours }, 200);
    }
  }

  let r;
  let duplicated = false;
  if (clientUuid) {
    r = await env.DB.prepare(
      'INSERT OR IGNORE INTO live_reports (type, note, lat, lon, device_id, client_uuid, severity, seg, dir, created_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)'
    ).bind(type, note, lat, lon, deviceId, clientUuid, severity || 'medium', seg, dir == null ? null : Math.round(dir), now, now + hours * 3600 * 1000).run();
    // changes == 0 یعنی همین client_uuid از قبل ثبت شده است — موفق و بدون تکرار
    duplicated = !!(r.meta && r.meta.changes === 0);
  } else {
    r = await env.DB.prepare(
      'INSERT INTO live_reports (type, note, lat, lon, device_id, severity, seg, dir, created_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)'
    ).bind(type, note, lat, lon, deviceId, severity || 'medium', seg, dir == null ? null : Math.round(dir), now, now + hours * 3600 * 1000).run();
  }
  const rid = r.meta?.last_row_id;
  if (!duplicated && rid) {
    await env.DB.prepare('INSERT INTO live_report_events (report_id, event, created_at) VALUES (?,?,?)')
      .bind(rid, 'created', now).run();
  }
  if (duplicated) return json({ ok: true, duplicate: true, expires_in_hours: hours }, 200);
  return json({ ok: true, expires_in_hours: hours }, 201);
}

/**
 * تأیید اجتماعی گزارش زنده توسط رانندگان دیگر (بخش ۹ سند جامع).
 *  - هر دستگاه برای هر گزارش فقط یک رأی (UNIQUE در سطح دیتابیس)
 *  - ۲ رأی تأیید → verification='confirmed' (اعتماد بالا)
 *  - ۳ رأی رد → verification='rejected' (از فهرست فعال حذف می‌شود؛ سابقه می‌ماند)
 *  - تاریخچهٔ تغییر وضعیت در live_report_events ذخیره می‌شود
 */
async function verifyLiveReport(env, id, request) {
  await ensureReportsSchema(env);
  let body;
  try { body = await request.json(); } catch { return err(400, 'bad_json', 'Body must be JSON'); }
  const deviceId = cleanStr(body.device_id, 64);
  const vote = cleanStr(body.vote, 10);
  if (!deviceId) return err(400, 'missing_device', 'device_id is required');
  if (vote !== 'confirm' && vote !== 'deny') return err(400, 'bad_vote', 'vote must be confirm or deny');

  const rep = await env.DB.prepare('SELECT id, expires_at, verification FROM live_reports WHERE id = ?').bind(id).first();
  if (!rep) return err(404, 'report_not_found', 'No such report');
  if (rep.expires_at < Date.now()) return err(410, 'expired', 'Report has expired');

  const dup = await env.DB.prepare(
    'SELECT id FROM live_report_votes WHERE report_id = ? AND device_id = ?'
  ).bind(id, deviceId).first();
  if (dup) return json({ ok: true, already_voted: true });

  const now = Date.now();
  await env.DB.prepare(
    'INSERT INTO live_report_votes (report_id, device_id, vote_type, created_at) VALUES (?,?,?,?)'
  ).bind(id, deviceId, vote, now).run();
  await env.DB.prepare('INSERT INTO live_report_events (report_id, event, created_at) VALUES (?,?,?)')
    .bind(id, `vote_${vote}`, now).run();

  const counts = await env.DB.prepare(
    'SELECT vote_type, COUNT(*) AS n FROM live_report_votes WHERE report_id = ? GROUP BY vote_type'
  ).bind(id).all();
  const confirm = counts.results?.find((v) => v.vote_type === 'confirm')?.n || 0;
  const deny = counts.results?.find((v) => v.vote_type === 'deny')?.n || 0;

  let verification = rep.verification || 'unverified';
  if (confirm >= LIVE_VERIFY_CONFIRM && deny < LIVE_VERIFY_DENY) verification = 'confirmed';
  if (deny >= LIVE_VERIFY_DENY) verification = 'rejected';
  await env.DB.prepare('UPDATE live_reports SET confirm_count = ?, deny_count = ?, verification = ? WHERE id = ?')
    .bind(confirm, deny, verification, id).run();
  if (verification !== (rep.verification || 'unverified')) {
    await env.DB.prepare('INSERT INTO live_report_events (report_id, event, created_at) VALUES (?,?,?)')
      .bind(id, `verification_${verification}`, now).run();
  }
  return json({ ok: true, confirm_count: confirm, deny_count: deny, verification });
}

// ---------- موتور ترافیک: نمونه‌ها (بخش ۱۱) ----------

/**
 * دریافت نمونه‌های سرعت ناشناس. فیلترهای ضدخطا (بخش ۱۳): سرعت ۳–۱۵۰ km/h،
 * ساعت کلاینت فقط تا ۶ ساعت عقب پذیرفته می‌شود، ضدتکرار در سطح دیتابیس
 * (seg+device+دقیقه). هیچ مختصاتی ذخیره نمی‌شود — مرکز قطعه از شناسهٔ آن
 * ساخته می‌شود (حریم خصوصی بخش ۲۴: کمینه‌سازی داده).
 */
async function postTrafficSamples(env, request) {
  await ensureTrafficSchema(env);
  let body;
  try { body = await request.json(); } catch { return err(400, 'bad_json', 'Body must be JSON'); }
  const deviceId = cleanStr(body.device_id, 64);
  if (!deviceId) return err(400, 'missing_device', 'device_id is required');
  const arr = Array.isArray(body.samples) ? body.samples : [];
  if (!arr.length) return json({ ok: true, accepted: 0 });
  if (arr.length > 40) return err(400, 'too_many', 'Max 40 samples per batch');
  const now = Date.now();
  let accepted = 0;
  for (const s of arr.slice(0, 40)) {
    const seg = cleanStr(s.seg, 40);
    const speed = num(s.speed);
    const bearing = num(s.bearing);
    const acc = num(s.acc, 99);
    let ts = num(s.ts, now);
    if (!seg || !/^g-?\d+_-?\d+_[0-7]$/.test(seg)) continue;         // شکل شناسهٔ قطعه
    if (speed == null || speed < 3 || speed > 150) continue;          // ایست/چراغ/پرت
    if (bearing == null || bearing < 0 || bearing >= 360) continue;
    if (acc != null && acc > 50) continue;                            // GPS ضعیف
    if (!Number.isFinite(ts) || ts < now - SAMPLE_MAX_AGE_MS || ts > now + 5 * 60 * 1000) ts = now;
    const r = await env.DB.prepare(
      'INSERT OR IGNORE INTO traffic_samples (seg, device_id, speed_kmh, bearing, acc, minute, ts) VALUES (?,?,?,?,?,?,?)'
    ).bind(seg, deviceId, Math.round(speed * 10) / 10, Math.round(bearing), Math.round(acc), Math.floor(ts / 60000), ts).run();
    if (r.meta && r.meta.changes > 0) accepted++;
    // ثبت قطعه + نام سرک (اختیاری، اولین گزارش‌دهندهٔ هر قطعه)
    const rt = TRAFFIC_ROAD_TYPES.has(cleanStr(s.road_type, 20) || '') ? cleanStr(s.road_type, 20) : 'main';
    const c = cellCenterOfSeg(seg);
    await env.DB.prepare(
      'INSERT OR IGNORE INTO traffic_segments (seg, road_type, road_name, lat, lon, created_at) VALUES (?,?,?,?,?,?)'
    ).bind(seg, rt, cleanStr(s.road, 120), c ? c[0] : null, c ? c[1] : null, now).run();
  }
  return json({ ok: true, accepted, received: arr.length });
}

// ---------- موتور ترافیک: وضعیت‌ها (بخش ۱۲، ۱۳، ۱۴) ----------

let trafficSchemaReady = false;
async function ensureTrafficSchema(env) {
  if (trafficSchemaReady) return;
  const creates = [
    `CREATE TABLE IF NOT EXISTS traffic_segments (
       seg TEXT PRIMARY KEY,
       road_type TEXT NOT NULL DEFAULT 'main',
       road_name TEXT,
       lat REAL,
       lon REAL,
       created_at INTEGER NOT NULL
     )`,
    `CREATE TABLE IF NOT EXISTS traffic_samples (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       seg TEXT NOT NULL,
       device_id TEXT NOT NULL,
       speed_kmh REAL NOT NULL,
       bearing INTEGER NOT NULL,
       acc INTEGER,
       minute INTEGER NOT NULL,
       ts INTEGER NOT NULL
     )`,
    'CREATE UNIQUE INDEX IF NOT EXISTS idx_tsamples_dedupe ON traffic_samples (seg, device_id, minute)',
    'CREATE INDEX IF NOT EXISTS idx_tsamples_seg_time ON traffic_samples (seg, ts)',
    `CREATE TABLE IF NOT EXISTS traffic_states (
       seg TEXT PRIMARY KEY,
       state TEXT NOT NULL,
       intensity REAL,
       drivers INTEGER,
       baseline REAL,
       avg_speed REAL,
       confidence TEXT,
       started_at INTEGER,
       last_observed INTEGER,
       road_type TEXT,
       road_name TEXT,
       updated_at INTEGER
     )`,
    'CREATE INDEX IF NOT EXISTS idx_tstates_state ON traffic_states (state, last_observed)',
  ];
  for (const sql of creates) {
    try { await env.DB.prepare(sql).run(); } catch (e) { /* از قبل موجود است */ }
  }
  trafficSchemaReady = true;
}

/**
 * محاسبهٔ وضعیت زنده از نمونه‌های پنجرهٔ ۲۰ دقیقه — بخش ۱۲ تا ۱۴:
 *  - سرعت پایه از نوع سرک + ساعت روز (مقادیر اولیهٔ سند؛ قابل‌تنظیم)
 *  - شمار رانندهٔ مستقل: <۳ → «ناکافی»؛ ۳–۵ → اعتماد اولیه؛ >۵ → بالاتر
 *  - بازه‌های شدت: ≥۶۰٪ عادی / ≥۴۵٪ احتمال / ≥۳۵٪ متوسط / ≥۲۰٪ سنگین / <۲۰٪ بسیار شدید
 *  - «زمان شروع» فقط برای ادامهٔ همان دورهٔ ترافیک حفظ می‌شود؛ بازیابی سرعت =
 *    پایان دوره (state عادی ثبت و در فهرست فعال برنمی‌گردد).
 */
async function recomputeStates(env, now) {
  const since = now - SPEED_WINDOW_MS;
  const { results } = await env.DB.prepare(
    `SELECT seg, AVG(speed_kmh) AS avg_speed, COUNT(DISTINCT device_id) AS drivers
       FROM traffic_samples WHERE ts > ? GROUP BY seg LIMIT 5000`
  ).bind(since).all();
  const groups = results || [];
  if (!groups.length) return [];
  // نوع سرک/نام از جدول قطعات
  const segs = groups.map((g) => g.seg);
  const segMeta = new Map();
  for (let i = 0; i < segs.length; i += 200) {
    const part = segs.slice(i, i + 200);
    const { results: metas } = await env.DB.prepare(
      `SELECT seg, road_type, road_name FROM traffic_segments WHERE seg IN (${part.map(() => '?').join(',')})`
    ).bind(...part).all();
    for (const m of metas || []) segMeta.set(m.seg, m);
  }
  const out = [];
  for (const g of groups) {
    const meta = segMeta.get(g.seg) || { road_type: 'main', road_name: null };
    const baseline = Math.max(5, baselineFor(meta.road_type || 'main', now));
    const drivers = g.drivers || 0;
    const avg = g.avg_speed || 0;
    const ratio = avg / baseline;
    const state = drivers < 3 ? 'insufficient' : stateForRatio(ratio);
    const confidence = confidenceFor(drivers);
    const c = cellCenterOfSeg(g.seg);
    out.push({
      seg: g.seg, state,
      intensity: Math.round(Math.max(0, Math.min(1, 1 - ratio)) * 100) / 100,
      drivers, baseline: Math.round(baseline * 10) / 10, avg_speed: Math.round(avg * 10) / 10,
      confidence, started_at: now, last_observed: now,
      road_type: meta.road_type || 'main', road_name: meta.road_name || null,
      lat: c ? c[0] : null, lon: c ? c[1] : null,
    });
  }
  // ثبت/به‌روزرسانی وضعیت‌ها (با حفظ started_at همان دورهٔ ترافیک)
  for (const st of out) {
    const prev = await env.DB.prepare(
      'SELECT state, started_at FROM traffic_states WHERE seg = ?'
    ).bind(st.seg).first();
    const continuing = prev && prev.state !== 'normal' && st.state !== 'normal' && st.state !== 'insufficient';
    st.started_at = continuing ? prev.started_at : now;
    await env.DB.prepare(
      `INSERT INTO traffic_states (seg, state, intensity, drivers, baseline, avg_speed, confidence, started_at, last_observed, road_type, road_name, updated_at)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
         ON CONFLICT(seg) DO UPDATE SET
           state=excluded.state, intensity=excluded.intensity, drivers=excluded.drivers,
           baseline=excluded.baseline, avg_speed=excluded.avg_speed, confidence=excluded.confidence,
           started_at=excluded.started_at, last_observed=excluded.last_observed,
           road_type=excluded.road_type, road_name=excluded.road_name, updated_at=excluded.updated_at`
    ).bind(st.seg, st.state, st.intensity, st.drivers, st.baseline, st.avg_speed, st.confidence,
      st.started_at, st.last_observed, st.road_type, st.road_name, now).run();
  }
  return out;
}

async function getTrafficStates(env, url) {
  await ensureTrafficSchema(env);
  const now = Date.now();
  const bbox = parseBbox(url);
  const includeNormal = url.searchParams.get('include_normal') === '1';
  const lim = intLimit(url.searchParams.get('limit'), 500, 200);
  await recomputeStates(env, now);
  // وضعیت‌های پایان‌یافته حذف می‌شوند (بخش ۱۴: منقضی) — با تأخیر TTL برای تاریخچهٔ کوتاه
  await env.DB.prepare('DELETE FROM traffic_states WHERE last_observed < ?').bind(now - STATE_TTL_MS).run();
  const where = ['last_observed > ?'];
  const bind = [now - STATE_TTL_MS];
  if (!includeNormal) where.push("state NOT IN ('normal')");
  if (bbox) { where.push('lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?'); bind.push(bbox[0], bbox[2], bbox[1], bbox[3]); }
  const { results } = await env.DB.prepare(
    `SELECT seg, state, intensity, drivers, baseline, avg_speed, confidence, started_at, last_observed, road_type, road_name, lat, lon
       FROM traffic_states WHERE ${where.join(' AND ')} ORDER BY intensity DESC LIMIT ${lim}`
  ).bind(...bind).all();
  const states = (results || []).map((s) => ({ ...s, expires_at: s.last_observed + STATE_TTL_MS }));
  return json({
    ok: true, states, count: states.length,
    window_minutes: SPEED_WINDOW_MS / 60000,
    time: now,
  });
}

async function getTrafficAlongRoute(env, url) {
  await ensureTrafficSchema(env);
  const routeRaw = url.searchParams.get('route') || '';
  const corridor = Math.min(Math.max(num(url.searchParams.get('corridor'), 1200), 100), 5000);
  const lim = intLimit(url.searchParams.get('limit'), 500, 200);
  const pts = routeRaw.split(';').map((s) => {
    const [a, b] = s.split(',').map(parseFloat);
    return Number.isFinite(a) && Number.isFinite(b) ? [a, b] : null;
  }).filter(Boolean);
  if (pts.length < 2) return err(400, 'bad_route', 'route must be lat,lon;lat,lon with >= 2 points');
  const now = Date.now();
  await recomputeStates(env, now);
  await env.DB.prepare('DELETE FROM traffic_states WHERE last_observed < ?').bind(now - STATE_TTL_MS).run();
  const minLat = Math.min(...pts.map((p) => p[0])) - corridor / 111320;
  const maxLat = Math.max(...pts.map((p) => p[0])) + corridor / 111320;
  const lats = pts.map((p) => p[0]);
  const dl = corridor / (111320 * Math.max(0.2, Math.cos((Math.min(...lats) * Math.PI) / 180)));
  const minLon = Math.min(...pts.map((p) => p[1])) - dl;
  const maxLon = Math.max(...pts.map((p) => p[1])) + dl;
  const { results } = await env.DB.prepare(
    `SELECT seg, state, intensity, drivers, baseline, avg_speed, confidence, started_at, last_observed, road_type, road_name, lat, lon
       FROM traffic_states WHERE last_observed > ? AND state != 'normal'
         AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY intensity DESC LIMIT 500`
  ).bind(now - STATE_TTL_MS, minLat, maxLat, minLon, maxLon).all();
  const states = (results || [])
    .map((s) => {
      let best = Infinity;
      for (let i = 0; i < pts.length - 1; i++) {
        const d = distToSegment(s.lat, s.lon, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]);
        if (d < best) best = d;
      }
      return { ...s, route_distance_m: Math.round(best) };
    })
    .filter((s) => s.route_distance_m <= corridor)
    .slice(0, lim);
  return json({ ok: true, states, count: states.length, corridor, time: now });
}

// ---------- پاک‌سازی دوره‌ای (بخش ۲۵) ----------

async function cleanupExpired(env) {
  const now = Date.now();
  try {
    await env.DB.prepare('DELETE FROM traffic_samples WHERE ts < ?')
      .bind(now - SAMPLE_RETENTION_MS).run();
    await env.DB.prepare('DELETE FROM traffic_states WHERE last_observed < ?')
      .bind(now - STATE_TTL_MS).run();
    await env.DB.prepare('DELETE FROM live_reports WHERE expires_at < ?')
      .bind(now - 30 * 24 * 3600 * 1000).run();
    await env.DB.prepare('DELETE FROM road_poi_reports WHERE expires_at IS NOT NULL AND expires_at < ?')
      .bind(now - 60 * 24 * 3600 * 1000).run();
    await env.DB.prepare('DELETE FROM live_report_events WHERE created_at < ?')
      .bind(now - 90 * 24 * 3600 * 1000).run();
  } catch (e) { /* پاک‌سازی نباید کرون را بشکند */ }
}
