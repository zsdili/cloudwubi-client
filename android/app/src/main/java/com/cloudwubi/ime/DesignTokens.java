package com.cloudwubi.ime;

/**
 * DesignTokens - 云五笔全局设计规范（唯一配置源，v0.5.101）
 *
 * 铁律：颜色 / 布局 / 大小 / 字号 只允许在此处定义。
 * 任何组件不得另配数值，不得再出现散落硬编码（防加代码、防错乱、防不统一）。
 *
 * 规范摘要（对应 UI v12 效果图）：
 *  - 总高恒定 300dp：32 工具栏 + 28 备选栏 + 1 分隔线 + 239 键盘区（五面板切换零跳动）
 *  - 键面三色：白 #FFFFFF / 功能灰 #E8EAED / 选中浅灰蓝 #DCE1E7（零纯黑按钮）
 *  - 文字两档：主 #3A3F47 / 次 #9AA0A8
 *  - 圆角 8、间隙 3、左右留边 12、面板边距 8/6、4dp 网格
 */
public final class DesignTokens {

    private DesignTokens() { }

    // ========== 颜色（浅色主规范） ==========
    /** 全面板统一背景（键盘底板 / 工具栏 / 备选栏 / 面板） */
    public static final int BG = 0xFFF0F1F3;
    /** 普通键面 */
    public static final int KEY_NORMAL = 0xFFFFFFFF;
    /** 功能键面（⇧ ⌫ 123 中/英 空格 符 ← 等） */
    public static final int KEY_FUNC = 0xFFE8EAED;
    /** 选中 / 当前 / 回车 / 选中候选 */
    public static final int KEY_SELECT = 0xFFDCE1E7;
    /** 主文字（字母 / 数字 / 符号 / 功能键 / 候选字 / 图标） */
    public static final int TEXT_MAIN = 0xFF3A3F47;
    /** 次文字（字根标注 / 编码 / 次要信息 / 占位提示） */
    public static final int TEXT_SUB = 0xFF9AA0A8;
    /** 强调蓝（可点击链接 / 主题强调） */
    public static final int ACCENT = 0xFF1565C0;
    /** 首选候选橙（唯一保留的功能色：首选候选突出） */
    public static final int FIRST = 0xFFF59E0B;
    /** 错误红（计算错误 / 红点提醒 / 危险操作） */
    public static final int ERROR = 0xFFDC2626;
    /** 列表相间分隔浅色（剪贴板交替行） */
    public static final int LIST_ALT = 0xFFF7F8FA;

    // ========== 颜色（深色主题，仅系统深色时启用） ==========
    public static final int DARK_BG = 0xFF1B1F24;
    public static final int DARK_KEY_NORMAL = 0xFF1F2937;
    public static final int DARK_KEY_FUNC = 0xFF374151;
    public static final int DARK_KEY_SELECT = 0xFF4B5563;
    public static final int DARK_TEXT = 0xFFF3F4F6;
    public static final int DARK_TEXT_SUB = 0xFF6B7280;

    // ========== 布局 / 大小（唯一来源，dp 值） ==========
    /** 键盘总高（恒 300，五面板切换零跳动） */
    public static final int PANEL_HEIGHT_DP = 300;
    /** 工具栏行高 */
    public static final int TOOLBAR_HEIGHT_DP = 32;
    /** 备选栏行高（有字 / 无字恒同高） */
    public static final int CAND_BAR_HEIGHT_DP = 28;
    /** 工具栏与备选栏之间分隔线 1dp */
    public static final int DIVIDER_DP = 1;
    /** 主键盘键高 */
    public static final int KEY_HEIGHT_DP = 40;
    /** 键间隙 */
    public static final int KEY_GAP_DP = 3;
    /** 键圆角 */
    public static final int KEY_RADIUS_DP = 8;
    /** 左右留边 */
    public static final int MARGIN_DP = 12;
    /** 面板内边距 */
    public static final int PAD_DP = 8;
    /** 工具栏图标按钮尺寸 */
    public static final int TOOL_ICON_DP = 26;
    /** 工具栏图标线条（细线规范，随 density 换算） */
    public static final float TOOL_ICON_STROKE = 1.2f;

    // ========== 字号（唯一来源，dp 值） ==========
    /** 主键面字母 / 符号 */
    public static final int FONT_KEY_DP = 16;
    /** 数字面板数字 */
    public static final int FONT_NUM_DP = 18;
    /** 功能键 / 工具栏 / 面板 tab */
    public static final int FONT_FUNC_DP = 12;
    /** 字根标注 */
    public static final int FONT_RADICAL_DP = 8;
    /** 备选栏候选字 */
    public static final int FONT_CAND_DP = 14;
    /** 表情字号 */
    public static final int FONT_EMOJI_DP = 19;
    /** 剪贴板列表正文 */
    public static final int FONT_CLIP_DP = 13;
}
