package com.aicodestudio.ide.ai.tools;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;
import android.app.Activity;
import com.aicodestudio.ide.R;
import android.widget.TextView;
@SuppressWarnings("unchecked")

/**
 * 新增/编辑编译工具对话框
 * 文档 弹窗2
 */
public class EditBuildToolActivity extends Activity {

    private EditText etName;
    private EditText etUrl;
    private EditText etCompile;
    private EditText etRun;
    private RadioGroup rgType;
    private Spinner spinnerArch;
    private Switch swEnabled;
    private Button btnSave;
    private Button btnCancel;
    private TextView tvTitle;

    private BuildToolManager toolMgr;
    private String editToolId = "";
    private boolean isEditMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_edit_build_tool);

        toolMgr = BuildToolManager.getInstance(this);

        String mode = getIntent().getStringExtra("mode");
        isEditMode = "edit".equals(mode);

        initViews();
        setupSpinner();
        populateFields();

        btnCancel.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                finish();
            }
        });

        btnSave.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                saveTool();
            }
        });
    }

    private void initViews() {
        tvTitle = (TextView) findViewById(R.id.tv_dialog_title);
        etName = (EditText) findViewById(R.id.et_tool_name);
        etUrl = (EditText) findViewById(R.id.et_download_url);
        etCompile = (EditText) findViewById(R.id.et_compile_cmd);
        etRun = (EditText) findViewById(R.id.et_run_cmd);
        rgType = (RadioGroup) findViewById(R.id.rg_project_type);
        spinnerArch = (Spinner) findViewById(R.id.spinner_arch);
        swEnabled = (Switch) findViewById(R.id.switch_enabled);
        btnSave = (Button) findViewById(R.id.btn_save);
        btnCancel = (Button) findViewById(R.id.btn_cancel);

        if (isEditMode) {
            tvTitle.setText("编辑编译工具");
            editToolId = getIntent().getStringExtra("tool_id");
        }
    }

    private void setupSpinner() {
        String[] archs = {"全部架构", "arm64-v8a", "armeabi-v7a", "x86", "x86_64"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, archs);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerArch.setAdapter(adapter);
    }

    private void populateFields() {
        if (!isEditMode) return;

        BuildToolManager.BuildTool tool = toolMgr.getToolById(editToolId);
        if (tool == null) return;

        etName.setText(tool.name);
        etUrl.setText(tool.downloadUrl);
        etCompile.setText(tool.compileCommand);
        etRun.setText(tool.runCommand);
        swEnabled.setChecked(tool.enabled);

        // 设置项目类型
        if ("Android".equals(tool.projectType)) {
            ((RadioButton) findViewById(R.id.rb_android)).setChecked(true);
        } else if ("C++".equals(tool.projectType)) {
            ((RadioButton) findViewById(R.id.rb_cpp)).setChecked(true);
        } else if ("Python".equals(tool.projectType)) {
            ((RadioButton) findViewById(R.id.rb_python)).setChecked(true);
        } else if ("Java".equals(tool.projectType)) {
            ((RadioButton) findViewById(R.id.rb_java)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.rb_custom)).setChecked(true);
        }

        // 设置架构
        String[] archs = {"all", "arm64-v8a", "armeabi-v7a", "x86", "x86_64"};
        for (int i = 0; i < archs.length; i++) {
            if (archs[i].equals(tool.archFilter)) {
                spinnerArch.setSelection(i);
                break;
            }
        }
    }

    private void saveTool() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入工具名称", Toast.LENGTH_SHORT).show();
            return;
        }

        String url = etUrl.getText().toString().trim();
        String compile = etCompile.getText().toString().trim();
        String run = etRun.getText().toString().trim();

        // 获取项目类型
        int selectedId = rgType.getCheckedRadioButtonId();
        String projectType = "custom";
        if (selectedId == R.id.rb_android) projectType = "Android";
        else if (selectedId == R.id.rb_cpp) projectType = "C++";
        else if (selectedId == R.id.rb_python) projectType = "Python";
        else if (selectedId == R.id.rb_java) projectType = "Java";

        String arch = "all";
        String[] archs = {"all", "arm64-v8a", "armeabi-v7a", "x86", "x86_64"};
        int archIdx = spinnerArch.getSelectedItemPosition();
        if (archIdx >= 0 && archIdx < archs.length) arch = archs[archIdx];

        boolean enabled = swEnabled.isChecked();

        if (isEditMode) {
            BuildToolManager.BuildTool tool = toolMgr.getToolById(editToolId);
            if (tool != null) {
                tool.name = name;
                tool.downloadUrl = url;
                tool.projectType = projectType;
                tool.compileCommand = compile;
                tool.runCommand = run;
                tool.archFilter = arch;
                tool.enabled = enabled;
                toolMgr.updateTool(tool);
            }
        } else {
            toolMgr.createManualTool(name, url, projectType, compile, run, enabled);
        }

        Toast.makeText(this, "工具已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
