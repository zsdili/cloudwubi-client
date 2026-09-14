#!/usr/bin/env python3
"""v0.5.22 三层词库生成器（86∩jidian高频 → jidian Top450 → 日常层+保障词）
源：git show 9b014f2 的 wubi_phrase.txt（86 原库）+ rime-wubi86-jidian（Apache-2.0）
输出：android/app/src/main/assets/wubi_phrase.txt（编码序，同码保层序）
"""
import os, sys, zipfile, io

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W86 = "/tmp/wubi86_orig.txt"          # 86 原库（git show 9b014f2 提取）
JD  = "/tmp/jidian/wubi86_jidian.dict.yaml"   # jidian 词库
DAILY = "/tmp/daily_words_v2.txt"     # 日常词清单
OUT = os.path.join(ROOT, "android/app/src/main/assets/wubi_phrase.txt")

def parse_code_word(line):
    i = 0
    while i < len(line) and 'a' <= line[i] <= 'y': i += 1
    if 0 < i < len(line): return line[:i], line[i:]
    return None

def main():
    w86 = {}
    for line in open(W86, encoding='utf-8'):
        line = line.strip()
        if line:
            pc = parse_code_word(line)
            if pc: w86.setdefault(pc[0], []).append(pc[1])
    jd_w = {}
    for line in open(JD, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) >= 3:
            try: w = int(p[2])
            except: continue
            if len(p[0]) >= 2: jd_w.setdefault(p[1], []).append((p[0], w))
    # 层1：86∩jidian（任意权重，同码同词）
    layer86 = []; in_layers = set()
    for c, words in w86.items():
        if c not in jd_w: continue
        jw = {w for w, _ in jd_w[c]}
        for w in words:
            if w in jw:
                layer86.append(c + w); in_layers.add(c + w)
    # 层2：jidian Top450（去重，按权重降序）
    jd_order = []
    for c, l in jd_w.items():
        for w, wt in l:
            if c + w not in in_layers:
                jd_order.append((c, w, wt))
    jd_order.sort(key=lambda x: -x[2])
    layer_jd = []
    for c, w, wt in jd_order[:450]:
        cw = c + w
        in_layers.add(cw); layer_jd.append(cw)
    # 层3：日常清单 + 保障词
    daily_list = [l.strip() for l in open(DAILY, encoding='utf-8') if l.strip()]
    jd_code = {}
    for c, l in jd_w.items():
        for w, _ in l: jd_code.setdefault(w, c)
    layer_daily = []
    for w in daily_list:
        if w in jd_code:
            cw = jd_code[w] + w
            if cw not in in_layers:
                in_layers.add(cw); layer_daily.append(cw)
    for c, w in [('uabn','辛苦了'), ('dgqe','三角'), ('dgqe','感触'), ('imlf','没办法'), ('ilif','没办法')]:
        cw = c + w
        if cw not in in_layers:
            in_layers.add(cw); layer_daily.append(cw)
    all_lines = layer86 + layer_jd + layer_daily
    def code_key(line):
        i = 0
        while i < len(line) and 'a' <= line[i] <= 'y': i += 1
        return line[:i]
    out = sorted(all_lines, key=code_key)
    text = '\n'.join(out) + '\n'
    open(OUT, 'w', encoding='utf-8').write(text)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr('wubi_phrase.txt', text.encode())
    print('生成: %d行 %dKB 压缩%dKB' % (len(out), len(text.encode())//1024, len(buf.getvalue())//1024))
    for cw in ['uefj前进','ilif没办法','imlf没办法','dgqe三角','dgqe感触','uabn辛苦了','wqvb你好','ytyt谢谢','aawt工作','trwu我们','vbrq好的','ddgj大理']:
        print('  ', cw, 'OK' if cw in text else 'MISS')

if __name__ == '__main__':
    main()
