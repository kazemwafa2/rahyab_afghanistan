#!/usr/bin/env python3
"""افزودن آیکن «دوکان» و رنگ‌بندی دسته‌ای برچسب POI به سبک‌های آماده (rahyab_light/dark.json).

چرا مستقیم روی سبک‌های داخل assets؟  tools/offline_style_template.json از سبک فعلی عقب‌تر است
(لایه‌های poi-icon-* را ندارد)؛ بازسازی با make_styles.py آن لایه‌ها را حذف می‌کرد.
این اسکریپت idempotent است: اجرای دوباره تغییر اضافه‌ای نمی‌دهد.

آیکن‌های جدید (ic-shop, ic-cart) کاملاً طرح اختصاصی راه‌یاب‌اند و از هیچ سبک/اسپرایت
شخص ثالث (از جمله map.af) کپی نشده‌اند.

اجرا:  python3 tools/patch_poi_style.py
"""
import json, os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets')
SPRITE_JSON = os.path.join(ASSETS, 'sprites', 'sprite.json')
SPRITE_PNG = os.path.join(ASSETS, 'sprites', 'sprite.png')

SHOP_CLASSES = ['shop', 'grocery', 'clothing_store', 'jewelry', 'florist', 'butcher', 'bakery',
                'hairdresser', 'laundry', 'furniture', 'electronics', 'mobile_phone', 'books',
                'stationery', 'department_store', 'variety_store', 'car', 'bicycle', 'alcohol_shop']

# دسته‌های رنگی برچسب (روز، شب) — فقط برچسب متن؛ آیکن‌ها همان آیکن‌های فعلی‌اند
LABEL_COLORS = {
    'shop':   ('#B85C00', '#FFB74D', SHOP_CLASSES + ['marketplace', 'supermarket', 'convenience', 'mall']),
    'health': ('#C62828', '#EF9A9A', ['hospital', 'clinic', 'doctors', 'pharmacy']),
    'edu':    ('#1565C0', '#90CAF9', ['school', 'college', 'university', 'kindergarten', 'library']),
    'money':  ('#00695C', '#80CBC4', ['bank', 'atm', 'post']),
    'food':   ('#2E7D32', '#A5D6A7', ['restaurant', 'cafe', 'fast_food', 'hotel', 'guest_house']),
    'gov':    ('#4E342E', '#BCAAA4', ['townhall', 'courthouse', 'police', 'fire_station', 'museum', 'attraction']),
    'faith':  ('#6A1B9A', '#CE93D8', ['place_of_worship']),
}
DEFAULT_LIGHT, DEFAULT_DARK = '#263A40', '#E5EEF2'


def draw_icons():
    """دو آیکن 60x60 (pixelRatio 2) در خانه‌های خالی (4,3) و (5,3) شیت اسپرایت"""
    with open(SPRITE_JSON, encoding='utf-8') as f:
        sp = json.load(f)
    if 'ic-shop' in sp and 'ic-cart' in sp:
        return
    im = Image.open(SPRITE_PNG).convert('RGBA')
    S = 8  # فوق‌نمونه‌برداری برای لبه‌های نرم

    def tile(color, glyph):
        big = Image.new('RGBA', (60 * S, 60 * S), (0, 0, 0, 0))
        d = ImageDraw.Draw(big)
        d.rounded_rectangle([2 * S, 2 * S, 58 * S, 58 * S], radius=10 * S, fill=color)
        glyph(d)
        return big.resize((60, 60), Image.LANCZOS)

    def bag(d):
        w = (255, 255, 255, 255)
        d.rounded_rectangle([16 * S, 24 * S, 44 * S, 46 * S], radius=3 * S, fill=w)
        d.arc([22 * S, 12 * S, 38 * S, 34 * S], 180, 360, fill=w, width=int(2.6 * S))

    def cart(d):
        w = (255, 255, 255, 255)
        d.line([(12 * S, 16 * S), (19 * S, 16 * S), (23 * S, 36 * S), (43 * S, 36 * S)], fill=w, width=int(3 * S))
        d.polygon([(21 * S, 21 * S), (46 * S, 21 * S), (42 * S, 33 * S), (24 * S, 33 * S)], fill=w)
        for cx in (26, 40):
            d.ellipse([(cx - 3.2) * S, 40 * S, (cx + 3.2) * S, 46.4 * S], fill=w)

    for name, col, g, (cx, cy) in (('ic-shop', (92, 107, 192, 255), bag, (4, 3)),
                                   ('ic-cart', (121, 85, 72, 255), cart, (5, 3))):
        im.paste(tile(col, g), (cx * 60, cy * 60))
        sp[name] = {'width': 60, 'height': 60, 'x': cx * 60, 'y': cy * 60, 'pixelRatio': 2}
    im.save(SPRITE_PNG, optimize=True)
    with open(SPRITE_JSON, 'w', encoding='utf-8') as f:
        json.dump(sp, f, ensure_ascii=False, separators=(',', ':'))
    print('sprite: added ic-shop, ic-cart')


def patch_style(name, dark):
    path = os.path.join(ASSETS, 'style', name)
    with open(path, encoding='utf-8') as f:
        st = json.load(f)
    for l in st['layers']:
        if l['id'] == 'poi-icon-minor':
            l['minzoom'] = 15
            cls = l['filter'][2][1]  # ["literal", [...classes]]
            for c in SHOP_CLASSES:
                if c not in cls:
                    cls.append(c)
            expr = l['layout']['icon-image']
            if 'shop' not in expr:
                tail = expr[-1]
                expr = expr[:-1]
                for c in SHOP_CLASSES:
                    if c not in expr[2::2]:
                        expr += [c, 'ic-cart' if c in ('grocery', 'butcher', 'bakery', 'car', 'bicycle') else 'ic-shop']
                expr.append(tail)
                l['layout']['icon-image'] = expr
            l['layout']['icon-size'] = {'stops': [[15, 0.62], [17, 0.8], [19, 0.95]]}
        elif l['id'] == 'poi-label':
            l['minzoom'] = 15
            l['layout']['text-size'] = {'stops': [[15, 10.5], [18, 12.5]]}
            default = DEFAULT_DARK if dark else DEFAULT_LIGHT
            expr = ['match', ['get', 'class']]
            for light, night, classes in LABEL_COLORS.values():
                expr += [classes, night if dark else light]
            expr.append(default)
            l['paint']['text-color'] = expr
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(st, f, ensure_ascii=False, separators=(',', ':'))
    print('patched', name)


if __name__ == '__main__':
    draw_icons()
    patch_style('rahyab_light.json', False)
    patch_style('rahyab_dark.json', True)
