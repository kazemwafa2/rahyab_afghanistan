#!/usr/bin/env python3
"""POIs from zoom-14 tiles -> GeoJSON for low-zoom display. usage: extract_poi_lowzoom.py afghanistan_full.pmtiles out.geojson"""
import gzip, struct, sys, random, json, collections, math

PATH = sys.argv[1]  # afghanistan_full.pmtiles
f = open(PATH, 'rb')
h = f.read(127)
root_off, root_len, meta_off, meta_len, leaf_off, leaf_len, data_off, data_len = struct.unpack('<QQQQQQQQ', h[8:72])


def varint(b, i):
    r = 0; s = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7f) << s
        if not c & 0x80:
            return r, i
        s += 7


def read_dir(buf):
    buf = gzip.decompress(buf)
    n, i = varint(buf, 0)
    ids = []; last = 0
    for _ in range(n):
        d, i = varint(buf, i); last += d; ids.append(last)
    runs = []
    for _ in range(n):
        r, i = varint(buf, i); runs.append(r)
    lens = []
    for _ in range(n):
        r, i = varint(buf, i); lens.append(r)
    offs = []
    prev = None
    for k in range(n):
        o, i = varint(buf, i)
        if o == 0 and k > 0:
            o = offs[k - 1] + lens[k - 1]
        else:
            o = o - 1
        offs.append(o)
    return list(zip(ids, offs, lens, runs))


f.seek(root_off); root = read_dir(f.read(root_len))
entries = []  # (tile_id, offset, length, run)
for tid, off, ln, run in root:
    if run == 0:
        f.seek(leaf_off + off); sub = read_dir(f.read(ln))
        for e in sub:
            assert e[3] > 0
            entries.append((e[0], e[1], e[2], e[3]))
    else:
        entries.append((tid, off, ln, run))
entries.sort()
import bisect
starts = [e[0] for e in entries]


def id_to_zxy(tid):
    z = 0
    acc = 0
    while True:
        n = (1 << z) * (1 << z)
        if tid < acc + n:
            break
        acc += n; z += 1
    t = tid - acc
    x = y = 0
    s = 1
    n = 1 << z
    # hilbert d2xy
    tt = t
    s = 1
    while s < n:
        rx = 1 & (tt // 2)
        ry = 1 & (tt ^ rx)
        if ry == 0:
            if rx == 1:
                x = s - 1 - x; y = s - 1 - y
            x, y = y, x
        x += s * rx; y += s * ry
        tt //= 4
        s *= 2
    return z, x, y


def zxy_to_id(z, x, y):
    acc = sum((1 << k) * (1 << k) for k in range(z))
    n = 1 << z
    d = 0
    s = n // 2
    while s > 0:
        rx = 1 if (x & s) > 0 else 0
        ry = 1 if (y & s) > 0 else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x = s - 1 - x; y = s - 1 - y
            x, y = y, x
        s //= 2
    return acc + d


def get_tile(z, x, y):
    tid = zxy_to_id(z, x, y)
    k = bisect.bisect_right(starts, tid) - 1
    if k < 0: return None
    s, off, ln, run = entries[k]
    if tid >= s + run: return None
    f.seek(data_off + off); return gzip.decompress(f.read(ln))


def fields(b):
    i = 0; out = []
    while i < len(b):
        k, i = varint(b, i); fn, wt = k >> 3, k & 7
        if wt == 0:
            v, i = varint(b, i)
        elif wt == 2:
            l, i = varint(b, i); v = b[i:i + l]; i += l
        elif wt == 1:
            v = b[i:i + 8]; i += 8
        elif wt == 5:
            v = b[i:i + 4]; i += 4
        else:
            raise ValueError(wt)
        out.append((fn, v))
    return out


def decode(buf):
    layers = {}
    for fn, v in fields(buf):
        if fn != 3: continue
        name = None; keys = []; vals = []; feats = []
        for fn2, v2 in fields(v):
            if fn2 == 1: name = v2.decode()
            elif fn2 == 3: keys.append(v2.decode())
            elif fn2 == 4:
                for fn3, v3 in fields(v2):
                    if fn3 == 1: vals.append(v3.decode())
                    else: vals.append(v3)
            elif fn2 == 2: feats.append(v2)
        fl = []
        for fb in feats:
            tags = []; typ = None
            for fn3, v3 in fields(fb):
                if fn3 == 2:
                    j = 0
                    while j < len(v3):
                        a, j = varint(v3, j); tags.append(a)
                elif fn3 == 3: typ = v3
            d = {}
            for j in range(0, len(tags), 2):
                d[keys[tags[j]]] = vals[tags[j + 1]]
            fl.append((typ, d))
        layers[name] = fl
    return layers

def points(buf):
    out=[]
    for fn,v in fields(buf):
        if fn!=3: continue
        name=None; keys=[]; vals=[]; feats=[]; extent=4096
        for fn2,v2 in fields(v):
            if fn2==1: name=v2.decode()
            elif fn2==3: keys.append(v2.decode())
            elif fn2==4:
                val=None
                for fn3,v3 in fields(v2):
                    if fn3==1: val=v3.decode()
                    elif fn3 in (3,4,5,6,7): val=v3
                vals.append(val)
            elif fn2==2: feats.append(v2)
            elif fn2==5: extent=v2
        if name!='poi': continue
        for fb in feats:
            tags=[]; geom=[]
            for fn3,v3 in fields(fb):
                if fn3==2:
                    j=0
                    while j<len(v3):
                        a,j=varint(v3,j); tags.append(a)
                elif fn3==4:
                    j=0
                    while j<len(v3):
                        a,j=varint(v3,j); geom.append(a)
            d={keys[tags[j]]:vals[tags[j+1]] for j in range(0,len(tags),2)}
            # first MoveTo
            if len(geom)>=3 and (geom[0]&7)==1:
                zz=lambda n:(n>>1)^-(n&1)
                out.append((zz(geom[1]),zz(geom[2]),d,extent))
    return out

NOISE=set('gate lift_gate bollard cycle_barrier brownfield cemetery pitch playground waste_basket bicycle_parking telephone campsite theme_park running athletics billiards swimming multi music beer water_park garden park drinking_water toilets shelter'.split())
res=[]; seen={}
for tid,off,ln,run in entries:
    for t in range(tid,tid+run):
        z,x,y=id_to_zxy(t)
        if z!=14: continue
        f.seek(data_off+off); b=gzip.decompress(f.read(ln))
        for px,py,d,ext in points(b):
            c=d.get('class')
            if c in NOISE: continue
            lon=(x+px/ext)/2**14*360-180
            lat=math.degrees(math.atan(math.sinh(math.pi*(1-2*(y+py/ext)/2**14))))
            k=(round(lon,5),round(lat,5),c,d.get('name'))
            p={'class':c,'rank':d.get('rank',99)}
            for key in ('name','name:latin','name:ar','name:en'):
                if key in d: p[key]=d[key]
            seen[k]={'type':'Feature','geometry':{'type':'Point','coordinates':[round(lon,5),round(lat,5)]},'properties':p}
open(sys.argv[2],'w',encoding='utf-8').write(json.dumps({'type':'FeatureCollection','features':list(seen.values())},ensure_ascii=False,separators=(',',':')))
print(len(seen),'POIs',file=sys.stderr)
