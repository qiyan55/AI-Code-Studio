package com.aicodestudio.ide.ai.editor;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码编辑器核心组件
 *
 * 统一 WebView + highlight.js 渲染：无论编辑还是查看，显示完全一致（语法高亮、
 * 行号、'Courier New' monospace 12px 字体、横向滚动、不自动换行）。
 * 代码区为 contenteditable：点击即可直接编辑，输入时实时重新高亮（60ms 防抖，
 * 中文组合输入期间暂停，组合结束后立即刷新；回车插入换行不破坏行号）。
 * 内容权威缓冲 currentCode 由 JS 侧 onCodeChanged 实时回写。
 *
 * 语法高亮统一使用 highlight.js（assets/highlight），不再使用自研高亮引擎。
 */
public class CodeEditorView extends LinearLayout {

    private WebView codeWebView;       // 代码区（contenteditable + highlight.js）
    private TextView langLabel;        // 顶部语言标签

    private String filePath = "";
    private String language = "plaintext";  // highlight.js 语言 id
    private boolean dirty = false;
    private String snapshot = "";
    private String currentCode = "";   // 内容权威缓冲（与 WebView 双向同步）
    private String currentSelection = ""; // 当前选中文本（selectionchange 回写）

    private boolean pageReady = false; // WebView 页面是否就绪
    private String pendingCode = null; // 页面未就绪时暂存待显示代码

    private List<CodeError> errors = new ArrayList<CodeError>();
    private Handler mainHandler;
    private EditorListener listener;

    private static final int ERROR_COLOR = 0xFFF85149; // 错误红色

    /** 错误项 */
    public static class CodeError {
        public int line;
        public int col;
        public int start;
        public int end;
        public String message;
        public int severity; // 0=error, 1=warning

        public CodeError(int line, int col, int start, int end, String message, int severity) {
            this.line = line; this.col = col; this.start = start;
            this.end = end; this.message = message; this.severity = severity;
        }

        public boolean isError() { return severity == 0; }
    }

    public interface EditorListener {
        void onTextChanged(String code);
        void onCursorChanged(int row, int col);
        void onErrorClick(CodeError error);
        void onModifyStateChanged(boolean dirty);
    }

    public CodeEditorView(Context ctx) {
        super(ctx);
        init(ctx);
    }

