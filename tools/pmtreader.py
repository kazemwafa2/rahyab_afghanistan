"""Small PMTiles v3 + MVT point reader (stdlib only)."""
import gzip, struct, math, bisect

def _varint(b, i):
    r = 0; s = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7f) << s
        if not c & 0x80: return r, i
        s += 7

def _fields(b):
    i = 0; out = []
    while i < len(b):
        k, i = _varint(b, i); fn, wt = k >> 3, k & 7
        if wt == 0: v, i = _varint(b, i)
        elif wt == 2:
            l, i = _varint(b, i); v = b[i:i + l]; i += l
        elif wt == 1: v = b[i:i + 8]; i += 8
        elif wt == 5: v = b[i:i + 4]; i += 4
        else: raise ValueError(wt)
        out.append((fn, v))
    return out

def _read_dir(buf):
    buf = gzip.decompress(buf)
    n, i = _varint(buf, 0); ids = []; last = 0
    for _ in range(n):
        d, i = _varint(buf, i); last += d; ids.append(last)
    runs = []; lens = []
    for _ in range(n): r, i = _varint(buf, i); runs.append(r)
    for _ in range(n): r, i = _varint(buf, i); lens.append(r)
    offs = []
    for k in range(n):
        o, i = _varint(buf, i)
        offs.append(offs[k - 1] + lens[k - 1] if (o == 0 and k > 0) else o - 1)
    return list(zip(ids, offs, lens, runs))

def _id_to_zxy(tid):
    z = 0; acc = 0
    while True:
        n = (1 << z) * (1 << z)
        if tid < acc + n: break
        acc += n; z += 1
    t = tid - acc; n = 1 << z; x = y = 0; s = 1
    while s < n:
        rx = 1 & (t // 2); ry = 1 & (t ^ rx)
        if ry == 0:
            if rx == 1: x = s - 1 - x; y = s - 1 - y
            x, y = y, x
        x += s * rx; y += s * ry; t //= 4; s *= 2
    return z, x, y

class PMTiles:
    def __init__(self, path):
        self.f = open(path, 'rb'); h = self.f.read(127)
        if h[:7] != b'PMTiles' or h[7] != 3: raise ValueError('not PMTiles v3')
        (self.root_off, self.root_len, _mo, _ml, self.leaf_off, _ll, self.data_off, _dl) = struct.unpack('<QQQQQQQQ', h[8:72])
        self.minz, self.maxz = h[100], h[101]; self.tile_comp = h[98 + 0] if False else h[98]
        self.f.seek(self.root_off); root = _read_dir(self.f.read(self.root_len)); ents = []
        for tid, off, ln, run in root:
            if run == 0:
                self.f.seek(self.leaf_off + off); ents.extend(_read_dir(self.f.read(ln)))
            else: ents.append((tid, off, ln, run))
        ents.sort(); self.entries = ents
    def tiles(self):
        """yield (z, x, y, raw_mvt_bytes)"""
        for tid, off, ln, run in self.entries:
            self.f.seek(self.data_off + off); b = self.f.read(ln)
            try: b = gzip.decompress(b)
            except OSError: pass
            for t in range(tid, tid + run):
                z, x, y = _id_to_zxy(t); yield z, x, y, b

def points(buf, want_layer=None):
    """yield (layer, (px, py), props, extent) for point features of an MVT tile."""
    for fn, v in _fields(buf):
        if fn != 3: continue
        name = None; keys = []; vals = []; fe = []; extent = 4096
        for fn2, v2 in _fields(v):
            if fn2 == 1: name = v2.decode()
            elif fn2 == 3: keys.append(v2.decode())
            elif fn2 == 4:
                val = None
                for fn3, v3 in _fields(v2):
                    if fn3 == 1: val = v3.decode()
                    elif fn3 in (3, 4, 5, 6, 7): val = v3
                vals.append(val)
            elif fn2 == 2: fe.append(v2)
            elif fn2 == 5: extent = v2
        if want_layer and name != want_layer: continue
        for fb in fe:
            tags = []; geom = []; typ = None
            for fn3, v3 in _fields(fb):
                if fn3 == 2:
                    j = 0
                    while j < len(v3): a, j = _varint(v3, j); tags.append(a)
                elif fn3 == 3: typ = v3
                elif fn3 == 4:
                    j = 0
                    while j < len(v3): a, j = _varint(v3, j); geom.append(a)
            if typ != 1 or len(geom) < 3: continue
            zz = lambda n: (n >> 1) ^ -(n & 1)
            d = {keys[tags[j]]: vals[tags[j + 1]] for j in range(0, len(tags), 2)}
            yield name, (zz(geom[1]), zz(geom[2])), d, extent

def tile_point_to_lonlat(z, x, y, px, py, extent):
    n = 2 ** z
    return ((x + px / extent) / n * 360 - 180,
            math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + py / extent) / n)))))
