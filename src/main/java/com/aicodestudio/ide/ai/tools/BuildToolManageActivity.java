package com.aicodestudio.ide.ai.tools;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;
import android.widget.LinearLayout;
import com.aicodestudio.ide.R;
import java.util.List;
import com.aicodestudio.ide.ai.ui.AppleDialog;

@SuppressWarnings("unchecked")

/**
 * 编译工具管理界面
 * 文档 弹窗1: 编译工具管理弹窗
 */
public class BuildToolManageActivity extends Activity {

    private TextView tvTotalCount;
    private TextView tvAutoCount;
    private TextView tvManualCount;
    private LinearLayout containerTools;
    private Button btnAddTool;
    private Button btnExport;
    private Button btnImport;

    private BuildToolAdapter adapter;
    private BuildToolManager toolMgr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_build_tool_manage);

        toolMgr = BuildToolManager.getInstance(this);

        initViews();
        setupList();
        refreshStats();
    }

    private void initViews() {
        tvTotalCount = (TextView) findViewById(R.id.tv_total_count);
        tvAutoCount = (TextView) findViewById(R.id.tv_auto_count);
        tvManualCount = (TextView) findViewById(R.id.tv_manual_count);
        containerTools = (LinearLayout) findViewById(R.id.container_tools);
        btnAddTool = (Button) findViewById(R.id.btn_add_tool);
        btnExport = (Button) findViewById(R.id.btn_export_config);
        btnImport = (Button) findViewById(R.id.btn_import_config);

        btnAddTool.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                android.content.Intent intent = new android.content.Intent(
                    BuildToolManageActivity.this, EditBuildToolActivity.class);
                intent.putExtra("mode", "create");
                startActivity(intent);
            }
        });

        btnExport.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                String json = toolMgr.exportConfig();
                // 保存到文件
                try {
                    java.io.File file = new java.io.File(getExternalFilesDir(null), "build_tools_config.json");
                    java.io.FileWriter fw = new java.io.FileWriter(file);
                    fw.write(json);
                    fw.close();
                    Toast.makeText(BuildToolManageActivity.this,
                        "配置已导出: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(BuildToolManageActivity.this,
                        "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnImport.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("application/json");
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, 2001);
            }
        });
    }

    private void setupList() {
        List<BuildToolManager.BuildTool> tools = toolMgr.getAllTools();
        adapter = new BuildToolAdapter(this, tools, new BuildToolAdapter.OnToolActionListener() {
            @Override
            public void onEdit(BuildToolManager.BuildTool tool) {
                android.content.Intent intent = new android.content.Intent(
                    BuildToolManageActivity.this, EditBuildToolActivity.class);
                intent.putExtra("mode", "edit");
                intent.putExtra("tool_id", tool.id);
                startActivity(intent);
            }

            @Override
            public void onDelete(BuildToolManager.BuildTool tool) {
                new AppleDialog.Builder(BuildToolManageActivity.this)
                    .setTitle("确认删除")
                    .setMessage("确定要删除工具 \"" + tool.name + "\" 吗？")
                    .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            toolMgr.deleteTool(tool.id);
                            refreshData();
                            Toast.makeText(BuildToolManageActivity.this,
                                "工具已删除", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            }

            @Override
            public void onToggle(BuildToolManager.BuildTool tool) {
                toolMgr.setEnabled(tool.id, !tool.enabled);
                refreshData();
            }
        });
        adapter.renderInto(containerTools);
    }

    private void refreshData() {
        if (adapter == null) return;
        adapter.updateData(toolMgr.getAllTools());
        adapter.renderInto(containerTools);
        refreshStats();
    }

    private void refreshStats() {
        List<BuildToolManager.BuildTool> all = toolMgr.getAllTools();
        int auto = 0, manual = 0;
        for (BuildToolManager.BuildTool t : all) {
            if (t.autoCreated) auto++; else manual++;
        }
        tvTotalCount.setText("共 " + all.size() + " 个工具");
        tvAutoCount.setText("AI创建: " + auto);
        tvManualCount.setText("手动: " + manual);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2001 && resultCode == RESULT_OK && data != null) {
            try {
                android.net.Uri uri = (android.net.Uri) data.getData();
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                String json = s.hasNext() ? s.next() : "";
                int count = toolMgr.importConfig(json);
                refreshData();
                Toast.makeText(BuildToolManageActivity.this, "已导入 " + count + " 个工具配置", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(BuildToolManageActivity.this, "导入失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshData();
    }
}
