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
 *  POST /api/v1/reports              {type, note, lat, lon, device_id}
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

      return err(404, 'not_found', 'Unknown endpoint');
    } catch (e) {
      return json({ ok: false, error: 'server_error', message: String(e && e.message || e) }, 500);
    }
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
  const status = body.source === 'official' ? 'official' : 'unverified';

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
    cleanStr(body.source, 20) || 'user', now
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
  const lim = intLimit(url.searchParams.get('limit'), 500, 300);
  const bbox = parseBbox(url);
  const type = url.searchParams.get('type');
  const now = Date.now();
  const where = ['expires_at > ?'];
  const bind = [now];
  if (type && LIVE_REPORT_TYPES.has(type)) { where.push('type = ?'); bind.push(type); }
  if (bbox) { where.push('lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?'); bind.push(bbox[0], bbox[2], bbox[1], bbox[3]); }
  const { results } = await env.DB.prepare(
    `SELECT id, type, note, lat, lon, created_at, expires_at FROM live_reports
       WHERE ${where.join(' AND ')} ORDER BY created_at DESC LIMIT ${lim}`
  ).bind(...bind).all();
  return json({ ok: true, reports: results || [], count: results ? results.length : 0 });
}

async function createLiveReport(env, request) {
  let body;
  try { body = await request.json(); } catch { return err(400, 'bad_json', 'Body must be JSON'); }
  const type = cleanStr(body.type, 40);
  const note = cleanStr(body.note, 300);
  const lat = num(body.lat ?? body.latitude);
  const lon = num(body.lon ?? body.longitude);
  const deviceId = cleanStr(body.device_id, 64) || 'anon';
  if (!type || !LIVE_REPORT_TYPES.has(type)) return err(400, 'bad_type', 'Unknown report type');
  if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
    return err(400, 'bad_coords', 'Invalid coordinates');
  }
  if (isSensitive(note)) return err(403, 'sensitive_content', 'This note cannot be published');
  const now = Date.now();
  const hours = type === 'road_closed' || type === 'landslide' || type === 'flood' ? 48 : 24;
  await env.DB.prepare(
    'INSERT INTO live_reports (type, note, lat, lon, device_id, created_at, expires_at) VALUES (?,?,?,?,?,?,?)'
  ).bind(type, note, lat, lon, deviceId, now, now + hours * 3600 * 1000).run();
  return json({ ok: true, expires_in_hours: hours }, 201);
}
