package com.aicodestudio.ide.ai.compile;

/**
 * 标准化编译错误条目
 *
 * 将不同底层编译工具（ECJ / 后续 d8 / aapt2）的原生报错统一转换成
 * 该结构，供编辑器报错定位、日志面板展示、以及后续 AI 改错链路消费。
 * 与工具原生输出格式完全解耦。
 */
public class CompileError {

    public static final int SEVERITY_ERROR = 2;
    public static final int SEVERITY_WARNING = 1;
    public static final int SEVERITY_INFO = 0;

    /** 相对项目根的文件路径（如 src/main/java/com/demo/MainActivity.java），解析失败时为原始绝对路径 */
    public final String file;
    /** 行号，1 起；无法定位时为 -1 */
    public final int line;
    /** 列号，1 起；无法定位时为 -1 */
    public final int column;
    /** 错误消息（工具原文） */
    public final String message;
    /** 严重级别：ERROR / WARNING / INFO */
    public final int severity;

    public CompileError(String file, int line, int column, String message, int severity) {
        this.file = file;
        this.line = line;
        this.column = column;
        this.message = message;
        this.severity = severity;
    }

    public boolean isError() {
        return severity == SEVERITY_ERROR;
    }

    /** 紧凑单行文本：file:line:col: message */
    public String toShortString() {
        StringBuilder sb = new StringBuilder();
        if (file != null && file.length() > 0) {
            sb.append(file);
            if (line > 0) {
                sb.append(':').append(line);
                if (column > 0) sb.append(':').append(column);
            }
            sb.append(": ");
        }
        sb.append(message == null ? "" : message);
        return sb.toString();
    }

    @Override
    public String toString() {
        return toShortString();
    }
}
