package com.aicodestudio.ide.ai;

import com.aicodestudio.ide.ai.ui.UiKit;
import com.aicodestudio.ide.ai.ui.AISettingsDialog;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aicodestudio.ide.ai.compile.CompileExecutor;
import com.aicodestudio.ide.ai.compile.CompileLogPanel;
import com.aicodestudio.ide.ai.models.ModelManageDialog;
import com.aicodestudio.ide.ai.service.SettingsActivity;
import com.aicodestudio.ide.ai.tools.BuildToolManager;
import com.aicodestudio.ide.ai.tools.BuildToolManageDialog;

import java.io.File;

/**
 * 工作区 ③：编译运行界面
 *
 * 手机端独立编译工作区：
 *   顶部：返回主页 / 标题+项目名 / 编译工具 / 模型 / 设置
 *   中部上：编译控制区（大号绿色编译按钮 + 终止按钮 + 状态显示）
 *   中部下：编译日志面板（撑满剩余空间）
 */
public class RunActivity extends BaseWorkspaceActivity {

    // 编译入口已移至日志面板头部按钮区（清空 | 发送AI | 编译），
    // 不再使用中间圆形按钮，相关字段/常量已移除

    private TextView statusText;
    private CompileLogPanel logPanel;

    @Override
    protected int currentTab() { return TAB_RUN; }

