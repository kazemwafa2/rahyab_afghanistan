#!/usr/bin/env python3
"""ساخت سبک‌های نقشه روز/شب راه‌یاب از tools/offline_style_template.json.

خروجی در app/src/main/assets/style/ قرار می‌گیرد. اپ این سبک‌ها را به‌جای style.json داخل بسته
استفاده می‌کند (جای‌گذاری __PMTILES__ و __FONTS__ در زمان اجرا). بنابراین ظاهر نقشه بدون بازسازی
بسته‌های داده تغییر می‌کند؛ فقط نام لایه‌های برداری (طرح OpenMapTiles) مهم است.

پالت رسمی کاربر (۲۰۲۶-۱۱-۱۶) — روز: زمین روشن سبز-خاکستری با جاده‌های فیروزه‌ای؛
شب: سرمه‌ای تیره #111C28 با جاده‌های براق فیروزه‌ای. مقادیر دقیق در CHANGELOG.md نسخهٔ ۱.۱٫۳.

نکته: جاده‌های اصلی عمداً با رنگ سبز/قرمز «ترافیک» نشان داده نمی‌شوند، چون برنامه داده زنده ترافیک
ندارد و رنگ ترافیک نادرست گمراه‌کننده است (docs/TRAFFIC.md).
"""
import copy, json, os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'offline_style_template.json')
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'style')

# ---------- ابزار رنگ (همسان با MapTheme.kt) ----------

def _hex2rgb(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))

def _rgb2hex(r, g, b):
    return '#%02X%02X%02X' % (round(r) & 0xFF, round(g) & 0xFF, round(b) & 0xFF)

def shade(c, f):
    """تیره/روشن‌سازی مثل MapTheme.shade — f بین -۱ تا +۱"""
    import colorsys
    r, g, b = [x / 255.0 for x in _hex2rgb(c)]
    h, s, v = colorsys.rgb_to_hsv(r, g, b)
    delta = v * f if f < 0 else (1.0 - v) * f
    v = min(1.0, max(0.04, v + delta))
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return _rgb2hex(r * 255, g * 255, b * 255)

def mix(a, b, t):
    ra, ga, ba = _hex2rgb(a); rb, gb, bb = _hex2rgb(b)
    return _rgb2hex(ra + (rb - ra) * t, ga + (gb - ga) * t, ba + (bb - ba) * t)

# ---------- پالت رسمی راهیاب ----------

L_BG = '#F3F5F2'; L_BUILDING = '#E5E9E4'; L_BORDER = '#D0D8D2'
L_ALLEY = '#C8D2D0'; L_SECONDARY = '#2F9D93'; L_PRIMARY = '#13B0A2'
L_HIGHWAY = '#4FD1C5'; L_PARK = '#A8D08D'; L_TREES = '#55A65C'
L_WATER = '#4EA8E8'; L_ROUTE = '#3B82F6'; L_LABEL = '#263A40'
L_HALO = '#FFFFFF'; L_RESTAURANT = '#F39C12'; L_SHOP = '#8C6FE6'; L_HOSPITAL = '#EF5350'

D_BG = '#111C28'; D_BUILDING = '#263541'; D_BORDER = '#354652'
D_ALLEY = '#53636D'; D_SECONDARY = '#287F78'; D_PRIMARY = '#12A995'
D_HIGHWAY = '#38D6C0'; D_PARK = '#183C35'; D_TREES = '#3E9861'
D_WATER = '#173F59'; D_ROUTE = '#398BFF'; D_LABEL = '#E5EEF2'
D_HALO = '#111C28'; D_RESTAURANT = '#FFB547'; D_SHOP = '#A98BEE'; D_HOSPITAL = '#F16C78'

# سه دستهٔ پالت کاربر در نقطه‌های رنگی مکان‌ها (poi-dot)
POI_CATEGORY = {
    'hospital': 'hospital', 'clinic': 'hospital', 'doctors': 'hospital',
    'fire_station': 'hospital',
    'restaurant': 'restaurant', 'cafe': 'restaurant', 'fast_food': 'restaurant',
    'supermarket': 'shop', 'marketplace': 'shop',
}

