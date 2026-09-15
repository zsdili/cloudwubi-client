package com.cloudwubi.ime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WubiDbCore - 云五笔本地五笔86精简词库核心引擎（纯 Java，无 Android 依赖）
 * 线上唯一实现：WubiDb（Android 壳）只负责从 assets 读行并委托本类；
 * JVM 单元测试直接加载真实词库文件测本类（与线上同构，杜绝"我以为的逻辑"）。
 *
 * 数据来源：cloudwubi-rules（Rime 官方五笔86码表 LGPL-3.0 派生）
 * 设计：端侧兜底保证"可打字"，云端负责无限扩展（先科学后先进）
 */
public final class WubiDbCore {

    private static Map<String, List<String>> singleIndex;
    private static Map<String, List<String>> phraseIndex;
    /** 词组→编码 反向索引（MRU 置顶排序用） */
    private static Map<String, String> phraseCodeIndex;
    /** 单字→编码 反向索引（lastSelected 置顶需编码匹配，避免错位霸榜） */
    private static Map<String, String> singleCodeIndex;
    /** 高频搭配联想表键（associate 纯文本，前缀匹配 + 含字启发双用） */
    private static final String ASSOC_KEY = "zzzz";
    // ==================== v0.5.23 动态拼词引擎（钟总核心思路） ====================
    // 基础库（字根/一级/二级/三级简码单字全码）+ 86 版词组编码规则 → 动态生成词组
    // 86 规则：2字词=前字前2码+后字前2码；3字词=前2字各1码+末字前2码；4字词=前3字各1码+末字1码
    /** 前缀索引：编码前缀(1-3码) -> 字列表 */
    private static Map<String, List<String>> prefixIndex;
    /** 单字全码索引：字 -> 全码（拼词取码用；构建时 4 码/最长优先） */
    private static Map<String, String> fullCodeIndex;

    /** 86 版 25 键一级简码（1 码查询强制置顶） */
    private static final String[] SIMPLE1 = {
        "g一","f地","d在","s要","a工","h上","j是","k中","l国","m同",
        "t和","r的","e有","w人","q我","y主","u产","i不","o为","p这",
        "n民","b了","v发","c以","x经"
    };

    /** 保障词（特例置顶）：动态规则易错或用户必查的常用词，按编码精确置顶 */
    private static final String[] GUARANTEED = {
        "dgqe三角", "dgqe感触", "ilif没办法", "imlf没办法", "uabn辛苦了", "ytyt谢谢", "trwu我们", "vbrq好的",
        "ddgj大理", "uefj前进", "wqvb你好", "aawt工作", "qiuj乐意", "thtc怎么", "wftc什么", "ywtc为什么", "ypsu这样",
        "vfsu那样", "wygd今天", "jegd明天", "jtgd昨天", "gmdh现在", "cnhh马上", "ggfh一起", "ddpe大家",
        "jfwh时候", "fbyy地方", "eedc朋友", "peww家人", "bybb孩子", "tgit生活", "ipnu学习", "jney电脑",
        "rtsm手机", "tmwy微信", "gdrn天气", "uyad辛苦", "imde没有", "gide还有", "ldyl因为", "rnny所以",
        "wjjg但是", "vkjs如果", "kjqd虽然", "dmeg而且", "akft或者", "qdrg然后", "jbrg最后", "uttf首先",
        "mgjf同时", "djip非常", "trkl特别", "tgsv重要", "tuuj简单", "yywg方便", "nnqi快乐", "lkim加油",
        "pvwg安全", "wvyv健康", "ntna发展", "khlg中国", "wwna人民", "iutx没关系",
        "yguk请问", "thnn自己", "yfky认真听讲", "klwn中国人民", "qqit多少", "kvjf哪里", "gtut不知道", "iujg没问题", "gmmk一帆风顺",
                // v0.5.65 反馈⑤：高频日常三字词精选 24 个（86 规则算码；体积门禁 100KiB 内）
        "gfsv不需要",
        "gbgo不出来",
        "iwtc没什么",
        "gtut不知道",
        "iutx没关系",
        "iujg没问题",
        "tggh等一下",
        "ttsu怎么样",
        "ywtc为什么",
        "fwtc干什么",
        "cgfh对不起",
        "gprn不客气",
        "yywq谢谢你",
        "geyt不用谢",
        "snkc可以吗",
        "tgtf行不行",
        "vgvb好不好",
        "sgsv要不要",
        "cgce能不能",
        "wgwf会不会",
        "dkjf在哪里",
        "jwtc是什么",
        "ttlw怎么办",
        "duln有意思"
    };

