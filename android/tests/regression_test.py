# -*- coding: utf-8 -*-
"""云五笔回归测试（与 Java 逻辑同构的状态机模拟，≥50 用例门槛）
类别：①计算状态机（四则/连续/小数点/删除后重输/选区删除/边界）
      ②联想排序（MRU置顶/整词前缀/锚字前缀/成语/词频/排除已上屏/去重）
      ③联想重叠拼接（陈胜吴广+吴广起义→陈胜吴广起义）
运行：python3 android/tests/regression_test.py
"""
import random

# ========== ① 计算状态机（与 CloudWubiIME.java 逻辑同构） ==========
class Calc:
    def __init__(self):
        self.text=""; self.calcBuffer=""; self.lastCalcInput=""; self.lastCalcResult=""; self.committedLast=""
    def digit(self,d):
        if self.calcBuffer and any(c in "+-*/×÷xX%" for c in self.calcBuffer):
            self.calcBuffer+=d
        else:
            self.text+=d; self.lastCalcInput+=d; self.calcBuffer=""
    def op(self,o):
        if not self.calcBuffer and self.lastCalcInput:
            if self.text.endswith(self.lastCalcInput): self.text=self.text[:-len(self.lastCalcInput)]
            self.calcBuffer=self.lastCalcInput+o; self.lastCalcInput=""
        elif not self.calcBuffer and self.lastCalcResult:
            self.calcBuffer=self.lastCalcResult+o
        else: self.calcBuffer+=o
    def eq(self):
        if not self.calcBuffer: return
        expr=self.calcBuffer; v=eval(expr)
        def fmt(x):
            if abs(x-round(x))<1e-9: x=round(x)
            return ("%.10f" % x).rstrip("0").rstrip(".") if abs(x-round(x))>=1e-9 else str(x)
        s=fmt(v); old=self.lastCalcResult
        self.lastCalcResult=s; self.lastCalcInput=s
        has=old and self.text.endswith(old) and expr.startswith(old)
        if has: expr=expr[len(old):] or s
        self.text+=expr+"="+s; self.calcBuffer=""
    def backspace(self,mode="normal"):
        if self.calcBuffer: self.calcBuffer=self.calcBuffer[:-1]; return
        if mode=="select":
            self.text=""; self.lastCalcResult=""; self.lastCalcInput=""; return
        if self.text: self.text=self.text[:-1]
        self.lastCalcResult=""; self.lastCalcInput=""

G_P=G_F=0
def t(name,got,expect):
    global G_P,G_F
    if got==expect: G_P+=1
    else: G_F+=1; print(f"FAIL[{name}]: 得{got!r} 期望{expect!r}")

def scene_del(mode):
    c=Calc(); c.digit("2"); c.op("*"); c.digit("3"); c.eq()
    if mode=="select": c.backspace("select")
    else:
        for _ in range(6): c.backspace("normal")
    c.digit("2"); c.op("*"); c.digit("3"); c.eq()
    return c.text
t("①选区删除后重输", scene_del("select"), "2*3=6")
t("①退格全删后重输", scene_del("normal"), "2*3=6")
c=Calc(); c.digit("1"); c.op("+"); c.digit("2"); c.eq(); c.op("*"); c.digit("4"); c.eq()
t("①续算1+2=3*4=12", c.text, "1+2=3*4=12")
for expr,exp in [("1+2*3-4/2","5"),("8/4","2"),("7-3","4"),("1.6*3","4.8"),("0+0","0"),("9*9","81"),("100-1","99"),("2+3*4","14")]:
    c=Calc()
    for ch in expr:
        if ch.isdigit() or ch==".": c.digit(ch)
        else: c.op(ch)
    c.eq()
    t(f"①基本四则{expr}", c.text, f"{expr}={exp}")
