#!/usr/bin/env python3
"""ساخت سبک‌های نقشه روز/شب راه‌یاب از tools/offline_style_template.json.

خروجی در app/src/main/assets/style/ قرار می‌گیرد. اپ این سبک‌ها را به‌جای style.json داخل بسته
استفاده می‌کند (جای‌گذاری __PMTILES__ و __FONTS__ در زمان اجرا). بنابراین ظاهر نقشه بدون بازسازی
بسته‌های داده تغییر می‌کند؛ فقط نام لایه‌های برداری (طرح OpenMapTiles) مهم است.

نکته: جاده‌های اصلی عمداً با رنگ سبز/قرمز «ترافیک» نشان داده نمی‌شوند، چون برنامه داده زنده ترافیک
ندارد و رنگ ترافیک نادرست گمراه‌کننده است (docs/TRAFFIC.md).
"""
import copy, json, os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'offline_style_template.json')
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'style')

LIGHT = dict(
    bg='#EAE8E2', landuse='#E1DED7', landuse_op=1.0, park='#CDE4C4', park_op=0.9,
    water='#B7DCF0', building='#D9D5CD', building_line='#CAC5BB',
    path='#D8D4CB', rail='#B9B5AE',
    minor=('#FFFFFF', '#D5D1C8'), tertiary=('#FFFFFF', '#D2CEC4'),
    secondary=('#FFFFFF', '#CFCABF'), primary=('#FFF0BC', '#E2CA7C'),
    trunk=('#FFE39B', '#DDB65A'), motorway=('#FFCB6E', '#D9A042'),
    street_label='#4A5560', street_halo='#FFFFFF',
    hamlet='#1B6F8A', village='#1B6F8A', town='#12586F', city='#0F4C63', place_halo='#FFFFFF',
    poi_label='#46606E', poi_halo='#FFFFFF', poi_stroke='#FFFFFF',
)
DARK = dict(
    bg='#1E232B', landuse='#232933', landuse_op=1.0, park='#213A2A', park_op=0.85,
    water='#163247', building='#2A313C', building_line='#343D4A',
    path='#394250', rail='#4A5260',
    minor=('#3C4554', '#171B21'), tertiary=('#465063', '#171B21'),
    secondary=('#505C73', '#171B21'), primary=('#7C6C40', '#171B21'),
    trunk=('#8C7541', '#171B21'), motorway=('#AA853C', '#171B21'),
    street_label='#B7C4D2', street_halo='#11151B',
    hamlet='#9CCBE0', village='#9CCBE0', town='#B4DAEB', city='#CBE7F5', place_halo='#11151B',
    poi_label='#A6B6C4', poi_halo='#11151B', poi_stroke='#11151B',
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
