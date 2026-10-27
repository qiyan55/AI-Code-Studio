package com.aicodestudio.ide.ai.service;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.app.Activity;
import com.aicodestudio.ide.R;
@SuppressWarnings("unchecked")

/**
 * 系统全局设置页面
 * 文档 弹窗3: 系统全局设置
 */
public class SettingsActivity extends Activity {

    // AI联动
    private Switch swAutoSendError;
    private Switch swDetectFunction;
    private Switch swDetectFile;
    private Switch swAutoPushCompile;
    private Switch swAllowAutoTool;
    private Switch swAutoApplyFix;
    private Switch swConfirmApply;

    // 编辑器
    private Spinner spinnerSyntax;
    private RadioGroup rgAutocomplete;

    // 编译
    private Switch swSaveBeforeCompile;
    private Switch swStopOnError;
    private Switch swAllowDownload;

    // 外观
    private RadioGroup rgTheme;
    private SeekBar seekFontSize;

    private AIService aiService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        aiService = AIService.getInstance();

        initViews();
        loadSettings();
        setupListeners();
    }

    private void initViews() {
        swAutoSendError = (Switch) findViewById(R.id.sw_auto_send_error);
        swDetectFunction = (Switch) findViewById(R.id.sw_detect_function);
        swDetectFile = (Switch) findViewById(R.id.sw_detect_file);
        swAutoPushCompile = (Switch) findViewById(R.id.sw_auto_push_compile);
        swAllowAutoTool = (Switch) findViewById(R.id.sw_allow_auto_tool);
        swAutoApplyFix = (Switch) findViewById(R.id.sw_auto_apply_fix);
        swConfirmApply = (Switch) findViewById(R.id.sw_confirm_before_apply);

        spinnerSyntax = (Spinner) findViewById(R.id.spinner_syntax_engine);
        rgAutocomplete = (RadioGroup) findViewById(R.id.rg_autocomplete);

        swSaveBeforeCompile = (Switch) findViewById(R.id.sw_save_before_compile);
        swStopOnError = (Switch) findViewById(R.id.sw_stop_on_error);
        swAllowDownload = (Switch) findViewById(R.id.sw_allow_download);

        rgTheme = (RadioGroup) findViewById(R.id.rg_theme);
        seekFontSize = (SeekBar) findViewById(R.id.seekbar_font_size);

        // 语法引擎下拉
        String[] engines = {"内置高亮", "Tree-sitter", "自定义"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, engines);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSyntax.setAdapter(adapter);
    }

    private void loadSettings() {
        swAutoSendError.setChecked(aiService.isAutoSendErrors());
        swDetectFunction.setChecked(aiService.isDetectOnFunctionEnd());
        swDetectFile.setChecked(aiService.isDetectOnFileEnd());
        swAutoPushCompile.setChecked(aiService.isAutoPushCompileErrors());
        swAllowAutoTool.setChecked(aiService.isAllowAutoCreateTool());
    }

    private void setupListeners() {
        swAutoSendError.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                aiService.setAutoSendErrors(checked);
            }
        });

        swDetectFunction.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                aiService.setDetectOnFunctionEnd(checked);
            }
        });

        swDetectFile.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                aiService.setDetectOnFileEnd(checked);
            }
        });

        swAutoPushCompile.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                aiService.setAutoPushCompileErrors(checked);
            }
        });

        swAllowAutoTool.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                aiService.setAllowAutoCreateTool(checked);
            }
        });

        swAutoApplyFix.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                aiService.setAutoApplyFix(checked);
            }
        });

        swConfirmApply.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                aiService.setConfirmBeforeApply(checked);
            }
        });

        seekFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        Button btnClose = (Button) findViewById(R.id.btn_close_settings);
        btnClose.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                finish();
            }
        });
    }
}