    @Override
    protected View buildTopBar() {
        TopAction[] actions = new TopAction[]{
                new TopAction("ic_build", 0, new View.OnClickListener() {
                    @Override public void onClick(View v) { openToolManager(); }
                }),
                new TopAction("ic_chip", 0, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        new AISettingsDialog(RunActivity.this).show();
                    }
                }),
                new TopAction("ic_settings", 0, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        com.aicodestudio.ide.ai.ui.WorkspaceSettings
                                .showCompileSettings(RunActivity.this);
                    }
                }),
        };

        return makeTopBar("编译运行", state().getProjectName(),
                "ic_home", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        goHome();
                        overridePendingTransition(0, 0);
                    }
                }, actions);
    }

    @Override
    protected void buildContent(LinearLayout container) {
        // ===== 编译控制区 =====
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.VERTICAL);
        ctrl.setBackgroundColor(BG_SECONDARY);
        ctrl.setPadding(dp(16), dp(14), dp(16), dp(14));
        ctrl.setLayoutParams(fullWidth());
        container.addView(ctrl);

        // 工具链信息行
        TextView toolInfo = new TextView(this);
        toolInfo.setTextSize(11);
        toolInfo.setTextColor(TEXT_SECOND);
        toolInfo.setSingleLine(true);
        String toolName = resolveActiveToolName();
        toolInfo.setText("当前工具：" + toolName
                + "    项目类型：" + (state().getProjectType().length() > 0
                ? state().getProjectType() : "未识别"));
        ctrl.addView(toolInfo);

        ctrl.addView(gap(8));

        statusText = new TextView(this);
        statusText.setTextSize(11);
        statusText.setTextColor(TEXT_TERTIARY);
        statusText.setText("就绪");
        ctrl.addView(statusText);

        // ===== 日志区（撑满剩余空间）=====
        // 编译入口在日志面板头部按钮区（清空 | 发送AI | 编译），此处不再叠加圆形按钮
        logPanel = new CompileLogPanel(this);
        logPanel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        logPanel.setLogPanelListener(new CompileLogPanel.LogPanelListener() {
            @Override
            public void onSendLogToAI(String log) {
                // 把日志带到对话界面，让 AI 分析
                state().setSelectedCode(log);
                switchToTab(TAB_CHAT);
            }

            @Override
            public void onCompileClick() {
                // 空闲 → 编译；编译中 → 终止（按钮已切换为红色「终止」）
                if (CompileExecutor.getInstance().isCompiling()) {
                    stopCompile();
                } else {
                    startCompile();
                }
            }

            @Override
            public void onArtifactsClick() {
                com.aicodestudio.ide.ai.compile.ArtifactListDialog.show(RunActivity.this);
            }
        });
        container.addView(logPanel);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupCompileListener();
        refreshButtonState();
    }

    // ================= 编译 =================

    private String resolveActiveToolName() {
        try {
            BuildToolManager mgr = BuildToolManager.getInstance(this);

            // 已选中的工具
            String id = state().getActiveToolId();
            if (id.length() > 0) {
                BuildToolManager.BuildTool t = mgr.getToolById(id);
                if (t != null && t.enabled) return t.name;
            }

            // 按项目类型自动匹配一个可用工具
            String type = state().getProjectType();
            if (type.length() == 0) type = "custom";
            java.util.List<BuildToolManager.BuildTool> list = mgr.getToolsByType(type);
            if (list != null && list.size() > 0) {
                for (int i = 0; i < list.size(); i++) {
                    BuildToolManager.BuildTool t = list.get(i);
                    if (t != null && t.enabled) {
                        state().setActiveToolId(t.id);
                        return t.name;
                    }
                }
            }

            // 兜底：内置工具链（无需配置即可编译 Java/Android 源码）
            BuildToolManager.BuildTool builtin = mgr.getBuiltinTool();
            if (builtin != null) {
                state().setActiveToolId(builtin.id);
                return builtin.name;
            }
        } catch (Throwable t) {
            android.util.Log.e("RunActivity", "resolve tool failed", t);
        }
        return "未配置（点右上工具图标添加）";
    }

    private void openToolManager() {
        try {
            BuildToolManageDialog dlg = new BuildToolManageDialog(this);
            dlg.setListener(new BuildToolManageDialog.ToolDialogListener() {
                @Override
                public void onToolSelected(String toolId) {
                    state().setActiveToolId(toolId);
                    refreshToolInfo();
                }

                @Override
                public void onToolsChanged() {
                    refreshToolInfo();
                }

                @Override
                public void onAddTool() {
                    refreshToolInfo();
                }

                @Override
                public void onEditTool(BuildToolManager.BuildTool tool) {
                    refreshToolInfo();
                }
            });
            dlg.show();
        } catch (Throwable t) {
            android.util.Log.e("RunActivity", "open tool manager failed", t);
            toast("打开编译工具管理失败");
        }
    }

    private void refreshToolInfo() {
        // 重新构建内容区以刷新工具信息
        try {
            if (contentArea != null) {
                contentArea.removeAllViews();
                buildContent(contentArea);
            }
        } catch (Throwable t) {
            android.util.Log.e("RunActivity", "refresh failed", t);
        }
    }

    private void startCompile() {
        if (CompileExecutor.getInstance().isCompiling()) {
            toast("编译进行中，请稍候");
            return;
        }
        String proj = state().getProjectPath();
        if (proj.length() == 0) {
            toast("请先打开项目");
            return;
        }
        String toolId = state().getActiveToolId();
        if (toolId.length() == 0) {
            // 未配置工具时自动使用内置工具链
            try {
                BuildToolManager.BuildTool builtin =
                        BuildToolManager.getInstance(this).getBuiltinTool();
                if (builtin != null) {
                    toolId = builtin.id;
                    state().setActiveToolId(toolId);
                }
            } catch (Throwable t) {
                android.util.Log.e("RunActivity", "builtin tool resolve failed", t);
            }
            if (toolId.length() == 0) {
                toast("请先在编译工具中配置工具链");
                openToolManager();
                return;
            }
        }
        String mainFile = state().getCurrentFilePath();

        if (logPanel != null) logPanel.clearLog();
        setStatus("编译中…");
        refreshButtonState();

        CompileExecutor.getInstance()
                .startCompile(this, toolId, proj, mainFile);
    }

    private void stopCompile() {
        CompileExecutor.getInstance().stopCompile();
        setStatus("已终止");
        refreshButtonState();
    }

    private void setupCompileListener() {
        CompileExecutor.getInstance().setListener(new CompileExecutor.CompileListener() {
            @Override
            public void onCompileStart() {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setStatus("编译中…");
                        refreshButtonState();
                    }
                });
            }

            @Override
            public void onLogOutput(final CompileExecutor.LogEntry entry) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (logPanel != null) {
                            logPanel.appendLine(entry.message, entry.level);
                        }
                    }
                });
            }

            @Override
            public void onCompileSuccess(final String log) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setStatus("编译成功");
                        state().setLastCompileLog(log);
                        refreshButtonState();
                        // APK 已生成 → 弹选择框（导出并安装 / 直接安装 / 取消）
                        File apk = CompileExecutor.getInstance().getLastApkFile();
                        if (apk != null && apk.exists()) {
                            showCompileResultDialog(apk);
                        } else {
                            toast("编译成功");
                        }
                    }
                });
            }

            @Override
            public void onCompileFailed(final String log, int exitCode) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setStatus("编译失败 · 可点日志区「发给AI」");
                        state().setLastCompileLog(log);
                        refreshButtonState();
                    }
                });
            }

            @Override
            public void onCompileCancelled() {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setStatus("已取消");
                        refreshButtonState();
                    }
                });
            }
        });
    }

    private void setStatus(String s) {
        if (statusText != null) statusText.setText(s);
    }

    // ================= APK 产物：选择框 / 导出 / 安装 / 清理 =================

    /** 编译成功且 APK 已生成 → 统一风格选择框：导出并安装 / 直接安装 / 取消 */
    private void showCompileResultDialog(final File apk) {
        com.aicodestudio.ide.ai.compile.CompileResultDialog.show(
                this, apk.getName(), apk.length(),
                new com.aicodestudio.ide.ai.compile.CompileResultDialog.OnChoice() {
                    @Override
                    public void onExportAndInstall() {
                        doExportAndInstall(apk);
                    }

                    @Override
                    public void onInstallOnly() {
                        installApk(apk, null);
                    }

                    @Override
                    public void onCancel() {
                        appendLog("已保留 APK，可通过「产物」按钮管理", CompileExecutor.LOG_DEBUG);
                    }
                });
    }

    /** 导出到 SD 卡 AI Code Studio/ 目录（需「所有文件访问」权限），然后自动安装 */
    private void doExportAndInstall(final File apk) {
        if (!com.aicodestudio.ide.ai.compile.ApkInstaller.hasAllFilesAccess(this)) {
            com.aicodestudio.ide.ai.ui.AppleDialog.confirm(this, "需要存储权限",
                    "导出到 SD 卡需要「所有文件访问」权限。\n是否前往系统设置开启？",
                    "去开启", new Runnable() {
                        @Override public void run() {
                            com.aicodestudio.ide.ai.compile.ApkInstaller.openAllFilesSettings(RunActivity.this);
                        }
                    });
            return;
        }
        File exported = com.aicodestudio.ide.ai.compile.ArtifactManager
                .exportApk(apk, state().getProjectName());
        if (exported != null) {
            appendLog("已导出：" + exported.getAbsolutePath(), CompileExecutor.LOG_SUCCESS);
            com.aicodestudio.ide.ai.ui.AppleDialog.message(this, "导出成功",
                    "APK 已导出到：\n" + exported.getAbsolutePath()
                            + "\n\n若自动安装无响应（部分系统会拦截自动安装），"
                            + "可打开文件管理器手动点击该 APK 安装；"
                            + "遇到「安全守护/未备案」提示时，勾选风险知晓并继续即可。"
                            + "\n\n可在编译页「产物」按钮中查看和管理。");
        } else {
            appendLog("导出失败（请检查存储权限）", CompileExecutor.LOG_ERROR);
        }
        // 导出后仍自动安装；安装完成后清理私有临时产物（公共目录副本保留）
        installApk(apk, null);
    }

    /** 安装 APK（系统安装器，与手动点 APK 同路径，规避部分 ROM 对 PackageInstaller 的静默拦截）；
     *  安装成功即清理私有临时产物 */
    private void installApk(final File apk, final String pkg) {
        final String targetPkg = (pkg != null && pkg.length() > 0)
                ? pkg : CompileExecutor.getInstance().getLastApkPackageName();
        appendLog("正在安装…", CompileExecutor.LOG_INFO);
        com.aicodestudio.ide.ai.compile.ApkInstaller.installWithSystemInstaller(
                RunActivity.this, apk, targetPkg,
                new com.aicodestudio.ide.ai.compile.ApkInstaller.InstallCallback() {
                    @Override
                    public void onInstallSuccess(String packageName) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                appendLog("安装成功", CompileExecutor.LOG_SUCCESS);
                                // 装完即删私有临时产物（不打扰用户，无多余提示）
                                com.aicodestudio.ide.ai.compile.ArtifactManager
                                        .clearPrivateApks(RunActivity.this);
                                setStatus("已安装");
                            }
                        });
                    }

                    @Override
                    public void onInstallFailed(String message) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                appendLog("安装失败：" + message
                                        + "（APK 已保留，可在「产物」中查看或重装）",
                                        CompileExecutor.LOG_ERROR);
                                setStatus("安装失败");
                            }
                        });
                    }

                    @Override
                    public void onNeedPermission() {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                appendLog("需要「安装未知应用」授权", CompileExecutor.LOG_WARN);
                                com.aicodestudio.ide.ai.ui.AppleDialog.confirm(
                                        RunActivity.this, "需要授权",
                                        "安装 APK 需要开启「安装未知应用」。\n是否前往系统设置开启？",
                                        "去开启", new Runnable() {
                                            @Override public void run() {
                                                com.aicodestudio.ide.ai.compile.ApkInstaller
                                                        .openInstallSettings(RunActivity.this);
                                            }
                                        });
                            }
                        });
                    }
                });
    }

    /** 向日志面板追加一行（分级渲染） */
    private void appendLog(String msg, int level) {
        if (logPanel != null) logPanel.appendLine(msg, level);
    }

    /** dp 转像素（浮点，圆角用） */
    private float dpF(float v) {
        // 统一委托 UiKit
        return UiKit.dpF(this, v);
    }

    /**
     * 同步日志面板编译按钮状态：空闲「编译」(绿) / 编译中「终止」(红)。
     * 编译状态以 CompileExecutor.isCompiling() 为唯一权威来源（已在
     * startCompile 同步置位，UI 点击瞬间即可刷新）。
     */
    private void refreshButtonState() {
        boolean compiling = CompileExecutor.getInstance().isCompiling();
        if (logPanel != null) logPanel.setCompiling(compiling);
    }
}
