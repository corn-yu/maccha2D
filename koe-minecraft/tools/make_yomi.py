# よみ辞書 resources/koemine/yomi.txt.gz を作る（pip download jamdict-data で取った jamdict.db から。EDRDG ライセンス CC BY-SA 4.0）
import sqlite3, gzip, re, sys
D='/tmp/dict/jamdict_data-1.5/jamdict_data/jamdict.db'
c=sqlite3.connect(D)
def hira(s): return ''.join(chr(ord(ch)-0x60) if 'ァ'<=ch<='ヶ' else ch for ch in s)
iskanji=lambda ch: '一'<=ch<='鿿' or ch in '々〆'
# kanji readings
kan={}
for lit, rtype, val in c.execute("select ch.literal, r.r_type, r.value from character ch join rm_group g on g.cid=ch.ID join reading r on r.gid=g.ID where r.r_type in ('ja_on','ja_kun') order by r.rowid"):
    v=hira(val).replace('-','')
    if not v: continue
    l=kan.setdefault(lit,[]); x=('k' if rtype=='ja_kun' else 'o')+v
    if x not in l: l.append(x)
# words
words={}
q="select k.text, kn.text from Kanji k join Kana kn on kn.idseq=k.idseq order by k.idseq, kn.ID"
for kt, rd in c.execute(q):
    if not (2<=len(kt)<=8) or not any(iskanji(ch) for ch in kt): continue
    r=hira(rd)
    if not re.fullmatch(r'[ぁ-ゖー]+', r): continue
    l=words.setdefault(kt,[])
    if r not in l and len(l)<4: l.append(r)
with gzip.open('yomi.txt.gz','wt',encoding='utf-8') as f:
    f.write('# よみ辞書（JMdict・KANJIDIC2 から作成、EDRDG ライセンス CC BY-SA 4.0）\n')
    for k,v in kan.items(): f.write('@'+k+'\t'+','.join(v)+'\n')
    for k,v in words.items(): f.write(k+'\t'+','.join(v)+'\n')
print(len(kan), len(words))
for k in '前上下置壊右左跳':
    print(k, kan.get(k))
