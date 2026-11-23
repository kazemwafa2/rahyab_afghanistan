#!/usr/bin/env python3
"""افزودن لایه‌های برچسب و عوارض که در سبک راه‌یاب نبودند (idempotent).

لایه‌های داده‌ای بستهٔ afghanistan_full (طرح OpenMapTiles) که تا امروز اصلاً رسم نمی‌شدند:
  water_name (نام دریاچه/رودخانه)، mountain_peak (نام و ارتفاع قله)، housenumber (شمارهٔ پلاک)،
  aeroway (باند فرودگاه)، و در place: state/country/island. همچنین نام سرک/کوچه فقط از `name`
  می‌آمد؛ حالا با ترتیب name:fa → name:ar → name → name:latin مثل بقیهٔ برچسب‌ها خوانده می‌شود.

فقط از دو فونت موجود در برنامه (Noto Sans Regular/Bold) استفاده می‌شود؛ نماد ▲ در فونت نیست،
پس قله با آیکن sprite (ic-peak، طرح اختصاصی) نشان داده می‌شود.

هر لایه‌ای که در داده نباشد (مثلاً نام ساختمان) در MapLibre بی‌خطا خالی می‌ماند.

اجرا:  python3 tools/patch_label_layers.py
"""
import json, os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets')
SPRITE_JSON = os.path.join(ASSETS, 'sprites', 'sprite.json')
SPRITE_PNG = os.path.join(ASSETS, 'sprites', 'sprite.png')

# داده فقط name / name:en / name:ar / name:latin دارد (name:fa و name:ps در بسته وجود ندارند — بررسی ۲۰۲۶-۱۱-۱۸).
# name:ar گاهی ترجمهٔ عربی است («جامعة كابل») و گاهی نوشتهٔ فارسی‌خط یک نام لاتین («آسیا مارکیت»).
# قاعده: اگر name لاتین است (برابر name:latin) و name:ar هست → name:ar؛ وگرنه name؛ بعد name:latin.
NAME = ['case',
        ['all', ['has', 'name:ar'], ['==', ['get', 'name'], ['get', 'name:latin']]], ['get', 'name:ar'],
        ['coalesce', ['get', 'name'], ['get', 'name:latin'], ['get', 'name:en']]]
OLD_COALESCE = ['coalesce', ['get', 'name:fa'], ['get', 'name:ar'], ['get', 'name'], ['get', 'name:latin']]


def swap_name(expr, top=True):
    """جایگزینی عبارت‌های قدیمی نام (idempotent): coalesce قدیمی در هر عمقی، و ['get','name'] فقط
    وقتی کل text-field است (تا داخل عبارت جدید دوباره جایگزین نشود)."""
    if expr == OLD_COALESCE or (top and expr == ['get', 'name']):
        return NAME
    if isinstance(expr, list) and expr is not NAME and expr != NAME:
        return [swap_name(e, False) for e in expr]
    return expr
REG, BOLD = ['Noto Sans Regular'], ['Noto Sans Bold']

THEMES = {
    'light': dict(text='#263A40', halo='#FFFFFF', water='#1565C0', water_halo='#E3F2FD',
                  peak='#5D4037', peak_halo='#FFFFFF', hn='#6D4C41', runway='#B0BEC5',
                  landuse='#E8E3D8', admin='#6D4C41'),
    'dark': dict(text='#E5EEF2', halo='#111C28', water='#81D4FA', water_halo='#0B1218',
                 peak='#D7CCC8', peak_halo='#111C28', hn='#BCAAA4', runway='#455A64',
                 landuse='#1A2531', admin='#BCAAA4'),
}


