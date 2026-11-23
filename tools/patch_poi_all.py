#!/usr/bin/env python3
"""Replace the low-zoom POI layers by layers over the unified GeoJSON (assets/poi/poi_all.geojson, built by
tools/build_poi_all.py) for ALL zooms; the old vector `poi` layers are switched off (their POIs are inside the GeoJSON,
deduplicated). Idempotent. Run after tools/patch_poi_classes.py."""
import json, copy
ICON = {'hospital': 'ic-hospital', 'university': 'ic-university', 'airport': 'ic-airport', 'government': 'ic-gov', 'landmark': 'ic-museum',
        'bank': 'ic-bank', 'fuel': 'ic-fuel', 'clinic': 'ic-clinic', 'transit': 'ic-bus', 'park': 'ic-park', 'market': 'ic-cart',
        'mosque': 'ic-mosque', 'school': 'ic-school', 'hotel': 'ic-hotel', 'pharmacy': 'ic-pharmacy', 'shop': 'ic-shop',
        'restaurant': 'ic-food', 'cafe': 'ic-food', 'parking': 'ic-parking'}
TIERS = [  # id, cats, icon minzoom, label minzoom
    ('a', ['hospital', 'university', 'airport'], 8.5, 10.0),
    ('b', ['government', 'landmark', 'bank', 'fuel', 'clinic', 'transit', 'park', 'market'], 11.0, 12.5),
    ('c', ['mosque', 'school', 'hotel', 'pharmacy'], 12.5, 14.0),
    ('d', ['shop', 'restaurant', 'cafe', 'parking'], 13.5, 15.0),
    ('e', ['business', 'other', 'travel', 'courier'], 15.0, 15.5)]
REP = {'shop': 'shop', 'market': 'marketplace', 'hospital': 'hospital', 'clinic': 'hospital', 'pharmacy': 'pharmacy', 'school': 'school',
       'university': 'school', 'bank': 'bank', 'restaurant': 'restaurant', 'cafe': 'restaurant', 'hotel': 'restaurant', 'government': 'townhall',
       'landmark': 'townhall', 'mosque': 'place_of_worship'}
NAME = ['coalesce', ['get', 'fa'], ['get', 'en']]
def colour_expr(old):
    """category colour expression derived from the theme's existing class-based text colours"""
    byclass = {}; default = old[-1]
    for i in range(2, len(old) - 1, 2):
        lab = old[i]
        for c in (lab if isinstance(lab, list) else [lab]): byclass[c] = old[i + 1]
    e = ['match', ['get', 'c']]
    for cat, cls in REP.items():
        if cls in byclass: e += [cat, byclass[cls]]
    return e + [default]
def patch(path):
    s = json.load(open(path, encoding='utf-8')); L = s['layers']
    L[:] = [l for l in L if not l['id'].startswith(('poi-low-', 'poi-all-'))]
    s['sources'].pop('poi-low', None)
    s['sources']['poi-all'] = {'type': 'geojson', 'data': '__POI_ALL__', 'maxzoom': 16}
    byid = {l['id']: l for l in L}
    for i in ('poi-dot', 'poi-icon-major', 'poi-icon-minor', 'poi-label'): byid[i]['filter'] = ['==', 1, 0]   # off (POIs live in poi-all)
    lab = byid['poi-label']; major = byid['poi-icon-major']
    colour = colour_expr(lab['paint']['text-color']); sortkey = ['match', ['get', 's'], 'p', 0, 'o', 1, 2]
    imgs = ['match', ['get', 'c']]
    for c, ic in ICON.items(): imgs += [c, ic]
    imgs.append('ic-dot')
    new = []
    for tid, cats, zi, zl in TIERS:
        inn = ['in', ['get', 'c'], ['literal', cats]]
        a = copy.deepcopy(major); a.update({'id': 'poi-all-icon-' + tid, 'source': 'poi-all', 'minzoom': zi, 'filter': inn}); a.pop('source-layer', None)
        if tid == 'e': a['filter'] = ['all', inn, ['any', ['has', 'fa'], ['has', 'en']]]
        a['layout'].update({'icon-image': imgs, 'icon-size': {'stops': [[8.5, 0.45], [12, 0.58], [15, 0.72], [18, 0.92]]},
                            'icon-allow-overlap': False, 'symbol-sort-key': sortkey, 'icon-padding': 2})
        b = copy.deepcopy(lab); b.update({'id': 'poi-all-label-' + tid, 'source': 'poi-all', 'minzoom': zl,
                                          'filter': ['all', inn, ['any', ['has', 'fa'], ['has', 'en']]]}); b.pop('source-layer', None)
        b['layout'].update({'text-field': NAME, 'symbol-sort-key': sortkey, 'text-size': {'stops': [[10, 10], [15, 10.5], [18, 12.5]]}})
        b['paint']['text-color'] = colour
        new += [a, b]
    for cid, cat, mz, size in (('poi-all-label-neigh', 'neighborhood', 13.0, 11.5), ('poi-all-label-street', 'street', 15.5, 10.5)):
        b = copy.deepcopy(lab); b.update({'id': cid, 'source': 'poi-all', 'minzoom': mz, 'filter': ['==', ['get', 'c'], cat]}); b.pop('source-layer', None)
        b['layout'].update({'text-field': NAME, 'text-size': size, 'symbol-sort-key': sortkey, 'text-optional': False}); b['layout'].pop('text-offset', None)
        b['paint']['text-color'] = lab['paint']['text-color'][-1] if isinstance(lab['paint']['text-color'], list) else lab['paint']['text-color']
        new.append(b)
    at = [l['id'] for l in L].index('poi-icon-major'); L[at:at] = new
    json.dump(s, open(path, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    print(path, len(L), 'layers')
for t in ('light', 'dark'): patch(f'app/src/main/assets/style/rahyab_{t}.json')
