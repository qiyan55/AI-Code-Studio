package com.aicodestudio.ide.ai.compile;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 编译运行日志面板
 * 实现文档 4.4.1 编译运行面板功能：
 * - 实时滚动日志输出区域
 * - 日志分级渲染：普通日志(白色)、警告(黄色)、错误(红色)、成功(绿色)
 * - 日志缓存、一键清空日志
 * - 一键发送日志给 AI（文档 4.4.2-1）
 */
public class CompileLogPanel extends LinearLayout {

    private TextView logText;
    private ScrollView scrollView;
    private Button btnClear;
    private Button btnSendToAI;
    private Button btnCompile;
    private Button btnArtifacts;

    private SpannableStringBuilder logBuffer;
    private LogPanelListener listener;

    // 日志分级配色（文档 4.4.1）
    private static final int C_INFO    = 0xFFE6EDF3; // 普通-白
    private static final int C_WARN    = 0xFFF0883E; // 警告-黄
    private static final int C_ERROR   = 0xFFF85149; // 错误-红
    private static final int C_SUCCESS = 0xFF3FB950; // 成功-绿
    private static final int C_DEBUG   = 0xFF8B949E; // 调试-灰

    public interface LogPanelListener {
        void onSendLogToAI(String log);
        void onCompileClick();
        void onArtifactsClick();
    }

    public CompileLogPanel(Context ctx) {
        super(ctx);
        init(ctx);
    }

    public CompileLogPanel(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init(ctx);
    }

    private void init(Context ctx) {
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.BG_PRIMARY);

        // ===== 标题栏 =====
        LinearLayout header = new LinearLayout(ctx);
        header.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, dp(36)));
        header.setOrientation(HORIZONTAL);
        header.setBackgroundColor(Theme.BG_SECONDARY);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(8), 0);

        TextView title = new TextView(ctx);
        title.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        title.setText("编译运行日志");
        title.setTextColor(Theme.TEXT_PRIMARY);
        title.setTextSize(13);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        // 清空日志按钮（胶囊：黑底 + 红描边 + 红字）
        btnClear = makePillButton(ctx, "清空", "#F85149");
        btnClear.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { clearLog(); }
        });
        header.addView(btnClear);

        // 发送日志给 AI 按钮（文档 4.4.2-1，胶囊：黑底 + 蓝描边 + 蓝字）
        btnSendToAI = makePillButton(ctx, "发送AI", "#2196F3");
        btnSendToAI.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onSendLogToAI(getPlainLog());
            }
        });
        header.addView(btnSendToAI);

        // 编译/重新编译按钮（绿色胶囊，与清空/发送AI同款样式；编译中变红「终止」）
        btnCompile = makePillButton(ctx, "编译", "#2EA043");
        btnCompile.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onCompileClick();
            }
        });
        header.addView(btnCompile);

        // 产物按钮（紫色胶囊：查看已导出的 APK，删除等管理操作）
        btnArtifacts = makePillButton(ctx, "产物", "#A371F7");
        btnArtifacts.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onArtifactsClick();
            }
        });
        header.addView(btnArtifacts);

        addView(header);

        // ===== 日志区域 =====
        logBuffer = new SpannableStringBuilder();

        scrollView = new ScrollView(ctx);
        scrollView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        scrollView.setBackgroundColor(Theme.BG_PRIMARY);
        scrollView.setFillViewport(true);

        logText = new TextView(ctx);
        logText.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        logText.setTextColor(C_INFO);
        logText.setTextSize(11);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setPadding(dp(10), dp(8), dp(10), dp(8));
        logText.setTextIsSelectable(true);
        scrollView.addView(logText);

        addView(scrollView);

        appendLine("就绪。点击工具栏「编译运行」开始构建。", CompileExecutor.LOG_DEBUG);
    }

    /** 胶囊形小按钮：黑底 + 彩色描边 + 同色文字（圆角 = 高度一半） */
    private Button makePillButton(Context ctx, String text, String colorHex) {
        int h = 30;                    // 胶囊高度
        float r = h / 2f;              // 圆角 = 高度一半 → 标准胶囊
        int color = Color.parseColor(colorHex);

        Button b = new Button(ctx);
        LayoutParams lp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(h));
        lp.setMargins(0, 0, dp(6), 0);
        b.setLayoutParams(lp);

        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(Theme.BG_PRIMARY);   // 黑底
        g.setCornerRadius(dpF(r));
        g.setStroke(Math.max(1, dp(1)), color);    // 同色描边
        b.setBackgroundDrawable(g);

        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(color);
        b.setAllCaps(false);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(h));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    /** dp 转像素（浮点，圆角用） */
    private float dpF(float v) {
        // 统一委托 UiKit
        return UiKit.dpF(getContext(), v);
    }

    /**
     * 追加一行日志（分级渲染）
     */
    public void appendLine(String msg, int level) {
        if (msg == null) return;
        int color = getColorForLevel(level);
        int start = logBuffer.length();
        String line = msg + "\n";
        logBuffer.append(line);
        logBuffer.setSpan(new ForegroundColorSpan(color), start,
                start + line.length(),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        logText.setText(logBuffer);
        scrollToBottom();
    }

    /** 批量追加（用于加载历史日志） */
    public void appendLines(java.util.List<CompileExecutor.LogEntry> entries) {
        if (entries == null) return;
        for (CompileExecutor.LogEntry e : entries) {
            int color = getColorForLevel(e.level);
            int start = logBuffer.length();
            String line = e.message + "\n";
            logBuffer.append(line);
            logBuffer.setSpan(new ForegroundColorSpan(color), start,
                    start + line.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        logText.setText(logBuffer);
        scrollToBottom();
    }

    private int getColorForLevel(int level) {
        switch (level) {
            case CompileExecutor.LOG_WARN:    return C_WARN;
            case CompileExecutor.LOG_ERROR:   return C_ERROR;
            case CompileExecutor.LOG_SUCCESS: return C_SUCCESS;
            case CompileExecutor.LOG_DEBUG:   return C_DEBUG;
            case CompileExecutor.LOG_INFO:
            default:                          return C_INFO;
        }
    }

    /**
     * 清空日志（文档 4.4.1）
     */
    public void clearLog() {
        logBuffer.clear();
        logText.setText("");
    }

    /** 获取纯文本日志（用于发送给 AI） */
    public String getPlainLog() {
        return logBuffer == null ? "" : logBuffer.toString();
    }

    private void scrollToBottom() {
        scrollView.post(new Runnable() {
            @Override public void run() {
                scrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    public void setLogPanelListener(LogPanelListener l) { this.listener = l; }

    /** 编译状态切换：空闲「编译」(绿) / 编译中「终止」(红) */
    public void setCompiling(boolean compiling) {
        if (btnCompile == null) return;
        if (compiling) {
            btnCompile.setText("终止");
            btnCompile.setTextColor(Color.parseColor("#F85149"));
            android.graphics.drawable.GradientDrawable g =
                    (android.graphics.drawable.GradientDrawable) btnCompile.getBackground();
            if (g != null) g.setStroke(Math.max(1, dp(1)), Color.parseColor("#F85149"));
        } else {
            btnCompile.setText("编译");
            btnCompile.setTextColor(Color.parseColor("#2EA043"));
            android.graphics.drawable.GradientDrawable g =
                    (android.graphics.drawable.GradientDrawable) btnCompile.getBackground();
            if (g != null) g.setStroke(Math.max(1, dp(1)), Color.parseColor("#2EA043"));
        }
    }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(getContext(), v);
    }
}
