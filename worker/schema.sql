-- ============================================================
-- راه‌یاب افغانستان — ضمیمهٔ ۱: سیستم جامع اطلاعات جاده‌ای
-- جداول D1 برای مکان‌های ثابت، گزارش‌های مکان‌ها، رأی‌ها،
-- خدمات عمومی و تجهیزات کنترولی.
-- اجرا:  wrangler d1 execute rahyab-reports --file=schema.sql --remote
-- ============================================================

-- جدول مکان‌های ثابت جاده‌ای (بخش ششم — road_pois)
CREATE TABLE IF NOT EXISTS road_pois (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  category TEXT NOT NULL,                -- speed_camera / bump / police_station / toll_plaza / fuel_station / hospital / ...
  subcategory TEXT,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  province TEXT,
  district TEXT,
  road_name TEXT,
  direction TEXT,                        -- جهت کنترل (برای کمره/سرعت‌گیر): north/south/east/west/both
  description TEXT,
  properties_json TEXT,                  -- دسته‌محور: speed_limit، height_limit_m، fuel_type، lanes، opening_hours…
  verification_status TEXT NOT NULL DEFAULT 'unverified',  -- unverified / user_verified / official
  source TEXT NOT NULL DEFAULT 'user',   -- user / osm / official
  created_at INTEGER NOT NULL,           -- epoch ms
  updated_at INTEGER NOT NULL,
  last_verified_at INTEGER,
  is_active INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_pois_cat     ON road_pois (category, is_active);
CREATE INDEX IF NOT EXISTS idx_pois_coord   ON road_pois (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_pois_status  ON road_pois (verification_status, is_active);
CREATE INDEX IF NOT EXISTS idx_pois_created ON road_pois (created_at);
CREATE INDEX IF NOT EXISTS idx_pois_updated ON road_pois (updated_at);
CREATE INDEX IF NOT EXISTS idx_pois_prov    ON road_pois (province);

-- گزارش کاربران دربارهٔ مکان‌های ثابت (کمره خراب شده، عوارض تعطیل، پمپ بنزین سوخت ندارد…)
CREATE TABLE IF NOT EXISTS road_poi_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  poi_id INTEGER NOT NULL,
  report_type TEXT NOT NULL,             -- broken / removed / wrong_info / moved / closed / reopened / price_changed
  description TEXT,
  device_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open',   -- open / accepted / rejected
  created_at INTEGER NOT NULL,
  expires_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_poireports_poi    ON road_poi_reports (poi_id, status);
CREATE INDEX IF NOT EXISTS idx_poireports_time   ON road_poi_reports (created_at);
CREATE INDEX IF NOT EXISTS idx_poireports_device ON road_poi_reports (device_id);

-- رأی تأیید یا رد کاربران دربارهٔ درستی مکان‌ها
CREATE TABLE IF NOT EXISTS road_poi_votes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  poi_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  vote_type TEXT NOT NULL,               -- confirm / deny
  created_at INTEGER NOT NULL,
  UNIQUE(poi_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_poivotes_poi ON road_poi_votes (poi_id, vote_type);

-- خدمات عمومی و امکانات رفاهی مسیر (بخش چهارم)
CREATE TABLE IF NOT EXISTS road_services (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  service_type TEXT NOT NULL,            -- fuel / repair / medical / parking / rest / mosque / market / terminal…
  name TEXT NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  phone TEXT,                            -- فقط شمارهٔ عمومی تأییدشده
  opening_hours TEXT,
  available_services TEXT,               -- جداشده با کاما: tire,wash,oil
  verification_status TEXT NOT NULL DEFAULT 'unverified',
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_services_type   ON road_services (service_type);
CREATE INDEX IF NOT EXISTS idx_services_coord  ON road_services (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_services_status ON road_services (verification_status);

-- تجهیزات کنترولی و ایمنی جاده (بخش اول و دوم)
-- نکتهٔ حریم خصوصی: فقط موقعیت و مشخصات عمومی؛ هیچ دسترسی به تصاویر یا شبکهٔ دوربین‌ها نیست.
CREATE TABLE IF NOT EXISTS road_equipment (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  equipment_type TEXT NOT NULL,          -- speed_camera / surveillance_camera / avg_speed_check / smart_speed_sign / speed_warning_display / vehicle_counter / traffic_sensor / tunnel_bridge_alert
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  direction TEXT,
  speed_limit INTEGER,                   -- فقط وقتی محدودیت سرعت مرتبط واقعاً تأیید شده باشد
  road_id TEXT,
  verification_status TEXT NOT NULL DEFAULT 'unverified',
  source TEXT NOT NULL DEFAULT 'user',
  last_verified_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_equip_type   ON road_equipment (equipment_type, verification_status);
CREATE INDEX IF NOT EXISTS idx_equip_coord  ON road_equipment (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_equip_time   ON road_equipment (last_verified_at);

-- گزارش‌های زندهٔ جاده (بخش پنجم — به سیستم گزارش‌دهی قبلی متصل است)
-- client_uuid (بند ۱۹ سند جامع): شناسهٔ یکتای عملیات از کلاینت برای idempotency؛
-- برای دیتابیس‌های «موجود» نیازی به اجرای دوبارهٔ این فایل نیست — Worker هنگام
-- نخستین POST ستون و ایندکس را به‌صورت خودکار و امن (ADD COLUMN + IF NOT EXISTS) اضافه می‌کند.
CREATE TABLE IF NOT EXISTS live_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,                    -- accident / heavy_traffic / road_closed / road_damage / hazard / speed_bump / …
  note TEXT,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  device_id TEXT,
  client_uuid TEXT,                      -- شناسهٔ یکتای عملیات (تکراری‌فرستانی ردیف دوباره نمی‌سازد)
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL            -- پیش‌فرض ۲۴ ساعت (انسداد/ریزش/سیلاب: ۴۸ ساعت)
);

CREATE INDEX IF NOT EXISTS idx_live_reports_time ON live_reports (expires_at);
CREATE INDEX IF NOT EXISTS idx_live_reports_type ON live_reports (type, expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_live_reports_client_uuid ON live_reports (client_uuid) WHERE client_uuid IS NOT NULL;
