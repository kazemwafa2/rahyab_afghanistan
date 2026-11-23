#!/usr/bin/env python3
"""آزمون یکپارچگی گراف RAFG1 (موتررو) — فقط numpy/scipy.

  python3 tools/graph_check.py afghanistan_full.rafgraph

۱) بزرگ‌ترین مؤلفهٔ قویاً همبند و مؤلفه‌های بزرگ بیرون از آن؛
۲) مسیر موتررو بین مرکز هر ۳۴ ولایت (همه جفت‌ها، دو جهت)، با چسباندن مرکز به نزدیک‌ترین گرهٔ مؤلفهٔ اصلی؛
۳) نسبت طول مسیر به خط مستقیم و جفت‌های نامتقارن.
نکته: اگر مرکز به «نزدیک‌ترین گره» بچسبد (نه نزدیک‌ترین گرهٔ مؤلفهٔ اصلی)، ۳۳٪ جفت‌ها در یک جهت
بی‌مسیر می‌شوند؛ دلیلش گره‌های بن‌بست/یک‌طرفهٔ محلی است (حدود ۴٪ گره‌های موتررو).
"""
import numpy as np, struct, json, math
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import connected_components, dijkstra
import sys
b=open(sys.argv[1],'rb').read()
N,E,NB=struct.unpack('<iii',b[10:22]); p=38
lat=np.frombuffer(b,'<i4',N,p)/1e7; p+=4*N
lon=np.frombuffer(b,'<i4',N,p)/1e7; p+=4*N
csr=np.frombuffer(b,'<u4',N+1,p).astype(np.int64); p+=4*(N+1)
to=np.frombuffer(b,'<u4',E,p).astype(np.int64); p+=4*E
lenm=np.frombuffer(b,'<u2',E,p).astype(np.float64); p+=2*E
spd=np.frombuffer(b,'<u1',E,p).astype(np.float64); p+=E
flg=np.frombuffer(b,'u1',E,p)
src=np.repeat(np.arange(N),np.diff(csr)); car=(flg&1)>0
sc,tc=src[car],to[car]; lc=lenm[car]; tm=lc/(spd[car]/3.6)
Gd=csr_matrix((lc,(sc,tc)),shape=(N,N)); Gt=csr_matrix((tm,(sc,tc)),shape=(N,N))
nc,lab=connected_components(csr_matrix((np.ones(len(sc)),(sc,tc)),shape=(N,N)),directed=True,connection='strong')
print('car nodes with edges',int(((np.bincount(sc,minlength=N)+np.bincount(tc,minlength=N))>0).sum()),'; strong comps',nc)
deg=np.zeros(N,bool); deg[sc]=True; deg[tc]=True
sizes=np.bincount(lab,minlength=lab.max()+1); main=sizes.argmax()
# Large components outside main strong comp
print('Strong comps >=200 nodes outside main:')
big=[c for c in np.argsort(-sizes) if c!=main and sizes[c]>=200][:12]
for c in big:
    m=lab==c; print(f'  size {sizes[c]:6d} centroid lat {lat[m].mean():.2f} lon {lon[m].mean():.2f}')
caps={'Kabul':(34.53,69.17),'Kandahar':(31.62,65.71),'Herat':(34.34,62.20),'Balkh':(36.71,67.11),'Nangarhar':(34.43,70.45),'Kunduz':(36.73,68.87),'Ghazni':(33.55,68.42),'Khost':(33.34,69.92),'Helmand':(31.59,64.37),'Nimruz':(30.96,61.86),'Farah':(32.37,62.11),'Badghis':(34.99,63.13),'Ghor':(34.52,65.25),'Bamyan':(34.82,67.82),'Parwan':(35.01,69.17),'Kapisa':(35.02,69.34),'Wardak':(34.40,68.87),'Logar':(33.99,69.02),'Paktia':(33.60,69.22),'Paktika':(33.14,68.72),'Kunar':(34.87,71.15),'Laghman':(34.67,70.20),'Nuristan':(35.42,70.92),'Badakhshan':(37.12,70.58),'Takhar':(36.73,69.53),'Baghlan':(35.95,68.71),'Samangan':(36.26,68.02),'SarePol':(36.22,65.93),'Faryab':(35.92,64.78),'Jowzjan':(36.67,65.75),'Uruzgan':(32.63,65.87),'Zabul':(32.11,66.90),'Daykundi':(33.72,66.13),'Panjshir':(35.31,69.52)}
carnodes=np.where(deg&(lab==main))[0]
def snap(la,lo):
    d=(lat[carnodes]-la)**2+((lon[carnodes]-lo)*math.cos(math.radians(la)))**2
    k=carnodes[d.argmin()]; return k,math.sqrt(d.min())*111.0
names=list(caps); idx=[]; snapkm=[]
for n in names:
    k,km=snap(*caps[n]); idx.append(k); snapkm.append(km)
inmain=[bool(lab[k]==main) for k in idx]
print('snap to MAIN-SCC node, distance km:',[(n,round(s,2)) for n,s in zip(names,snapkm) if s>0.3])
print('capital snapped node NOT in main strong comp:',[n for n,m in zip(names,inmain) if not m])
Dd=dijkstra(Gd,indices=idx); Dt=dijkstra(Gt,indices=idx)
def hav(a,b):
    la1,lo1=map(math.radians,a); la2,lo2=map(math.radians,b)
    h=math.sin((la2-la1)/2)**2+math.cos(la1)*math.cos(la2)*math.sin((lo2-lo1)/2)**2
    return 2*6371*math.asin(math.sqrt(h))
unreach=[];ratios=[];asym=[]
for i,a in enumerate(names):
    for j,c in enumerate(names):
        if i>=j: continue
        d1=Dd[i,idx[j]]/1000; d2=Dd[j,idx[i]]/1000
        if not np.isfinite(d1) or not np.isfinite(d2): unreach.append((a,c,np.isfinite(d1),np.isfinite(d2))); continue
        s=hav(caps[a],caps[c]); ratios.append((d1/s,a,c,round(d1),round(s)))
        if abs(d1-d2)/max(d1,d2)>0.15: asym.append((a,c,round(d1),round(d2)))
print('pairs',len(names)*(len(names)-1)//2,'unreachable pairs',len(unreach)); print(unreach[:20])
r=np.array([x[0] for x in ratios]); print('route/straight ratio: median %.2f p95 %.2f max %.2f'%(np.median(r),np.percentile(r,95),r.max()))
print('worst ratios',sorted(ratios,reverse=True)[:8])
print('asymmetric (>15% fwd vs back):',len(asym),asym[:10])
# key trunk journeys
def trip(a,c):
    i,j=names.index(a),names.index(c); print(f'{a}->{c}: {Dd[i,idx[j]]/1000:.0f} km, {Dt[i,idx[j]]/3600:.1f} h (back {Dd[j,idx[i]]/1000:.0f} km, {Dt[j,idx[i]]/3600:.1f} h)')
for a,c in (('Kabul','Kandahar'),('Kabul','Herat'),('Kabul','Balkh'),('Kabul','Kunduz'),('Kabul','Nangarhar'),('Herat','Kandahar'),('Kandahar','Balkh'),('Kabul','Badakhshan'),('Kabul','Nimruz')): trip(a,c)