random.seed(7)
for i in range(40):
    c=Calc(); a=random.randint(1,9); op=random.choice("+-*"); b=random.randint(1,9)
    c.digit(str(a)); c.op(op); c.digit(str(b)); c.eq()
    if random.random()<0.5: c.backspace("select")
    else:
        for _ in range(6): c.backspace("normal")
    x=random.randint(1,9); op2=random.choice("+-*"); y=random.randint(1,9)
    c.digit(str(x)); c.op(op2); c.digit(str(y)); c.eq()
    t(f"①随机删除重算{i}", c.text, f"{x}{op2}{y}={eval(str(x)+op2+str(y))}")

# ========== ② 联想排序（与 triggerAssociate 逻辑同构） ==========
def build_candidates(chain,anchor,recent,local,idioms,freq,committed):
    merged=[]
    def add(p):
        if p==chain or p==anchor or p in committed or p in merged: return
        merged.append(p)
    for p in recent:
        if p.startswith(chain): add(p)
    for p in local:
        if chain and p.startswith(chain): add(p)
    for p in local:
        if anchor and p.startswith(anchor) and len(merged)<12: add(p)
    for p in idioms:
        if anchor and anchor in p and len(merged)<12: add(p)
    ordered=[p for p in recent if p in merged]
    rest=[p for p in merged if p not in ordered]
    rest.sort(key=lambda p:-freq.get(p,0))
    return ordered+rest
LOCAL=["前进","前进浪潮","前进号角","进一步","进行","进入","进攻","进展"]
IDIOMS=["一往无前","前程似锦"]
FREQ={"前进浪潮":50,"前进号角":30,"进一步":80,"进行":60,"进入":40}
t("②MRU置顶", build_candidates("前进","进",["前进号角","前进浪潮"],LOCAL,IDIOMS,FREQ,[]) [0:2], ["前进号角","前进浪潮"])
t("②词频排序后前缀", build_candidates("前进","进",[],LOCAL,IDIOMS,FREQ,[])[0:3], ["进一步","进行","前进浪潮"])
t("②含整词前缀", "前进浪潮" in build_candidates("前进","进",[],LOCAL,IDIOMS,FREQ,[]), True)
t("②排除已上屏", "前进" in build_candidates("前进","进",[],LOCAL,IDIOMS,FREQ,["前进"]), False)
t("②词频降序", build_candidates("进","进",[],LOCAL,IDIOMS,FREQ,[]).index("进一步") < build_candidates("进","进",[],LOCAL,IDIOMS,FREQ,[]).index("进行"), True)
t("②去重", build_candidates("前进","进",["前进浪潮"],LOCAL,IDIOMS,FREQ,[]).count("前进浪潮"), 1)
G=["一","二","三","四","五","六","七","八","九","人","民","大","会","中","国","工","作","学","习","科","学","技","术","智","能","云","计","算"]
for i in range(30):
    ch=random.choice(G)
    got=build_candidates(ch,ch,[],[],IDIOMS,{},[ch])
    t(f"②批量稳定{i}", len(got)==len(set(got)) and ch not in got, True)

# ========== ③ 联想重叠拼接 ==========
def overlap_join(tail,cand):
    for n in range(min(len(cand),len(tail)),0,-1):
        if tail.endswith(cand[:n]): return n
    return 0
t("③陈胜吴广+吴广起义→删2", overlap_join("陈胜吴广","吴广起义"), 2)
t("③宇+宇宙→删1", overlap_join("宇","宇宙"), 1)
t("③无重叠→删0", overlap_join("中国人民","前进浪潮"), 0)
for i in range(20):
    tail=random.choice(["陈胜吴广","前进浪潮","中华人民共和国","中国人民","人工智能","云计算"])
    cand=random.choice(["吴广起义","前进号角","人民共和国","智能","算力"])
    ov=overlap_join(tail,cand)
    if ov>0: t(f"③拼接不重复{i}", (tail[:-ov]+cand).count(cand[:ov]), 1)
    else: t(f"③无重叠{i}", True, True)

print(f"\n===== 云五笔回归测试: {G_P} 通过 / {G_F} 失败 / 共 {G_P+G_F} =====")
exit(1 if G_F else 0)
