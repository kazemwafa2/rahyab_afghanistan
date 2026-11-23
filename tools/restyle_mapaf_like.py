#!/usr/bin/env python3
"""Recolor rahyab_light.json toward a warm-beige, filled-building look (own palette; no map.af assets copied).
Idempotent. Usage: python3 -I tools/restyle_mapaf_like.py"""
import json, re
P = 'app/src/main/assets/style/rahyab_light.json'
s = json.load(open(P, encoding='utf-8'))
BG, WATER = '#EFE8DA', '#A9D3E3'
ROAD = {  # class: (fill, casing)
 'minor': ('#FFFFFF', '#D8CCB6'), 'service': ('#FFFFFF', '#D8CCB6'), 'path': ('#E2D6C0', None),
 'tertiary': ('#FFFFFF', '#CDBFA6'), 'secondary': ('#FFF1C2', '#D9B866'),
 'primary': ('#FBDC8E', '#D4A94A'), 'trunk': ('#F7C873', '#CC9A3A'), 'motorway': ('#F4B860', '#C08A2E')}
def setp(l, k, v): l.setdefault('paint', {})[k] = v
for l in s['layers']:
    i = l['id']
    if i == 'background': setp(l, 'background-color', BG)
    elif i == 'water': setp(l, 'fill-color', WATER)
    elif i == 'waterway': setp(l, 'line-color', WATER)
    elif i == 'landcover-wood': setp(l, 'fill-color', '#CFDDB0')
    elif i == 'landcover-grass': setp(l, 'fill-color', '#DCE6BF')
    elif i == 'park': setp(l, 'fill-color', '#CFE3B4'); setp(l, 'fill-opacity', 0.85)
    elif i == 'landuse-residential': setp(l, 'fill-color', '#EBE2D2'); setp(l, 'fill-opacity', 1)
    elif i == 'building':
        l['minzoom'] = 13
        setp(l, 'fill-color', ['interpolate',['linear'],['zoom'],13,'#E3D8C6',16,'#DDD0BB'])
        setp(l, 'fill-outline-color', '#C9BBA3')
    elif i == 'building-3d':
        l['minzoom'] = 15.5
        setp(l, 'fill-extrusion-color', ['interpolate',['linear'],['get','render_height'],0,'#E0D4C0',30,'#E8DECD',80,'#EFE7D9'])
        setp(l, 'fill-extrusion-opacity', 0.92)
    else:
        m = re.match(r'(road|bridge|tunnel)-(path|minor|tertiary|secondary|primary|trunk|motorway)(-casing)?$', i)
        if m and m.group(2) in ROAD:
            fill, cas = ROAD[m.group(2)]
            if m.group(3): setp(l, 'line-color', cas or '#D8CCB6')
            else: setp(l, 'line-color', fill)
    if l['type'] == 'symbol' and l.get('paint', {}).get('text-halo-color'):
        l['paint']['text-halo-color'] = '#FBF7EE'
        if l['paint'].get('text-color') == '#263A40': l['paint']['text-color'] = '#4A4034'
json.dump(s, open(P, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print('ok', len(s['layers']), 'layers')
