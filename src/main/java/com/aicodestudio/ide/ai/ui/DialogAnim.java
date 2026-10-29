package com.aicodestudio.ide.ai.ui;

import android.app.Dialog;
import android.view.Window;

import com.aicodestudio.ide.R;

/**
 * 弹窗动画统一入口
 *
 * <p>项目里弹窗分两类，动画挂法是一样的，所以收敛到这一个工具类：</p>
 * <ul>
 *   <li>{@link AppleDialog} —— Builder 风格的 AlertDialog 包装（32 处调用）</li>
 *   <li>自定义 Dialog 子类 —— 编译工具管理 / 编辑工具 / 系统设置 / 本地模型 等</li>
 * </ul>
 *
 * <p><b>为什么用 setWindowAnimations 而不是在 theme 里配：</b><br>
 * 现有 Dialog 都是 {@code new Dialog(context)} 无参构造，没有指定 theme。
 * 如果改用 theme 方式，要改 5 处构造函数，且 theme 会连带影响
 * 背景、标题栏等一堆默认属性，风险大。
 * {@code Window.setWindowAnimations()} 是 Window 级别的，一行搞定，
 * 且 Dialog 内部同样是 PhoneWindow，实测有效。</p>
 *
 * <p><b>用法：</b></p>
 * <pre>
 *   Dialog d = new Dialog(this);
 *   d.setContentView(root);
 *   DialogAnim.apply(d);      // ← 加这一行
 *   d.show();
 * </pre>
 *
 * <p><b>注意：</b>部分国产 ROM 会忽略 Dialog 的退出动画（只播进场不播退场），
 * 这是系统层行为，应用层无法强制。进场动画在所有 ROM 上都生效。</p>
 */
public final class DialogAnim {

    private DialogAnim() {
        // 工具类，禁止实例化
    }

    /**
     * 给弹窗套上统一的进出场动画。
     * 必须在 {@code show()} 之前调用。
     */
    public static void apply(Dialog d) {
        apply(d == null ? null : d.getWindow());
    }

    /**
     * 给 Window 套上统一的进出场动画。
     *
     * @param w 弹窗的 Window；传 null 直接返回，不抛异常
     */
    public static void apply(Window w) {
        if (w == null) return;
        try {
            w.setWindowAnimations(R.style.AppleDialogAnim);
        } catch (Throwable ignored) {
            // 个别 ROM 可能抛异常，动画失效不影响功能
        }
    }

    /**
     * 关闭弹窗（走统一退场动画）。
     *
     * <p>直接 {@code dismiss()} 也会播退场动画（由 windowExitAnimation 控制），
     * 这个方法只是加了 null 保护和异常兜底，推荐统一走它。</p>
     */
    public static void dismiss(Dialog d) {
        if (d == null) return;
        try {
            if (d.isShowing()) d.dismiss();
        } catch (Throwable ignored) {
        }
    }
}