    public CodeEditorView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init(ctx);
    }

    private void init(Context ctx) {
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.BG_PRIMARY);
        mainHandler = new Handler(Looper.getMainLooper());

        // ===== 顶部工具条：语言标签（无模式切换按钮，始终可编辑） =====
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Theme.BG_SECONDARY);
        topBar.setPadding(dp(12), 0, dp(12), 0);
        topBar.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, dp(32)));

        langLabel = new TextView(ctx);
        langLabel.setTextSize(11);
        langLabel.setTextColor(Theme.ACCENT_GREEN);
        langLabel.setText("PLAINTEXT");
        langLabel.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        topBar.addView(langLabel);
        addView(topBar);

        // ===== 代码区：WebView（contenteditable + highlight.js） =====
        codeWebView = new WebView(ctx);
        WebSettings ws = codeWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setDefaultTextEncodingName("UTF-8");
        codeWebView.setBackgroundColor(0xFF282C34);
        codeWebView.setVerticalScrollBarEnabled(true);
        codeWebView.setHorizontalScrollBarEnabled(true);
        codeWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingCode != null) {
                    final String c = pendingCode;
                    pendingCode = null;
                    doSetCode(c);
                }
            }
        });
        // JS → Java 桥：输入/选区变化实时回写
        codeWebView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        codeWebView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        addView(codeWebView);
        codeWebView.loadDataWithBaseURL(
                "file:///android_asset/highlight/",
                buildEditorHtml(),
                "text/html", "UTF-8", null);
    }

    /** JS → Java 桥 */
    private class JsBridge {
        /** fromInput=true：用户输入（标脏）；false：程序性 setCode（不标脏） */
        @JavascriptInterface
        public void onCodeChanged(String text, boolean fromInput) {
            currentCode = text == null ? "" : text;
            if (fromInput) {
                dirty = true;
                if (listener != null) listener.onModifyStateChanged(true);
                if (listener != null) listener.onTextChanged(currentCode);
            }
        }

        @JavascriptInterface
        public void onSelectionChanged(String text) {
            currentSelection = text == null ? "" : text;
        }
    }

    // ================= WebView 内容注入 =================

    private void doSetCode(String code) {
        if (code == null) code = "";
        if (!pageReady) {
            pendingCode = code;
            return;
        }
        try {
            codeWebView.evaluateJavascript(
                    "setLanguage('" + language + "');setCode('" + escapeJs(code) + "');",
                    null);
        } catch (Throwable ignored) {
        }
    }

    /** JS 字符串转义（防止代码中的引号/换行/</script> 破坏 JS） */
    private static String escapeJs(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                case '&': sb.append("\\u0026"); break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 编辑器 WebView HTML：contenteditable 代码区 + highlight.js 实时高亮 + 行号。
     * 编辑与查看显示完全一致；输入实时高亮（中文组合期间暂停）。
     */
    private String buildEditorHtml() {
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<style>\n" +
                "  html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: #282c34; color: #abb2bf; }\n" +
                "  body { display: flex; }\n" +
                "  /* 行号列独立固定（不参与横向滚动，物理上不会动）；代码区独立滚动，纵向用 scrollTop 同步行号 */\n" +
                "  #lnWrap { flex: none; width: 56px; height: 100%; overflow: hidden; background: #21252b; }\n" +
                "  #ln { margin: 0; padding: 10px 8px 10px 10px; text-align: right; color: #7f848e; font-family: 'Courier New', monospace; font-size: 12px; line-height: 1.5; user-select: none; white-space: pre; box-sizing: border-box; }\n" +
                "  #codeScroll { flex: 1; min-width: 0; height: 100%; overflow: auto; background: #282c34; }\n" +
                "  #codeHolder { min-height: 100%; padding: 10px 16px 10px 10px; box-sizing: border-box; }\n" +
                "  #code { display: block; outline: none; font-family: 'Courier New', monospace; font-size: 12px; line-height: 1.5; color: #abb2bf; background: #282c34; white-space: pre; word-wrap: normal; min-height: 100%; caret-color: #61afef; }\n" +
                "  .hljs { color: #abb2bf; background: #282c34; }\n" +
                "  .hljs-keyword, .hljs-selector-tag, .hljs-literal, .hljs-section, .hljs-link { color: #c678dd; }\n" +
                "  .hljs-string, .hljs-attr, .hljs-symbol, .hljs-bullet, .hljs-addition { color: #98c379; }\n" +
                "  .hljs-comment, .hljs-quote { color: #7f848e; font-style: italic; }\n" +
                "  .hljs-number, .hljs-regexp, .hljs-variable, .hljs-template-variable { color: #d19a66; }\n" +
                "  .hljs-function .hljs-title, .hljs-title.function_, .hljs-title, .hljs-name { color: #61afef; }\n" +
                "  .hljs-type, .hljs-title.class_, .hljs-built_in { color: #e5c07b; }\n" +
                "  .hljs-params { color: #d19a66; }\n" +
                "  .hljs-operator, .hljs-meta, .hljs-deletion { color: #56b6c2; }\n" +
                "  .hljs-attribute { color: #d19a66; }\n" +
                "  .hljs-tag { color: #e06c75; }\n" +
                "  .hljs-emphasis { font-style: italic; }\n" +
                "  .hljs-strong { font-weight: bold; }\n" +
                "  ::-webkit-scrollbar { width: 8px; height: 8px; }\n" +
                "  ::-webkit-scrollbar-track { background: #282c34; }\n" +
                "  ::-webkit-scrollbar-thumb { background: #3e4451; border-radius: 4px; }\n" +
                "  /* 滚动条交叉角落（右下角）与拖拽块：与背景同色，消除白点残留 */\n" +
                "  ::-webkit-scrollbar-corner { background: #282c34; }\n" +
                "  ::-webkit-resizer { background: #282c34; }\n" +
                "  /* 代码补全弹层（textcomplete 现成 UI，仅配色适配深色主题） */\n" +
                "  .textcomplete-dropdown { position: absolute; z-index: 9999; max-height: 240px; overflow-y: auto; overscroll-behavior: contain; -webkit-overflow-scrolling: touch; background: #21252b; border: 1px solid #3e4451; border-radius: 6px; font-family: 'Courier New', monospace; font-size: 12px; box-shadow: 0 4px 14px rgba(0,0,0,0.45); margin: 0; padding: 4px 0; }\n" +
                "  .textcomplete-dropdown a { display: block; padding: 4px 12px; color: #abb2bf; text-decoration: none; white-space: nowrap; }\n" +
                "  .textcomplete-dropdown a.active { background: #3e4451; color: #ffffff; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div id=\"lnWrap\"><pre id=\"ln\"></pre></div>\n" +
                "<div id=\"codeScroll\">\n" +
                "  <div id=\"codeHolder\"><code id=\"code\" class=\"language-plaintext\" contenteditable=\"true\" spellcheck=\"false\"></code></div>\n" +
                "</div>\n" +
                "<script src=\"highlight.min.js\"></script>\n" +
                "<script src=\"jquery.min.js\"></script>\n" +
                "<script src=\"textcomplete.min.js\"></script>\n" +
                "<script src=\"android_api.js\"></script>\n" +
                "<script>\n" +
                "  var codeEl = document.getElementById('code');\n" +
                "  var lnEl = document.getElementById('ln');\n" +
                "  var lnWrap = document.getElementById('lnWrap');\n" +
                "  var codeScroll = document.getElementById('codeScroll');\n" +
                "  var currentLang = 'plaintext';\n" +
                "  var composing = false;\n" +
                "  var hlTimer = null;\n" +
                "  var braceBusy = false;  // 括号配对/反缩进防递归标志（input 事件内改文本会再次触发 input）\n" +
                "  var lastTextLen = 0;    // 上次 input 时文本长度（删除检测：文本变短则跳过括号智能处理，避免删掉的 } 被自动补回）\n" +
                "  var lastAutoBrace = -1;  // keydown/input 自动补全括号的插入位置（防重复补全：异步派发的 input 不再二次补）\n" +
                "  var pendingBrace = -1;   // 待决补全：输入 { 后不立即补，等事件检测输入法是否已自动补 }（避免双 } 与包裹用户输入）\n" +
                "  var braceTimeout = null;  // 兜底定时器：仅当输入 { 后长时间无任何输入事件时才补（无事件=用户停住，无包裹风险）\n" +
                "  function clearPendingBrace() { pendingBrace = -1; clearTimeout(braceTimeout); }\n" +
                "  function startBraceTimeout() {\n" +
                "    clearTimeout(braceTimeout);\n" +
                "    braceTimeout = setTimeout(function(){\n" +
                "      if (pendingBrace < 0) return;\n" +
                "      pendingBrace = -1;\n" +
                "      var _t2 = codeEl.textContent;\n" +
                "      var _c2 = getCaretOffset();\n" +
                "      // 输入法未补且光标前仍是 { 且后无 }（用户停住未输入其他内容）→ 由我们补\n" +
                "      if (_c2 > 0 && _t2.charAt(_c2 - 1) === '{' && _t2.charAt(_c2) !== '}') {\n" +
                "        lastAutoBrace = _c2;\n" +
                "        braceBusy = true;\n" +
                "        codeEl.textContent = _t2.substring(0, _c2) + '}' + _t2.substring(_c2);\n" +
                "        doHighlight(true);\n" +
                "        setCaret(_c2);\n" +
                "        braceBusy = false;\n" +
                "      }\n" +
                "    }, 150);\n" +
                "  }\n" +
                "  var caretPos = 0;  // 自维护的光标偏移（高亮重绘破坏 selection 时的稳定基准）\n" +
                "\n" +
                "  // 同步自维护光标偏移：仅当 selection 有效（节点仍在 codeEl 内）时更新，损坏时保留旧值\n" +
                "  function syncCaretPos() {\n" +
                "    var sel = window.getSelection();\n" +
                "    if (!sel.rangeCount) return;\n" +
                "    var c = sel.getRangeAt(0).endContainer;\n" +
                "    if (!codeEl.contains(c)) return;\n" +
                "    try {\n" +
                "      var range = sel.getRangeAt(0);\n" +
                "      var pre = range.cloneRange();\n" +
                "      pre.selectNodeContents(codeEl);\n" +
                "      pre.setEnd(c, range.endOffset);\n" +
                "      caretPos = pre.toString().length;\n" +
                "    } catch(e) {}\n" +
                "  }\n" +
                "\n" +
                "  // 代码区纵向滚动时，行号列 scrollTop 同步（同一帧生效，无延迟）；行号列不参与横向滚动，天然固定\n" +
                "  codeScroll.addEventListener('scroll', function() {\n" +
                "    lnWrap.scrollTop = codeScroll.scrollTop;\n" +
                "  });\n" +
                "\n" +
                "  function setLanguage(lang) {\n" +
                "    currentLang = lang;\n" +
                "    codeEl.className = 'language-' + lang;\n" +
                "  }\n" +
                "\n" +
                "  function renderLineNumbers() {\n" +
                "    var t = codeEl.textContent;\n" +
                "    var n = 1;\n" +
                "    for (var i = 0; i < t.length; i++) if (t.charAt(i) === '\\n') n++;\n" +
                "    var s = '';\n" +
                "    for (var i = 1; i <= n; i++) { s += i; if (i < n) s += '\\n'; }\n" +
                "    lnEl.textContent = s;\n" +
                "  }\n" +
                "\n" +
                "  function getCaretOffset() {\n" +
                "    var sel = window.getSelection();\n" +
                "    if (!sel.rangeCount) return caretPos;\n" +
                "    var c = sel.getRangeAt(0).endContainer;\n" +
                "    if (!codeEl.contains(c)) return caretPos;  // selection 指向旧 DOM 节点（重绘竞争）→ 回退自维护值\n" +
                "    var range = sel.getRangeAt(0);\n" +
                "    var pre = range.cloneRange();\n" +
                "    pre.selectNodeContents(codeEl);\n" +
                "    pre.setEnd(range.endContainer, range.endOffset);\n" +
                "    return pre.toString().length;\n" +
                "  }\n" +
                "\n" +
                "  function setCaret(offset) {\n" +
                "    var sel = window.getSelection();\n" +
                "    var range = document.createRange();\n" +
                "    var walked = 0;\n" +
                "    var found = false;\n" +
                "    function walk(n) {\n" +
                "      if (n.nodeType === 3) {\n" +
                "        if (walked + n.textContent.length >= offset) {\n" +
                "          range.setStart(n, offset - walked);\n" +
                "          range.collapse(true);\n" +
                "          found = true;\n" +
                "          return;\n" +
                "        }\n" +
                "        walked += n.textContent.length;\n" +
                "      } else {\n" +
                "        for (var i = 0; i < n.childNodes.length; i++) {\n" +
                "          walk(n.childNodes[i]);\n" +
                "          if (found) return;\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    walk(codeEl);\n" +
                "    if (!found) {\n" +
                "      var last = codeEl.lastChild;\n" +
                "      if (last && last.nodeType === 3) range.setStart(last, last.textContent.length);\n" +
                "      else range.setStart(codeEl, 0);\n" +
                "      range.collapse(true);\n" +
                "    }\n" +
                "    sel.removeAllRanges();\n" +
                "    sel.addRange(range);\n" +
                "  }\n" +
                "\n" +
                "  // Android API 二次着色：highlight.js Java 语法不识别 import 来的类型，用 ANDROID_API 词表\n" +
                "  // 把未着色的 Android 类名（Bundle/View/WindowManager/R 等）补上 hljs-type 橙色，保持类型色一致\n" +
                "  var androidTypeSet = null;\n" +
                "  function colorAndroidTypes(html) {\n" +
                "    if (currentLang === 'plaintext') return html;\n" +
                "    var api = window.ANDROID_API;\n" +
                "    if (!api || !api.classes) return html;\n" +
                "    if (!androidTypeSet) {\n" +
                "      androidTypeSet = {};\n" +
                "      for (var b in api.classes) {\n" +
                "        var cl = api.classes[b];\n" +
                "        for (var i = 0; i < cl.length; i++) androidTypeSet[cl[i]] = 1;\n" +
                "      }\n" +
                "    }\n" +
                "    var set = androidTypeSet;\n" +
                "    return html.replace(/>([^<>]+)</g, function(m, txt) {\n" +
                "      return '>' + txt.replace(/(\\b[A-Z][A-Za-z0-9_]{1,}\\b)/g, function(w) {\n" +
                "        if (set[w]) return '<span class=\"hljs-type\">' + w + '</span>';\n" +
                "        return w;\n" +
                "      }) + '<';\n" +
                "    });\n" +
                "  }\n" +
                "\n" +
                "  function doHighlight(fromInput) {\n" +
                "    syncCaretPos();\n" +
                "    var off = getCaretOffset();\n" +
                "    var text = codeEl.textContent;\n" +
                "    try {\n" +
                "      var r = hljs.highlight(text, {language: currentLang});\n" +
                "      codeEl.innerHTML = colorAndroidTypes(r.value);\n" +
                "    } catch(e) { codeEl.textContent = text; }\n" +
                "    renderLineNumbers();\n" +
                "    if (fromInput) setCaret(Math.min(off, text.length));\n" +
                "    syncCaretPos();\n" +
                "    if (window.AndroidBridge) AndroidBridge.onCodeChanged(codeEl.textContent, !!fromInput);\n" +
                "  }\n" +
                "\n" +
                "  function scheduleHighlight() {\n" +
                "    if (hlTimer) clearTimeout(hlTimer);\n" +
                "    hlTimer = setTimeout(function(){ doHighlight(true); }, 60);\n" +
                "  }\n" +
                "\n" +
                "  // 手动插入文本（绕过 execCommand，Android WebView 对 execCommand 支持不可靠）\n" +
                "  function insertTextAtCaret(text) {\n" +
                "    var off = getCaretOffset();\n" +
                "    var cur = codeEl.textContent;\n" +
                "    codeEl.textContent = cur.substring(0, off) + text + cur.substring(off);\n" +
                "    doHighlight(true);\n" +
                "    setCaret(Math.min(off + text.length, codeEl.textContent.length));\n" +
                "  }\n" +
                "\n" +
                "  codeEl.addEventListener('compositionstart', function(){ composing = true; });\n" +
                "  codeEl.addEventListener('compositionend', function(){ composing = false; doHighlight(true); });\n" +
                "  codeEl.addEventListener('input', function(){\n" +
                "    if (composing) return;\n" +
                "    syncCaretPos();\n" +
                "    // 括号智能处理（兜底路径：Android 软键盘符号面板可能不派发 keydown，但 input 一定触发）\n" +
                "    var _t = codeEl.textContent;\n" +
                "    // 删除检测：文本变短（Backspace/Delete）→ 只做高亮，跳过括号智能处理\n" +
                "    // （否则删掉的 } 会被 '{' 分支判定'光标后无配对'而自动补回，且光标被拉回括号中间）\n" +
                "    if (_t.length < lastTextLen) {\n" +
                "      lastTextLen = _t.length;\n" +
                "      clearPendingBrace();\n" +
                "      scheduleHighlight();\n" +
                "      return;\n" +
                "    }\n" +
                "    lastTextLen = _t.length;\n" +
                "    var _c = getCaretOffset();\n" +
                "    var _ch = _c > 0 ? _t.charAt(_c - 1) : '';\n" +
                "    // 待决补全检测（事件驱动，不依赖固定延时）：\n" +
                "    // ①光标前 { 且光标后 } → 输入法已自动补配对 → 我们不补（清待决）\n" +
                "    // ②光标前不再是 {（用户已输入其他内容/光标移动）→ 不补，避免把用户输入包裹进括号（清待决）\n" +
                "    // ③仍是 { 后无 } → 输入法未补、用户未输入 → 保持待决，等兜底定时器\n" +
                "    if (pendingBrace >= 0) {\n" +
                "      if (_c > 0 && _t.charAt(_c - 1) === '{' && _t.charAt(_c) === '}') {\n" +
                "        clearPendingBrace();\n" +
                "      } else if (_c > 0 && _t.charAt(_c - 1) === '{' && _t.charAt(_c) !== '}') {\n" +
                "        // 保持待决\n" +
                "      } else {\n" +
                "        clearPendingBrace();\n" +
                "      }\n" +
                "    }\n" +
                "    if (!braceBusy && (_ch === '{' || _ch === '}')) {\n" +
                "      // 防重复补全：keydown/上一次 input 刚补过配对，本次 input 光标仍在补全区间内 → 跳过\n" +
                "      if (lastAutoBrace >= 0) {\n" +
                "        var _ab = lastAutoBrace;\n" +
                "        lastAutoBrace = -1;\n" +
                "        if (_c >= _ab && _c <= _ab + 2) {\n" +
                "          scheduleHighlight();\n" +
                "          return;\n" +
                "        }\n" +
                "      }\n" +
                "      if (_ch === '{' && _t.charAt(_c) !== '}') {\n" +
                "        // 输入 {：不立即补，标记待决并启动兜底定时器。\n" +
                "        // 后续 input 事件会检测：输入法若已补 } 则清待决不补；用户继续输入则清待决不包裹；\n" +
                "        // 只有输入 { 后无任何事件（用户停住）才由兜底补。\n" +
                "        if (pendingBrace < 0) {\n" +
                "          pendingBrace = _c;\n" +
                "          startBraceTimeout();\n" +
                "        }\n" +
                "        return;\n" +
                "      }\n" +
                "      if (_ch === '}') {\n" +
                "        // 光标后也是 }（自动补的配对）→ 跳过：删除刚输入的 }，保留原配对，光标越过\n" +
                "        if (_t.charAt(_c) === '}') {\n" +
                "          braceBusy = true;\n" +
                "          codeEl.textContent = _t.substring(0, _c - 1) + _t.substring(_c);\n" +
                "          doHighlight(true);\n" +
                "          setCaret(_c);\n" +
                "          braceBusy = false;\n" +
                "          return;\n" +
                "        }\n" +
                "        // 反缩进：当前行行首到光标前只有空白 → 删除一层缩进（4 空格）再保留 }\n" +
                "        var _ls = _t.lastIndexOf('\\n', _c - 1) + 1;\n" +
                "        var _pre = _t.substring(_ls, _c - 1);\n" +
                "        if (/^[ \\t]+$/.test(_pre)) {\n" +
                "          var _ni = _pre.replace(/^ {1,4}/, '');\n" +
                "          if (_ni === _pre && _pre.charAt(0) === '\\t') _ni = _pre.substring(1);\n" +
                "          braceBusy = true;\n" +
                "          // 复用配对：光标后（跳过空白）紧跟 } 时，删掉该配对，由本次 } 取代，避免重复生成\n" +
                "          var _after = _t.substring(_c);\n" +
                "          var _am = _after.match(/^\\s*}/);\n" +
                "          if (_am) {\n" +
                "            codeEl.textContent = _t.substring(0, _ls) + _ni + '}' + _after.substring(_am[0].length);\n" +
                "            doHighlight(true);\n" +
                "            setCaret(_ls + _ni.length + 1);\n" +
                "          } else {\n" +
                "            codeEl.textContent = _t.substring(0, _ls) + _ni + '}' + _after;\n" +
                "            doHighlight(true);\n" +
                "            setCaret(_ls + _ni.length + 1);\n" +
                "          }\n" +
                "          braceBusy = false;\n" +
                "          return;\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    scheduleHighlight();\n" +
                "    // 兜底触发补全：Android 软键盘对 contenteditable 的 keyup 不可靠，手动派发 keyup 事件\n" +
                "    if (window.jQuery && window.jQuery.fn && window.jQuery.fn.textcomplete) {\n" +
                "      try { codeEl.dispatchEvent(new KeyboardEvent('keyup', { key: 'Process', keyCode: 0, bubbles: true })); } catch(e2) {}\n" +
                "    }\n" +
                "  });\n" +
                "  codeEl.addEventListener('keyup', function(){ if (!composing) scheduleHighlight(); });\n" +
                "  codeEl.addEventListener('keydown', function(e) {\n" +
                "    // 中文/IME 组合期间（keyCode 229）不拦截，避免破坏拼音输入确认\n" +
                "    if (composing || e.keyCode === 229) return;\n" +
                "    // 补全弹层打开时，回车/Tab 交给补全库选择候选，不插入文本\n" +
                "    if (ddVisible()) return;\n" +
                "    if (e.key === 'Enter' || e.keyCode === 13) {\n" +
                "      // 回车自动缩进 + 块展开：行尾是 { 时自动补配对 } 并定位中间；普通行复制当前行缩进\n" +
                "      e.preventDefault();\n" +
                "      var _tx = codeEl.textContent;\n" +
                "      var _cc = getCaretOffset();\n" +
                "      var _lss = _tx.lastIndexOf('\\n', _cc - 1) + 1;\n" +
                "      var _lt = _tx.substring(_lss, _cc);\n" +
                "      var _in = (_lt.match(/^[ \\t]*/) || [''])[0];\n" +
                "      var _tl = _lt.replace(/[ \\t]+$/, '');\n" +
                "      // 块展开条件：行尾是 {，或光标紧跟在 { 后（自动配对 {} 中间，行首到光标不含 {）\n" +
                "      var _openBrace = (_tl.length > 0 && _tl.charAt(_tl.length - 1) === '{')\n" +
                "              || (_cc > 0 && _tx.charAt(_cc - 1) === '{');\n" +
                "      if (_openBrace) {\n" +
                "        // 块展开：光标后的配对 } 移到新行末尾（父级缩进，顶格对齐），光标停在中间\n" +
                "        var _rest = _tx.substring(_cc);\n" +
                "        var _m2 = _rest.match(/^\\s*}/);\n" +
                "        var _ins2 = '\\n' + _in + '    ' + '\\n' + _in + '}';\n" +
                "        if (_m2) {\n" +
                "          codeEl.textContent = _tx.substring(0, _cc) + _ins2 + _rest.substring(_m2[0].length);\n" +
                "          doHighlight(true);\n" +
                "          setCaret(_cc + 1 + _in.length + 4);\n" +
                "        } else {\n" +
                "          insertTextAtCaret(_ins2);\n" +
                "          setCaret(_cc + 1 + _in.length + 4);\n" +
                "        }\n" +
                "      } else {\n" +
                "        insertTextAtCaret('\\n' + _in);\n" +
                "      }\n" +
                "      return;\n" +
                "    }\n" +
                "    if (e.key === '{' || (e.keyCode === 219 && e.shiftKey)) {\n" +
                "      // 输入 { 自动补配对 }：记录补全位置，防异步 input 二次补全（重复生成右括号）\n" +
                "      e.preventDefault();\n" +
                "      var _o = getCaretOffset();\n" +
                "      var _tx2 = codeEl.textContent;\n" +
                "      if (_tx2.charAt(_o) === '}') {\n" +
                "        lastAutoBrace = _o - 1;\n" +
                "        insertTextAtCaret('{');\n" +
                "      } else {\n" +
                "        lastAutoBrace = _o;\n" +
                "        insertTextAtCaret('{}');\n" +
                "        setCaret(_o + 1);\n" +
                "      }\n" +
                "      return;\n" +
                "    }\n" +
                "    if (e.key === '}' || e.keyCode === 221) {\n" +
                "      var _t3 = codeEl.textContent;\n" +
                "      var _c3 = getCaretOffset();\n" +
                "      // 光标后紧跟 }（自动补的配对）→ 跳过：光标越过配对，不重复输入\n" +
                "      if (_t3.charAt(_c3) === '}') {\n" +
                "        e.preventDefault();\n" +
                "        setCaret(_c3 + 1);\n" +
                "        return;\n" +
                "      }\n" +
                "      // 输入 } 自动反缩进：} 应与代码块开头对齐，块内代码才缩进。\n" +
                "      // 仅当光标前（当前行行首到光标）只有空白时反缩进一层（4 空格）\n" +
                "      var _ls3 = _t3.lastIndexOf('\\n', _c3 - 1) + 1;\n" +
                "      var _pre3 = _t3.substring(_ls3, _c3);\n" +
                "      if (/^[ \\t]+$/.test(_pre3)) {\n" +
                "        e.preventDefault();\n" +
                "        var _ni3 = _pre3.replace(/^ {1,4}/, '');\n" +
                "        if (_ni3 === _pre3 && _pre3.charAt(0) === '\\t') _ni3 = _pre3.substring(1);\n" +
                "        // 复用配对：光标后（跳过空白）紧跟 } 时，删掉该配对，由本次 } 取代，避免重复生成\n" +
                "        var _after3 = _t3.substring(_c3);\n" +
                "        var _am3 = _after3.match(/^\\s*}/);\n" +
                "        if (_am3) {\n" +
                "          codeEl.textContent = _t3.substring(0, _ls3) + _ni3 + '}' + _after3.substring(_am3[0].length);\n" +
                "          doHighlight(true);\n" +
                "          setCaret(_ls3 + _ni3.length + 1);\n" +
                "        } else {\n" +
                "          codeEl.textContent = _t3.substring(0, _ls3) + _ni3 + '}' + _after3;\n" +
                "          doHighlight(true);\n" +
                "          setCaret(_ls3 + _ni3.length + 1);\n" +
                "        }\n" +
                "        return;\n" +
                "      }\n" +
                "    }\n" +
                "    if (e.key === 'Tab' || e.keyCode === 9) {\n" +
                "      e.preventDefault();\n" +
                "      insertTextAtCaret('    ');\n" +
                "    }\n" +
                "  });\n" +
                "  document.addEventListener('selectionchange', function() {\n" +
                "    syncCaretPos();\n" +
                "    var t = '';\n" +
                "    try { t = window.getSelection().toString(); } catch(e) {}\n" +
                "    if (window.AndroidBridge) AndroidBridge.onSelectionChanged(t);\n" +
                "  });\n" +
                "\n" +
                "  function setCode(text) {\n" +
                "    codeEl.textContent = text;\n" +
                "    caretPos = text.length;\n" +
                "    lastTextLen = text.length;\n" +
                "    doHighlight(false);\n" +
                "  }\n" +
                "  function appendCode(text) {\n" +
                "    codeEl.textContent = codeEl.textContent + text;\n" +
                "    lastTextLen = codeEl.textContent.length;\n" +
                "    doHighlight(false);\n" +
                "  }\n" +
                "\n" +
                "  // ===== 代码补全（textcomplete 现成库 + SDK API 词表，不改变任何已有编辑逻辑） =====\n" +
                "  var JAVA_KEYWORDS = ['abstract','assert','boolean','break','byte','case','catch','char','class','const','continue','default','do','double','else','enum','extends','final','finally','float','for','goto','if','implements','import','instanceof','int','interface','long','native','new','package','private','protected','public','return','short','static','strictfp','super','switch','synchronized','this','throw','throws','transient','try','void','volatile','while','true','false','null','var','record','sealed','permits'];\n" +
                "\n" +
                "  function ddVisible() {\n" +
                "    var d = document.querySelector('.textcomplete-dropdown');\n" +
                "    return d != null && d.style.display !== 'none' && d.offsetParent !== null;\n" +
                "  }\n" +
                "\n" +
                "  function apiCandidates(term) {\n" +
                "    var t = term.toLowerCase();\n" +
                "    var seen = {};\n" +
                "    var out = [];\n" +
                "    function push(n) {\n" +
                "      if (!seen[n]) { seen[n] = true; out.push(n); }\n" +
                "    }\n" +
                "    var i, k;\n" +
                "    for (i = 0; i < JAVA_KEYWORDS.length; i++) {\n" +
                "      k = JAVA_KEYWORDS[i];\n" +
                "      if (k.length > t.length && k.toLowerCase().indexOf(t) === 0) push(k);\n" +
                "    }\n" +
                "    var api = window.ANDROID_API;\n" +
                "    if (api) {\n" +
                "      var b = t.charAt(0);\n" +
                "      var cl = api.classes[b];\n" +
                "      if (cl) for (i = 0; i < cl.length && out.length < 40; i++) {\n" +
                "        k = cl[i];\n" +
                "        if (k.length > t.length && k.toLowerCase().indexOf(t) === 0) push(k);\n" +
                "      }\n" +
                "      var ml = api.methods[b];\n" +
                "      if (ml) for (i = 0; i < ml.length && out.length < 60; i++) {\n" +
                "        k = ml[i];\n" +
                "        if (k.length > t.length && k.toLowerCase().indexOf(t) === 0) push(k);\n" +
                "      }\n" +
                "    }\n" +
                "    return out.slice(0, 30);\n" +
                "  }\n" +
                "\n" +
                "  // 点号成员补全：Xxx. 或 android.app.Activity. → 弹该类的方法/字段（词表 classMembers，来自 SDK 官方 API）\n" +
                "  function memberCandidates(className) {\n" +
                "    var api = window.ANDROID_API;\n" +
                "    var out = [];\n" +
                "    if (api && api.classMembers && className) {\n" +
                "      var m = api.classMembers[className];\n" +
                "      if (!m) {\n" +
                "        // 兼容全限定名尾段：android.app.Activity. → Activity\n" +
                "        var dot = className.lastIndexOf('.');\n" +
                "        if (dot >= 0) m = api.classMembers[className.substring(dot + 1)];\n" +
                "      }\n" +
                "      if (!m) {\n" +
                "        // 大小写不敏感回退：activity. → Activity（首字母桶内线性扫描，桶均 ~150 项，快）\n" +
                "        var lc = className.toLowerCase();\n" +
                "        var idx = api.cmIndex && api.cmIndex[lc.charAt(0)];\n" +
                "        if (idx) for (var i = 0; i < idx.length && !m; i++) {\n" +
                "          if (idx[i][1] === lc) m = api.classMembers[idx[i][0]];\n" +
                "        }\n" +
                "      }\n" +
                "      if (m) {\n" +
                "        var i, seen = {};\n" +
                "        for (i = 0; i < m.length && out.length < 30; i++) {\n" +
                "          var n = m[i];\n" +
                "          if (!seen[n]) { seen[n] = true; out.push(n); }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    return out;\n" +
                "  }\n" +
                "\n" +
                "  // 回车自动缩进：新行复制当前行缩进；行尾是 { 时再多缩进一层（4 空格）\n" +
                "  function getAutoIndent() {\n" +
                "    var text = codeEl.textContent;\n" +
                "    var caret = getCaretOffset();\n" +
                "    var lineStart = text.lastIndexOf('\\n', caret - 1) + 1;\n" +
                "    var lineText = text.substring(lineStart, caret);\n" +
                "    var indent = (lineText.match(/^[ \\t]*/) || [''])[0];\n" +
                "    var tail = lineText.replace(/[ \\t]+$/, '');\n" +
                "    var extra = '';\n" +
                "    if (tail.length > 0 && tail.charAt(tail.length - 1) === '{') extra = '    ';\n" +
                "    return '\\n' + indent + extra;\n" +
                "  }\n" +
                "\n" +
                "  // textcomplete 的 ContentEditable 适配器 getTextFromHeadToCaret 只取光标所在文本节点\n" +
                "  // （高亮重绘后节点分裂，拿到的是不完整文本）→ 用 context 钩子重写为完整光标前文本\n" +
                "  // 基于自维护的 caretPos（纯 textContent 计算，不依赖浏览器 selection，重绘竞争下稳定）\n" +
                "  function fullCaretText() {\n" +
                "    var t = codeEl.textContent;\n" +
                "    return t.substring(0, Math.min(caretPos, t.length));\n" +
                "  }\n" +
                "\n" +
                "  // ===== 补全弹层智能定位共用函数 =====\n" +
                "  // 关键场景：手机上手写代码时系统输入法必弹出、占屏幕下方约 1/3，弹层放下方必被遮挡。\n" +
                "  // 因此以 visualViewport（可见区域，自动排除输入法）为可用空间基准：\n" +
                "  //  ① 光标上方够 → 弹层放光标上方（不遮正在输入的行）；\n" +
                "  //  ② 上方不够但下方够 → 放下方（仅在输入法收起/空间充足时走这条）；\n" +
                "  //  ③ 上下都不够 → 贴可见区顶部并动态压缩弹层高度（至少能看到几条候选）。\n" +
                "  function visH() {\n" +
                "    if (window.visualViewport && window.visualViewport.height > 0) return window.visualViewport.height;\n" +
                "    return window.innerHeight;\n" +
                "  }\n" +
                "  function visTop() {\n" +
                "    return (window.visualViewport && window.visualViewport.offsetTop) || 0;\n" +
                "  }\n" +
                "  function computeDdPos(pTop, pBottom, ddH) {\n" +
                "    var vh = visH(), vtop = visTop();\n" +
                "    // 输入法检测：visualViewport 高度明显小于布局视口 = 软键盘弹出（占下方约 1/3）\n" +
                "    // 输入法弹出时弹层绝不能放下方（必被遮），一律放光标上方/贴顶压缩\n" +
                "    var kbOpen = (window.visualViewport && window.visualViewport.height < window.innerHeight - 60);\n" +
                "    var rTop = pTop - vtop;                 // 光标相对可见区顶部\n" +
                "    var rBottom = pBottom - vtop;\n" +
                "    var spaceAbove = rTop;\n" +
                "    var spaceBelow = vh - rBottom;\n" +
                "    var maxH = Math.min(ddH, Math.max(60, vh - 20));\n" +
                "    var top;\n" +
                "    if (spaceAbove >= maxH + 6) {\n" +
                "      top = rTop - maxH - 6;               // 光标上方\n" +
                "    } else if (!kbOpen && spaceBelow >= maxH + 6) {\n" +
                "      top = rBottom + 2;                    // 无输入法且下方够→下方\n" +
                "    } else if (spaceAbove > 60) {\n" +
                "      top = 2; maxH = spaceAbove - 8;       // 输入法弹出/下方不够→贴顶+压缩\n" +
                "    } else {\n" +
                "      top = 2; maxH = Math.max(60, vh - 10 - Math.max(0, spaceAbove));  // 贴顶小框\n" +
                "    }\n" +
                "    return { top: top + vtop, maxH: maxH };\n" +
                "  }\n" +
                "\n" +
                "  try {\n" +
                "    if (window.jQuery && window.jQuery.fn && window.jQuery.fn.textcomplete) {\n" +
                "      window.jQuery(codeEl).textcomplete([{\n" +
                "        // 策略1（优先）：点号成员补全，Xxx. 弹出该类成员\n" +
                "        context: function() { return fullCaretText(); },\n" +
                "        match: function(text) {\n" +
                "          if (composing) return /(?!)a/;\n" +
                "          return /([a-zA-Z_][\\w]*)\\.$/;\n" +
                "        },\n" +
                "        // index 0：term 取整个匹配（含点号，如 \"activity.\"），与字母策略的 term（\"activity\"）区分，\n" +
                "        // 避免 textcomplete 的 term 去重（_term === term 直接跳过）导致点号后不重新搜索\n" +
                "        index: 0,\n" +
                "        search: function(term, callback) {\n" +
                "          callback(memberCandidates(term.replace(/\\.$/, '')));\n" +
                "        },\n" +
                "        replace: function(value) {\n" +
                "          return value;\n" +
                "        },\n" +
                "        maxCount: 30\n" +
                "      },{\n" +
                "        // 策略2：标识符前缀补全（关键字/类/方法）\n" +
                "        context: function() { return fullCaretText(); },\n" +
                "        match: function(text) {\n" +
                "          // textcomplete v1 要求 match 返回正则（内部用 text.match(正则)）；组合输入中返回永不匹配正则\n" +
                "          if (composing) return /(?!)a/;\n" +
                "          return /([a-zA-Z_][\\w]*)$/;\n" +
                "        },\n" +
                "        search: function(term, callback) {\n" +
                "          callback(apiCandidates(term));\n" +
                "        },\n" +
                "        replace: function(value) {\n" +
                "          // 不加尾部空格：避免 textcomplete 把末尾空格转成 &nbsp; 污染代码文本\n" +
                "          return value;\n" +
                "        },\n" +
                "        index: 1,\n" +
                "        maxCount: 30\n" +
                "      }], {\n" +
                "        dropdownClassName: 'aicode-dd textcomplete-dropdown',\n" +
                "        // 补全弹层智能定位：以可见区（visualViewport，排除输入法）为基准，优先光标上方，\n" +
                "        // 上下不足则贴顶压缩高度，右边界防溢出。listPosition 由 textcomplete 每次显示时调用。\n" +
                "        listPosition: function(pos) {\n" +
                "          var dd = document.querySelector('.textcomplete-dropdown');\n" +
                "          var ddH = (dd && dd.offsetHeight > 0) ? dd.offsetHeight : 200;  // 显示前估算\n" +
                "          var lineH = pos.lineHeight || 18;\n" +
                "          var p = computeDdPos(pos.top, pos.top + lineH, ddH);\n" +
                "          var left = pos.left;\n" +
                "          if (left + 260 > window.innerWidth) left = Math.max(2, window.innerWidth - 264);\n" +
                "          this.$el.css({ top: p.top + 'px', left: left + 'px', bottom: 'auto', maxHeight: p.maxH + 'px' });\n" +
                "        }\n" +
                "      });\n" +
                "      // 补全选中后手动触发高亮与回写（textcomplete 的 DOM 插入可能不触发 input 事件）\n" +
                "      window.jQuery(codeEl).on('textComplete:select', function(e, value) {\n" +
                "        if (value) doHighlight(true);\n" +
                "      });\n" +
                "    }\n" +
                "  } catch(e) {}\n" +
                "  // ===== 补全弹层精确微调（显示后用实际高度校正，避免估算误差导致遮挡/越界） =====\n" +
                "  function smartReposition() {\n" +
                "    var dd = document.querySelector('.textcomplete-dropdown');\n" +
                "    if (!dd || dd.offsetHeight === 0) return;\n" +
                "    var sel = window.getSelection();\n" +
                "    if (!sel || sel.rangeCount === 0) return;\n" +
                "    var r = sel.getRangeAt(0).getBoundingClientRect();\n" +
                "    if (!r || (r.width === 0 && r.height === 0)) return;\n" +
                "    var p = computeDdPos(r.top, r.bottom, dd.offsetHeight);\n" +
                "    if (dd.style.top !== p.top + 'px') dd.style.top = p.top + 'px';\n" +
                "    if (dd.style.maxHeight !== p.maxH + 'px') dd.style.maxHeight = p.maxH + 'px';\n" +
                "  }\n" +
                "  (function watchDropdown() {\n" +
                "    var dd = document.querySelector('.textcomplete-dropdown');\n" +
                "    if (!dd) { setTimeout(watchDropdown, 500); return; }  // 弹层懒创建：轮询等待\n" +
                "    // ===== 滑动与选中处理（关键：textcomplete 在 touchstart 上直接选中，滑动时手指碰条目即误选中） =====\n" +
                "    // ①capture 阶段 stopImmediatePropagation 阻断库的 touchstart 选中委托 → 滑动浏览不再误选中；\n" +
                "    // ②改为：触摸滑动浏览候选 + 停止后 tap（click）选中；滑动位移>10px 后 300ms 内的 click 视为误触忽略；\n" +
                "    // ③弹层滚动不穿透页面（overscroll-behavior: contain，CSS 已加）。\n" +
                "    var _ddMoved = false, _ddStart = null;\n" +
                "    dd.addEventListener('touchstart', function(e) {\n" +
                "      _ddStart = { x: e.touches[0].clientX, y: e.touches[0].clientY };\n" +
                "    }, true);\n" +
                "    dd.addEventListener('touchstart', function(e) { e.stopImmediatePropagation(); }, true);\n" +
                "    dd.addEventListener('touchmove', function(e) {\n" +
                "      if (_ddStart && (Math.abs(e.touches[0].clientY - _ddStart.y) > 10 || Math.abs(e.touches[0].clientX - _ddStart.x) > 10)) {\n" +
                "        _ddMoved = true;\n" +
                "      }\n" +
                "    }, { passive: true });\n" +
                "    dd.addEventListener('touchend', function() {\n" +
                "      _ddStart = null;\n" +
                "      setTimeout(function() { _ddMoved = false; }, 300);\n" +
                "    }, { passive: true });\n" +
                "    dd.addEventListener('click', function(e) {\n" +
                "      if (_ddMoved) {\n" +
                "        e.preventDefault(); e.stopPropagation();\n" +
                "        if (e.stopImmediatePropagation) e.stopImmediatePropagation();\n" +
                "        return;\n" +
                "      }\n" +
                "    }, true);\n" +
                "    var _ddShown = false;\n" +
                "    new MutationObserver(function() {\n" +
                "      var isShown = dd.style.display !== 'none';\n" +
                "      if (isShown && !_ddShown) { _ddShown = true; dd.scrollTop = 0; }  // 每次显示重置滚动到顶部\n" +
                "      if (!isShown) _ddShown = false;\n" +
                "      if (isShown) setTimeout(smartReposition, 0);\n" +
                "    }).observe(dd, { attributes: true, attributeFilter: ['style', 'class'], childList: true, subtree: true });\n" +
                "  })();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    /** 文件扩展名 → highlight.js 语言 id */
    public static String detectLanguage(String fileName) {
        if (fileName == null) return "plaintext";
        String n = fileName.toLowerCase();
        if (n.endsWith(".java")) return "java";
        if (n.endsWith(".kt") || n.endsWith(".kts")) return "kotlin";
        if (n.endsWith(".py") || n.endsWith(".pyw")) return "python";
        if (n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx")
                || n.endsWith(".hpp") || n.endsWith(".hh")) return "cpp";
        if (n.endsWith(".c") || n.endsWith(".h")) return "c";
        if (n.endsWith(".xml") || n.endsWith(".axml")) return "xml";
        if (n.endsWith(".json")) return "json";
        if (n.endsWith(".sh") || n.endsWith(".bash") || n.endsWith(".zsh")) return "bash";
        if (n.endsWith(".js") || n.endsWith(".mjs") || n.endsWith(".cjs")) return "javascript";
        if (n.endsWith(".ts") || n.endsWith(".tsx")) return "typescript";
        if (n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".vue")) return "xml";
        if (n.endsWith(".css") || n.endsWith(".scss") || n.endsWith(".less")) return "css";
        if (n.endsWith(".sql")) return "sql";
        if (n.endsWith(".go")) return "go";
        if (n.endsWith(".rs")) return "rust";
        if (n.endsWith(".yaml") || n.endsWith(".yml")) return "yaml";
        if (n.endsWith(".md") || n.endsWith(".markdown")) return "markdown";
        if (n.endsWith(".gradle")) return "gradle";
        if (n.endsWith(".properties") || n.endsWith(".ini")
                || n.equals(".gitignore")) return "properties";
        if (n.equals("dockerfile") || n.endsWith(".dockerfile")) return "dockerfile";
        return "plaintext";
    }

    // ================= 公开接口 =================

    /** 在指定位置插入代码（AI 插入） */
    public void insertCode(String text, int position) {
        if (text == null) return;
        int pos = position;
        if (pos < 0 || pos > currentCode.length()) pos = currentCode.length();
        currentCode = currentCode.substring(0, pos) + text + currentCode.substring(pos);
        dirty = true;
        if (listener != null) listener.onModifyStateChanged(true);
        if (listener != null) listener.onTextChanged(currentCode);
        doSetCode(currentCode);
    }

    /** 替换选中代码 */
    public void replaceSelection(String newText) {
        if (newText == null) return;
        String sel = currentSelection;
        if (sel.length() > 0) {
            int s = currentCode.indexOf(sel);
            if (s >= 0) {
                currentCode = currentCode.substring(0, s) + newText
                        + currentCode.substring(s + sel.length());
            } else {
                currentCode = currentCode + newText;
            }
        } else {
            currentCode = currentCode + newText;
        }
        dirty = true;
        if (listener != null) listener.onModifyStateChanged(true);
        if (listener != null) listener.onTextChanged(currentCode);
        doSetCode(currentCode);
    }

    /** 获取当前文件完整源码 */
    public String getCode() {
        return currentCode;
    }

    /** 获取选中片段 */
    public String getSelection() {
        return currentSelection;
    }

    public List<CodeError> getErrors() { return errors; }

    public List<String> getErrorMessages() {
        List<String> list = new ArrayList<String>();
        for (CodeError e : errors) {
            list.add((e.isError() ? "[Error] " : "[Warn] ")
                    + "Line " + e.line + ": " + e.message);
        }
        return list;
    }

    /** 设置语法错误（保留接口；高亮统一由 highlight.js 负责） */
    public void setErrors(List<CodeError> newErrors) {
        this.errors = newErrors == null
                ? new ArrayList<CodeError>() : new ArrayList<CodeError>(newErrors);
    }

    /** 打开文件 */
    public void openFile(String path, String content) {
        this.filePath = path;
        this.language = detectLanguage(path);
        this.currentCode = content == null ? "" : content;
        this.dirty = false;
        this.snapshot = currentCode;
        langLabel.setText(language.toUpperCase());
        langLabel.setTextColor(Theme.ACCENT_GREEN);
        doSetCode(currentCode);
        if (listener != null) listener.onModifyStateChanged(false);
    }

    public String getFilePath() { return filePath; }
    public String getFileName() {
        if (filePath == null) return "Untitled";
        int i = filePath.lastIndexOf('/');
        return i >= 0 ? filePath.substring(i + 1) : filePath;
    }
    public String getLanguage() { return language; }
    public boolean isDirty() { return dirty; }

    public void markSaved() {
        dirty = false;
        snapshot = currentCode;
        if (listener != null) listener.onModifyStateChanged(false);
    }

    public String getSnapshot() { return snapshot; }
    public void createSnapshot() { snapshot = currentCode; }
    public void restoreSnapshot() {
        currentCode = snapshot == null ? "" : snapshot;
        dirty = false;
        doSetCode(currentCode);
        if (listener != null) listener.onModifyStateChanged(false);
    }

    /** 聚焦代码区（点击即可编辑） */
    public void requestEditorFocus() {
        try {
            if (pageReady) {
                codeWebView.evaluateJavascript("codeEl.focus();", null);
            }
        } catch (Throwable ignored) {
        }
    }

    public int[] getCursorPosition() {
        return new int[]{1, 1};
    }

    public void setEditorListener(EditorListener l) { this.listener = l; }

    private int dp(int v) {
        return UiKit.dp(getContext(), v);
    }
}