def draw_peak_icon():
    with open(SPRITE_JSON, encoding='utf-8') as f:
        sp = json.load(f)
    if 'ic-peak' in sp:
        return
    im = Image.open(SPRITE_PNG).convert('RGBA')
    if im.height < 300:  # ردیف پنجم برای آیکن‌های تازه
        canvas = Image.new('RGBA', (im.width, 300), (0, 0, 0, 0))
        canvas.paste(im, (0, 0))
        im = canvas
    S = 8
    big = Image.new('RGBA', (60 * S, 60 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    d.rounded_rectangle([2 * S, 2 * S, 58 * S, 58 * S], radius=10 * S, fill=(109, 76, 65, 255))
    w = (255, 255, 255, 255)
    d.polygon([(10 * S, 46 * S), (24 * S, 20 * S), (34 * S, 36 * S), (40 * S, 28 * S), (50 * S, 46 * S)], fill=w)
    d.polygon([(21 * S, 26 * S), (24 * S, 20 * S), (27 * S, 26 * S), (24 * S, 24.5 * S)], fill=(109, 76, 65, 255))
    im.paste(big.resize((60, 60), Image.LANCZOS), (0, 240))
    im.save(SPRITE_PNG, optimize=True)
    sp['ic-peak'] = {'width': 60, 'height': 60, 'x': 0, 'y': 240, 'pixelRatio': 2}
    with open(SPRITE_JSON, 'w', encoding='utf-8') as f:
        json.dump(sp, f, ensure_ascii=False, separators=(',', ':'))
    print('sprite: added ic-peak')


def new_layers(t):
    halo = lambda c, w=1.3: {'text-halo-color': c, 'text-halo-width': w}
    return [
        {'id': 'landuse-other', 'type': 'fill', 'source': 'rahyab', 'source-layer': 'landuse', 'minzoom': 12,
         'filter': ['in', ['get', 'class'], ['literal', ['cemetery', 'industrial', 'hospital', 'school', 'university',
                                                         'retail', 'military', 'railway', 'stadium']]],
         'paint': {'fill-color': t['landuse'], 'fill-opacity': 0.8}},
        {'id': 'aeroway-runway', 'type': 'line', 'source': 'rahyab', 'source-layer': 'aeroway', 'minzoom': 10,
         'filter': ['in', ['get', 'class'], ['literal', ['runway', 'taxiway']]],
         'paint': {'line-color': t['runway'], 'line-width': {'stops': [[10, 1.5], [14, 6], [17, 16]]}}},
        {'id': 'water-name', 'type': 'symbol', 'source': 'rahyab', 'source-layer': 'water_name', 'minzoom': 5,
         'layout': {'text-field': NAME, 'text-font': REG, 'text-size': {'stops': [[5, 11], [12, 14]]},
                    'text-max-width': 8, 'text-letter-spacing': 0, 'text-optional': True},
         'paint': dict({'text-color': t['water']}, **halo(t['water_halo']))},
        {'id': 'waterway-label', 'type': 'symbol', 'source': 'rahyab', 'source-layer': 'waterway', 'minzoom': 11,
         'filter': ['has', 'name'],
         'layout': {'symbol-placement': 'line', 'text-field': NAME, 'text-font': REG,
                    'text-size': 11, 'text-letter-spacing': 0, 'text-max-angle': 38},
         'paint': dict({'text-color': t['water']}, **halo(t['water_halo']))},
        {'id': 'mountain-peak', 'type': 'symbol', 'source': 'rahyab', 'source-layer': 'mountain_peak', 'minzoom': 9,
         'layout': {'icon-image': 'ic-peak', 'icon-size': {'stops': [[9, 0.34], [14, 0.5]]},
                    'icon-optional': False, 'symbol-sort-key': ['*', -1, ['coalesce', ['to-number', ['get', 'ele']], 0]],
                    'text-field': ['case', ['has', 'ele'],
                                   ['concat', NAME, '\n', ['to-string', ['get', 'ele']], ' m'], NAME],
                    'text-font': REG, 'text-size': 11, 'text-offset': [0, 1.05], 'text-anchor': 'top',
                    'text-max-width': 8, 'text-letter-spacing': 0, 'text-optional': True, 'icon-padding': 4},
         'paint': dict({'text-color': t['peak']}, **halo(t['peak_halo']))},
        {'id': 'building-name', 'type': 'symbol', 'source': 'rahyab', 'source-layer': 'building', 'minzoom': 16.5,
         'filter': ['has', 'name'],
         'layout': {'text-field': NAME, 'text-font': REG, 'text-size': 10.5, 'text-max-width': 7,
                    'text-letter-spacing': 0, 'text-optional': True, 'text-padding': 2},
         'paint': dict({'text-color': t['text']}, **halo(t['halo']))},
        {'id': 'housenumber', 'type': 'symbol', 'source': 'rahyab', 'source-layer': 'housenumber', 'minzoom': 17.5,
         'layout': {'text-field': ['get', 'housenumber'], 'text-font': REG, 'text-size': 10, 'text-optional': True},
         'paint': dict({'text-color': t['hn']}, **halo(t['halo'], 1.0))},
        {'id': 'place-island', 'type': 'symbol', 'source': 'rahyab', 'source-layer': 'place', 'minzoom': 8,
         'filter': ['==', ['get', 'class'], 'island'],
         'layout': {'text-field': NAME, 'text-font': REG, 'text-size': 12, 'text-letter-spacing': 0},
         'paint': dict({'text-color': t['text']}, **halo(t['halo']))},
        {'id': 'place-state', 'type': 'symbol', 'source': 'rahyab', 'source-layer': 'place', 'minzoom': 4, 'maxzoom': 9,
         'filter': ['==', ['get', 'class'], 'state'],
         'layout': {'text-field': NAME, 'text-font': BOLD, 'text-size': {'stops': [[4, 10], [8, 14]]},
                    'text-max-width': 8, 'text-letter-spacing': 0.0},
         'paint': dict({'text-color': t['admin']}, **halo(t['halo'], 1.5))},
        {'id': 'place-country', 'type': 'symbol', 'source': 'rahyab', 'source-layer': 'place', 'minzoom': 1, 'maxzoom': 6,
         'filter': ['==', ['get', 'class'], 'country'],
         'layout': {'text-field': NAME, 'text-font': BOLD, 'text-size': {'stops': [[1, 11], [5, 16]]},
                    'text-max-width': 8, 'text-letter-spacing': 0.0},
         'paint': dict({'text-color': t['admin']}, **halo(t['halo'], 1.6))},
    ]


def patch_style(name, theme):
    path = os.path.join(ASSETS, 'style', name)
    with open(path, encoding='utf-8') as f:
        st = json.load(f)
    layers = st['layers']
    have = {l['id'] for l in layers}
    # نام سرک/کوچه با ترتیب زبان‌ها
    for l in layers:
        tf = l.get('layout', {}).get('text-field')
        if tf is not None and l['type'] == 'symbol':
            l['layout']['text-field'] = swap_name(tf)
    add = [l for l in new_layers(THEMES[theme]) if l['id'] not in have]
    # لایه‌های پرکننده (فقط landuse/aeroway) زیر جاده‌ها؛ برچسب‌ها بالای همه
    fills = [l for l in add if l['type'] in ('fill', 'line')]
    labels = [l for l in add if l['type'] == 'symbol']
    if fills:
        i = next(n for n, l in enumerate(layers) if l['id'] == 'building')
        layers[i:i] = fills
    layers.extend(labels)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(st, f, ensure_ascii=False, separators=(',', ':'))
    print('patched', name, '+%d layers' % len(add))


if __name__ == '__main__':
    draw_peak_icon()
    patch_style('rahyab_light.json', 'light')
    patch_style('rahyab_dark.json', 'dark')
