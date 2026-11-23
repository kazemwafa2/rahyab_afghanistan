#!/usr/bin/env python3
"""Align POI/place layers with the REAL OpenMapTiles classes found in afghanistan_full.pmtiles
(lodging, bus, dentist, town_hall, diplomatic, castle, art_gallery...), show POIs/labels from z14
(the only zoom that carries the poi layer) and add suburb/neighbourhood labels. Idempotent."""
import json, copy
ICON = {  # data class -> sprite icon
 'lodging':'ic-hotel','bus':'ic-bus','dentist':'ic-clinic','veterinary':'ic-clinic','town_hall':'ic-gov','diplomatic':'ic-gov',
 'prison':'ic-gov','castle':'ic-museum','art_gallery':'ic-museum','monument':'ic-museum','theatre':'ic-museum','cinema':'ic-museum',
 'information':'ic-museum','stadium':'ic-park','sports_centre':'ic-park','swimming_pool':'ic-park','ice_cream':'ic-food','bar':'ic-food',
 'bakery':'ic-cart','toilets':'ic-dot','shelter':'ic-dot'}
ALIAS = [('hospital',['dentist','veterinary']),('restaurant',['lodging','ice_cream','bar']),('townhall',['town_hall','diplomatic','prison']),
         ('museum',['castle','art_gallery','monument','theatre','cinema']),('school',['gymnastics']),('bus_station',['bus']),('hotel',['lodging'])]
NOISE = ['gate','lift_gate','bollard','cycle_barrier','brownfield','cemetery','pitch','playground','waste_basket','bicycle_parking','telephone',
         'campsite','theme_park','running','athletics','billiards','swimming','multi','music','beer','water_park','garden','park']
MAJOR = ['hospital','university','college','place_of_worship','fuel','bus','police','aerodrome','bank','pharmacy','school','lodging','attraction',
         'castle','museum','diplomatic','town_hall','dentist','marketplace','supermarket','mall']
def fix_match(expr):
    """match-Ausdruck: Aliase zu bestehenden Klassen hinzufügen (Listen erweitern / Paare ergänzen)."""
    if not (isinstance(expr, list) and expr and expr[0] == 'match'): return
    default = expr[-1]; pairs = [[expr[i], expr[i + 1]] for i in range(2, len(expr) - 1, 2)]
    present = set()
    for lab, _ in pairs: present |= set(lab if isinstance(lab, list) else [lab])
    out = []
    for lab, val in pairs:
        out.append([lab, val]); names = lab if isinstance(lab, list) else [lab]
        for base, extra in ALIAS:
            if base in names:
                add = [e for e in extra if e not in present]; present |= set(add)
                if not add: continue
                if isinstance(lab, list): out[-1][0] = out[-1][0] + add
                else: out.extend([[e, val] for e in add])
    expr[2:] = [x for pr in out for x in pr] + [default]