LIGHT = dict(
    bg=L_BG, landuse=mix(L_BG, L_BUILDING, 0.45), landuse_op=1.0,
    park=L_PARK, park_op=0.85, grass=L_PARK, trees=L_TREES,
    water=L_WATER, building=L_BUILDING, building_line=L_BORDER,
    building3d=(L_BUILDING, shade(L_BUILDING, 0.06), shade(L_BUILDING, 0.12)),
    path=L_ALLEY, rail=mix(L_ALLEY, L_LABEL, 0.5),
    minor=(L_ALLEY, shade(L_ALLEY, -0.24)), tertiary=(L_SECONDARY, shade(L_SECONDARY, -0.24)),
    secondary=(L_SECONDARY, shade(L_SECONDARY, -0.24)), primary=(L_PRIMARY, shade(L_PRIMARY, -0.24)),
    trunk=(L_HIGHWAY, shade(L_HIGHWAY, -0.24)), motorway=(L_HIGHWAY, shade(L_HIGHWAY, -0.24)),
    street_label=L_LABEL, street_halo=L_HALO,
    hamlet=L_LABEL, village=L_LABEL, town=L_LABEL, city=L_LABEL, place_halo=L_HALO,
    poi_label=L_LABEL, poi_halo=L_HALO, poi_stroke=L_HALO,
    poi_hospital=L_HOSPITAL, poi_restaurant=L_RESTAURANT, poi_shop=L_SHOP,
)

DARK = dict(
    bg=D_BG, landuse=mix(D_BG, D_BUILDING, 0.45), landuse_op=1.0,
    park=D_PARK, park_op=0.9, grass=D_PARK, trees=D_TREES,
    water=D_WATER, building=D_BUILDING, building_line=D_BORDER,
    building3d=(D_BUILDING, shade(D_BUILDING, 0.06), shade(D_BUILDING, 0.12)),
    path=D_ALLEY, rail=mix(D_ALLEY, D_LABEL, 0.5),
    minor=(D_ALLEY, shade(D_ALLEY, -0.52)), tertiary=(D_SECONDARY, shade(D_SECONDARY, -0.52)),
    secondary=(D_SECONDARY, shade(D_SECONDARY, -0.52)), primary=(D_PRIMARY, shade(D_PRIMARY, -0.52)),
    trunk=(D_HIGHWAY, shade(D_HIGHWAY, -0.52)), motorway=(D_HIGHWAY, shade(D_HIGHWAY, -0.52)),
    street_label=D_LABEL, street_halo=D_HALO,
    hamlet=D_LABEL, village=D_LABEL, town=D_LABEL, city=D_LABEL, place_halo=D_HALO,
    poi_label=D_LABEL, poi_halo=D_HALO, poi_stroke=D_HALO,
    poi_hospital=D_HOSPITAL, poi_restaurant=D_RESTAURANT, poi_shop=D_SHOP,
)

ROAD_IDS = ['road-minor', 'road-tertiary', 'road-secondary', 'road-primary', 'road-trunk', 'road-motorway']
ROAD_KEYS = {'road-minor': 'minor', 'road-tertiary': 'tertiary', 'road-secondary': 'secondary',
             'road-primary': 'primary', 'road-trunk': 'trunk', 'road-motorway': 'motorway'}
# عرض‌های جاده (نقطه‌های زوم) کمی ضخیم‌تر از قالب پایه برای خوانایی روی گوشی
WIDTHS = {
    'minor': [[12, 1.0], [14, 2.8], [16, 6], [18, 12]],
    'tertiary': [[10, 1.2], [14, 3.4], [16, 8], [18, 15]],
    'secondary': [[9, 1.4], [14, 4.2], [16, 9], [18, 17]],
    'primary': [[7, 1.4], [14, 5], [16, 11], [18, 19]],
    'trunk': [[6, 1.4], [14, 5.6], [16, 12], [18, 20]],
    'motorway': [[5, 1.6], [14, 6.6], [16, 14], [18, 22]],
}


def stops(pairs, extra=0.0):
    return {'stops': [[z, round(w + extra, 2)] for z, w in pairs]}


