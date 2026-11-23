#!/usr/bin/env python3
"""دریافت کاشی‌های لایهٔ places از سرور map.af (با اجازهٔ مدیر map.af) — برای اجرا در Termux. فقط کتابخانهٔ استاندارد.

استفاده:
  echo 'توکن' > ~/tk.txt            # مقدار tk (بدون نقل‌قول)
  python3 fetch_places.py --stage 1  # اول مرحلهٔ ۱ (۳۴ مرکز ولایت، ≈۴ هزار کاشی)
  python3 fetch_places.py --stage 2  # سپس ۲ (مرحلهٔ ۱ و ۲ — ادامه از جای قبل) و بعد --stage 3
قابل ادامه: کاشی‌های دریافت‌شده دوباره گرفته نمی‌شوند. هر زمان بخواهید با Ctrl+C متوقف کنید.
خروجی: places-tiles/z/x/y.mvt و در پایان places-tiles.zip
مؤدبانه: پیش‌فرض ۳ درخواست در ثانیه، ایستادن خودکار با 401/403 (توکن تمام شده) و عقب‌نشینی با 429/5xx."""
import os, sys, time, argparse, zipfile, urllib.request, urllib.error
HERE = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser()
ap.add_argument('--stage', type=int, default=1, help='بیشینهٔ مرحله (1..3)')
ap.add_argument('--rate', type=float, default=3.0, help='درخواست در ثانیه')
ap.add_argument('--token-file', default=os.path.expanduser('~/tk.txt'))
ap.add_argument('--out', default='places-tiles')
ap.add_argument('--base', default='https://tiles.map.af/places/{z}/{x}/{y}.mvt')
a = ap.parse_args()
tk = open(a.token_file, encoding='utf-8').read().strip()
if not tk: sys.exit('توکن خالی است: ' + a.token_file)
tiles = []
for line in open(os.path.join(HERE, 'places_tiles.txt'), encoding='utf-8'):
    if line.startswith('#') or not line.strip(): continue
    st, z, x, y = map(int, line.split())
    if st <= a.stage: tiles.append((z, x, y))
HEAD = {'User-Agent': 'RahyabAfghanistan-offline/1.0 (permitted by map.af admins)', 'Referer': 'https://map.af/', 'Origin': 'https://map.af'}
def fetch(z, x, y):
    url = a.base.format(z=z, x=x, y=y) + '?tk=' + tk
    req = urllib.request.Request(url, headers=HEAD)
    with urllib.request.urlopen(req, timeout=30) as r: return r.status, r.read()
def path(z, x, y): return os.path.join(a.out, str(z), str(x), str(y) + '.mvt')
todo = [t for t in tiles if not os.path.exists(path(*t))]
print('کاشی‌ها: کل', len(tiles), '| مانده', len(todo), '| نرخ', a.rate, '/s | ≈ %.0f دقیقه' % (len(todo) / a.rate / 60))
# بررسی اولیه: یک کاشی
if todo:
    try:
        st, b = fetch(*todo[0]); print('آزمایش: HTTP', st, len(b), 'بایت')
    except urllib.error.HTTPError as e:
        sys.exit('آزمایش ناموفق: HTTP %d — توکن یا آدرس را بررسی کنید' % e.code)
done = fails = 0; delay = 1.0 / a.rate; t0 = time.time()
try:
    for z, x, y in todo:
        for attempt in range(5):
            try:
                st, b = fetch(z, x, y); break
            except urllib.error.HTTPError as e:
                if e.code in (404, 204): st, b = 204, b''; break
                if e.code in (401, 403): sys.exit('\nتوقف: HTTP %d — توکن منقضی/نامعتبر است. توکن تازه بگذارید و دوباره اجرا کنید (ادامه می‌دهد).' % e.code)
                time.sleep(min(60, 2 ** attempt * 2)); st = None
            except Exception:
                time.sleep(min(60, 2 ** attempt * 2)); st = None
        if st is None:
            fails += 1
            if fails > 30: sys.exit('\nتوقف: خطاهای پیاپی؛ بعداً دوباره اجرا کنید.')
            continue
        fails = 0
        p = path(z, x, y); os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, 'wb').write(b)       # فایل خالی = کاشی بدون محتوا
        done += 1
        if done % 100 == 0: print('%d / %d  (%.1f/s)' % (done, len(todo), done / (time.time() - t0)), flush=True)
        time.sleep(delay)
except KeyboardInterrupt:
    print('\nمتوقف شد (می‌توانید دوباره اجرا کنید).')
n = 0
with zipfile.ZipFile('places-tiles.zip', 'w', zipfile.ZIP_STORED) as z_:
    for root, _, fs in os.walk(a.out):
        for f in fs:
            p = os.path.join(root, f)
            if os.path.getsize(p) > 0: z_.write(p, os.path.relpath(p, a.out)); n += 1
print('places-tiles.zip ساخته شد:', n, 'کاشی غیرخالی')