def patch(path):
    s = json.load(open(path, encoding='utf-8')); L = {l['id']: l for l in s['layers']}
    # dots: aliases + only first half-zoom (icons take over)
    d = L['poi-dot']; fix_match(d['paint']['circle-color']); d['minzoom'] = 14; d['maxzoom'] = 14.4
    # icons
    ic = L['poi-icon-major']['layout']['icon-image']; ic2 = L['poi-icon-minor']['layout']['icon-image']
    for e in (ic, ic2):
        have = set(e[2:-1:2])
        extra = []
        for k, v in ICON.items():
            if k not in have: extra += [k, v]
        e[-1:-1] = extra
    # unify: both layers share the same (superset) mapping; split by class importance
    L['poi-icon-minor']['layout']['icon-image'] = copy.deepcopy(L['poi-icon-major']['layout']['icon-image'])
    for e in (L['poi-icon-major']['layout']['icon-image'],):
        have = set(e[2:-1:2])
        for k, v in L['poi-icon-minor']['layout'].items(): pass
    major = L['poi-icon-major']; minor = L['poi-icon-minor']
    allmap = {}
    for src in (ic, ic2):
        for i in range(2, len(src) - 1, 2): allmap.setdefault(src[i], src[i + 1])
    flat = ['match', ['get', 'class']]
    for k, v in allmap.items(): flat += [k, v]
    flat.append('ic-dot')
    major['layout']['icon-image'] = flat; minor['layout']['icon-image'] = copy.deepcopy(flat)
    major['filter'] = ['in', ['get', 'class'], ['literal', MAJOR]]
    major['minzoom'] = 14; minor['minzoom'] = 14
    minor['filter'] = ['all', ['!', ['in', ['get', 'class'], ['literal', MAJOR + NOISE]]]]
    major['layout']['icon-size'] = {'stops': [[14, 0.62], [16, 0.78], [18, 0.95]]}
    minor['layout']['icon-size'] = {'stops': [[14, 0.55], [16, 0.7], [18, 0.9]]}
    for l in (major, minor):
        l['layout']['icon-allow-overlap'] = False; l['layout']['symbol-sort-key'] = ['get', 'rank']
    lab = L['poi-label']; lab['minzoom'] = 14
    lab['filter'] = ['all', ['has', 'name'], ['!', ['in', ['get', 'class'], ['literal', NOISE]]]]
    lab['layout']['text-size'] = {'stops': [[14, 10.5], [18, 12.5]]}
    lab['layout']['symbol-sort-key'] = ['get', 'rank']
    fix_match(lab['paint']['text-color'])
    L['building-name']['minzoom'] = 15.5
    # place labels: suburb / neighbourhood
    ids = [l['id'] for l in s['layers']]
    for cid, cls, mz, size in (('place-suburb', 'suburb', 11.5, 12), ('place-neighbourhood', 'neighbourhood', 13.5, 11)):
        if cid in ids: s['layers'].pop(ids.index(cid)); ids.remove(cid)
        base = copy.deepcopy(L['place-village']); base['id'] = cid
        base['filter'] = ['==', ['get', 'class'], cls]; base['minzoom'] = mz
        base['layout']['text-size'] = size; base['layout']['text-transform'] = 'uppercase' if False else 'none'
        base['layout']['text-optional'] = True; base['layout']['text-padding'] = 2
        s['layers'].insert(ids.index('place-village'), base); ids.insert(ids.index('place-village'), cid)

    # ---- مکان‌ها در زوم < ۱۴: کاشی‌ها لایهٔ poi را فقط در زوم ۱۴ دارند؛ نسخهٔ GeoJSON همان داده
    # (tools/extract_poi_lowzoom.py → assets/poi/poi_low.geojson) در زوم‌های پایین‌تر نمایش داده می‌شود.
    s['sources']['poi-low'] = {'type': 'geojson', 'data': '__POI_LOW__', 'maxzoom': 14}
    for l in [x for x in s['layers'] if x['id'].startswith('poi-low-')]: s['layers'].remove(l)
    T1 = ['hospital', 'college', 'university', 'attraction']
    T2 = ['place_of_worship', 'school', 'bus', 'fuel', 'lodging', 'police', 'bank', 'pharmacy', 'museum', 'town_hall', 'supermarket',
          'mall', 'marketplace', 'castle', 'diplomatic', 'library', 'dentist']
    tiers = [('1', 9.0, ['in', ['get', 'class'], ['literal', T1]]),
             ('2', 11.5, ['in', ['get', 'class'], ['literal', T2]]),
             ('3', 12.5, ['!', ['in', ['get', 'class'], ['literal', T1 + T2]]])]
    new = []
    for tid, mz, flt in tiers:
        a = copy.deepcopy(major); a['id'] = 'poi-low-icon-' + tid; a['source'] = 'poi-low'; a.pop('source-layer', None)
        a['filter'] = flt; a['minzoom'] = mz; a['maxzoom'] = 14
        a['layout']['icon-size'] = {'stops': [[9, 0.45], [12, 0.55], [14, 0.62]]}
        b = copy.deepcopy(lab); b['id'] = 'poi-low-label-' + tid; b['source'] = 'poi-low'; b.pop('source-layer', None)
        b['filter'] = ['all', ['has', 'name'], flt]; b['minzoom'] = mz + 1.2; b['maxzoom'] = 14
        b['layout']['text-size'] = {'stops': [[10, 10], [14, 10.5]]}
        new += [a, b]
    at = [x['id'] for x in s['layers']].index('poi-icon-major')
    s['layers'][at:at] = new
    json.dump(s, open(path, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    print(path, len(s['layers']), 'layers')
for t in ('light', 'dark'): patch(f'app/src/main/assets/style/rahyab_{t}.json')
