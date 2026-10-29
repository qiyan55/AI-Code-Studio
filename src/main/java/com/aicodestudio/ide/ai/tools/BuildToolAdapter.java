package com.aicodestudio.ide.ai.tools;

import com.aicodestudio.ide.ai.ui.Theme;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.aicodestudio.ide.R;
import java.util.List;

/**
 * 编译工具列表适配器（纯 Android SDK 实现，零 AndroidX 依赖）
 *
 * 改造记录：
 * 原实现继承 androidx.recyclerview.widget.RecyclerView.Adapter，而 AndroidX 库的
 * aar 资源未合并进本 APK，运行时 inflate RecyclerView 会因资源 ID 不匹配崩溃。
 * 现改为直接构建 View 并加入 LinearLayout 容器，纯原生实现。
 */
public class BuildToolAdapter {

    private final Context context;
    private List<BuildToolManager.BuildTool> tools;
    private OnToolActionListener listener;

    public interface OnToolActionListener {
        void onEdit(BuildToolManager.BuildTool tool);
        void onDelete(BuildToolManager.BuildTool tool);
        void onToggle(BuildToolManager.BuildTool tool);
    }

    public BuildToolAdapter(Context context,
                            List<BuildToolManager.BuildTool> tools,
                            OnToolActionListener l) {
        this.context = context;
        this.tools = tools;
        this.listener = l;
    }

    public void updateData(List<BuildToolManager.BuildTool> newTools) {
        this.tools = newTools;
    }

    public List<BuildToolManager.BuildTool> getData() {
        return tools;
    }

    public int getCount() {
        return tools == null ? 0 : tools.size();
    }

    /**
     * 把所有条目渲染进指定容器
     */
    public void renderInto(ViewGroup container) {
        if (container == null) return;
        container.removeAllViews();
        if (tools == null) return;

        for (int i = 0; i < tools.size(); i++) {
            container.addView(buildItemView(tools.get(i)));
        }
    }

    private View buildItemView(final BuildToolManager.BuildTool tool) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_build_tool, null);

        TextView tvName = (TextView) v.findViewById(R.id.tv_tool_name);
        TextView tvType = (TextView) v.findViewById(R.id.tv_tool_type);
        TextView tvTag = (TextView) v.findViewById(R.id.tv_tool_tag);
        ImageButton btnEdit = (ImageButton) v.findViewById(R.id.btn_edit_tool);
        ImageButton btnDelete = (ImageButton) v.findViewById(R.id.btn_delete_tool);
        View colorBar = v.findViewById(R.id.view_color_bar);

        if (tvName != null) {
            tvName.setText(tool.name);
            tvName.setTextColor(Color.parseColor(tool.enabled ? "#E6EDF3" : "#484F58"));
        }

        if (tvType != null) {
            tvType.setText(tool.projectType + "  |  " + tool.compileCommand);
        }

        if (tvTag != null) {
            if (tool.autoCreated) {
                tvTag.setText("AI自动创建");
                tvTag.setTextColor(Theme.ACCENT_BLUE);
            } else {
                tvTag.setText("手动创建");
                tvTag.setTextColor(Theme.FILE_GRADLE);
            }
        }

        if (colorBar != null) {
            colorBar.setBackgroundColor(
                    Color.parseColor(tool.enabled ? "#2196F3" : "#484F58"));
        }

        if (btnEdit != null) {
            btnEdit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (listener != null) listener.onEdit(tool);
                }
            });
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (listener != null) listener.onDelete(tool);
                }
            });
        }

        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (listener != null) listener.onToggle(tool);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 12);
        v.setLayoutParams(lp);

        return v;
    }
}