def patch_poi_dot(paint, theme):
    """رنگ سه دستهٔ رستوران/دوکان/شفاخانه از پالت کاربر داخل عبارت match سبک"""
    expr = paint.get('circle-color')
    if not (isinstance(expr, list) and len(expr) > 2 and expr[0] == 'match'):
        return
    out = [expr[0], expr[1]]
    i = 2
    while i + 1 < len(expr):
        cls, color = expr[i], expr[i + 1]
        cat = POI_CATEGORY.get(cls)
        if cat == 'hospital':
            color = theme['poi_hospital']
        elif cat == 'restaurant':
            color = theme['poi_restaurant']
        elif cat == 'shop':
            color = theme['poi_shop']
        out.append(cls); out.append(color)
        i += 2
    paint['circle-color'] = out


def build(theme):
    base = json.load(open(SRC, encoding='utf-8'))
    out = []
    casings = []
    for layer in base['layers']:
        l = copy.deepcopy(layer)
        i = l['id']
        p = l.setdefault('paint', {})
        if i == 'background':
            p['background-color'] = theme['bg']
        elif i == 'landcover-wood':
            p['fill-color'] = theme['trees']
        elif i == 'landcover-grass':
            p['fill-color'] = theme['grass']
        elif i == 'landuse-residential':
            p['fill-color'] = theme['landuse']; p['fill-opacity'] = theme['landuse_op']
        elif i == 'park':
            p['fill-color'] = theme['park']; p['fill-opacity'] = theme['park_op']
        elif i == 'water':
            p['fill-color'] = theme['water']
        elif i == 'waterway':
            p['line-color'] = theme['water']
        elif i == 'building':
            p['fill-color'] = theme['building']; p['fill-outline-color'] = theme['building_line']
        elif i == 'building-3d':
            c0, c24, c60 = theme['building3d']
            p['fill-extrusion-color'] = ['interpolate', ['linear'], ['get', 'render_height'],
                                         0, c0, 24, c24, 60, c60]
        elif i == 'road-path':
            p['line-color'] = theme['path']
        elif i == 'rail':
            p['line-color'] = theme['rail']
        elif i in ROAD_IDS:
            key = ROAD_KEYS[i]
            fill, casing = theme[key]
            c = copy.deepcopy(l)
            c['id'] = i + '-casing'
            c['paint'] = {'line-color': casing, 'line-width': stops(WIDTHS[key], 1.6)}
            casings.append(c)
            p['line-color'] = fill
            p['line-width'] = stops(WIDTHS[key])
        elif i == 'road-street-label':
            p['text-color'] = theme['street_label']; p['text-halo-color'] = theme['street_halo']
            l['layout']['text-size'] = 12
        elif i == 'airport-label':
            p['text-color'] = theme['city']; p['text-halo-color'] = theme['place_halo']
        elif i == 'place-hamlet':
            p['text-color'] = theme['hamlet']; p['text-halo-color'] = theme['place_halo']
            l['layout']['text-size'] = 12
        elif i == 'place-village':
            p['text-color'] = theme['village']; p['text-halo-color'] = theme['place_halo']
            l['layout']['text-size'] = 13
        elif i == 'place-town':
            p['text-color'] = theme['town']; p['text-halo-color'] = theme['place_halo']
        elif i == 'place-city':
            p['text-color'] = theme['city']; p['text-halo-color'] = theme['place_halo']
        elif i == 'poi-label':
            p['text-color'] = theme['poi_label']; p['text-halo-color'] = theme['poi_halo']
        elif i == 'poi-dot':
            p['circle-stroke-width'] = 1.2; p['circle-stroke-color'] = theme['poi_stroke']
            patch_poi_dot(p, theme)
        out.append(l)
    # همه پوشش‌ها (حاشیه جاده) پیش از پرکننده‌ها تا تقاطع‌ها یکپارچه دیده شوند
    first = next(n for n, l in enumerate(out) if l['id'] == 'road-minor')
    out[first:first] = casings
    base['layers'] = out
    base['name'] = 'Rahyab'
    return base


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, theme in (('rahyab_light', LIGHT), ('rahyab_dark', DARK)):
        path = os.path.join(OUT, name + '.json')
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(build(theme), f, ensure_ascii=False, separators=(',', ':'))
        print('written', path, os.path.getsize(path), 'bytes')


if __name__ == '__main__':
    main()
