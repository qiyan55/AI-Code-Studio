package com.aicodestudio.ide.ai.ui;

/**
 * 全局配色与尺寸主题 —— 单一来源（Single Source of Truth）
 *
 * 重构前的问题：
 *   全项目散落着 178 处 Color.parseColor("#XXXXXX") 魔法值，
 *   其中 #E6EDF3 出现 28 次、#0D1117 出现 25 次、#484F58 出现 22 次；
 *   同时 BG_PRIMARY / BG_CARD 等常量在 BaseWorkspaceActivity、
 *   WelcomeActivity、AISettingsDialog 等多个文件里各自定义了一遍。
 *   想改一个主题色，要动几十个地方，极易漏改导致界面配色不一致。
 *
 * 重构后：
 *   所有颜色、圆角、间距统一在此定义，其它文件一律引用。
 *   改主题色只需改这一处。
 *
 * 命名规则：
 *   BG_*        背景
 *   TEXT_*      文字
 *   ACCENT_*    主题强调色（原始饱和度，用于大面积填充）
 *   TAB_*       图标专用（高饱和高亮度，深色底上描边图标才够清晰）
 *   STROKE_*    描边
 */
public final class Theme {

    private Theme() {}   // 纯常量类，不允许实例化

    // ==================== 背景 ====================

    /** 主背景（最底层） */
    public static final int BG_PRIMARY   = 0xFF0D1117;
    /** 次级背景（顶栏、底栏、输入框） */
    public static final int BG_SECONDARY = 0xFF161B22;
    /** 卡片背景（弹窗、列表项、分组容器） */
    public static final int BG_CARD      = 0xFF1C2333;
    /** 三级背景（次要按钮、选项底） */
    public static final int BG_TERTIARY  = 0xFF21262D;

    // ==================== 文字 ====================

    /** 主文字（标题、正文） */
    public static final int TEXT_PRIMARY   = 0xFFE6EDF3;
    /** 次要文字（副标题、说明） */
    public static final int TEXT_SECOND    = 0xFF8B949E;
    /** 弱化文字（提示、占位符、时间戳） */
    public static final int TEXT_TERTIARY  = 0xFF484F58;
    /** 反色文字（深底按钮上的白字） */
    public static final int TEXT_ON_ACCENT = 0xFFFFFFFF;

    // ==================== 描边 / 分隔线 ====================

    /** 常规描边、分隔线 */
    public static final int STROKE        = 0xFF30363D;
    /** 弱化描边 */
    public static final int STROKE_SOFT   = 0xFF21262D;

    // ==================== 主题强调色 ====================

    /** 蓝 —— 对话、AI、主操作 */
    public static final int ACCENT_BLUE   = 0xFF2196F3;
    /** 绿 —— 编辑器、成功、完成 */
    public static final int ACCENT_GREEN  = 0xFF2EA043;
    /** 橙 —— 编译运行、警告 */
    public static final int ACCENT_ORANGE = 0xFFF0883E;
    /** 红 —— 错误、危险操作 */
    public static final int ACCENT_RED    = 0xFFF85149;

    /**
     * 图标专用色。
     *
     * 与 ACCENT_* 的区别：描边图标在深色底上，
     * 仅"提亮度"会把颜色推向白色导致发粉发灰。
     * 所以这里控制 L 不要过高、同时把饱和度拉满，
     * 保持和欢迎页色条一致的鲜艳度。
     *   TAB_BLUE   #3D9BFF  S=100% L=62%
     *   TAB_GREEN  #26D962  S= 70% L=50%（纯绿 S=100 会荧光，取 70 更耐看）
     *   TAB_ORANGE #FF9238  S=100% L=61%
     */
    public static final int ICON_BAR    = 0xFFE6EDF3;  // 顶栏图标（中性灰白）
    public static final int ICON_IDLE   = 0xFFC9D1D9;  // 底部未选中
    public static final int TAB_BLUE    = 0xFF3D9BFF;  // 对话（鲜艳蓝）
    public static final int TAB_GREEN   = 0xFF26D962;  // 编辑器（鲜艳绿）
    public static final int TAB_ORANGE  = 0xFFFF9238;  // 运行（鲜艳橙）
    public static final int ICON_RED    = 0xFFFFA198;  // 红色图标（提亮档）

    /**
     * 欢迎页专用图标档位（比工作区暗一档）。
     *
     * 这是【有意保留】的差异，不是重复：
     * 用户曾明确要求"只提亮工作区三个界面的图标，欢迎页保持原样"。
     * 所以欢迎页用 ICON_BAR_SOFT(#C9D1D9)，工作区用 ICON_BAR(#E6EDF3)。
     * 重构时若误合并为一个常量，会让欢迎页图标跟着变亮，违背该需求。
     */
    public static final int ICON_BAR_SOFT  = 0xFFC9D1D9;  // 欢迎页顶栏
    public static final int ICON_IDLE_SOFT = 0xFF8B949E;  // 欢迎页辅助图标

    // ==================== 语义色（便于一眼看懂用途） ====================

