#!/usr/bin/env python3
"""Unify POIs -> assets/poi/poi_all.geojson  (no duplicates; priority: places > osm-poi > af tiles).
usage: build_poi_all.py af_pois.geojson osm-poi.pmtiles places.pmtiles out.geojson [places-tiles.zip]
  (اختیاری) places-tiles.zip = خروجی tools/fetch_places.py؛ کاشی‌های z/x/y.mvt به places اضافه می‌شوند
  af_pois.geojson = output of tools/extract_poi_lowzoom.py afghanistan_full.pmtiles ...
Keys: c=category, fa/en/ps=labels, s=source (p places, o osm-poi, a tiles)."""
import sys, json, collections, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pmtreader as R

af_path, osm_path, places_path, out_path = sys.argv[1:5]
extra_zip = sys.argv[5] if len(sys.argv) > 5 else None
CLASS2CAT = {'place_of_worship': 'mosque', 'school': 'school', 'kindergarten': 'school', 'college': 'university', 'university': 'university',
  'hospital': 'hospital', 'dentist': 'clinic', 'clinic': 'clinic', 'doctors': 'clinic', 'veterinary': 'clinic', 'pharmacy': 'pharmacy',
  'fuel': 'fuel', 'lodging': 'hotel', 'restaurant': 'restaurant', 'fast_food': 'restaurant', 'ice_cream': 'cafe', 'cafe': 'cafe', 'bar': 'cafe',
  'bank': 'bank', 'atm': 'bank', 'post': 'government', 'police': 'government', 'town_hall': 'government', 'diplomatic': 'government',
  'prison': 'government', 'fire_station': 'government', 'courthouse': 'government', 'border_control': 'government',
  'grocery': 'shop', 'shop': 'shop', 'clothing_store': 'shop', 'hairdresser': 'shop', 'car': 'shop', 'butcher': 'shop', 'bakery': 'shop',
  'alcohol_shop': 'shop', 'laundry': 'shop', 'supermarket': 'market', 'marketplace': 'market', 'mall': 'market',
  'bus': 'transit', 'railway': 'transit', 'aerodrome': 'airport', 'attraction': 'landmark', 'castle': 'landmark', 'monument': 'landmark',
  'art_gallery': 'landmark', 'museum': 'landmark', 'library': 'landmark', 'theatre': 'landmark', 'cinema': 'landmark',
  'parking': 'parking', 'stadium': 'park', 'sports_centre': 'park', 'swimming_pool': 'park'}

def norm(s):
    if not s: return ''
    m = {'ي': 'ی', 'ى': 'ی', 'ك': 'ک', 'ة': 'ه', 'ۀ': 'ه', 'أ': 'ا', 'إ': 'ا', 'آ': 'ا'}
    return ''.join(m.get(c, c) for c in s.lower() if c.isalnum())
def latin(s): return any('a' <= c.lower() <= 'z' for c in (s or ''))
def txt(v): return v.strip() if isinstance(v, str) else ''

cands = []   # (priority, lon, lat, cat, fa, en, ps, src)
# 1) places.pmtiles (unique pid, keep highest zoom)
best = {}
def _places_tiles():
    yield from R.PMTiles(places_path).tiles()
    if extra_zip:
        import zipfile, gzip
        for n in zipfile.ZipFile(extra_zip).namelist():
            try: z, x, y = n[:-4].split('/'); z, x, y = int(z), int(x), int(y)
            except ValueError: continue
            b = zipfile.ZipFile(extra_zip).read(n)
            if b[:2] == b'\x1f\x8b': b = gzip.decompress(b)
            yield z, x, y, b
for z, x, y, b in _places_tiles():
    for layer, (px, py), d, ext in R.points(b):
        pid = d.get('pid')
        if pid is None or (pid in best and best[pid][0] >= z): continue
        lon, lat = R.tile_point_to_lonlat(z, x, y, px, py, ext)
        best[pid] = (z, lon, lat, d, layer)
for z, lon, lat, d, layer in best.values():
    fa = txt(d.get('label_fa')) or txt(d.get('name_fa')) or txt(d.get('name')) or txt(d.get('label_en'))
    en = txt(d.get('label_en')) or txt(d.get('name_en')); ps = txt(d.get('label_ps'))
    cands.append((0, lon, lat, d.get('category') or 'other', fa, en, ps, 'p'))
# 2) osm-poi.pmtiles
best = {}
for z, x, y, b in R.PMTiles(osm_path).tiles():
    for layer, (px, py), d, ext in R.points(b):
        lon, lat = R.tile_point_to_lonlat(z, x, y, px, py, ext)
        k = (round(lon, 4), round(lat, 4), txt(d.get('label_fa')), txt(d.get('label_en')))
        if k in best and best[k][0] >= z: continue
        best[k] = (z, lon, lat, d)
for z, lon, lat, d in best.values():
    cands.append((1, lon, lat, d.get('category') or 'other', txt(d.get('label_fa')) or txt(d.get('label_en')), txt(d.get('label_en')), txt(d.get('label_ps')), 'o'))
# 3) tiles of afghanistan_full
for f in json.load(open(af_path, encoding='utf-8'))['features']:
    p = f['properties']; lon, lat = f['geometry']['coordinates']
    fa = txt(p.get('name:ar')) if (p.get('name') and p.get('name') == p.get('name:latin') and p.get('name:ar')) else txt(p.get('name'))
    en = txt(p.get('name:en')) or txt(p.get('name:latin'))
    cands.append((2, lon, lat, CLASS2CAT.get(p.get('class'), 'other'), fa, en if latin(en) else '', '', 'a'))

# dedupe: same normalised name within ~110 m; unnamed vs same category within ~14 m
CELL = 0.001
grid = collections.defaultdict(list); out = []; dropped = collections.Counter()
def nearby(lon, lat):
    gi, gj = int(lat / CELL), int(lon / CELL)
    for a in (-1, 0, 1):
        for b in (-1, 0, 1): yield from grid.get((gi + a, gj + b), ())
for pr, lon, lat, cat, fa, en, ps, src in sorted(cands, key=lambda c: c[0]):
    n = norm(fa) or norm(en); dup = False
    for o in nearby(lon, lat):
        dx = (o['lon'] - lon) * 111320 * 0.83; dy = (o['lat'] - lat) * 110540; dist2 = dx * dx + dy * dy
        if n and o['n'] == n and dist2 < 110 ** 2: dup = True; break
        if (not n or not o['n']) and o['cat'] == cat and dist2 < 14 ** 2: dup = True; break
    if dup: dropped[src] += 1; continue
    rec = {'lon': lon, 'lat': lat, 'n': n, 'cat': cat}
    grid[(int(lat / CELL), int(lon / CELL))].append(rec)
    props = {'c': cat, 's': src}
    if fa: props['fa'] = fa
    if en and en != fa and latin(en): props['en'] = en
    if ps and ps != fa: props['ps'] = ps
    out.append({'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [round(lon, 5), round(lat, 5)]}, 'properties': props})
json.dump({'type': 'FeatureCollection', 'features': out}, open(out_path, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
c = collections.Counter(f['properties']['s'] for f in out)
print('written', len(out), dict(c), 'dropped dups', dict(dropped), 'bytes', os.path.getsize(out_path), file=sys.stderr)