    private WubiDbCore() { }

    /** 由 WubiDb（Android 壳）传入词库行列表，构建全部索引 */
    public static synchronized void init(List<String> singleLines, List<String> phraseLines) {
        if (singleIndex != null) return;
        singleIndex = new HashMap<>();
        phraseIndex = new HashMap<>();
        phraseCodeIndex = new HashMap<>();
        singleCodeIndex = new HashMap<>();
        if (singleLines != null) loadLines(singleLines, singleIndex);
        if (phraseLines != null) loadLines(phraseLines, phraseIndex);
        // 词组反向索引（同一词可能多码，保留首条）
        if (phraseIndex != null) {
            for (Map.Entry<String, List<String>> e : phraseIndex.entrySet()) {
                for (String w : e.getValue()) {
                    if (!phraseCodeIndex.containsKey(w)) phraseCodeIndex.put(w, e.getKey());
                }
            }
        }
        // 单字反向索引（全码/最长优先——拼词取前2码与 MRU 编码匹配需全码）
        if (singleIndex != null) {
            for (Map.Entry<String, List<String>> e : singleIndex.entrySet()) {
                String code = e.getKey();
                for (String w : e.getValue()) {
                    if (w.length() == 1) {
                        String old = singleCodeIndex.get(w);
                        if (old == null || code.length() >= old.length()) singleCodeIndex.put(w, code);
                    }
                }
            }
        }
        // 动态拼词：前缀索引 + 全码索引
        if (singleIndex != null && prefixIndex == null) {
            prefixIndex = new HashMap<>();
            fullCodeIndex = new HashMap<>();
            for (Map.Entry<String, List<String>> e : singleIndex.entrySet()) {
                String code = e.getKey();
                int clen = code.length();
                for (int p = 1; p <= clen && p <= 3; p++) {
                    String pre = code.substring(0, p);
                    List<String> list = prefixIndex.get(pre);
                    if (list == null) { list = new ArrayList<>(); prefixIndex.put(pre, list); }
                    for (String w : e.getValue()) {
                        if (!list.contains(w)) list.add(w);
                    }
                }
                for (String w : e.getValue()) {
                    String old = fullCodeIndex.get(w);
                    if (old == null || clen >= old.length()) fullCodeIndex.put(w, code);
                }
            }
        }
    }

