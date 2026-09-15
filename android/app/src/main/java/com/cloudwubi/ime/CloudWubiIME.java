package com.cloudwubi.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudWubiIME - 云五笔 Android 输入法服务（v0.4.8）
 *
 * v0.4.8 修复清单（真机第四轮 8 项反馈）：
 *   ① 词组智能联想：上屏单字后，最近 3 个选中词组置顶 + 带此字的词组（本地+云端热点）
 *   ② 退格：全选/部分选中时正确删除选区（此前全选无反应、部分选中只删末字）
 *   ③ 剪贴板历史行间距加大，易点选
 *   ④ 回车键无候选时上屏换行（此前误上屏空格）
 *   ⑤ 🎤 键改为空格键（上屏空格/选首选），移除语音占位提示
 *   ⑥ 键帽深色边框改为羽化渐变边框
 *   ⑦ 编码括号移至候选末尾（1.钟 2.鈡（qkhh））；选字后云端提示英文翻译
 *   ⑧ 数字面板 4 列（左侧 + - × ÷），实时计算并默认「带公式上屏」，长按 = 仅上屏结果
 */
public class CloudWubiIME extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    /** 云端网关地址（腾讯云 SCF 函数 URL，v0.4.7 启用） */
    private static final String GATEWAY_URL =
            "https://1251037126-bglnivgmaf.ap-guangzhou.tencentscf.com";
    private static final boolean GATEWAY_READY = true;

    // ===== 功能键编码（与 XML 严格对应，v0.4.6 按截图 / v0.4.8 数字面板） =====
    private static final int KEY_123 = -101;      // 数字面板
    private static final int KEY_LANG = -102;     // 中/英
    private static final int KEY_SYMBOL = -103;   // 面板返回主键盘
    private static final int KEY_SYM_IN = -104;   // 符号面板
    // v0.5.34 符号面板分类式（参考用户截图：最近/中文/英文/表情/网络 + 底部导航）
    private static final int KEY_SYM_RECENT = -311;
    private static final int KEY_SYM_CN = -312;
    private static final int KEY_SYM_EN = -313;
    private static final int KEY_SYM_EMOJI = -314;
    private static final int KEY_SYM_NET = -315;
    private static final int KEY_SYM_LOCK = -321;
    private static final int KEY_SYM_UP = -322;
    private static final int KEY_SYM_DOWN = -323;
    private static final int KEY_NET_COM = -341;
    private static final int KEY_NET_NET = -342;
    private static final int KEY_NET_CN = -343;
    private static final int KEY_NET_WWW = -344;
    private static final int KEY_NET_HTTP = -345;
    private static final int KEY_NET_HTTPS = -346;
    private static final int KEY_NET_SLASH2 = -347;
    private static final int KEY_NET_COMSLASH = -348;
    private static final int KEY_SHIFT = -105;    // ↑ Shift（单击切换/双击锁定大写）
    private static final int KEY_PUNCT_BANG = -106; // !，双标点循环
    private static final int KEY_MIC = -107;      // v0.4.8: 空格键（原🎤语音占位已移除）
    private static final int KEY_PUNCT_QM = -108; // ？。双标点循环
    private static final int KEY_CALC_EQ = -201;  // 数字面板 = 计算上屏
    private static final int KEY_CALC_DIV = -202; // 数字面板 ÷
    private static final int KEY_CALC_MUL = -203; // 数字面板 ×
    private static final int KEY_UNDO = -209;     // v0.4.9 取消↺
    private static final int KEY_REDO = -210;     // v0.4.9 重做↻
    private static final int KEY_SEARCH = -211;   // v0.5.0 搜索键（数字面板）
    private static final int KEY_SPACE = 32;
    private static final int KB_DELETE = -5;      // Keyboard.KEYCODE_DELETE
    private static final int KB_ENTER = -4;       // Keyboard.KEYCODE_ENTER

    // ===== v0.4.9 FLAT 主题（跟随系统浅色/深色） =====
    private static final int THEME_LIGHT = 0;
    private static final int THEME_DARK = 1;
    private static final int THEME_LIGHT_KB_BG = 0xFFE8EBEF;   // 键盘底色（浅）
    private static final int THEME_LIGHT_CAND_BG = 0xFFFFFFFF; // 候选条背景（浅）
    private static final int THEME_LIGHT_TEXT = 0xFF1F2937;    // 主文字（浅）
    private static final int THEME_LIGHT_HINT = 0xFF9CA3AF;    // 上滑标注/弱文字（浅）
    private static final int THEME_DARK_KB_BG = 0xFF1B1F24;    // 键盘底色（深）
    private static final int THEME_DARK_CAND_BG = 0xFF23272E;  // 候选条背景（深）
    private static final int THEME_DARK_TEXT = 0xFFF3F4F6;     // 主文字（深）
    private static final int THEME_DARK_HINT = 0xFF6B7280;     // 弱文字（深）
    private static final int THEME_ACCENT = 0xFF3B82F6;        // 主题蓝
    private static final int THEME_FIRST = 0xFFF59E0B;         // 首选橙色
    private static final int THEME_ERROR = 0xFFDC2626;         // 计算错误红
    private int theme = THEME_LIGHT;

    // ===== v0.4.9 连续联想（陈→胜→吴广） =====
    private String lastCommittedText = "";   // 最近上屏的连续文本（联想链拼接）

    // ===== v0.4.9 候选翻页 =====
    private static final int CAND_PAGE_SIZE = 5;
    private int candPage = 0;
    private int pinyinInsertPos = 0;   // v0.5.36 反馈⑨：拼音候选插入位置（本地五笔候选之后）

    // ===== v0.4.9 取消↺ / 重做↻（编辑快照栈） =====
    private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> redoStack = new java.util.ArrayDeque<>();
    private static final int UNDO_MAX = 30;

    // ===== 剪贴板历史（仅复制文本，v0.4.5） =====
    private static final int CLIP_MAX = 20;
    private static final String PREFS_NAME = "cloudwubi";
    private static final String PREFS_CLIP = "clip_history";
    /** v0.5.34 反馈⑧：剪贴板项标记 span（长按定位删除） */
    private static class ClipTagSpan { int index; ClipTagSpan(int i) { index = i; } }
    private float lastTouchX = 0f, lastTouchY = 0f;   // v0.5.34 长按定位坐标
    private android.view.GestureDetector candFlingDetector;   // v0.5.35 反馈③：候选左右滑动翻页
    private static final String PREFS_PHRASES = "recent_phrases";  // v0.4.8 MRU 词组
    private static final String PREFS_LAST_SEL = "last_selected";  // v0.5.9 反馈⑧：上次选中字/词持久化（字频调整跨会话生效）
    private static final String PREFS_MRU = "mru_list";   // v0.5.50：同码 MRU 历史（写死规则：之前打过的字/词前置）

    private final StringBuilder composingCode = new StringBuilder();
    private List<String> candidates = new ArrayList<>();
    private TextView candidateView;
    private CloudKeyboardView keyboardView;
    private LinearLayout rootView;
    private LinearLayout toolRow;   // v0.5.0 反馈①：工具行（全选/取消↺/重做↻）
    private android.widget.TextView hideBtn;   // v0.5.4 反馈⑨：闲置 2 秒后出现的收起键盘按钮
    /** v0.5.5：闲置定时器——2 秒出现收起按钮（反馈④，INVISIBLE 占位不跳动）
     *  v0.5.8 反馈⑤：取消状态栏/备选栏自动清空（原 1 秒清空候选已移除，候选保留待用户操作） */
    private final android.os.Handler idleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            // v0.5.10 反馈③：下隐常显，不再计时显示
        }
    };
    /** v0.5.5 反馈①：密码框模式（禁用联想/剪贴板/翻译，保证可输入） */
    private boolean isPassword = false;
    /** v0.5.5 反馈⑤：符号面板来源面板（0=主键盘，1=数字面板），用于"返回"逐层回退 */
    private int prevPanel = 0;
    /** v0.5.5 反馈⑦：进入输入状态时联想基准字（光标前一字），云端回填校验用 */
    private String enterAssociateChar = "";
    /** v0.5.67 云端语义衔接候选插入点（联想路径=MRU段后；打字路径=-1 走原逻辑） */
    private int cloudAssocInsert = -1;
    private Keyboard keyboardMain;
    private Keyboard keyboardNum;
    private Keyboard keyboardSymRecent;   // v0.5.34 符号面板分类：最近
    private Keyboard keyboardSymCn;       // 中文
    private Keyboard keyboardSymEn;       // 英文
    private Keyboard keyboardSymEmoji;    // 表情
    private Keyboard keyboardSymNet;      // 网络
    private boolean chineseMode = true;
    private boolean fromChineseShift = false;   // v0.5.10 反馈①：记录 shift 是否从中文切入英文大写
    private int panelMode = 0;              // 0=主键盘 1=数字 2=符号
    private boolean clipMode = false;       // 候选条是否显示剪贴板历史
    private boolean infoPanelMode = false;  // v0.5.13 反馈①：候选条是否显示 app 信息面板
    private int candViewH = 0;              // v0.5.14 反馈⑤：备选栏固定高度（正常态单行不抖动）
    private String lastSelected = "";       // MRU 置顶（最近一个，写死规则：上屏必置顶）
    // v0.5.50 写死规则固化：同码打过的字/词全部前置（按时间倒序）——"之前打过的字或词就要放最前面，
    //   除非有其他同码的字或词新上屏了"（新上屏的排第一，旧的依次在后）
    private final java.util.List<String> mruList = new java.util.ArrayList<>();
    private String lastCommittedChar = "";  // v0.4.8 最近上屏单字（触发联想）
    private String lastEnHint = "";         // v0.4.8 最近选字英文翻译提示
    private android.widget.TextView statusInfo;   // v0.5.8 状态栏：编码 + 英文翻译（左侧"云五笔"固定）
    private boolean calcAuto = false;       // v0.5.8 反馈⑥：续算去重仅用于运算符自动续接（防误伤手动输入）
    private String lastCalcInput = "";      // v0.5.35 反馈①：最近一次直接上屏的数字（运算符按下时回收为表达式起点）
    private List<String> recentPhrases = new ArrayList<>();  // v0.4.8 最近3个选中词组
    private String calcBuffer = "";         // v0.4.8 数字面板计算表达式
    private String lastCalcResult = "";     // v0.5.3 反馈②：上次计算结果（上屏后接运算符可继续计算）
    private boolean calcFormula = true;     // v0.4.8 默认带公式上屏（长按=仅结果）
    /** v0.5.58：真实计算引擎（纯 Java 可测——测试对象=线上对象；主类字段仅作 UI 快照） */
    private final CalcEngine calcEngine = new CalcEngine();
    /** CalcEngine 操作后同步快照字段（供 UI 读取） */
    private void syncCalcState() {
        this.calcBuffer = calcEngine.calcBuffer;
        this.lastCalcInput = calcEngine.lastCalcInput;
        this.lastCalcResult = calcEngine.lastCalcResult;
        this.calcAuto = calcEngine.calcAuto;
    }
    /** v0.5.59 反馈：点击"云五笔"时检测新版本（不点击不检测）；有新版→可点击下载升级 */
    private String latestVersion = null;
    private boolean updateChecked = false;
    private long lastUpdateCheck = 0L;
    private volatile boolean checkingUpdate = false;

    // Shift / Caps（反馈③）
    private int shiftState = 0;             // 0=小写 1=单次大写 2=锁定大写
    private long lastShiftTap = 0L;

    private ClipboardManager clipManager;
    private SharedPreferences prefs;
    private List<String> clipHistory = new ArrayList<>();
    // v0.5.36 反馈④：剪贴板长按删除 → 点"取消↺"恢复还原（防误操作）
    private String clipUndoItem = null;
    private int clipUndoIndex = -1;
    /** v0.5.14 反馈②：端侧词频缓存（最近 3 个月输入记录——上屏词→次数，联想排序权重） */
    private static final String PREFS_FREQ = "cw_freq";
    private final Map<String, Integer> freqMap = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        WubiDb.init(this);   // 加载离线词库（res/raw）
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadClipHistory();
        loadRecentPhrases();
        loadFreq();   // v0.5.14 反馈②：加载端侧词频缓存
        // v0.5.9 反馈⑧：恢复上次选中字/词（字频调整跨会话生效，重启仍记忆）
        lastSelected = prefs.getString(PREFS_LAST_SEL, "");
        // v0.5.50：加载同码 MRU 历史（写死规则：之前打过的字/词前置）
        String mruSaved = prefs.getString(PREFS_MRU, "");
        if (!mruSaved.isEmpty()) {
            String[] parts = mruSaved.split("\u0001");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (!parts[i].isEmpty()) mruList.add(parts[i]);
            }
        }
        try {
            clipManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipManager != null) {
                clipManager.addPrimaryClipChangedListener(clipListener);
            }
        } catch (Exception ignored) { }
        fetchCloudLinks();   // v0.6.3 CCA 联动：异步拉取云端衔接映射表（实时生效免发版）
    }

    @Override
    public void onDestroy() {
        try {
            if (clipManager != null) clipManager.removePrimaryClipChangedListener(clipListener);
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        candidateView = new TextView(this);
        candidateView.setTextSize(18);   // v0.5.14 反馈⑥：备选词组大字显示（透明背景默认）
        candidateView.setPadding(14, 12, 14, 12);
        // v0.5.14 反馈⑤：固定备选栏高度（单行）→ 不撑大显示范围、无画面抖动
        candViewH = Math.round(46 * getResources().getDisplayMetrics().density);
        candidateView.setMinHeight(candViewH);
        candidateView.setMaxHeight(candViewH);
        candidateView.setMaxLines(1);
        candidateView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        candidateView.setHighlightColor(0x00000000);
        candidateView.setGravity(android.view.Gravity.CENTER_VERTICAL);   // v0.5.70：文本垂直居中——有字/无字视觉高度一致
        // v0.5.35 反馈③：候选条左右滑动翻页（替代点击翻页）
        candFlingDetector = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float velocityX, float velocityY) {
                // v0.5.36 反馈①：所有翻页都用滑动——左右/上下滑动均翻页
                float vx = Math.abs(velocityX), vy = Math.abs(velocityY);
                if (Math.max(vx, vy) > 200) {
                    if (vx > vy) {
                        if (velocityX < 0) { nextCandidatePage(); return true; }
                        prevCandidatePage(); return true;
                    } else {
                        if (velocityY < 0) { nextCandidatePage(); return true; }
                        prevCandidatePage(); return true;
                    }
                }
                return false;
            }
        });
        // v0.5.10 反馈②：点击候选区空白（非条目/非返回）→ 关闭剪贴板回正常输入
        candidateView.setOnClickListener(v -> {
            if (clipMode) {
                clipMode = false;
                updateCandidateView();
            }
        });
        // v0.5.34 反馈⑧：长按剪贴板列表某一项 = 删除该项（通过触摸坐标定位）
        candidateView.setOnLongClickListener(v -> {
            if (clipMode) {
                try {
                    int off = candidateView.getOffsetForPosition(lastTouchX, lastTouchY);
                    ClipTagSpan[] tags = candidateView.getText() == null ? null
                            : (ClipTagSpan[]) ((android.text.Spanned) candidateView.getText()).getSpans(off, off, ClipTagSpan.class);
                    if (tags != null && tags.length > 0) {
                        removeClipItem(tags[0].index);
                        updateCandidateView();
                        return true;
                    }
                } catch (Exception ignored) { }
            }
            return false;
        });
        // v0.5.34 反馈⑧：记录触摸坐标（长按定位用）+ v0.5.35 反馈③：左右滑动翻页
        // v0.5.39 反馈②⑤⑥：点候选条任何部位——若为 app 信息面板则关闭；若为剪贴板且点非列表项则关闭
        candidateView.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchX = ev.getX();
                lastTouchY = ev.getY();
                if (infoPanelMode) {
                    // v0.5.47 反馈②：点中 github 链接 span 时保留面板（让 LinkMovementMethod 在 ACTION_UP 触发跳转）
                    boolean onGithub = false;
                    try {
                        int off = candidateView.getOffsetForPosition(lastTouchX, lastTouchY);
                        if (candidateView.getText() instanceof android.text.Spanned) {
                            android.text.style.ClickableSpan[] cs = ((android.text.Spanned) candidateView.getText())
                                    .getSpans(off, off, android.text.style.ClickableSpan.class);
                            onGithub = (cs != null && cs.length > 0);
                        }
                    } catch (Exception ignored) { }
                    if (!onGithub) {
                        infoPanelMode = false;
                        updateCandidateView();
                    }
                } else if (clipMode) {
                    try {
                        int off = candidateView.getOffsetForPosition(lastTouchX, lastTouchY);
                        ClipTagSpan[] tags = candidateView.getText() == null ? null
                                : (ClipTagSpan[]) ((android.text.Spanned) candidateView.getText()).getSpans(off, off, ClipTagSpan.class);
                        if (tags == null || tags.length == 0) {
                            clipMode = false;
                            updateCandidateView();
                        }
                    } catch (Exception ignored) {
                        clipMode = false;
                        updateCandidateView();
                    }
                }
            }
            candFlingDetector.onTouchEvent(ev);
            return false;   // 不消费：让 LinkMovementMethod 处理点击/长按
        });

        keyboardMain = new Keyboard(this, R.xml.keyboard_qwerty);
        keyboardNum = new Keyboard(this, R.xml.keyboard_num);
        keyboardSymRecent = new Keyboard(this, R.xml.keyboard_sym_recent);
        keyboardSymCn = new Keyboard(this, R.xml.keyboard_sym_cn);
        keyboardSymEn = new Keyboard(this, R.xml.keyboard_sym_en);
        keyboardSymEmoji = new Keyboard(this, R.xml.keyboard_sym_emoji);
        keyboardSymNet = new Keyboard(this, R.xml.keyboard_sym_net);
        keyboardView = new CloudKeyboardView(this, null);
        keyboardView.setKeyboard(keyboardMain);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);
        keyboardView.setHapticFeedbackEnabled(false);   // v0.5.8 反馈②：去掉击键感应（震动）
        // 反馈④：键盘左右留边（截图约 4.5% 屏宽）
        int dp12 = Math.round(12 * getResources().getDisplayMetrics().density);
        int dp6 = Math.round(6 * getResources().getDisplayMetrics().density);
        keyboardView.setPadding(dp12, dp6, dp12, dp6);
        // v0.5.44 反馈⑦：固定键盘高度（主键盘 4 行 × 56dp + padding 12dp ≈ 236dp）——切数字/符号面板不跳动
        keyboardView.setMinimumHeight(Math.round(236 * getResources().getDisplayMetrics().density));
        keyboardView.setMinimumWidth(Math.round(340 * getResources().getDisplayMetrics().density));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // v0.5.0 反馈①：输入状态条工具行（全选 / 取消↺ / 重做↻）+ v0.5.1 反馈⑦：亖剪贴板置前
        // v0.5.8 反馈①：状态栏左侧"云五笔"固定 + 编码 + 英文翻译；右侧 全选|取消↺|重做↻|亖|下隐
        toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toolRow.setPadding(10, 3, 4, 3);
        android.widget.TextView brand = new android.widget.TextView(this);
        brand.setText("云五笔");
        brand.setTextSize(14);   // v0.5.34 反馈④：状态栏字体调大（参考截图）
        brand.setTextColor(dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT);
        // v0.5.13 反馈①：去掉 ⓘ 按钮（AlertDialog 在 IME 服务 Context 无法显示）→ 点"云五笔"文字打开 app 信息面板
        brand.setPadding(6, 6, 6, 6);
        brand.setOnClickListener(v -> showInfoPanel());
        toolRow.addView(brand);
        statusInfo = new android.widget.TextView(this);
        statusInfo.setTextSize(14);   // v0.5.34 反馈④
        statusInfo.setPadding(8, 0, 0, 0);
        statusInfo.setSingleLine(true);
        statusInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusInfo.setMaxWidth(Math.round(140 * getResources().getDisplayMetrics().density));   // v0.5.40 反馈⑤：限定显示宽度
        statusInfo.setTextColor(dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT);
        // v0.5.9 反馈⑦：点击状态栏英文翻译 → 上屏翻译内容
        statusInfo.setOnClickListener(v -> {
            if (!lastEnHint.isEmpty()) {
                commitText(lastEnHint);
                lastEnHint = "";
                updateCandidateView();
            }
        });
        toolRow.addView(statusInfo);
        android.widget.Space spacer = new android.widget.Space(this);
        toolRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        // v0.5.55 反馈：全选图标⭕️→〇（⭕️为红色字符，与取消/删除等工具栏图标颜色不统一；〇为普通字符跟随主题色）
        toolRow.addView(makeToolButton("〇", v -> selectAll()));
        // v0.5.14 反馈③：工具栏加"删除"（删光标前字符/选区，与退格同功能）
        // v0.5.36 反馈③：取消/删除只用图标节省空间（✕=删除、↺=取消、↻=重做）
        toolRow.addView(makeToolButton("✕", v -> handleBackspace()));
        toolRow.addView(makeToolButton("↺", v -> doUndo()));
        toolRow.addView(makeToolButton("↻", v -> doRedo()));
        toolRow.addView(makeToolButton("亖", v -> {
            // v0.5.5 反馈①：密码框禁用剪贴板（隐私）
            if (isPassword) return;
            infoPanelMode = false;   // v0.5.13：切剪贴板时关闭信息面板
            clipMode = true;
            updateCandidateView();
        }));
        // v0.5.10 反馈③：下隐功能键常显（取消闲置计时），图标 🔽
        hideBtn = makeToolButton("🔽", v -> {
            try { requestHideSelf(0); } catch (Exception ignored) { }
        });
        hideBtn.setVisibility(android.view.View.VISIBLE);
        toolRow.addView(hideBtn);
        // v0.5.8 反馈①：第一行状态栏（云五笔|编码|翻译 | 工具），第二行备选栏，第三行键盘
        root.addView(toolRow);
        root.addView(candidateView);
        root.addView(keyboardView);
        rootView = root;
        // v0.5.4 反馈③：回车键长按 → 强制换行（单行/多行均生效）
        keyboardView.setOnLongPressListener(key -> {
            if (key != null && key.codes != null && key.codes.length > 0 && key.codes[0] == KB_ENTER) {
                commitText("\n");
            }
        });
        resetIdleTimers();
        applyTheme();   // v0.4.9 反馈③：FLAT + 跟随系统深浅色
        applyLangLabels();
        applyLetterCase();   // 中文模式默认大写显示
        updateCandidateView();
        return root;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyTheme();   // v0.4.9 反馈③：系统切换浅/深色时实时跟随
    }

    /** v0.5.0 反馈①：工具行按钮（全选/取消↺/重做↻） */
    private TextView makeToolButton(String text, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        // v0.5.54 反馈：取消↺/重做↻ 仍偏小 → 放大至 22；其余统一 15——工具栏图标视觉统一
        boolean isRound = text.equals("↺") || text.equals("↻");
        float ts = isRound ? 22f : 15f;
        tv.setTextSize(ts);
        tv.setGravity(android.view.Gravity.CENTER);
        if (isRound) tv.setIncludeFontPadding(false);   // v0.5.56：↺↻ 大字符基线偏下 → 去字体内边距视觉行居中
        tv.setOnClickListener(listener);
        // v0.5.40 反馈④：固定宽度放置工具栏按钮，避免文字宽度差异导致位移晃动
        // v0.5.60 反馈③：宽度 44dp→34dp（5 按钮间隔缩小一半）；↺↻ 再下移 3 像素视觉对齐
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(34 * getResources().getDisplayMetrics().density),
                LinearLayout.LayoutParams.MATCH_PARENT));
        if (isRound) tv.setTranslationY(-6f);   // v0.5.70 反馈：↺↻ 仍下掉 → 上移 6 像素（22sp 大字符基线偏下）
        return tv;
    }

    /** v0.5.13 反馈①：app 信息面板（IME 内嵌，候选条区域渲染，点任意键关闭）
     *  原因：AlertDialog 在 InputMethodService Context 下被系统拦截无法显示（v0.5.11 弹窗未出现的根因） */
    private void showInfoPanel() {
        // v0.5.28 反馈①：点一次显示 app 信息，再点消失（toggle）
        infoPanelMode = !infoPanelMode;
        if (!infoPanelMode) {
            candidateView.setSingleLine(true);
            candidateView.setMaxLines(1);
            candidateView.setMinHeight(candViewH);
            candidateView.setMaxHeight(candViewH);
        }
        clipMode = false;
        updateCandidateView();
        if (infoPanelMode) checkUpdateAsync();   // v0.5.59：打开面板即检测新版本（不点击"云五笔"不做在线检测）
    }

    /** v0.5.59：异步检测最新版本（GitHub releases/latest；不阻塞 UI；失败静默——面板仍显示基础信息） */
    private void checkUpdateAsync() {
        if (checkingUpdate) return;
        long now = System.currentTimeMillis();
        if (updateChecked && now - lastUpdateCheck < 30000) return;   // 30 秒冷却：避免反复点击重复请求
        checkingUpdate = true;
        new Thread(() -> {
            // v0.5.60：双源检测（GitHub 主 → Gitee 兜底——国内网络 GitHub API 常不通）
            String latest = null;
            String[] urls = {
                "https://api.github.com/repos/zsdili/cloudwubi-client/releases/latest",
                "https://gitee.com/api/v5/repos/zsdili/cloudwubi-client/releases/latest"
            };
            for (String ustr : urls) {
                java.net.HttpURLConnection conn = null;
                try {
                    java.net.URL u = new java.net.URL(ustr);
                    conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("User-Agent", "CloudWubi-IME");
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    String json = sb.toString();
                    int i = json.indexOf("\"tag_name\":\"");
                    if (i >= 0) {
                        latest = json.substring(i + 12, json.indexOf('"', i + 12));
                        break;
                    }
                } catch (Exception ignored) { } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Exception ignored) { }
                }
            }
            final String v = latest;
            idleHandler.post(() -> {
                latestVersion = v;
                updateChecked = true;
                lastUpdateCheck = System.currentTimeMillis();
                checkingUpdate = false;
                if (infoPanelMode) updateCandidateView();   // 面板仍打开才刷新（否则下次打开再显示）
            });
        }).start();
    }

    /** 版本号比较（v0.5.60：逻辑提取到 VersionUtil——纯 Java 可测） */
    private static int compareVersions(String a, String b) { return VersionUtil.compare(a, b); }

    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.5.28";
        }
    }

    private void renderInfoPanel() {
        int c = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        // v0.5.40 反馈①：app 信息只显示 版本号 + github(可点击跳转) + 微信，不得显示其他内容
        sb.append("v").append(currentVersion());
        sb.append("   github");
        int gStart = sb.length() - 6;   // github 起始（"   github" 末尾 6 位）
        int gEnd = sb.length();
        sb.append("   微信：175571");
        ClickableSpan gh = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                try {
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/zsdili"));
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) { }
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(c);
                ds.setUnderlineText(true);
            }
        };
        sb.setSpan(gh, gStart, gEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // v0.5.59：点击"云五笔"时检测新版本——有新版则提示可点击下载升级（GitHub APK 直链）
        if (latestVersion != null && compareVersions(latestVersion, currentVersion()) > 0) {
            int uStart = sb.length();
            sb.append("  ·  发现新版本 v").append(latestVersion).append(" [点击下载升级]");
            int uEnd = sb.length();
            ClickableSpan dl = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    try {
                        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/zsdili/cloudwubi-client/releases/latest/download/CloudWubi.apk"));
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) { }
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(dark() ? 0xFF4FC3F7 : 0xFF1565C0);
                    ds.setUnderlineText(true);
                }
            };
            sb.setSpan(dl, uStart, uEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (updateChecked) {
            sb.append(latestVersion == null ? "  ·  检查更新失败（网络）" : "  ·  已是最新版本");
        }
        sb.setSpan(new ForegroundColorSpan(c), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // v0.5.65 反馈①：app 信息仅限一行（不换行、超宽省略），保证不撑大显示范围
        candidateView.setSingleLine(true);
        candidateView.setMaxLines(1);
        candidateView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        candidateView.setMinHeight(candViewH);
        candidateView.setMaxHeight(candViewH);
        candidateView.setTextSize(16f);
        safeSetText(sb);
    }

    /** v0.5.0 反馈①：全选当前文本框内容 */
    private void selectAll() {        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            CharSequence b = ic.getTextBeforeCursor(2000, 0);
            CharSequence a = ic.getTextAfterCursor(2000, 0);
            int len = (b == null ? 0 : b.length()) + (a == null ? 0 : a.length());
            ic.setSelection(0, len);
        } catch (Exception ignored) { }
    }

    /** v0.4.9 反馈③：FLAT 风格 + 深浅色主题（键帽/键盘底/候选条/文字全部跟随系统外观） */
    private void applyTheme() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        theme = dark ? THEME_DARK : THEME_LIGHT;
        if (keyboardView == null || candidateView == null) return;
        // 键帽：v0.4.9 纯色 FLAT 全自绘（无立体/渐变），深浅两套实时切换
        keyboardView.applyTheme(dark);
        keyboardView.setBackgroundColor(dark ? THEME_DARK_KB_BG : THEME_LIGHT_KB_BG);
        keyboardView.setHintColor(dark ? THEME_DARK_HINT : THEME_LIGHT_HINT);
        // 候选条
        candidateView.setBackgroundColor(dark ? THEME_DARK_CAND_BG : THEME_LIGHT_CAND_BG);
        candidateView.setTextColor(dark ? THEME_DARK_TEXT : THEME_LIGHT_TEXT);
        // 工具行（v0.5.0 反馈①）
        if (toolRow != null) {
            int tc = dark ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
            for (int i = 0; i < toolRow.getChildCount(); i++) {
                View v = toolRow.getChildAt(i);
                if (v instanceof TextView) ((TextView) v).setTextColor(tc);
            }
        }
        if (rootView != null) rootView.setBackgroundColor(dark ? THEME_DARK_KB_BG : THEME_LIGHT_KB_BG);
    }

    private android.graphics.drawable.Drawable getDrawableCompat(int resId) {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            return getResources().getDrawable(resId, getTheme());
        }
        return getResources().getDrawable(resId);
    }

    // ===== 生命周期 =====

    @Override
    public void onFinishInput() {
        resetComposing();
        // v0.5.17 反馈⑤（举一反三：窗口生命周期完整感知）：离开输入触点（焦点转移/输入结束）→
        //   主动收起输入界面，符合"科学、合理"的输入法基础规范
        hideWindow();
        super.onFinishInput();
    }

    @Override
    public void onStartInput(android.view.inputmethod.EditorInfo attribute, boolean restarting) {
        // v0.5.5 反馈①：标准会话入口（密码框/WebView 等必须重写，保证输入可用）
        super.onStartInput(attribute, restarting);
    }

    public void onStartInputView(EditorInfo info, boolean restarting) {
        resetComposing();
        panelMode = 0;
        clipMode = false;
        prevPanel = 0;
        keyboardView.setKeyboard(keyboardMain);
        applyLetterCase();
        // v0.5.3 反馈①：新输入会话重置"最近上屏"记录
        committedLast = "";
        // v0.5.5 反馈①：密码框检测（密码/网络密码）——禁联想/剪贴板/翻译，保证可输入
        isPassword = (info.inputType & android.text.InputType.TYPE_MASK_VARIATION)
                == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                || (info.inputType & android.text.InputType.TYPE_MASK_VARIATION)
                == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        // v0.5.5 反馈④⑧：新会话隐藏收起按钮（INVISIBLE 占位）+ 重启双闲置计时
        if (hideBtn != null) hideBtn.setVisibility(android.view.View.INVISIBLE);
        resetIdleTimers();
        // v0.5.5 反馈⑦：进入输入状态时，识别光标前一字进行联想（不自动上屏）
        // v0.5.20（用户指令）：联想已去除——不再做"进入输入状态时光标前字联想"
        enterAssociateChar = "";
        super.onStartInputView(info, restarting);
    }

    /** v0.5.65 反馈②：意思衔接词表（前进→方向 是"衔接"，不是"进行"式组词）——
     *  词义上"下一个自然出现的词"，让用户少打很多字 */
        private static final java.util.Map<String, String[]> ASSOC_LINK = new java.util.HashMap<>();
    static {
        // v0.5.65 反馈②：意思衔接词表（前进→方向 是"衔接"，非"进行"式组词）——核心 40 组（体积门禁 100KiB 内）
        ASSOC_LINK.put("前进", new String[]{"方向", "道路", "号角", "浪潮", "脚步", "吧"});
        ASSOC_LINK.put("方向", new String[]{"明确", "正确", "目标", "感"});
        ASSOC_LINK.put("吃", new String[]{"饭", "亏", "苦", "东西", "早餐", "午饭"});
        ASSOC_LINK.put("喝", new String[]{"水", "茶", "酒", "咖啡"});
        ASSOC_LINK.put("走", new String[]{"路", "开", "出", "进", "吧"});
        ASSOC_LINK.put("看", new String[]{"书", "电影", "电视", "手机", "一下"});
        ASSOC_LINK.put("听", new String[]{"音乐", "歌", "话", "讲座"});
        ASSOC_LINK.put("学", new String[]{"习", "校", "生", "知识"});
        ASSOC_LINK.put("说", new String[]{"话", "明", "道", "一下"});
        ASSOC_LINK.put("想", new String[]{"你", "办法", "法", "一下"});
        ASSOC_LINK.put("做", new String[]{"事", "完", "好", "饭", "决定"});
        ASSOC_LINK.put("打", new String[]{"电话", "字", "开", "球", "工"});
        ASSOC_LINK.put("开", new String[]{"门", "会", "车", "始", "心"});
        ASSOC_LINK.put("来", new String[]{"了", "到", "电", "吧", "啦"});
        ASSOC_LINK.put("去", new String[]{"了", "过", "上班", "吧"});
        ASSOC_LINK.put("买", new String[]{"东西", "单", "菜", "票"});
        ASSOC_LINK.put("工作", new String[]{"顺利", "忙碌", "辛苦", "安排"});
        ASSOC_LINK.put("生活", new String[]{"美好", "幸福", "充实", "愉快"});
        ASSOC_LINK.put("问题", new String[]{"解决", "不大", "分析", "所在"});
        ASSOC_LINK.put("天气", new String[]{"很好", "不错", "变化", "预报"});
        ASSOC_LINK.put("谢谢", new String[]{"你", "大家", "帮忙"});
        ASSOC_LINK.put("加油", new String[]{"努力", "奋斗", "吧"});
        ASSOC_LINK.put("开始", new String[]{"了", "吧", "工作", "行动"});
        ASSOC_LINK.put("明白", new String[]{"了", "吧"});
        ASSOC_LINK.put("知道", new String[]{"了", "吗"});
        ASSOC_LINK.put("支持", new String[]{"你", "一下", "我们"});
        ASSOC_LINK.put("喜欢", new String[]{"你", "这个", "生活"});
        ASSOC_LINK.put("努力", new String[]{"工作", "学习", "奋斗", "上进"});
        ASSOC_LINK.put("成功", new String[]{"了", "在望", "喜悦", "经验"});
        ASSOC_LINK.put("快乐", new String[]{"每一天", "生活", "成长"});
        ASSOC_LINK.put("幸福", new String[]{"生活", "快乐", "美满"});
        ASSOC_LINK.put("健康", new String[]{"快乐", "长寿", "第一"});
        ASSOC_LINK.put("美好", new String[]{"生活", "未来", "时光", "祝愿"});
        ASSOC_LINK.put("未来", new String[]{"可期", "美好", "发展"});
        ASSOC_LINK.put("目标", new String[]{"明确", "实现", "达成"});
        ASSOC_LINK.put("计划", new String[]{"落实", "执行", "安排"});
        ASSOC_LINK.put("合作", new String[]{"共赢", "伙伴", "愉快"});
        ASSOC_LINK.put("项目", new String[]{"推进", "落地", "启动"});
        ASSOC_LINK.put("早上好", new String[]{"钟总", "大家", "朋友们"});
        ASSOC_LINK.put("下午好", new String[]{"钟总", "大家"});
        ASSOC_LINK.put("晚上好", new String[]{"钟总", "大家"});
        ASSOC_LINK.put("辛苦了", new String[]{"钟总", "大家", "你"});
    }

    /** v0.6.3 CCA 联动增强：云端衔接映射表缓存（云端规则实时生效，免发版）
     *  打字/联想路径双表查锚定：内置 ASSOC_LINK + 云端 ngram_link 缓存 */
    private static java.util.Map<String, java.util.List<String>> CLOUD_LINKS = new java.util.HashMap<>();
    /** v0.6.6 逗号补全：最近一次逗号触发的下半句候选（云端返回，空=无） */
    private volatile java.util.List<String> pendingComma = null;
    private static java.util.List<String> getLinks(String chain) {
        java.util.List<String> r = new java.util.ArrayList<>();
        String[] local = ASSOC_LINK.get(chain);
        if (local != null) java.util.Collections.addAll(r, local);
        synchronized (CLOUD_LINKS) {
            java.util.List<String> cloud = CLOUD_LINKS.get(chain);
            if (cloud != null) r.addAll(cloud);
        }
        return r;
    }

    /** v0.5.65 反馈③：光标前字符是否为数字（决定"+"等运算符进计算缓冲还是直接上屏）
     *  规则：calcBuffer 非空（正在计算）或光标前字符是数字 → 进缓冲继续计算；
     *       否则（中文后直接点符号）→ 直接上屏，无需按空格 */
    private boolean prevCharIsDigit() {
        if (!calcBuffer.isEmpty()) return true;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        try {
            CharSequence t = ic.getTextBeforeCursor(1, 0);
            return t != null && t.length() > 0 && Character.isDigit(t.charAt(0));
        } catch (Exception e) { return false; }
    }

    /** v0.5.5 反馈⑦：进入输入状态/光标前字联想（本地 MRU + 含字词组 + 云端前缀） */
    private void showAssociateForChar(String ch) {
        candidates.clear();
        List<String> merged = new ArrayList<>();
        int mruCount = 0;   // v0.5.67：云端语义衔接候选插到 MRU 段后（修"了解"置顶——语义衔接>字词搭配）
        for (String p : recentPhrases) {
            if (p.startsWith(ch) && !merged.contains(p)) { merged.add(p); mruCount++; }
        }
        List<String> byChar = WubiDb.queryByChar(ch);
        if (byChar != null) {
            for (String p : byChar) {
                if (!merged.contains(p)) merged.add(p);
                if (merged.size() >= 12) break;
            }
        }
        pinyinInsertPos = merged.size();   // v0.5.36 反馈⑨：拼音候选插到本地五笔候选之后
        candidates.addAll(merged);
        candPage = 0;
        updateCandidateView();
        cloudAssocInsert = mruCount;       // v0.5.67：云端回填插 MRU 后（了解类字词搭配被挤后）
        if (GATEWAY_READY && !merged.isEmpty()) queryAssociateAsync(ch, ch);
    }

    private void resetComposing() {
        composingCode.setLength(0);
        candidates.clear();
        clipMode = false;
        lastCommittedChar = "";
        lastCommittedText = "";
        lastEnHint = "";
        associateActive = false;
        candPage = 0;
        updateCandidateView();
    }

    // ===== 剪贴板历史（仅复制触发） =====

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> {
        try {
            ClipData cd = clipManager.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).coerceToText(CloudWubiIME.this);
                if (t != null && t.length() > 0 && t.length() <= 5000) {
                    addClipHistory(t.toString());
                    // v0.5.47 反馈⑦：复制后不自动打开剪贴板（只记录历史；需要时点亖 查看）——避免无意义弹出
                }
            }
        } catch (Exception ignored) { }
    };

    /** v0.5.34 反馈⑧：删除剪贴板历史某一项（长按/点✕） */
    private void removeClipItem(int index) {
        if (index < 0 || index >= clipHistory.size()) return;
        clipUndoItem = clipHistory.get(index);   // v0.5.36 反馈④：记录待撤销项
        clipUndoIndex = index;
        clipHistory.remove(index);
        saveClipHistory();
    }

    private void addClipHistory(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String norm = text.replace('\u0001', ' ');
        clipHistory.remove(norm);
        clipHistory.add(0, norm);
        while (clipHistory.size() > CLIP_MAX) clipHistory.remove(clipHistory.size() - 1);
        saveClipHistory();
    }

    private void saveClipHistory() {
        StringBuilder sb = new StringBuilder();
        for (String s : clipHistory) sb.append(s).append('\u0001');
        prefs.edit().putString(PREFS_CLIP, sb.toString()).apply();
    }

    private void loadClipHistory() {
        clipHistory.clear();
        String raw = prefs.getString(PREFS_CLIP, "");
        if (!raw.isEmpty()) {
            String[] arr = raw.split("\u0001", -1);
            for (String s : arr) if (!s.isEmpty()) clipHistory.add(s);
        }
    }

    // ===== v0.4.8 最近选中词组（MRU，最多 3 个，用于字后联想置顶） =====

    private void loadRecentPhrases() {
        recentPhrases.clear();
        Set<String> set = prefs.getStringSet(PREFS_PHRASES, new HashSet<>());
        List<String> tmp = new ArrayList<>(set);
        for (int i = tmp.size() - 1; i >= 0; i--) recentPhrases.add(tmp.get(i));
        while (recentPhrases.size() > 3) recentPhrases.remove(recentPhrases.size() - 1);
    }

    private void rememberPhrase(String phrase) {
        if (phrase == null || phrase.length() < 2) return;
        recentPhrases.remove(phrase);
        recentPhrases.add(0, phrase);
        while (recentPhrases.size() > 3) recentPhrases.remove(recentPhrases.size() - 1);
        prefs.edit().putStringSet(PREFS_PHRASES, new HashSet<>(recentPhrases)).apply();
    }

    // ===== v0.4.8 数字面板四则计算（先乘除后加减，左结合） =====

    private static double calcEval(String expr) { return CalcEngine.calcEval(expr); }

    private static String fmtResult(double v) { return CalcEngine.fmtResult(v); }

    // ===== 键盘文字：中文「中」/ 英文「EN」及功能键英文（反馈②） =====

    private void applyLangLabels() {
        if (keyboardMain == null) return;
        for (Keyboard.Key k : keyboardMain.getKeys()) {
            int c = k.codes[0];
            if (c == KEY_LANG) {
                k.label = chineseMode ? "中" : "en";   // v0.5.27 反馈④：英文切换显示小写 en
            } else if (c == KEY_SYM_IN) {
                k.label = chineseMode ? "符" : "SYM";
            } else if (c == KEY_PUNCT_BANG) {
                // v0.5.3 反馈⑤：上下两行（上行！下行，）——CloudKeyboardView 识别 \n 分行绘制
                k.label = chineseMode ? "！\n，" : "!\n,";
            } else if (c == KEY_PUNCT_QM) {
                k.label = chineseMode ? "？\n。" : "?\n.";
            } else if (c == KEY_MIC) {
                k.label = chineseMode ? "空格" : "space";   // v0.4.8 反馈⑤：空格键
            }
        }
        keyboardView.invalidateAllKeys();
    }

    // ===== 字母大小写显示（反馈③：不依赖 shiftLabel，直接切换 label） =====

    private void applyLetterCase() {
        if (keyboardMain == null) return;
        boolean upper = chineseMode || shiftState > 0;
        for (Keyboard.Key k : keyboardMain.getKeys()) {
            int c = k.codes[0];
            if (c >= 'a' && c <= 'z') {
                k.label = upper ? String.valueOf(Character.toUpperCase((char) c))
                                : String.valueOf((char) c);
            } else if (c == KEY_SHIFT) {
                // v0.5.41 反馈③：shift 激活视觉——大写模式显示实心 ⇧（明确"已切换"），否则 ↑
                k.label = shiftState > 0 ? "⇧" : "↑";
            }
        }
        keyboardView.invalidateAllKeys();
        keyboardView.invalidate();   // v0.5.40 反馈②：双保险重绘，确保键面大小写跟随切换
    }

    // ===== Shift / Caps 逻辑（反馈③） =====

    private void handleShift() {
        // v0.5.10 反馈①：单击切换"持续大写"模式（Caps 锁定式，非单次复位）
        // 中文 → 英文大写；从中文切来的英文大写 → 回中文；纯英文 → 大写/小写切换
        if (chineseMode) {
            chineseMode = false;
            composingCode.setLength(0);
            candidates.clear();
            shiftState = 2;                 // 英文大写模式
            fromChineseShift = true;        // 记录"从中文切来"，再单击回中文
            lastCommittedChar = "";
            lastCommittedText = "";
            lastEnHint = "";
            associateActive = false;
            candPage = 0;
        } else if (fromChineseShift) {
            chineseMode = true;             // 恢复中文输入
            shiftState = 0;
            fromChineseShift = false;
        } else if (shiftState == 0) {
            shiftState = 2;                 // 英文小写 → 持续大写
        } else {
            shiftState = 0;                 // 英文大写 → 恢复小写
        }
        lastShiftTap = System.currentTimeMillis();
        applyLetterCase();
        applyLangLabels();
        updateCandidateView();
    }

    // ===== KeyboardView.OnKeyboardActionListener =====

    /** v0.5.4 反馈⑨ + v0.5.5 反馈④⑧：重置双闲置计时器（每次按键触发；1 秒清空备选栏、2 秒显示收起按钮） */
    private void resetIdleTimers() {
        // v0.5.10 反馈③：下隐常显，取消闲置计时
        idleHandler.removeCallbacks(idleRunnable);
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        // v0.5.44 反馈①：任何按键（打字/符号/删除）→ 关闭剪贴板面板（点选或点其他部位即消失）
        if (clipMode) {
            clipMode = false;
            updateCandidateView();
        }
        resetIdleTimers();   // v0.5.4 反馈⑨ + v0.5.5 反馈⑧：任何按键重置双闲置计时
        // v0.5.13 反馈①：信息面板点任意键关闭
        if (infoPanelMode) {
            infoPanelMode = false;
            updateCandidateView();
        }
        // v0.5.9 反馈⑩：剪贴板界面有键盘点击 → 自动收回剪贴板到正常输入界面
        if (clipMode) {
            clipMode = false;
            updateCandidateView();
        }
        // v0.5.17 反馈①（举一反三：InputType 完整感知）：密码框直通模式——
        //   字母/数字/空格/回车/运算符直接上屏，不进编码缓冲/候选/联想（密码可见性 + 输入体验根本保障）
        //   v0.5.21 修复：Shift/面板切换/退格/中英/标点等控制键不再被吞——只对已直通键 return，
        //   其余继续走正常逻辑（否则密码框无法切换大小写、无法进数字/符号面板）
        if (isPassword) {
            if (primaryCode >= 'a' && primaryCode <= 'z') {
                boolean upper = shiftState > 0;
                commitText(String.valueOf(upper ? Character.toUpperCase((char) primaryCode)
                                                 : (char) primaryCode));
                return;
            } else if (primaryCode >= '0' && primaryCode <= '9') {
                commitText(String.valueOf((char) primaryCode));
                return;
            } else if (primaryCode == KEY_SPACE || primaryCode == KEY_MIC) {
                commitText(" ");
                return;
            } else if (primaryCode == KB_ENTER) {
                sendDefaultEditorAction(true);   // 执行输入框的"完成/搜索/前往"等动作
                return;
            } else if (primaryCode == KEY_CALC_DIV) {
                commitText("÷"); return;
            } else if (primaryCode == KEY_CALC_MUL) {
                commitText("×"); return;
            } else if (primaryCode == KEY_CALC_EQ) {
                commitText("="); return;
            } else if (primaryCode == 43 || primaryCode == 45 || primaryCode == 42) {
                commitText(String.valueOf((char) primaryCode)); return;
            } else if (primaryCode >= 32 && primaryCode <= 126) {
                // v0.5.24 修复③：密码框 ASCII 可见字符兜底直通（特殊字符在密码模式直接上屏）
                commitText(String.valueOf((char) primaryCode)); return;
            }
            // 其余键（Shift/123/符号/退格/中英/返回）→ 不拦截，走正常逻辑（面板切换/大小写/删除可用）
        }
        // 字母键
        if (primaryCode >= 'a' && primaryCode <= 'z') {
            if (chineseMode) {
                appendCode((char) primaryCode);
            } else {
                // v0.5.0 反馈⑤：英文输入进编码 → 自动补全候选（@邮箱、ht→https:// 等）
                appendEnglishCode((char) primaryCode);
            }
            return;
        }
        // 数字（v0.5.35 反馈①：纯数字直接上屏——只有表达式已含运算符时才进缓冲，根治"打5出1.5=5 2.5"）
        if (primaryCode >= '0' && primaryCode <= '9') {
            // v0.5.41 反馈⑤：数字键即时触感（消除"粘粘"卡顿感——按键即有反馈）
            keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            // v0.5.58：统一走真实计算引擎（CalcEngine，JVM 单元测试直接测线上逻辑）
            CalcEngine.Action ca = calcEngine.onDigit((char) primaryCode);
            syncCalcState();
            if (ca.commit != null) commitText(ca.commit);
            else updateCandidateView();
            return;
        }
        switch (primaryCode) {
            case KEY_123:
                // v0.5.27 反馈③：数字面板↔主键盘切换时，未上屏的表达式先带式上屏（防计算结果丢失/上不了屏）
                if (panelMode == 1 && !calcBuffer.isEmpty()) commitCalc(true);
                panelMode = 1;
                calcBuffer = "";
                keyboardView.setKeyboard(keyboardNum);
                updateCandidateView();
                return;
            case KEY_SYM_IN:   // v0.5.34 符号面板分类式：进入"最近"分类（参考用户截图）
                if (panelMode < 2 || panelMode > 6) {
                    prevPanel = panelMode;
                }
                panelMode = 2;
                keyboardView.setKeyboard(keyboardSymRecent);
                return;
            case KEY_SYM_RECENT:   // v0.5.34 分类切换（左栏）
                panelMode = 2;
                keyboardView.setKeyboard(keyboardSymRecent);
                return;
            case KEY_SYM_CN:
                panelMode = 3;
                keyboardView.setKeyboard(keyboardSymCn);
                return;
            case KEY_SYM_EN:
                panelMode = 4;
                keyboardView.setKeyboard(keyboardSymEn);
                return;
            case KEY_SYM_EMOJI:
                panelMode = 5;
                keyboardView.setKeyboard(keyboardSymEmoji);
                return;
            case KEY_SYM_NET:
                panelMode = 6;
                keyboardView.setKeyboard(keyboardSymNet);
                return;
            case KEY_SYM_LOCK:   // 🔒 面板锁定（占位：不自动收起）
                return;
            case KEY_SYM_UP:     // ⌃ 无多页翻页（占位）
            case KEY_SYM_DOWN:   // ⌄
                return;
            case KEY_NET_COM: commitText(".com"); return;
            case KEY_NET_NET: commitText(".net"); return;
            case KEY_NET_CN: commitText(".cn"); return;
            case KEY_NET_WWW: commitText("www."); return;
            case KEY_NET_HTTP: commitText("http://"); return;
            case KEY_NET_HTTPS: commitText("https://"); return;
            case KEY_NET_SLASH2: commitText("//"); return;
            case KEY_NET_COMSLASH: commitText(".com/"); return;
            case KEY_SYMBOL:   // v0.5.34 符号面板返回：分类 → 来源面板/主键盘；数字面板有表达式先带式上屏
                if (panelMode >= 2 && panelMode <= 6) {
                    if (prevPanel == 1) {
                        panelMode = 1;
                        keyboardView.setKeyboard(keyboardNum);
                    } else {
                        panelMode = 0;
                        keyboardView.setKeyboard(keyboardMain);
                    }
                    updateCandidateView();
                    return;
                }
                if (panelMode == 1 && !calcBuffer.isEmpty()) commitCalc(true);
                panelMode = 0;
                calcBuffer = "";
                keyboardView.setKeyboard(keyboardMain);
                applyLetterCase();
                updateCandidateView();
                return;
            case KEY_LANG:
                if (panelMode != 0) {
                    panelMode = 0;
                    calcBuffer = "";
                    keyboardView.setKeyboard(keyboardMain);
                    applyLetterCase();
                } else {
                    toggleLang();
                }
                return;
            case KEY_SHIFT:
                handleShift();
                return;
            case KEY_PUNCT_BANG:   // v0.5.1：点击默认"，"/","（英文态），上滑"！"
                commitText(chineseMode ? "，" : ",");
                return;
            case KEY_PUNCT_QM:     // v0.5.1：点击默认"。" / "."（英文态），上滑"？"
                commitText(chineseMode ? "。" : ".");
                return;
            case KEY_MIC:          // v0.5.4 反馈⑦：空格键——备选栏有候选则上屏首选，否则输出空格
            case KEY_SPACE:
                commitSpaceOrFirst();
                return;
            case KEY_CALC_EQ:      // = 计算上屏（旧面板键，保留兼容）
                commitCalc(true);
                return;
            case KEY_CALC_DIV:     // ÷
                if (panelMode == 1 && prevCharIsDigit()) { calcAppendOp("÷"); } else commitText("÷");
                return;
            case KEY_CALC_MUL:     // ×
                if (panelMode == 1 && prevCharIsDigit()) { calcAppendOp("×"); } else commitText("×");
                return;
            case 43:               // +
                if (panelMode == 1 && prevCharIsDigit()) { calcAppendOp("+"); } else commitText("+");
                return;
            case 45:               // -
                if (panelMode == 1 && prevCharIsDigit()) { calcAppendOp("-"); } else commitText("-");
                return;
            case 42:               // *（数字面板计算）
                if (panelMode == 1 && prevCharIsDigit()) { calcAppendOp("*"); } else commitText("*");
                return;
            case 47:               // /（数字面板计算）
                if (panelMode == 1 && prevCharIsDigit()) { calcAppendOp("/"); } else commitText("/");
                return;
            case 64:               // @（数字面板邮箱直上屏）
                commitText("@");
                return;
            case KEY_UNDO:         // v0.4.9 取消↺
                doUndo();
                return;
            case KEY_REDO:         // v0.4.9 重做↻
                doRedo();
                return;
            case KEY_SEARCH:       // v0.5.0 搜索键（数字面板）：有表达式→带式上屏；否则执行搜索动作
                if (panelMode == 1 && !calcBuffer.isEmpty()) {
                    commitCalc(true);
                    return;
                }
                InputConnection sic = getCurrentInputConnection();
                if (sic != null) {
                    try { sic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); } catch (Exception ignored) { }
                }
                return;
            case KB_DELETE:
            case KeyEvent.KEYCODE_DEL:
                handleBackspace();
                return;
            case KB_ENTER:
            case KeyEvent.KEYCODE_ENTER:
                commitFirstCandidate();
                return;
            case 44:
                if (panelMode == 1) { calcBuffer += ","; updateCandidateView(); return; }
                commitText(chineseMode ? "，" : ",");
                return;
            case 46:
                // v0.5.54：小数点直接上屏并累积到 lastCalcInput（防 calcBuffer 残留 "." 导致 1.6 拆散错乱）
                if (panelMode == 1) { commitText("."); lastCalcInput += "."; updateCandidateView(); return; }
                commitText(chineseMode ? "。" : ".");
                return;
            case 0xFF01:   // v0.5.17 修复回归：！，键上滑 → ！（CloudKeyboardView.swipeSymbol 直达）
                commitText("！");
                // v0.5.47 反馈①：上滑输入后清空输入状态（候选/编码不残留——"上滑不消失"根治）
                composingCode.setLength(0);
                candidates.clear();
                updateCandidateView();
                return;
            case 0xFF1F:   // v0.5.17 修复回归：？。键上滑 → ？
                commitText("？");
                composingCode.setLength(0);
                candidates.clear();
                updateCandidateView();
                return;
            case 0x3001:   // v0.5.70 反馈：M 键上滑 → 顿号、
                commitText("、");
                composingCode.setLength(0);
                candidates.clear();
                updateCandidateView();
                return;
            default:
                // v0.5.24 修复②：符号键兜底——symbolToText 未覆盖的 ASCII 可见字符直接上屏
                //   （91[]/93]/123{/125}/35#/37%/94^/61= 等特殊字符，密码框与常规模式通用）
                String s = symbolToText(primaryCode);
                if (s != null) {
                    commitText(s);
                } else if (primaryCode >= 32 && primaryCode <= 126) {
                    commitText(String.valueOf((char) primaryCode));
                }
        }
    }

    private int lastPressCode = 0;   // v0.5.1 上滑输出（！/？）

    @Override
    public void onPress(int primaryCode) {
        lastPressCode = primaryCode;
    }

    @Override
    public void onRelease(int primaryCode) { }

    @Override
    public void onText(CharSequence text) { }

    @Override
    public void swipeLeft() { }

    @Override
    public void swipeRight() { }

    @Override
    public void swipeDown() { }

    @Override
    public void swipeUp() {
        // v0.5.1 反馈④：！，键上滑→！，？。键上滑→？
        if (lastPressCode == KEY_PUNCT_BANG) {
            commitText("！");
            lastPressCode = 0;
        } else if (lastPressCode == KEY_PUNCT_QM) {
            commitText("？");
            lastPressCode = 0;
        }
    }

    // ===== 物理键盘支持 =====

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int c = event.getUnicodeChar();
        if (c >= 'a' && c <= 'y' && chineseMode) {
            appendCode((char) c);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                handleBackspace();
                return true;
            case KeyEvent.KEYCODE_ENTER:
                commitFirstCandidate();
                return true;
            case KeyEvent.KEYCODE_SPACE:
                commitFirstCandidate();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===== 核心逻辑 =====

    private void toggleLang() {
        fromChineseShift = false;   // v0.5.10：手动中英键切换非 shift 切入
        chineseMode = !chineseMode;
        if (!chineseMode) {
            composingCode.setLength(0);
            candidates.clear();
            shiftState = 0;
            lastCommittedChar = "";
            lastCommittedText = "";
            lastEnHint = "";
            associateActive = false;
            candPage = 0;
        }
        applyLetterCase();
        applyLangLabels();
        updateCandidateView();
    }

    private void appendCode(char c) {
        // v0.5.35 反馈⑥：放宽到 8 码支持拼音全拼混打（五笔查询自动截前4，>4 码走拼音分支）
        if (composingCode.length() >= 8) return;
        associateActive = false;   // v0.4.9 开始新编码 → 联想链断开
        pendingComma = null;       // v0.6.6 新打字 → 逗号补全候选失效
        candPage = 0;
        composingCode.append(c);
        queryCandidates();
    }

    /** v0.5.0 反馈⑤：英文输入进编码（放宽到 24 字符，触发补全候选）
     *  v0.5.16 反馈②：shift 大写生效——shiftState>0（大写锁定/中文切英文大写）时存大写，否则小写 */
    private void appendEnglishCode(char c) {
        if (composingCode.length() >= 24) return;
        pendingComma = null;       // v0.6.6 新打字 → 逗号补全候选失效
        candPage = 0;
        boolean upper = shiftState > 0;
        composingCode.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
        queryCandidates();
    }

    private void handleBackspace() {
        // v0.4.8：数字面板优先删计算表达式（v0.5.58 走 CalcEngine）
        if (panelMode == 1 && !calcEngine.calcBuffer.isEmpty()) {
            calcEngine.calcBuffer = calcEngine.calcBuffer.substring(0, calcEngine.calcBuffer.length() - 1);
            syncCalcState();
            updateCandidateView();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        // v0.5.11 反馈⑥：文本框有选区（全选/部分选中）→ 优先删除选区（而非备选栏编码）
        boolean hasSelection = false;
        if (ic != null) {
            try {
                CharSequence sel = ic.getSelectedText(0);
                if (sel != null && sel.length() > 0) hasSelection = true;
            } catch (Exception ignored) { }
        }
        if (hasSelection) {
            pushUndo();
            // v0.5.58：CalcEngine.onBackspace 统一清计算续算基准（选区删除清 lastCalcResult/lastCalcInput）
            CalcEngine.Action ca = calcEngine.onBackspace(panelMode == 1, true);
            syncCalcState();
            if (ca.commit != null) ic.commitText(ca.commit, 0);
            return;
        }
        if (composingCode.length() > 0) {
            composingCode.deleteCharAt(composingCode.length() - 1);
            queryCandidates();
        } else {
            if (ic == null) return;
            // v0.4.8 反馈②：全选/部分选中时删除整个选区（此分支现仅兜底，选区已在上面优先处理）
            // v0.5.3 反馈①：删除刚上屏的字后允许重新输入（清空过滤键）
            String prev = getCursorPrevChar();
            if (!prev.isEmpty() && prev.equals(committedLast)) committedLast = "";
            pushUndo();   // v0.4.9 取消↺ 可恢复删除
            // v0.5.58：CalcEngine.onBackspace 统一清计算状态（删除上屏文本=放弃续算基准）
            CalcEngine.Action ca = calcEngine.onBackspace(panelMode == 1, false);
            syncCalcState();
            if (ca.delBefore > 0) ic.deleteSurroundingText(ca.delBefore, 0);
        }
    }

    /** 上屏（v0.4.5：不写入剪贴板历史；v0.4.9：自动记录 undo 快照） */
    private void commitText(String s) {
        pushUndo();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            // v0.5.5 反馈①：先结束组合态再提交（密码框/WebView 兼容，避免吞字）
            try { ic.finishComposingText(); } catch (Exception ignored) { }
            ic.commitText(s, 1);
        }
        maybeTriggerComma(s);   // v0.6.6 上屏含中文逗号 → 联想下半句/下半段
    }

    // ===== v0.5.14 反馈②：端侧词频缓存（最近 3 个月输入记录，联想排序权重） =====

    private void loadFreq() {
        String j = prefs.getString(PREFS_FREQ, "");
        if (j.isEmpty()) return;
        try {
            for (String kv : j.split(",")) {
                int i = kv.indexOf(':');
                if (i > 0) freqMap.put(kv.substring(0, i), Integer.parseInt(kv.substring(i + 1)));
            }
        } catch (Exception ignored) { }
    }

    private void saveFreq() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : freqMap.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        prefs.edit().putString(PREFS_FREQ, sb.toString()).apply();
    }

    private void bumpFreq(String text) {
        if (text == null || text.isEmpty()) return;
        freqMap.put(text, freqOf(text) + 1);
        saveFreq();
    }

    private int freqOf(String w) {
        Integer n = freqMap.get(w);
        return n == null ? 0 : n;
    }

    // ===== v0.4.9 取消↺ / 重做↻（编辑快照栈，双向 30 层） =====

    /** 记录当前文档快照（光标前 2000 字 + 分隔符 + 光标后 2000 字） */
    private String snapshotDoc() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        String b = "", a = "";
        try {
            CharSequence cb = ic.getTextBeforeCursor(2000, 0);
            CharSequence ca = ic.getTextAfterCursor(2000, 0);
            b = cb == null ? "" : cb.toString();
            a = ca == null ? "" : ca.toString();
        } catch (Exception ignored) { }
        return b + "\u0002" + a;
    }

    /** 任意文档变更前调用：当前快照入 undo 栈，清空 redo 栈 */
    private void pushUndo() {
        String snap = snapshotDoc();
        if (snap.isEmpty() || snap.equals("\u0002")) return;
        undoStack.push(snap);
        while (undoStack.size() > UNDO_MAX) undoStack.removeLast();
        redoStack.clear();
    }

    private void doUndo() {
        // v0.5.36 反馈④：剪贴板长按删除 → 点取消↺恢复还原
        if (clipUndoItem != null) {
            clipHistory.add(Math.min(clipUndoIndex, clipHistory.size()), clipUndoItem);
            saveClipHistory();
            clipUndoItem = null;
            clipUndoIndex = -1;
            if (clipMode) updateCandidateView();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || undoStack.isEmpty()) return;
        redoStack.push(snapshotDoc());
        while (redoStack.size() > UNDO_MAX) redoStack.removeLast();
        restoreDoc(undoStack.pop(), ic);
    }

    private void doRedo() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || redoStack.isEmpty()) return;
        undoStack.push(snapshotDoc());
        while (undoStack.size() > UNDO_MAX) undoStack.removeLast();
        restoreDoc(redoStack.pop(), ic);
    }

    /** 用快照整体恢复文档（先清光标两侧文本，再写入快照并定位光标） */
    private void restoreDoc(String snap, InputConnection ic) {
        int sep = snap.indexOf('\u0002');
        String before = sep < 0 ? "" : snap.substring(0, sep);
        String after = sep < 0 ? "" : snap.substring(sep + 1);
        String curB = "", curA = "";
        try {
            CharSequence cb = ic.getTextBeforeCursor(2000, 0);
            CharSequence ca = ic.getTextAfterCursor(2000, 0);
            curB = cb == null ? "" : cb.toString();
            curA = ca == null ? "" : ca.toString();
        } catch (Exception ignored) { }
        try {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(curB.length(), curA.length());
            ic.commitText(before + after, 1);
            ic.setSelection(before.length(), before.length());
            ic.endBatchEdit();
        } catch (Exception ignored) { }
    }

    /** 码点 -> 上屏文本（中文标点映射全角） */
    private String symbolToText(int code) {
        switch (code) {
            case 44: return "，";
            case 46: return "。";
            case 63: return "？";
            case 33: return "！";
            case 58: return "：";
            case 59: return "；";
            case 40: return "（";
            case 41: return "）";
            case 183: return "·";
            case 8226: return "·";
            case 34: return "\"";
            case 39: return "'";
            case 45: return "-";
            case 47: return "/";
            case 64: return "@";
            case 35: return "#";
            case 95: return "_";
            case 38: return "&";
            case 42: return "*";
            case 37: return "%";
            case 43: return "+";
            case 61: return "=";
            case 91: return "[";
            case 93: return "]";
            default:
                // v0.5.34 支持 emoji 码点（>0xFFFF 用 toChars 双字符）
                if (code >= 33 && code <= 0x10FFFF) {
                    try {
                        return String.valueOf(Character.toChars(code));
                    } catch (Exception e) {
                        return null;
                    }
                }
                return null;
        }
    }

    /** 候选查询：v0.5.0 反馈②排序（①上次上屏+热点词组 ②传统五笔 ③预测/云端）+ 反馈⑤英文补全 */
    private void queryCandidates() {
        candidates.clear();
        String code = composingCode.toString();
        if (code.isEmpty()) {
            updateCandidateView();
            return;
        }
        // v0.5.0 反馈⑤：英文输入态 → 补全候选（@邮箱 / ht→https:// / .com 等）
        if (!chineseMode) {
            checkEnglishCompletion(code);
            if (candidates.isEmpty()) candidates.add(code);
            updateCandidateView();
            return;
        }
        // v0.5.35 反馈⑥：超 4 码 = 拼音全拼模式——五笔仅用前 4 码查词组，拼音候选云端异步置前
        if (code.length() > 4) {
            String wb = code.substring(0, 4);
            List<String> pri = WubiDb.query(wb);
            if (pri != null) {
                for (String c2 : pri) {
                    if (c2.length() < 2) continue;
                    if (!isJustCommitted(c2) && !candidates.contains(c2)) candidates.add(c2);
                }
            }
            updateCandidateView();
            queryPinyin(code, true);
            return;
        }
        List<String> merged = new ArrayList<>();
        // v0.5.3 反馈①：只滤"最近一次上屏的同一字/词"（避免重复显示）；MRU 词组保留置顶
        // v0.5.4 反馈②：上次选中的字/词置顶须"编码匹配当前输入"（避免错位霸榜挡住四码词组）
        // v0.5.8 反馈③⑧：排序硬规则——1 码一级简码字最前、4 码词组最前；2/3 码 MRU 置顶
        // v0.5.9 反馈⑧：MRU 精确匹配（编码==code）置顶最优先（字频调整：上次上屏的字/词永远第一位）
        // v0.5.17 反馈④（举一反三）：MRU 置顶不受 isJustCommitted 限制——重打同码时"最近打过的字"必须置顶
        // v0.5.50 写死规则：之前打过的字或词全部前置（新上屏第一，旧的按序在后）——
        //   遍历 mruList（最近 N 个上屏，时间倒序），编码匹配（全码/简码）的依次加入
        if (!lastSelected.isEmpty()) {
            String lc = lastSelected.length() >= 2 ? WubiDb.phraseCode(lastSelected) : WubiDb.singleCode(lastSelected);
            boolean mruMatch = (lc != null && lc.equals(code));
            if (!mruMatch && lastSelected.length() == 1 && code.length() == 1) {
                String s1 = WubiDb.simple1Char(code.charAt(0));
                mruMatch = (s1 != null && s1.equals(lastSelected));
            }
            if (mruMatch && !merged.contains(lastSelected)) merged.add(lastSelected);
        }
        for (String m : mruList) {
            if (m.equals(lastSelected)) continue;   // lastSelected 已置顶
            String mc = m.length() >= 2 ? WubiDb.phraseCode(m) : WubiDb.singleCode(m);
            boolean m2 = (mc != null && mc.equals(code));
            if (!m2 && m.length() == 1 && code.length() == 1) {
                String s1 = WubiDb.simple1Char(code.charAt(0));
                m2 = (s1 != null && s1.equals(m));
            }
            if (m2 && !merged.contains(m)) merged.add(m);
        }
        for (String p : recentPhrases) {
            String pc = WubiDb.phraseCode(p);
            if (pc != null && pc.equals(code) && !merged.contains(p)) merged.add(p);
        }
        if (code.length() == 1 || code.length() == 3 || code.length() == 4) {
            List<String> pri = WubiDb.query(code);
            if (pri != null) {
                for (String c : pri) {
                    // v0.5.37 反馈②：4 码单字不再过滤（WubiDb.query(4) 顺序=词组先单字后，词组仍优先）
                    if (!isJustCommitted(c) && !merged.contains(c)) merged.add(c);
                }
            }
        }
        // ① MRU：上次选中的字/词（编码前缀匹配，v0.5.44 反馈⑤：1 码只精确匹配——打 r 不提示"白"rrrr）+ 最近上屏词组
        if (!lastSelected.isEmpty() && code.length() >= 2) {
            String lc = lastSelected.length() >= 2 ? WubiDb.phraseCode(lastSelected) : WubiDb.singleCode(lastSelected);
            if (lc != null && lc.startsWith(code) && !lc.equals(code) && !merged.contains(lastSelected)) merged.add(lastSelected);
        }
        for (String p : recentPhrases) {
            String pc = WubiDb.phraseCode(p);
            if (pc != null && pc.startsWith(code) && !pc.equals(code) && !merged.contains(p)) merged.add(p);
        }
        // v0.5.23 动态拼词（钟总核心思路）：基础库+86规则 → 4 码词组无限，
        //   保障词置顶 + 2+2/1+1+2/1+1+1+1 动态组合（词组在前、单字殿后）
        // v0.5.44 反馈④：禁用前端动态拼词（"战行/点行"类 2+2 噪声组合）
        //   云端真词库 64,935 条覆盖；动态拼词噪声大且非词典词——回归"先科学后先进"
        // v0.5.66 革命性创新——上下文编码锚定 CCA（Coding-Anchored Prediction）：
        //   把 triggerAssociate 的 ASSOC_LINK 意思衔接词（前进→方向/道路/号角）接入打字路径：
        //   用户上屏"前进"后敲 f（方向首码）→ 候选立即置顶"方向"——只敲 1 码就见到衔接词！
        //   原理：五笔编码确定性 + 语义衔接（拼音输入法无法将"正在敲的音节"与衔接词做
        //   确定性映射——这是云五笔独有、三 AI 联想方案均不具备的编码×上下文双锚定）
        String ccaChain = lastCommittedText;
        if (ccaChain != null && !ccaChain.trim().isEmpty() && !code.isEmpty()) {
            ccaChain = ccaChain.trim();
            java.util.List<String> links = getLinks(ccaChain);   // v0.6.3：内置+云端双表
            if (!links.isEmpty()) {
                for (String lk : links) {
                    String lc = WubiDb.phraseCode(lk);
                    if (lc == null) lc = WubiDb.singleCode(lk);   // 单字衔接词（饭/亏/你/了）回退单字码
                    if (lc != null && lc.startsWith(code) && !merged.contains(lk)) merged.add(lk);
                }
            }
            String anchor1 = ccaChain.substring(ccaChain.length() - 1);
            java.util.List<String> links2 = getLinks(anchor1);   // v0.6.3：内置+云端双表
            if (!links2.isEmpty()) {
                for (String lk : links2) {
                    String lc = WubiDb.phraseCode(lk);
                    if (lc == null) lc = WubiDb.singleCode(lk);
                    if (lc != null && lc.startsWith(code) && !merged.contains(lk)) merged.add(lk);
                }
            }
        }
        // ② 第二位：传统五笔（高频字/字根/一至四码简码词组，优先照顾老用户习惯）
        List<String> local = WubiDb.query(code);
        if (local != null) {
            for (String c : local) {
                if (isJustCommitted(c)) continue;
                if ((code.length() == 1 || code.length() == 4) && merged.contains(c)) continue;   // 已在优先段
                if (!merged.contains(c)) merged.add(c);
            }
        }
        // ③ 3码预测第4码高频词组
        if (code.length() == 3) {
            List<String> predict = WubiDb.queryPredict(code);
            if (predict != null) {
                for (String p : predict) {
                    if (isJustCommitted(p)) continue;
                    if (!merged.contains(p)) merged.add(p);
                }
            }
        }
        pinyinInsertPos = merged.size();   // v0.5.36 反馈⑨：拼音候选插到本地五笔候选之后
        candidates.addAll(merged);
        // v0.5.46 反馈③：1 码一级简码双保险——云端/本地任何回填后，简码字强制置顶（打 r=的/i=不/w=人/p=这）
        applySimple1Top(code);
        // v0.5.61 写死规则：本地查询收尾必调 MRU 置顶（上屏过的字/词最前）——
        //   旧版只在云端 hot 回填后调，本地查询路径漏调 + 动态词反查失败 → 用户实测"从未前移"
        applyMruTop(code);
        candPage = 0;
        if (candidates.isEmpty()) candidates.add(code);
        updateCandidateView();
        // ④ 云端热点词组：异步回填，插到 MRU 段之后、五笔之前
        if (GATEWAY_READY) {
            cloudInsertPos = merged.size();
            queryGatewayAsync(code);
        }
        // v0.5.35 反馈⑥：≤4 码也异步查拼音（如 nihao 前 4 码 niha 期间，候选追加"你好"类辅助）
        if (code.length() >= 2) queryPinyin(code, false);
    }

    /** v0.5.0 反馈⑤：英文/HTML 自动补全（输入 ht→https://、@→邮箱后缀、.→域名后缀） */
    // v0.5.41 反馈④：英文常用词表（真实常用英文单词，前缀匹配自动补全）
    //   体积优化：单长字符串 + split（省数组常量池开销，APK≤100KB 门禁）
    private static final String EN_WORDS_STR = "the and you hello world help home hope here have has how what when where who why which work with well will good great get go going come coming can could should would please thank thanks this that these those there their they them we our ours your yours my mine his her its not no yes ok okay sure sorry friend family love like life time today morning ; private static final String[] EN_WORDS = night a lot are welcome nice day see later problem at all is about much many me i take care luck best wishes happy birthday merry christmas congratulations keep never give up hard study everyday progress success health wealth happiness friends dream future peace";
    private static final String[] EN_WORDS = EN_WORDS_STR.split(" ");

    private void checkEnglishCompletion(String code) {
        String lower = code.toLowerCase();
        if (lower.startsWith("ht") && lower.length() <= 5) {
            candidates.add("https://");
            candidates.add("http://");
        } else if (code.endsWith("@")) {
            candidates.add("@qq.com");
            candidates.add("@163.com");
            candidates.add("@gmail.com");
            candidates.add("@outlook.com");
            candidates.add("@foxmail.com");
        } else if (code.endsWith(".")) {
            candidates.add(".com");
            candidates.add(".cn");
            candidates.add(".net");
            candidates.add(".org");
            candidates.add(".io");
        } else if (code.endsWith("/")) {
            candidates.add("www.");
        } else if (code.length() >= 2 && code.length() <= 6) {
            // v0.5.41 反馈④：英文常用词表前缀补全（备选栏显示，点击上屏）
            for (String w : EN_WORDS) {
                if (w.startsWith(lower) && !candidates.contains(w)) candidates.add(w);
            }
        }
    }

    /** 云端网关查询（异步：后台请求，主线程回填，不阻塞输入） */
    private void queryGatewayAsync(final String code) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            List<String> cloud = new ArrayList<>();
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                String body = "{\"code\":\"" + code + "\",\"phrase\":true}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        List<String> parsed = parseCandidates(sb.toString());
                        if (parsed != null) cloud.addAll(parsed);
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) { }
            final List<String> result = cloud;
            handler.post(() -> {
                // 仅当编码仍一致时回填，避免过期结果覆盖
                if (!code.equals(composingCode.toString())) return;
                // v0.5.0 反馈②：热点词组插到 MRU 段之后、传统五笔之前（排第二位的五笔不动）
                // v0.5.2 反馈⑥：云端回填同样过滤已上屏字/词
                // v0.5.6：整码(4码)且本地候选全是单字 → 云端词组置顶（四码词组优先全局生效，
                //        解决"常用词组打不出来"——云端 5.7 万词组全量兜底）
                int pos = Math.min(Math.max(cloudInsertPos, 0), candidates.size());
                // v0.5.43 反馈②：2/3 码词组优先——云端词组插到单字区前（MRU 后、单字前）
                if (code.length() < 4) {
                    int singleStart = candidates.size();
                    for (int i = 0; i < candidates.size(); i++) {
                        if (candidates.get(i).length() == 1) { singleStart = i; break; }
                    }
                    pos = singleStart;
                }
                if (code.length() == 4) {
                    boolean localHasPhrase = false;
                    for (String c : candidates) {
                        if (c.length() > 1) { localHasPhrase = true; break; }
                    }
                    if (!localHasPhrase) pos = 0;
                    // v0.5.44 反馈③：MRU 置顶最优先——云端词组不挤掉最近上屏字/词
                    if (!candidates.isEmpty() && candidates.get(0).equals(lastSelected)) pos = 1;
                }
                int inserted = 0;
                for (String s : result) {
                    if (isJustCommitted(s)) continue;
                    // v0.5.6 fix：整码置顶时只置顶词组（云端单字本地已全，置顶会挤占词组位）
                    if (pos == 0 && s.length() < 2) continue;
                    if (!candidates.contains(s)) {
                        candidates.add(pos + inserted, s);
                        inserted++;
                        if (inserted >= 8) break;
                    }
                }
                // v0.5.40 反馈③：云端高频单字（hot）插到本地单字区首位（词组/MRU 之后）
                //   ——"栏/送/钟"等高频字必靠前（打字排序按频率调整）
                int hotPos = candidates.size();
                for (int i = 0; i < candidates.size(); i++) {
                    if (candidates.get(i).length() == 1) { hotPos = i; break; }
                }
                boolean hotChanged = false;
                for (String h : cloudHot) {
                    if (isJustCommitted(h)) continue;
                    candidates.remove(h);
                    candidates.add(Math.min(hotPos, candidates.size()), h);
                    hotPos++;
                    hotChanged = true;
                }
                if (inserted > 0 || hotChanged) {
                    // v0.5.47 顽疾根治：hot 回填后重跑一级简码置顶——hot 异步覆盖此前双保险（如 r 查询 hot=[白,的] 把白插到的前）
                    // v0.5.49：hot 回填后同时重跑 MRU 置顶——上次上屏字（如"呢"=knx）不被云端 hot 挤下（截图 1.叫 2.绝 3.呢 → 呢 必须回第 1）
                    String cur = composingCode.toString();
                    applyMruTop(cur);
                    if (cur.length() == 1) applySimple1Top(cur);
                    updateCandidateView();
                }
            });
        });
        t.start();
    }

    /** v0.5.31 云端词库统计（词条数上报：点击"云五笔"弹窗显示分类词库规模） */
    private int cloudCatCount = 0;
    private int cloudCatWords = 0;
    private final List<String> cloudHot = new ArrayList<>();   // v0.5.40 反馈③：云端高频单字（回填时插到单字区首位）

    private List<String> parseCandidates(String json) {
        List<String> list = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(json);
            // v0.5.16 反馈①：词组（phrases）优先于单字（candidates）——四码"dgqe"的感触/三角不被截断
            JSONArray phrases = obj.optJSONArray("phrases");
            if (phrases != null) {
                for (int i = 0; i < phrases.length(); i++) {
                    String p = phrases.getString(i);
                    if (p != null && p.length() >= 2) list.add(p);
                }
            }
            // v0.5.17 反馈②③（举一反三）：动态构词（gen）排真词组之后——ilif 未收录时"渐法/水国法"等
            //   无意义组合不挡道；补词后"没办法"（lexicon）在 phrases 里优先返回
            // v0.5.31 词条数上报：云端分类词库规模（弹窗显示）
            if (obj.has("cat_count")) {
                cloudCatCount = obj.optInt("cat_count", 0);
                cloudCatWords = obj.optInt("cat_words", 0);
            }
            // v0.5.40 反馈③：hot 单独存 cloudHot（不混入 list），回填时插到本地单字区首位
            JSONArray hot = obj.optJSONArray("hot");
            cloudHot.clear();
            if (hot != null) {
                for (int i = 0; i < hot.length(); i++) {
                    String h = hot.getString(i);
                    if (h != null && h.length() == 1) cloudHot.add(h);
                }
            }
            JSONArray gen = obj.optJSONArray("gen");
            if (gen != null) {
                for (int i = 0; i < gen.length(); i++) {
                    String p = gen.getString(i);
                    if (p != null && p.length() >= 2) list.add(p);
                }
            }
            JSONArray cps = obj.optJSONArray("candidates");
            if (cps != null) {
                for (int i = 0; i < cps.length(); i++) {
                    int cp = cps.getInt(i);
                    if (cp > 0) list.add(new String(Character.toChars(cp)));
                }
            }
        } catch (Exception ignored) { }
        return list;
    }

    /** 候选条渲染：空闲态（云五笔 ▾ 剪贴板）/ 剪贴板历史 / 数字计算 / 候选列表 */
    /** v0.5.60：内容相同不重绘（消除联想上屏等场景屏幕闪动——setText 全量替换触发重绘闪烁） */
    private void safeSetText(CharSequence t) {
        if (candidateView == null) return;
        CharSequence cur = candidateView.getText();
        if (cur != null && t != null && cur.toString().equals(t.toString())) return;
        candidateView.setText(t == null ? "" : t);   // v0.5.64 修复闪退根因：原写 safeSetText(t) 递归自身 → StackOverflow
    }
    private void safeStatusText(CharSequence t) {
        if (statusInfo == null) return;
        CharSequence cur = statusInfo.getText();
        if (cur != null && t != null && cur.toString().equals(t.toString())) return;
        statusInfo.setText(t == null ? "" : t);   // v0.5.64 修复闪退根因：原写 safeStatusText(t) 递归自身
    }

    /** v0.5.62 备选栏状态规范化：每次渲染前强制复位（46dp 固定高/单行/18f 字号/1f 行距）
     *  根治"不打字与打字时备选栏高度不一致闪屏"：此前 renderInfoPanel 残留 16f+1.25f 行距、
     *  renderClipboardList 残留 80dp+10 行+1.3f 行距，正常态只恢复 MinHeight → 各路径退出后状态不一致 */
    private void resetCandidateStyle() {
        if (candidateView == null) return;
        candidateView.setMinHeight(candViewH);
        candidateView.setMaxHeight(candViewH);
        candidateView.setMaxLines(1);
        candidateView.setSingleLine(true);
        candidateView.setTextSize(18);
        candidateView.setLineSpacing(0f, 1f);
        candidateView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        candidateView.setGravity(android.view.Gravity.CENTER_VERTICAL);   // v0.5.70：正常态文本垂直居中
    }

    private void updateCandidateView() {
        if (candidateView == null) return;
        resetCandidateStyle();   // v0.5.62：状态规范化——所有分支渲染前统一复位
        // v0.5.13 反馈①：app 信息面板优先渲染
        if (infoPanelMode) {
            renderInfoPanel();
            return;
        }
        // v0.5.11 反馈⑥：剪贴板态优先渲染（英文输入态也可用剪贴板，原英文分支提前 return 导致不可用）
        if (clipMode) {
            renderClipboardList();
            return;
        }
        // v0.5.34 反馈①：数字面板实时计算——候选条显示"带式"和"仅结果"两个候选，点选上屏
        if (panelMode == 1 && !calcBuffer.isEmpty()) {
            renderCalcCandidates();
            return;
        }
        // v0.5.14 反馈⑤：恢复正常态固定单行高度（剪贴板态放宽后恢复，防画面抖动）
        candidateView.setMaxLines(1);
        candidateView.setMinHeight(candViewH);
        candidateView.setMaxHeight(candViewH);
        // v0.5.8 反馈⑨：英文模式——候选条渲染字母串 + 自动补全建议（原只显示 EN，用户看不到输入导致"打不上字"）
        if (!chineseMode) {
            String ec = composingCode.toString();
            if (statusInfo != null) safeStatusText(ec);
            if (!candidates.isEmpty() || !ec.isEmpty()) {
                int tColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
                int eColor = dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT;
                SpannableStringBuilder esb = new SpannableStringBuilder();
                int es0 = esb.length();
                esb.append(ec).append("  ");
                esb.setSpan(new ForegroundColorSpan(eColor), es0, es0 + ec.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                for (int i = 0; i < candidates.size(); i++) {
                    String c = candidates.get(i);
                    int s = esb.length();
                    esb.append(String.valueOf(i + 1)).append(".").append(c).append("  ");
                    int e = esb.length();
                    final int idx = i;
                    ClickableSpan cs = new ClickableSpan() {
                        @Override
                        public void onClick(View widget) {
                            selectCandidate(idx);
                        }
                        @Override
                        public void updateDrawState(android.text.TextPaint ds) {
                            ds.setColor(tColor);
                            ds.setUnderlineText(false);
                        }
                    };
                    esb.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                candidateView.setSingleLine(true);   // v0.5.11 反馈②：英文候选单行不换行
                candidateView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                safeSetText(esb);
            } else {
                setHintText("EN");
                if (statusInfo != null) safeStatusText("");
            }
            return;
        }
        String code = composingCode.toString();
        // v0.5.8 反馈①：编码实时回显到状态栏（备选栏只显示候选）
        if (statusInfo != null) {
            // v0.5.40 反馈⑤：直接显示英语翻译（去掉 EN: 前缀）+ 限宽不挤占按钮
            String info = code;
            if (!lastEnHint.isEmpty()) info += "  " + lastEnHint;
            safeStatusText(info);
        }
        // v0.5.8 反馈①：数字面板计算式同步状态栏
        if (panelMode == 1 && statusInfo != null) {
            safeStatusText(calcBuffer.isEmpty() ? "123" : calcBuffer);
        }
        // v0.4.8 反馈③：非剪贴板状态恢复默认行距
        candidateView.setLineSpacing(0f, 1.0f);
        // v0.4.8 反馈⑧：数字面板计算状态实时显示（v0.5.1 候选条提供 带式/仅结果 两种上屏）
        if (panelMode == 1 && !calcBuffer.isEmpty()) {
            renderCalcState();
            return;
        }
        // v0.5.1：数字面板空缓冲提示
        if (panelMode == 1) {
            safeSetText("");   // v0.5.9 反馈④：去掉自以为是提示
            return;
        }
        if (clipMode) {
            renderClipboardList();
            return;
        }
        // v0.5.11 反馈②：候选/联想单行显示（不换行），英文翻译只显示在第一行状态栏
        if (code.isEmpty()) {
            // v0.5.11 反馈②：候选/联想单行显示（不换行），英文翻译只显示在第一行状态栏
            if (statusInfo != null) safeStatusText(lastEnHint.isEmpty() ? "" : lastEnHint);
            // v0.5.55：恢复上下文联想渲染——联想态且有候选时显示"最近上屏 ▸ 联想词"
            if (associateActive && !candidates.isEmpty()) {
                renderAssociateHint();
            } else {
                safeSetText("");
            }
            return;
        }
        renderCandidates();
    }

    /** v0.4.8 数字面板：实时显示 表达式=结果 */
    /** v0.5.1 反馈②：数字面板实时计算；v0.5.15 反馈③：去掉〔带式〕〔结果〕候选，上屏走 = 键（带式默认） */
    private void renderCalcState() {
        if (calcBuffer.isEmpty()) {
            safeSetText("");   // v0.5.9 反馈④：去掉自以为是提示
            return;
        }
        double v = calcEval(calcBuffer);
        String res = fmtResult(v);
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int s0 = sb.length();
        sb.append(calcBuffer);
        sb.setSpan(new ForegroundColorSpan(textColor), s0, s0 + calcBuffer.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(" = ").append(res);
        safeSetText(sb);
    }

    /** v0.4.8 反馈①⑦：上屏单字后的联想词组（MRU 置顶 + 本地锚字前缀词组 + 云端热点）
     *  v0.5.11 反馈②：英文翻译不在此显示（仅第一行状态栏），且单行不换行 */
    private void renderAssociateHint() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(lastCommittedText).append(" ▸ ");
        if (!candidates.isEmpty()) {
            int total = candidates.size();
            int pages = Math.max(1, (total + CAND_PAGE_SIZE - 1) / CAND_PAGE_SIZE);
            if (candPage >= pages) candPage = pages - 1;
            int from = candPage * CAND_PAGE_SIZE;
            int to = Math.min(total, from + CAND_PAGE_SIZE);
            for (int i = from; i < to; i++) {
                String c = candidates.get(i);
                int s = sb.length();
                sb.append(String.valueOf(i + 1)).append(".").append(c).append("  ");
                int e = sb.length();
                final int idx = i;
                ClickableSpan cs = new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        selectCandidate(idx);
                    }
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        ds.setColor(dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT);
                        ds.setUnderlineText(false);
                    }
                };
                sb.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            appendPager(sb, pages);
        } else {
            sb.append("（暂无联想，继续输入编码）");
        }
        safeSetText(sb);
    }

    /** v0.5.39 反馈①③：翻页按钮去掉——纯滑动翻页（左右/上下），指示改为"左右滑动查看"文本 */
    private void appendPager(SpannableStringBuilder sb, int pages) {
        if (pages <= 1) return;
        int pagerColor = dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT;
        int pStart = sb.length();
        // v0.5.55 反馈：去掉翻页提示"1/3·左右滑动查看"（翻页功能保留，仅不显示指示文本）
        // sb.append("  ").append(String.valueOf(candPage + 1)).append("/").append(String.valueOf(pages)).append(" · 左右滑动查看");
        // sb.setSpan(new ForegroundColorSpan(pagerColor), pStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private boolean dark() {
        return theme == THEME_DARK;
    }

    private void setHintText(String text) {
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new ForegroundColorSpan(dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        safeSetText(ss);
    }

    /** 剪贴板历史列表（仅复制文本，最新在前，最多 8 条显示；点击上屏）
     *  v0.5.3 反馈④：2 倍行距 + 灰色下横线分隔 + 跟随系统色
     *  v0.5.11 反馈②：剪贴板列表恢复多行（候选单行仅限候选/联想态） */
    private void renderClipboardList() {
        // v0.5.14 反馈⑤：剪贴板态放宽高度（多行列表），其他态固定单行
        int dp8 = Math.round(8 * getResources().getDisplayMetrics().density);
        candidateView.setMinHeight(dp8 * 10);
        candidateView.setMaxHeight(dp8 * 10);
        candidateView.setMaxLines(10);
        candidateView.setSingleLine(false);
        candidateView.setEllipsize(null);
        // v0.5.9 反馈⑨：适度行距（0,1.3f 非增大 extra）——条目间用浅色相间背景区分（非虚横线、非空行）
        candidateView.setLineSpacing(0f, 1.3f);
        candidateView.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);   // v0.5.70：剪贴板列表顶对齐
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        int altBg = dark() ? 0x2AFFFFFF : 0xFFF3F3F3;   // 相间浅色背景（跟随深浅色）
        StringBuilder sb = new StringBuilder();
        sb.append("◀ 返回   ");
        if (clipHistory.isEmpty()) {
            sb.append("（剪贴板暂无历史，复制的文本将自动记录）");
        } else {
            sb.append("剪贴板（").append(clipHistory.size()).append("）\n");
            int shown = Math.min(8, clipHistory.size());
            for (int i = 0; i < shown; i++) {
                String item = clipHistory.get(i);
                String line = item.replace('\n', ' ');
                if (line.length() > 18) line = line.substring(0, 18) + "…";
                sb.append("  ").append(i + 1).append("·").append(line).append("\n");
            }
        }
        SpannableStringBuilder css = new SpannableStringBuilder(sb.toString());
        ClickableSpan backCs = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                clipMode = false;
                updateCandidateView();
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(textColor);
                ds.setUnderlineText(false);
            }
        };
        css.setSpan(backCs, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int shown = Math.min(8, clipHistory.size());
        for (int i = 0; i < shown; i++) {
            final String item = clipHistory.get(i);
            String marker = "  " + (i + 1) + "·";
            int idx = sb.indexOf(marker, 8);
            if (idx < 0) continue;
            int start = idx + marker.length();
            int end = sb.indexOf("\n", start);
            if (end < 0) end = sb.length();
            ClickableSpan cs = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    commitText(item);   // v0.4.9 自动记录 undo
                    clipMode = false;   // v0.5.36 反馈②：点选剪贴板项后自动关闭（不再常驻）
                    updateCandidateView();
                    clipMode = false;
                    updateCandidateView();
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(textColor);
                    ds.setUnderlineText(false);
                }
            };
            css.setSpan(cs, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // v0.5.55 反馈：去掉行末"✕"删除链接（保留长按删除），避免误触
            final int fi = i;
            // v0.5.34 反馈⑧：ClipTagSpan 标记整项（长按定位删除）
            css.setSpan(new ClipTagSpan(fi), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // v0.5.9 反馈⑨：浅色相间背景（隔行着色）替代虚横线，视觉区分且不增加行高
            if (i % 2 == 1) {
                css.setSpan(new android.text.style.BackgroundColorSpan(altBg), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        safeSetText(css);
    }

    /** 候选列表：v0.5.3 反馈③⑦——纯候选（去杂项）；v0.5.4 反馈④：编码前缀实时显示（含删除时同步）
     *  v0.5.11 反馈②：单行不换行 */
    /** v0.5.35 反馈③：候选左右滑动翻页 */
    private void nextCandidatePage() {
        int pages = Math.max(1, (candidates.size() + CAND_PAGE_SIZE - 1) / CAND_PAGE_SIZE);
        if (candPage < pages - 1) { candPage++; updateCandidateView(); }
    }
    private void prevCandidatePage() {
        if (candPage > 0) { candPage--; updateCandidateView(); }
    }

    /** v0.5.35 反馈①：表达式是否已含运算符（决定数字是否进缓冲） */
    private boolean hasCalcOp(String expr) { return CalcEngine.hasCalcOp(expr); }

    /** v0.5.34 反馈①：数字面板实时计算候选（带式 / 仅结果，点选上屏，上屏后可续算）
     *  v0.5.35 反馈①：表达式不含运算符（纯数字直接上屏）时清空候选，不弹"1.5=5 2.5" */
    private void renderCalcCandidates() {
        if (calcBuffer.isEmpty() || !hasCalcOp(calcBuffer)) {
            safeSetText("");
            return;
        }
        double v = calcEval(calcBuffer);
        if (Double.isNaN(v)) {
            candidateView.setSingleLine(true);
            safeSetText(calcBuffer);
            return;
        }
        final String res = fmtResult(v);
        final String full = calcBuffer + "=" + res;
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        SpannableStringBuilder csb = new SpannableStringBuilder();
        int s0 = csb.length();
        csb.append("1.").append(full).append("   ");
        int e0 = csb.length();
        csb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                commitCalcAs(true);
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(textColor);
                ds.setUnderlineText(false);
            }
        }, s0, e0, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int s1 = csb.length();
        csb.append("2.").append(res).append("   ");
        int e1 = csb.length();
        csb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                commitCalcAs(false);
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(textColor);
                ds.setUnderlineText(false);
            }
        }, s1, e1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        candidateView.setSingleLine(true);
        safeSetText(csb);
    }

    /** v0.5.34 反馈①：计算候选上屏（withFormula=带式/仅结果），结果保留供运算符续算 */
    private void commitCalcAs(boolean withFormula) {
        if (calcEngine.calcBuffer.isEmpty()) return;
        // v0.5.58：统一走真实计算引擎（CalcEngine.onEq）
        boolean textEndsWithResult = false;
        if (!calcEngine.lastCalcResult.isEmpty()) {
            InputConnection cic = getCurrentInputConnection();
            try {
                CharSequence tb = cic == null ? null : cic.getTextBeforeCursor(calcEngine.lastCalcResult.length(), 0);
                if (tb != null && tb.toString().equals(calcEngine.lastCalcResult)) textEndsWithResult = true;
            } catch (Exception ignored) { }
        }
        calcEngine.calcFormula = withFormula;
        CalcEngine.Action ca = calcEngine.onEq(textEndsWithResult);
        if (ca.commit != null) commitText(ca.commit);
        syncCalcState();
        updateCandidateView();
    }

    private void renderCandidates() {
        candidateView.setSingleLine(true);
        candidateView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String code = composingCode.toString();
        int total = candidates.size();
        int pages = Math.max(1, (total + CAND_PAGE_SIZE - 1) / CAND_PAGE_SIZE);
        if (candPage >= pages) candPage = pages - 1;
        int from = candPage * CAND_PAGE_SIZE;
        int to = Math.min(total, from + CAND_PAGE_SIZE);
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        // v0.5.8 反馈①：编码实时回显已移至状态栏（statusInfo），备选栏仅显示候选
        for (int i = from; i < to; i++) {
            String c = candidates.get(i);
            int s = sb.length();
            sb.append(String.valueOf(i + 1)).append(".").append(c).append("  ");
            int e = sb.length();
            final int idx = i;
            ClickableSpan cs = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    selectCandidate(idx);
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(textColor);
                    ds.setUnderlineText(false);
                }
            };
            sb.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // v0.4.9 翻页指示（跟随系统色）
        appendPager(sb, pages);
        safeSetText(sb);
    }

    /** v0.4.8 反馈④：回车——无候选时上屏换行；v0.5.3 反馈⑥：单行文本框回车无反应（不换行不空格）
     *  v0.5.11 反馈④：按回车 → 上屏当前编码的小写英文（qq → qq），不再误选中文候选（多） */
    private void commitFirstCandidate() {
        // v0.5.35 反馈⑥：超 4 码（拼音全拼模式）回车上屏首选候选（nihao → 你好）
        if (composingCode.length() > 4 && !candidates.isEmpty()) {
            selectCandidate(0);
            return;
        }
        if (composingCode.length() > 0) {
            String raw = composingCode.toString();
            composingCode.setLength(0);
            candidates.clear();
            candPage = 0;
            commitText(raw);
            updateCandidateView();
        } else if (isMultiline()) {
            commitText("\n");
        }
    }

    /** v0.5.3 反馈⑥：当前输入框是否多行（多行才允许回车换行） */
    private boolean isMultiline() {
        try {
            EditorInfo ei = getCurrentInputEditorInfo();
            return ei != null && (ei.inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** v0.5.11 反馈④：空格——备选栏第一个是中文候选则上中文；候选仅有编码本身（无中文命中）则输出空格 */
    private void commitSpaceOrFirst() {
        if (!chineseMode) {
            // v0.5.40 反馈⑥：纯英文输入——全部输完才上屏：空格直接上屏空格（不选候选），单词由字母自然组成
            commitText(" ");
            composingCode.setLength(0);
            candidates.clear();
            updateCandidateView();
            return;
        }
        if (composingCode.length() > 0 && !candidates.isEmpty()
                && !candidates.get(0).equals(composingCode.toString())) {
            selectCandidate(0);
        } else {
            commitText(" ");
        }
    }

    /** v0.4.8 数字面板：= 或退出时上屏。带公式（默认）上屏 "1+2=3"；纯数字直接上屏 */
    /** v0.5.3 反馈②：数字面板运算符——上次结果上屏后接运算符自动续算（8 → +2 → 8+2=10） */
    private void calcAppendOp(String op) {
        // v0.5.58：统一走真实计算引擎（CalcEngine.onOp——含 lastCalcInput 优先 + 删除已上屏数字）
        String savedInput = calcEngine.lastCalcInput;
        CalcEngine.Action ca = calcEngine.onOp(op);
        if (ca.delBefore > 0) {
            InputConnection cic = getCurrentInputConnection();
            try {
                if (cic != null) {
                    cic.deleteSurroundingText(ca.delBefore, 0);
                    // v0.5.56：删除后验证——若仍残留（编辑器时序/兼容差异），重删一次防"22*3"式重复累积
                    try {
                        CharSequence tb = cic.getTextBeforeCursor(ca.delBefore, 0);
                        if (tb != null && tb.toString().equals(savedInput)) {
                            cic.deleteSurroundingText(ca.delBefore, 0);
                        }
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
        }
        syncCalcState();
        updateCandidateView();
    }

    /** v0.5.27 反馈②：读取系统剪贴板文本（复制的文本→备选栏点选上屏） */
    private String getClipboardText() {
        if (clipManager == null || !clipManager.hasPrimaryClip()) return "";
        try {
            android.content.ClipData cd = clipManager.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "";
            CharSequence t = cd.getItemAt(0).coerceToText(this);
            if (t == null) return "";
            String s = t.toString().trim();
            return s.isEmpty() ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private void commitCalc(boolean fromEq) {
        if (calcEngine.calcBuffer.isEmpty()) return;
        // 读文本框末尾判断"上次结果是否已在屏"（CalcEngine.onEq 去重依据）
        boolean textEndsWithResult = false;
        if (!calcEngine.lastCalcResult.isEmpty()) {
            InputConnection cic = getCurrentInputConnection();
            try {
                CharSequence tb = cic == null ? null : cic.getTextBeforeCursor(calcEngine.lastCalcResult.length(), 0);
                if (tb != null && tb.toString().equals(calcEngine.lastCalcResult)) textEndsWithResult = true;
            } catch (Exception ignored) { }
        }
        // v0.5.58：统一走真实计算引擎（CalcEngine.onEq——去重 + 带式上屏 + 状态更新）
        CalcEngine.Action ca = calcEngine.onEq(textEndsWithResult);
        if (ca.commit != null) commitText(ca.commit);
        syncCalcState();
        // v0.5.4 反馈⑥：结果上屏后保留数字面板——继续按运算符接续计算（2+3=5 → + → 5+）
        panelMode = 1;
        keyboardView.setKeyboard(keyboardNum);
        updateCandidateView();
    }

    private boolean associateActive = false;   // v0.4.9 联想态标记（用于连续联想链）
    private int cloudInsertPos = 0;            // v0.5.0 云端热点插入位（MRU 段后）
    /** v0.5.3 反馈①：最近一次上屏的字/词（过滤键，仅滤"紧接着重复出现"，不累积，避免高频字消失） */
    private String committedLast = "";

    /** v0.5.3 反馈①：候选是否为"刚上屏过"的同一内容（只滤最近一次，MRU/高频字不受影响） */
    /** v0.5.47：1 码一级简码强制置顶（本地/云端/hot 任何回填后调用——顽疾根治） */
    private void applySimple1Top(String code) {
        if (code == null || code.length() != 1 || candidates == null) return;
        String s1 = WubiDb.simple1Char(code.charAt(0));
        if (s1 == null) return;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).equals(s1)) {
                if (i != 0) {
                    candidates.remove(i);
                    candidates.add(0, s1);
                }
                return;
            }
        }
        // 简码字被过滤（isJustCommitted）——重新加回第一位
        candidates.add(0, s1);
    }

    /** v0.5.49/50 写死规则：MRU 置顶防云端覆盖——同码打过的字/词（新上屏第一、旧的按序）强制前移
     *  v0.5.61 委托 MruEngine（正向匹配：候选可见即置顶——本地/动态/云端词全命中，
     *  根治"上屏字词从未前移"：旧逻辑编码反查动态词返回 null 永不置顶） */
    private void applyMruTop(String code) {
        if (code == null || code.isEmpty()) return;
        MruEngine.applyTop(candidates, lastSelected, mruList);
    }

    /** v0.5.50 写死规则：记录同码 MRU 历史（新上屏排头，去重，最多 10 个，持久化） */
    private void rememberMru(String text) {
        if (text == null || text.isEmpty()) return;
        mruList.remove(text);
        mruList.add(0, text);
        while (mruList.size() > 10) mruList.remove(mruList.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mruList.size(); i++) {
            if (i > 0) sb.append("\u0001");
            sb.append(mruList.get(i));
        }
        try { prefs.edit().putString(PREFS_MRU, sb.toString()).apply(); } catch (Exception ignored) { }
    }

    private boolean isJustCommitted(String c) {
        return !committedLast.isEmpty() && c != null && c.equals(committedLast);
    }

    private void selectCandidate(int idx) {
        if (idx < 0 || idx >= candidates.size()) return;
        String text = candidates.get(idx);
        // v0.5.12 根治（用户已 5-6 次反馈）：前缀去重——候选以"刚上屏内容/上次结果"开头时，
        //   先删除光标前的旧内容再上屏（所见即所得）：
        //   · 上屏"宇"后点联想"宇宙" → 得"宇宙"（非"宇宇宙"）
        //   · 上屏"陈"后点"陈胜" → 得"陈胜"；再点"陈胜吴广" → 得"陈胜吴广"（联想链正常）
        //   · 计算 8*4=32 后点带式"32/16=2"（若走此路径）→ 得"/16=2"（非"32/16=2"重复）
        if (chineseMode) {
            String base = !committedLast.isEmpty() ? committedLast : lastCalcResult;
            if (!base.isEmpty() && base.length() < text.length() && text.startsWith(base)) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    try {
                        CharSequence before = ic.getTextBeforeCursor(base.length(), 0);
                        if (before != null && before.toString().equals(base)) {
                            pushUndo();
                            ic.deleteSurroundingText(base.length(), 0);
                        }
                    } catch (Exception ignored) { }
                }
            } else if (associateActive) {
                // v0.5.57 联想态通用重叠拼接：陈胜吴广 + 吴广起义 → 陈胜吴广起义（不出现"陈胜吴广吴广起义"）
                // v0.5.59：逻辑提取到 AssociateEngine（纯 Java 可测——测试对象=线上对象）
                // 直接读文本框末尾找最大重叠，不依赖 committedLast（防状态不一致导致删除失败）
                InputConnection cic = getCurrentInputConnection();
                if (cic != null) {
                    String tail = "";
                    try {
                        CharSequence tb = cic.getTextBeforeCursor(Math.min(text.length(), 10), 0);
                        if (tb != null) tail = tb.toString();
                    } catch (Exception ignored) { }
                    int overlap = AssociateEngine.overlapJoin(tail, text);
                    if (overlap > 0) {
                        try {
                            pushUndo();
                            cic.deleteSurroundingText(overlap, 0);
                        } catch (Exception ignored) { }
                    }
                }
            }
        }
        // v0.5.0 反馈⑤：英文补全——直接上屏补全串，不进五笔联想链
        if (!chineseMode) {
            commitText(text);
            composingCode.setLength(0);
            candPage = 0;
            updateCandidateView();
            return;
        }
        commitText(text);   // v0.4.9 自动记录 undo 快照
        lastSelected = text;   // MRU 置顶
        rememberMru(text);   // v0.5.50：同码 MRU 历史（写死规则）
        prefs.edit().putString(PREFS_LAST_SEL, text).apply();   // v0.5.9 反馈⑧：持久化字频调整
        bumpFreq(text);   // v0.5.14 反馈②：端侧词频累计（最近 3 个月输入记录）
        // v0.5.3 反馈①：记录最近一次上屏的字/词（仅滤紧接着的重复出现）
        committedLast = text;
        if (GATEWAY_READY) reportSelection(text);
        composingCode.setLength(0);
        candidates.clear();
        // v0.5.20（用户指令）：联想功能暂时全部去除——不拼接联想链、不触发字后/连续/云端联想
        lastCommittedText = text;
        if (text.length() >= 2) rememberPhrase(text);   // MRU（最近 3 词组）保留（记忆，非联想）
        lastEnHint = "";
        // v0.5.5 反馈①：密码框禁翻译（隐私）
        if (isPassword) {
            updateCandidateView();
            return;
        }
        String lastChar = lastCommittedText.isEmpty() ? "" : lastCommittedText.substring(lastCommittedText.length() - 1);
        if (!lastChar.isEmpty()) {
            queryTranslation(lastChar);   // 仅保留状态栏英文翻译（v0.5.13 要求，非联想）
        }
        // v0.5.55：恢复上下文联想（用户强化要求：光标前字/整词上下文联想——MRU置顶+整词前缀+锚字+成语+云端顺承）
        associateActive = true;
        triggerAssociate();
    }

    /** v0.4.9 反馈① + v0.5.1 反馈⑤：联想基准=整个上屏词组（如"前进"→"前进浪潮/前进号角"），
     *  不再按单字含字联想（避免"驶进/共进"式不合理联想）；MRU 置顶 + 整词前缀 + 锚字前缀 + 云端 */
    private void triggerAssociate() {
        // v0.5.0 反馈③：联想锚字取光标前一字（删除光标前字、移动光标后自动重新联想）
        String anchor = getCursorPrevChar();
        if (anchor.isEmpty()) {
            if (lastCommittedText.isEmpty()) return;
            anchor = lastCommittedText.substring(lastCommittedText.length() - 1);
        }
        String chain = lastCommittedText;   // v0.5.1：联想基准=整个已上屏词组（整词，非单字）
        List<String> merged = new ArrayList<>();
        // ① MRU：最近选中的词组（以整词开头 或 含整词）置顶
        for (String p : recentPhrases) {
            if (p.startsWith(chain) && !merged.contains(p)) merged.add(p);
        }
        // ②d v0.5.65 反馈②：意思衔接词表（前进→方向/道路/号角——"衔接"非"组词"，让人少打很多字）
        //   先整词精确衔接（前进→方向），再尾字衔接（进→一步/取/来）；排位=MRU 之后、词库前缀之前
        if (!chain.isEmpty()) {
            java.util.List<String> links = getLinks(chain);   // v0.6.3：内置+云端双表
            if (!links.isEmpty()) {
                for (String lk : links) {
                    if (!merged.contains(lk)) merged.add(lk);
                }
            }
        }
        if (merged.size() < 12 && !anchor.isEmpty()) {
            java.util.List<String> links2 = getLinks(anchor);   // v0.6.3：内置+云端双表
            if (!links2.isEmpty()) {
                for (String lk : links2) {
                    if (!merged.contains(lk)) merged.add(lk);
                    if (merged.size() >= 12) break;
                }
            }
        }
        // ② 整词前缀联想：本地词库以整词开头（前进→前进浪潮/前进号角）
        if (!chain.isEmpty()) {
            List<String> prefix = WubiDb.queryByPrefix(chain);
            if (prefix != null) {
                for (String p : prefix) if (!merged.contains(p)) merged.add(p);
            }
        }
        // ②b v0.5.65 移除：锚字前缀联想（进→进行/进一步/进入）是"组词"非"意思衔接"，
        //   用户明确"如果是组词的话那就完全错了"——以 ASSOC_LINK 衔接词表替代
        // ②c v0.5.9 反馈②：积极成语联想层（含锚字成语——阳光向上、有启发有感悟、眼前一亮）
        if (merged.size() < 12 && !anchor.isEmpty()) {
            List<String> idms = WubiDb.queryIdioms(anchor);
            for (String p : idms) {
                if (!merged.contains(p)) merged.add(p);
                if (merged.size() >= 12) break;
            }
        }
        // v0.5.14 反馈②：端侧词频排序（结合最近 3 个月输入记录——MRU 置顶保持，其余按上屏频次降序）
        List<String> ordered = new ArrayList<>();
        for (String p : recentPhrases) {
            if (merged.contains(p) && !ordered.contains(p)) ordered.add(p);
        }
        List<String> rest = new ArrayList<>();
        for (String p : merged) {
            if (!ordered.contains(p)) rest.add(p);
        }
        // 稳定降序：频次高者前（同频保持原顺序——本地词库序/成语序不被打乱）
        for (int i = 1; i < rest.size(); i++) {
            String k = rest.get(i);
            int j = i - 1;
            while (j >= 0 && freqOf(rest.get(j)) < freqOf(k)) {
                rest.set(j + 1, rest.get(j));
                j--;
            }
            rest.set(j + 1, k);
        }
        ordered.addAll(rest);
        candidates.clear();
        candidates.addAll(ordered);
        candPage = 0;
        updateCandidateView();
        // ③ 云端热点：上下文通道（整句上文→语境连续联想）+ 锚字前缀兜底
        cloudAssocInsert = -1;   // v0.5.67：打字路径云端回填走原逻辑（末尾）
        if (GATEWAY_READY) queryAssociateAsync(chain, anchor);
    }

    /** v0.5.0 反馈③：读取光标前一个字（联想锚字来源） */
    private String getCursorPrevChar() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        try {
            CharSequence cb = ic.getTextBeforeCursor(1, 0);
            if (cb != null && cb.length() > 0) return cb.toString();
        } catch (Exception ignored) { }
        return "";
    }

    /** v0.5.0 反馈③：光标移动/编辑后刷新联想（空闲态且中文模式） */
    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if (!chineseMode || composingCode.length() > 0 || !associateActive) return;
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            String prev = getCursorPrevChar();
            if (!prev.isEmpty()) {
                String lastCharOfChain = lastCommittedText.isEmpty()
                        ? "" : lastCommittedText.substring(lastCommittedText.length() - 1);
                if (!prev.equals(lastCharOfChain)) {
                    lastCommittedText = prev;   // 光标移到新字前 → 联想链重置为新锚字
                    lastEnHint = "";
                }
            }
            triggerAssociate();   // 以新光标前字重新联想
        }
    }

    /** v0.6 反馈①②：云端上下文连续联想（{"context":"光标前8字上文"} → 语境连续联想）
     *  革命性：不再单字联想，结合输入框上下文/前后文，参考主流输入法联想原理的云端实现 */
    private void queryAssociateAsync(final String chain, final String lastChar) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final String context = getContextBefore(8);   // 收集光标前 8 字整句上文
        Thread t = new Thread(() -> {
            List<String> cloud = new ArrayList<>();
            try {
                // ① 上下文通道（主）：整句上文 → 成语启发 + 热词趋势 + bigram 顺承 + 学习词
                if (context != null && !context.isEmpty()) {
                    List<String> p1 = postGateway("{\"context\":\"" + jsonEscape(context) + "\"}");
                    if (p1 != null) cloud.addAll(p1);
                }
                // ② 锚字前缀通道（兜底）：光标前 1 字前缀联想
                if (cloud.size() < 12) {
                    String query = lastChar == null || lastChar.isEmpty() ? chain : lastChar;
                    List<String> p2 = postGateway("{\"prefix\":\"" + jsonEscape(query) + "\"}");
                    if (p2 != null) {
                        for (String s : p2) {
                            if (!cloud.contains(s)) cloud.add(s);
                        }
                    }
                }
            } catch (Exception ignored) { }
            final List<String> result = cloud;
            handler.post(() -> {
                // v0.5.5 反馈⑦：联想基准=上屏链 或 进入输入状态的光标前字，两者一致才回填
                if (!chain.equals(lastCommittedText) && !chain.equals(enterAssociateChar)) return;
                boolean changed = false;
                int ins = cloudAssocInsert;
                for (String s : result) {
                    if (!candidates.contains(s)) {
                        // v0.5.67：联想路径插 MRU 后（语义衔接>字词搭配）；打字路径插末尾
                        if (ins >= 0 && ins <= candidates.size()) {
                            candidates.add(ins, s);
                            ins++;
                        } else {
                            candidates.add(s);
                        }
                        changed = true;
                    }
                }
                if (changed) updateCandidateView();
            });
        });
        t.start();
    }

    /** v0.6 反馈①：读取光标前 n 字（上下文连续联想输入） */
    private String getContextBefore(int n) {
        try {
            InputConnection ic = getCurrentInputConnection();
            CharSequence cb = ic == null ? null : ic.getTextBeforeCursor(n, 0);
            return cb == null ? "" : cb.toString();
        } catch (Exception e) { return ""; }
    }

    /** v0.6：JSON 字符串转义（上文可能含引号/反斜杠） */
    private String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** v0.6.3 CCA 联动增强：异步拉取云端全量衔接映射表（{"linkmap":true} → ngram_link 1981 条）
     *  云端规则实时生效免发版；失败降级为内置 ASSOC_LINK */
    private void fetchCloudLinks() {
        Thread t = new Thread(() -> {
            try {
                String body = "{\"linkmap\":true}";
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        JSONObject obj = new JSONObject(sb.toString());
                        JSONObject links = obj.optJSONObject("links");
                        if (links != null) {
                            java.util.Map<String, java.util.List<String>> map = new java.util.HashMap<>();
                            java.util.Iterator<String> keys = links.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                JSONArray arr = links.optJSONArray(k);
                                java.util.List<String> lst = new java.util.ArrayList<>();
                                if (arr != null) {
                                    for (int i = 0; i < arr.length(); i++) lst.add(arr.getString(i));
                                }
                                map.put(k, lst);
                            }
                            synchronized (CLOUD_LINKS) { CLOUD_LINKS = map; }
                        }
                    }
                }
            } catch (Exception ignored) { }
        });
        t.start();
    }

    /** v0.6.6 逗号补全联想：上屏中文逗号 → 光标前末句 → 云端查下半句 → 候选置顶
     *  数据源为公共知识（诗词/俗语/名言），非个人语料 */
    private void maybeTriggerComma(String s) {
        if (s == null || !s.contains("，")) return;
        new Thread(() -> {
            try {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                String txt = ic.getTextBeforeCursor(2000, 0).toString().replace("，", "").trim();
                if (txt.length() < 2 || txt.length() > 12) return;
                int cut = txt.lastIndexOf("。"), c2 = txt.lastIndexOf("！");
                if (c2 > cut) cut = c2;
                c2 = txt.lastIndexOf("？");
                if (c2 > cut) cut = c2;
                if (cut >= 0 && cut < txt.length() - 1) txt = txt.substring(cut + 1);
                if (txt.length() < 2 || txt.length() > 12) return;
                final String q = txt;
                List<String> rs = postGateway("{\"comma\":\"" + jsonEscape(q) + "\"}");
                if (rs == null || rs.isEmpty()) return;
                final List<String> result = rs;
                idleHandler.post(() -> {   // 复用既有主线程 Handler，省体积
                    pendingComma = result;
                    for (int i = result.size() - 1; i >= 0; i--) {
                        String w = result.get(i);
                        if (w != null && !candidates.contains(w)) candidates.add(0, w);
                    }
                    updateCandidateView();
                });
            } catch (Exception ignored) { }
        }).start();
    }

    /** 云端 POST 请求（返回 phrases 数组，失败返回 null） */
    private List<String> postGateway(String body) {
        List<String> cloud = new ArrayList<>();
        try {
            URL url = new URL(GATEWAY_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    JSONObject obj = new JSONObject(sb.toString());
                    JSONArray ps = obj.optJSONArray("phrases");
                    if (ps != null) {
                        for (int i = 0; i < ps.length(); i++) {
                            String p = ps.getString(i);
                            if (p != null && p.length() >= 2) cloud.add(p);
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Exception ignored) { }
        return cloud;
    }

    /** v0.4.8 反馈⑦ + v0.4.9：选字后云端英文翻译（{"word":"钟","en":true} → "bell; clock"） */
    /** v0.5.35 反馈⑥：云端拼音/简拼查询（异步；isLong>4 码时拼音候选插最前——回车即上屏拼音首选） */
    private void queryPinyin(final String code, final boolean isLong) {
        if (!GATEWAY_READY || code.length() < 2) return;
        final int codeAtStart = composingCode.length();
        Thread t = new Thread(() -> {
            final java.util.List<String> cloud = new java.util.ArrayList<>();
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                String body = "{\"py\":\"" + code + "\"}";
                try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        JSONArray ps = new JSONObject(sb.toString()).optJSONArray("phrases");
                        if (ps != null) {
                            for (int i = 0; i < ps.length(); i++) {
                                String p = ps.getString(i);
                                if (p != null && p.length() >= 2) cloud.add(p);
                            }
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) { }
            final boolean insertFront = isLong;
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> {
                if (composingCode.length() != codeAtStart) return;   // 输入已变化，丢弃过期结果
                if (cloud.isEmpty()) return;
                boolean changed = false;
                for (int i = cloud.size() - 1; i >= 0; i--) {
                    String p = cloud.get(i);
                    if (candidates.contains(p)) continue;
                    // v0.5.37 反馈①：拼音候选恢复尾部（不抢位置）；仅 >4 码拼音模式插最前
                    if (insertFront) candidates.add(0, p); else candidates.add(p);
                    changed = true;
                }
                if (changed) updateCandidateView();
            });
        });
        t.start();
    }

    private void queryTranslation(final String word) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            final String chainAtStart = lastCommittedText;
            String en2 = "";
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                String body = "{\"word\":\"" + word + "\",\"en\":true}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        en2 = new JSONObject(sb.toString()).optString("en", "");
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) { }
            final String hint = en2;
            handler.post(() -> {
                if (!chainAtStart.equals(lastCommittedText)) return;
                lastEnHint = hint;
                updateCandidateView();
            });
        });
        t.start();
    }

    /** 上报选词（云端 MRU 学习，尽力而为） */
    private void reportSelection(final String phrase) {
        if (!GATEWAY_READY) return;
        Thread t = new Thread(() -> {
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                String body = "{\"learn\":\"" + phrase + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) { }
        });
        t.start();
    }
}