    /** 成功 / 完成按钮底色 */
    public static final int OK_GREEN     = 0xFF26D962;
    /** 关闭 / 取消按钮描边与文字 */
    public static final int CLOSE_RED    = 0xFFFF7B72;
    /** 删除等危险操作的描边与文字 */
    public static final int DANGER_RED   = 0xFFF85149;
    /** 停用 / 禁用状态的文字 */
    public static final int DISABLED     = 0xFF484F58;

    // ==================== 特定场景配色 ====================

    /** 模型「已加载 / 可用」状态色（比 ACCENT_GREEN 亮，用于状态标签） */
    public static final int STATE_LOADED = 0xFF3FB950;
    /** Logo / 主按钮渐变起始蓝（与 #2196F3→#56B6C2 渐变配套） */
    public static final int LOGO_BLUE    = 0xFF1F6FEB;

    // ==================== 文件类型标识色 ====================
    //
    // 文件树按扩展名给图标配色（.java 青、.py 蓝、.gradle 绿…）。
    // 独立于上面的 UI 主题色，因为它们是"语义色"，
    // 改 UI 主题时不应连带改动文件类型的辨识配色。

    /** Java / Kotlin 源文件 */
    public static final int FILE_JAVA   = 0xFF56B6C2;
    /** Python 源文件 */
    public static final int FILE_PYTHON = 0xFF79C0FF;
    /** Gradle 构建脚本 */
    public static final int FILE_GRADLE = 0xFF98C379;
    /** XML / 资源文件 */
    public static final int FILE_XML    = 0xFFD2A8FF;

    // ==================== 代码语法高亮（GitHub Dark 配色） ====================
    //
    // 同样是独立配色组：代码高亮需要足够的色相差异来区分 token，
    // 与界面主题解耦，改 UI 配色时不应影响代码可读性。

    /** 关键字 */
    public static final int SYN_KEYWORD = 0xFFD2A8FF;
    /** 字符串 */
    public static final int SYN_STRING  = 0xFF98C379;
    /** 类型名 / 类名 */
    public static final int SYN_TYPE    = 0xFF79C0FF;
    /** 注释 */
    public static final int SYN_COMMENT = 0xFF484F58;
    /** 数字 */
    public static final int SYN_NUMBER  = 0xFFF0883E;
    /** 函数名 */
    public static final int SYN_FUNC    = 0xFF56B6C2;

    // ==================== 尺寸（单位 dp） ====================

    /** 弹窗圆角 */
    public static final float RADIUS_DIALOG = 14f;
    /** 卡片 / 按钮圆角 */
    public static final float RADIUS_CARD   = 8f;
    /** 输入框圆角 */
    public static final float RADIUS_INPUT  = 8f;
    /** 气泡圆角 */
    public static final float RADIUS_BUBBLE = 12f;

    /** 顶栏高度 */
    public static final int BAR_HEIGHT    = 50;
    /** 底部导航高度 */
    public static final int NAV_HEIGHT    = 54;
    /** 弹窗按钮高度 */
    public static final int BTN_HEIGHT    = 38;
    /** 主操作按钮高度（如"新增编译工具"） */
    public static final int BTN_LG_HEIGHT = 44;
    /** 行最小可点击高度（Material 建议 48，此处略收紧） */
    public static final int ROW_HEIGHT    = 46;

    // ==================== 弹窗尺寸 ====================
    //
    // 重构前：380dp 宽、72% 高的计算式在 SettingsDialog、
    // BuildToolManageDialog、EditBuildToolDialog 三个文件里各写了一遍，
    // 而 AppleDialog 里已有 MAX_W_DP 常量却没被复用。

    /** 弹窗最大宽度（dp）；实际取 min(此值, 屏宽 - DIALOG_MARGIN_DP) */
    public static final int DIALOG_MAX_W_DP    = 380;
    /** 弹窗左右留白（dp） */
    public static final int DIALOG_MARGIN_DP   = 36;
    /** 弹窗内容区最大高度占屏幕比例 */
    public static final float DIALOG_MAX_H_RATIO = 0.72f;

    // ==================== 气泡尺寸 ====================
    //
    // 0.78f（气泡最大宽度占屏比）曾在 AIChatActivity 与 ChatPanel 各写一遍。

    /** 用户/系统气泡最大宽度占屏幕比例（AI 气泡占满，不受此限） */
    public static final float BUBBLE_MAX_W_RATIO = 0.78f;

    // ==================== 工具方法 ====================

    /**
     * 取颜色的半透明版本，用于气泡等"淡底 + 描边"的场景。
     *
     * @param color   目标颜色（0xAARRGGBB）
     * @param alphaF  不透明度 0.0f ~ 1.0f
     */
    public static int alpha(int color, float alphaF) {
        int a = (int) (0xFF * Math.max(0f, Math.min(1f, alphaF))) & 0xFF;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /**
     * 把 #RRGGBB 或 #AARRGGBB 字符串解析为颜色整数。
     * 统一入口，便于将来集中处理解析异常。
     */
    public static int parse(String hex) {
        try {
            return android.graphics.Color.parseColor(hex);
        } catch (Throwable t) {
            return TEXT_PRIMARY;
        }
    }
}
