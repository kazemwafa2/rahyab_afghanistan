#!/usr/bin/env python3
"""آمار محتوای یک فایل PMTiles/MVT بدون وابستگی خارجی (فقط کتابخانهٔ استاندارد).

  python3 tools/pmtiles_stats.py file.pmtiles [--json out.json]

خروجی: تعداد کاشی هر زوم؛ برای ۱۲۰ کاشی z14 مرکز کابل و ۱۲۰۰ کاشی z14 تصادفی کشور،
تعداد عوارض هر لایه و تعداد عوارضی که کلیدهای name / name:fa / name:ps / name:en / name:ar /
name:latin دارند + render_height ساختمان + ele قله. برای مقایسهٔ «کاشی مرجع map.af» با «کاشی ساخت
خود پروژه» (workflow: build-osm-tiles.yml) به کار می‌رود.
"""
import gzip, struct, sys, random, json, collections, math

PATH = sys.argv[1]
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


# ----------------------------- گزارش -----------------------------
import math, collections, json as _json
random.seed(11)


def ll2t(lat, lon, z):
    n = 1 << z
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat)))
                                          / math.pi) / 2 * n)


def summarize(tiles):
    cnt = collections.Counter(); keyc = collections.defaultdict(collections.Counter)
    for t in tiles:
        for ln, fl in decode(t).items():
            cnt[ln] += len(fl)
            for _typ, d in fl:
                for k in d: keyc[ln][k] += 1
    out = {}
    for ln in sorted(cnt):
        kc = keyc[ln]
        out[ln] = {'features': cnt[ln],
                   **{k: kc[k] for k in ('name', 'name:fa', 'name:ps', 'name:en', 'name:ar', 'name:latin',
                                         'ele', 'housenumber', 'render_height') if kc.get(k)}}
    return out


def rd(e):
    f.seek(data_off + e[1]); return gzip.decompress(f.read(e[2]))


report = {}
tiles = [(tid + k, off, ln) for tid, off, ln, run in entries for k in range(run)]
report['tiles_total'] = len(tiles)
x0, y1 = ll2t(34.45, 69.05, 14); x1, y0 = ll2t(34.60, 69.30, 14)
kabul = [t for t in (get_tile(14, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)) if t]
report['kabul_z14'] = {'tiles': len(kabul), 'layers': summarize(kabul)}
z14 = [t for t in tiles if id_to_zxy(t[0])[0] == 14]
report['random_z14'] = {'tiles': 1200, 'layers': summarize([rd(t) for t in random.sample(z14, min(1200, len(z14)))])}
for sec in ('kabul_z14', 'random_z14'):
    print('==', sec, report[sec]['tiles'], 'tiles')
    for ln, v in report[sec]['layers'].items():
        print(f"  {ln:20s}", v)
if '--json' in sys.argv:
    _json.dump(report, open(sys.argv[sys.argv.index('--json') + 1], 'w'), ensure_ascii=False, indent=1)