    /** 解析"编码+候选"紧凑行（v0.4.9：前缀连续 a-y 字母为编码，其余为候选，多候选空格分隔） */
    static void loadLines(List<String> lines, Map<String, List<String>> map) {
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int i = 0;
            int len = line.length();
            while (i < len) {
                char c = line.charAt(i);
                if (c >= 'a' && c <= 'y') i++; else break;
            }
            if (i <= 0 || i >= len) continue;
            String code = line.substring(0, i);
            String rest = line.substring(i).trim();
            if (code.length() > 4 || rest.isEmpty()) continue;
            for (String w : rest.split("\\s+")) {
                if (w.isEmpty()) continue;
                List<String> list = map.get(code);
                if (list == null) { list = new ArrayList<>(); map.put(code, list); }
                list.add(w);
            }
        }
    }

    private static void ensureIndex() {
        if (singleIndex == null) {
            singleIndex = new HashMap<>();
            phraseIndex = new HashMap<>();
        }
    }

    /** 取某键的一级简码字（无则 null） */
    public static String simple1Char(char key) {
        for (String s : SIMPLE1) if (s.charAt(0) == key) return s.substring(1);
        return null;
    }

    /** 含指定字的积极成语/搭配（联想启发层） */
    public static List<String> queryIdioms(String ch) {
        List<String> out = new ArrayList<>();
        if (ch == null || ch.isEmpty()) return out;
        List<String> list = phraseIndex == null ? null : phraseIndex.get(ASSOC_KEY);
        if (list != null) {
            for (String w : list) {
                if (w.indexOf(ch) >= 0 && !w.equals(ch)) out.add(w);
            }
        }
        return out;
    }

    /** 3码预测：预测第 4 码高频词组 */
    public static List<String> queryPredict(String code3) {
        if (code3 == null || code3.length() != 3) return null;
        ensureIndex();
        List<String> result = new ArrayList<>();
        // v0.5.61：保障词并入（离线兜底——uab→辛苦了）
        for (String g : GUARANTEED) {
            if (g.length() > 4 && g.startsWith(code3)) {
                String w = g.substring(4);
                if (!result.contains(w)) result.add(w);
            }
        }
        if (phraseIndex != null) {
            for (Map.Entry<String, List<String>> e : phraseIndex.entrySet()) {
                String c = e.getKey();
                if (c.length() == 4 && c.startsWith(code3)) {
                    result.addAll(e.getValue());
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 字后联想（最多 12 条） */
    public static List<String> queryByChar(String ch) {
        if (ch == null || ch.length() != 1) return null;
        ensureIndex();
        List<String> result = new ArrayList<>();
        if (phraseIndex != null) {
            for (List<String> list : phraseIndex.values()) {
                if (list == null) continue;
                for (String w : list) {
                    if (w.indexOf(ch) >= 0 && !result.contains(w)) {
                        result.add(w);
                        if (result.size() >= 12) return result;
                    }
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 前缀联想（最多 10 条） */
    public static List<String> queryByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.length() > 4) return null;
        ensureIndex();
        List<String> result = new ArrayList<>();
        if (phraseIndex != null) {
            for (List<String> list : phraseIndex.values()) {
                if (list == null) continue;
                for (String w : list) {
                    if (w.startsWith(prefix) && !result.contains(w)) {
                        result.add(w);
                        if (result.size() >= 10) return result;
                    }
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 常用字频序（近似《现代汉语常用字表》高频字序） */
    private static final String COMMON_FREQ = "一是人了不在有大中国和为这上他个地年来我会以到时要出的生学说道民家子也成行下们于后就发自之对得主长可过天作分方用多你着部能市等业全里工公经本都而高政法面门动日进区事代那去心小同北定开产前其军还然起种所如现理机体表力好外与文当两实重新三么只山水关明从化平建又制南内西没此将员名手最东头者月间无安看见各城十相但已些正口通想度加第她合院物性战由位常点海意场武使次二向治因立数样身情入原问把路被并利石老教万知级量任江及应省资委务元美特期世湖回系比气汉总展电科金先声提品设或义王社很统处四首共马形己儿司太目基领队直计别女权话少流命至报米给打变果书清活几州华解议更称程今决张导术府才保交放管结师便走达族反再题色五京河接条规式县白它改风光运信受什组听布百济党指论强做取技黄神选记斯真却职号界件花类何眼兵传带空干农边据集联古广完质阳难增历史专官每住商即步认车台林必死游举线言皇土团收考求德叫近备研争非具李众连调感转笑革该持始英克士尔让拉思根格造较际亲单朝红型价校约器字段周亚深候则功属积快图火千准究往极育装许参半令吃观鱼精办像帝八复影告远群包整构料随划算象容示投势热值夫网望源息语股铁断派速怎需片爱律纪支早况病境证编越局推满且列觉服双未居除乐企引标确织初青志率项飞球节察龙响药站施均消客失轻存低甚般击曾防请离落显罗营足素视护副食创余照兴占巴虽洲村费易试星木黑左宝置跟央识维采六底宫房音环案批切斗富乡另倒若按查故突责严桥模仅胜杀围席态破承招杨负层须父供续状域似依银范修找九致密终血旅钱赛独细效玉冲获习医演毛尽脸弹楼艺航陆右协七攻镇检写苏宗章注阿抗弟坐验封紧劳户优财养适陈喜卫排射哥油刻留急降念云微伤例景拿绝阶座刘刚害印亿沙母酒助闻超审待压升送监策略限竟香配藏敌呢差仍兰温园树征善波哪词岛止预怕继皮执味份角草男普答益谁船惊核街夏宣掌田久著画辑奇尼剑吧谈背免孩礼材愿洋春架筑括晚乱乎讲尚良友临激刀夜室既敢邦挥昌板胡欧福港叶简苦担句岁荆贵娘守辖威宜衣帮块堂额错剧充欢够孙班呼阵销坚练脚退读测吴希宁换版异某顾曲楚典朱毒菜判救宋茶洪含顺啊鲜败货矿端兄归冷忙买险康评肉吗厂永哈沉散遗停笔假输牛洞松渐顶训录否述毕督控丰献姑忽爷互亮纳襄登咱钟伯臣雄季脑介鄂召饭暗扩祖齐短烈赶牌恩诉移诗础露届蒙静喝盘卖植授伊湾博痛减穿逐秘庭陵固禁票灵杂姓泽吸侧庆妈遇追甲馆补唐炮沿殿刺怪彩俄旧警索岸轮妇载靠附毫怀软骨探雷旁罪枪牙迎序慢盛雨墙恶谷顿危稳熟概酸操诸绿佛荣针托宽折野付午肯库厚缺罢耳屋嘴末谢巨培页瓦款犯困店智拥雪翻圣戏旗吉婚奖岩疑币圆歌廷健卡烧析讨跑烟误仙疗舞亡闭汽伸脱秋姐繁侵川莫麻秀借寻私岗卷跳丽横驻套兼您君丁束纸夺袁灯坏坦丝径购阴床瞧择墓宪峰遍鲁庙掉丹桃御舰避售怒课播拔奥延虚隐粮络遭摇潜庄混厅婆奴鼓赵访睡震予童徐韦殖抓拜吨扬址洛休纵逃染纷贸透汇灭蛋森仪塔距狐融郡缓聚盖拍迹忠释润粉涓孔岭搜紫虑促抵钢塞寺津液码虎坛珍硬梁奔累役偏迫锛凡损壁哭替税综伦冰盟挂韩竞乌尤弱铺妹秦尊竹珠迅脉泥鬼纯睛刑途隆潮幅杯握谋剂幸奉乘抱朋谓频崇壮骑紝恐享鸡虫绍铜呈泛械摆欲奶敬措爆暴签猛郭嘉障缩亦废搞胞埃曰撤暖寒订俗绩阻盐萨勒忘奏孝贴灰梅触玩默醒胸莲篇柱裁啦淡抢捕闹纺截讯朗誉雅忍梦伙勇峡徒丈尾迷唱泉泰佳残闪伍呀疾署剩贼冠倾豆申贫诺麦泪羊尖辈镜涉贡爹缘摩妻殊贝零映甘骂糖岳饮奋棉雕跃汗冒渡努赞启阁斤裂患伏池鹿洗劲晋倍圈媒箭沟锋胆凭挑抬闯隔弄曹汤苗迁叹唯振储贯彻桌祭符僧衡炸旋喊凤黎郎援肥磁忌赏辽祥董仁辛瑞询敏浪貌毁昨巧腿抽荷陷焦净腹弃乃湘亩滑狗冬宏皆番尸伟桂览恢龄绕趣晶坡魏摸伴墨浓绪舍蓝荡阅井鸿旦惯症鸟窗扎辞聘穷堰宇键荒递恨隶厉杜闲腰袭侍灾涨叔湿寨幕豪郑磨浮薄券赤腐译租氧戴邓煤肠牧孤诏妙旨堡册锅胖柳阔吹丘趋锦颜悬陶拳诚尺晓插蒋艇勤穴摄燕垂罚辆戒稀腾粗袋绘炎氏肩枝狂泊估杭扑臂哲寡偷懂琴悲盾炒稍矛愈籍颁吐呆违亭眉撞贷刊巡屈堆曼饰碎滚悉寄浜迟描污辅魔烦鼻盗餐幼凉仗冈澳驾銆菌肚肃爸仰抚慈扶盆仿炼纲倘碗杰忧惜扫暂祝跨渔宾漫寿猪涌凝邻赴恰劝仇践顷赋悄莱拟贤愤姆乏轰粒逼傅陕昆溶葬燃魂挺腊耐犹辉乳陪颇斜棋殑熊浅沈姊返翼丧拖惨俊驱袖惠涂添牵咸详碰割侯纤柔档糊岂跪拒覆绣吓宿偶揭赖烤卢娃颗邮扇伐循衰弦凯羽枚帅锁疏搭俱帐胶赫鐨埋蒸壳剉彼脏箱浙弯瓜挡拱筹疆肿膜刷杆凶债甜泡玄贾谱夹乾遣薪灌咬尘填廊钻丛狼牢脊熙卒碑漠躲削徽踏贺朵遵狠菲撒扰蛇锡炉纹匹亏鉴慕跌慌穆邀芳爬豫吾奸棒淮捷耕艘齿醉脂兽滴盈卵滋柴溪妃浠碍瓶辩遂怨拨肌俘挖恒励鸣肝腔偿秒拦允塑拆靖耗凌披胁吏纽烂尝垸辟耶艰佩敦疼荐厘匠柏悠壤拾乔轴妖喷掩璃孟轨歇猜晨坊桑堤畅瞎氨辨鞋昏恭畜浩迪雾丢咨擦窝洁飘捉搬奈肤愁砖辣幽嘛赢藕挤舒狮耀诊扣篮尿唤梯勾霍舌侠筋枢屏衙殷栏纠链恋惧笼寸冶弥晃叙吊哩稿娜剥拼欺榜囊汪逆骗堪猎棺胎俩郊掘匆缝乙藻携慧函辱扯嫩癌悟滩祸秉慰驰狱砍糕漏吞纬茅渠催踪叛浑牲杖鞭腺邪欣汝碳彭咐椒绳颈漆遥夷郁斑忆阀卑宴抑逻嫁扭胃仔恼贪兆庸屽僭疯侦鹰驶斩鹤猴蜂瘦赐闷柄椅轿拓扮砂傻粘辐啥鏄伪抛玻昂圳侨吟刃饱吕玛碱冯仓钦哼庞儒叉泄臭艾蓉鼠祯捧舱坝芙瘤勃敲帽吻契舟夸葡剪抖霸艳宸聪仆躺瑶谦炭卧袍猫珊溜漂衔苍坑串浆碧巷咽铸押";

    /** 词得分 = Σ(3500 - 字频排名)；2 字×2、4 字×1.5、3 字×1 */
    private static int wordScore(String w) {
        int total = 0;
        int n = COMMON_FREQ.length();
        for (int i = 0; i < w.length(); i++) {
            int r = COMMON_FREQ.indexOf(w.charAt(i));
            total += (r < 0) ? 0 : (n - r);
        }
        if (w.length() == 2) total *= 2;
        else if (w.length() == 4) total = total * 3 / 2;
        return total;
    }

    /** 候选组合后按词得分降序重排（保障词置顶后） */
    private static void reorderByScore(List<String> list, int guaranteed) {
        if (list.size() <= guaranteed + 1) return;
        List<String> head = new ArrayList<>(list.subList(0, guaranteed));
        List<String> tail = new ArrayList<>(list.subList(guaranteed, list.size()));
        tail.sort((a, b) -> wordScore(b) - wordScore(a));
        list.clear();
        list.addAll(head);
        list.addAll(tail);
    }

    private static void sortByFreq(List<String> list) {
        for (int i = 1; i < list.size(); i++) {
            String k = list.get(i);
            int j = i - 1;
            int fk = COMMON_FREQ.indexOf(k);
            while (j >= 0 && COMMON_FREQ.indexOf(list.get(j)) > fk) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, k);
        }
    }

    /** 动态拼词（钟总核心思路）：基础库 + 86 规则 → 词组无限；保障词置顶，最多 15 条 */
    public static List<String> buildDynamicWords(String code) {
        if (code == null || code.length() != 4) return null;
        ensureIndex();
        List<String> out = new ArrayList<>();
        for (String g : GUARANTEED) {
            if (g.length() > 4 && g.startsWith(code)) {
                String w = g.substring(4);
                if (!out.contains(w)) out.add(w);
            }
        }
        int gCount = out.size();
        List<String> a2 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(0, 2)));
        List<String> b2 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(2, 4)));
        if (a2 != null) sortByFreq(a2);
        if (b2 != null) sortByFreq(b2);
        if (a2 != null && b2 != null) {
            int la = Math.min(a2.size(), 6), lb = Math.min(b2.size(), 6);
            for (int i = 0; i < la; i++) {
                for (int j = 0; j < lb; j++) {
                    String w = a2.get(i) + b2.get(j);
                    if (!out.contains(w)) out.add(w);
                }
            }
        }
        List<String> c1 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(0, 1)));
        List<String> c2 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(1, 2)));
        List<String> c3 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(2, 4)));
        if (c1 != null) sortByFreq(c1);
        if (c2 != null) sortByFreq(c2);
        if (c3 != null) sortByFreq(c3);
        if (c1 != null && c2 != null && c3 != null) {
            int l1 = Math.min(c1.size(), 4), l2 = Math.min(c2.size(), 4), l3 = Math.min(c3.size(), 4);
            for (int i = 0; i < l1; i++) {
                for (int j = 0; j < l2; j++) {
                    for (int k = 0; k < l3; k++) {
                        String w = c1.get(i) + c2.get(j) + c3.get(k);
                        if (!out.contains(w)) out.add(w);
                    }
                }
            }
        }
        List<String> d1 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(0, 1)));
        List<String> d2 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(1, 2)));
        List<String> d3 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(2, 3)));
        List<String> d4 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(3, 4)));
        if (d1 != null) sortByFreq(d1);
        if (d2 != null) sortByFreq(d2);
        if (d3 != null) sortByFreq(d3);
        if (d4 != null) sortByFreq(d4);
        if (d1 != null && d2 != null && d3 != null && d4 != null) {
            int l1 = Math.min(d1.size(), 3), l2 = Math.min(d2.size(), 3), l3 = Math.min(d3.size(), 3), l4 = Math.min(d4.size(), 3);
            for (int i = 0; i < l1; i++) {
                for (int j = 0; j < l2; j++) {
                    for (int k = 0; k < l3; k++) {
                        for (int m = 0; m < l4; m++) {
                            String w = d1.get(i) + d2.get(j) + d3.get(k) + d4.get(m);
                            if (!out.contains(w)) out.add(w);
                        }
                    }
                }
            }
        }
        reorderByScore(out, gCount);
        if (out.size() > 15) out = new ArrayList<>(out.subList(0, 15));
        return out;
    }

    /** 动态词组编码（MRU 置顶用）：按 86 规则从单字全码反算词编码；2字=前2+前2；3字=1+1+2；4字=1+1+1+1 */
    public static String dynamicPhraseCode(String word) {
        if (word == null) return null;
        int n = word.length();
        if (n < 2 || n > 4) return null;
        ensureIndex();
        StringBuilder sb = new StringBuilder();
        try {
            if (n == 2) {
                for (int i = 0; i < 2; i++) {
                    String fc = fullCodeIndex == null ? null : fullCodeIndex.get(String.valueOf(word.charAt(i)));
                    if (fc == null) return null;
                    sb.append(fc, 0, Math.min(2, fc.length()));
                }
            } else if (n == 3) {
                for (int i = 0; i < 2; i++) {
                    String fc = fullCodeIndex == null ? null : fullCodeIndex.get(String.valueOf(word.charAt(i)));
                    if (fc == null) return null;
                    sb.append(fc.charAt(0));
                }
                String fc = fullCodeIndex == null ? null : fullCodeIndex.get(String.valueOf(word.charAt(2)));
                if (fc == null) return null;
                sb.append(fc, 0, Math.min(2, fc.length()));
            } else {
                for (int i = 0; i < 4; i++) {
                    String fc = fullCodeIndex == null ? null : fullCodeIndex.get(String.valueOf(word.charAt(i)));
                    if (fc == null) return null;
                    sb.append(fc.charAt(0));
                }
            }
        } catch (Exception ex) { return null; }
        return sb.length() == 4 ? sb.toString() : null;
    }

    /** 查候选（1 码简码单字 / 2 码先单后词 / 3 码单字+预测 / 4 码词组优先） */
    public static List<String> query(String code) {
        if (code == null || code.isEmpty()) return null;
        ensureIndex();
        List<String> result = new ArrayList<>();
        // v0.5.61：保障词并入本地查询（离线兜底——断网也能打"前进/没办法/中国人民/辛苦了"）
        //   仅 2+ 码并入：1 码是简码/字根区（一级简码"不/的"必须最前，不受保障词干扰）
        if (code.length() >= 2) {
            for (String g : GUARANTEED) {
                if (g.length() > 4 && g.startsWith(code)) {
                    String w = g.substring(4);
                    if (!result.contains(w)) result.add(w);
                }
            }
        }
        List<String> singles = singleIndex == null ? null : singleIndex.get(code);
        List<String> phr = phraseIndex == null ? null : phraseIndex.get(code);
        int len = code.length();
        if (singles != null && singles.size() > 1) sortByFreq(singles);
        if (len == 1) {
            if (singles != null) {
                String expect = null;
                for (String s1 : SIMPLE1) {
                    if (s1.charAt(0) == code.charAt(0)) { expect = s1.substring(1); break; }
                }
                if (expect != null) {
                    result.add(expect);
                    for (String c : singles) if (!c.equals(expect)) result.add(c);
                } else {
                    result.addAll(singles);
                }
            }
        } else if (len == 2) {
            if (phr != null) result.addAll(phr);
            if (singles != null) result.addAll(singles);
        } else if (len == 3) {
            // v0.5.65 反馈④：三码查询=三级简码单字优先（86 老习惯：三码出字）——
            //   先精确单字、再全码前缀单字（如 nua→nuak屏），词组殿后（避免单字被词组截断）
            if (singles != null) {
                if (singles.size() > 1) sortByFreq(singles);
                result.addAll(singles);
            }
            if (singleIndex != null) {
                for (Map.Entry<String, List<String>> e : singleIndex.entrySet()) {
                    String k = e.getKey();
                    if (k.length() > 3 && k.startsWith(code)) {
                        for (String w : e.getValue()) {
                            if (!result.contains(w)) result.add(w);
                        }
                    }
                }
            }
            if (phr != null) result.addAll(phr);
            if (phraseIndex != null) {
                List<String> prePhr = new ArrayList<>();
                for (Map.Entry<String, List<String>> e : phraseIndex.entrySet()) {
                    String k = e.getKey();
                    if (k.length() > 3 && k.startsWith(code)) {
                        for (String w : e.getValue()) {
                            if (!prePhr.contains(w)) prePhr.add(w);
                        }
                    }
                }
                if (!prePhr.isEmpty()) result.addAll(prePhr);
            }
            if (result.size() > 1) sortByFreq(result);
            if (result.size() > 10) result = new ArrayList<>(result.subList(0, 10));
        } else if (len == 4) {
            if (phr != null) result.addAll(phr);
            if (singles != null) {
                if (singles.size() > 1) sortByFreq(singles);
                result.addAll(singles);
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 取词组的五笔编码（反向索引首条），无则回退动态编码（86 规则反算） */
    public static String phraseCode(String word) {
        if (word == null || word.isEmpty()) return null;
        ensureIndex();
        if (phraseCodeIndex != null && phraseCodeIndex.containsKey(word)) return phraseCodeIndex.get(word);
        // v0.5.61：动态词（陈胜/吴广等不在词组文件）回退 86 规则反算——MRU 置顶匹配成功的关键
        return dynamicPhraseCode(word);
    }

    /** 取单字的五笔编码（反向索引首条），无则返回 null */
    public static String singleCode(String ch) {
        if (ch == null || ch.length() != 1) return null;
        ensureIndex();
        if (singleCodeIndex == null) return null;
        return singleCodeIndex.get(ch);
    }
}
