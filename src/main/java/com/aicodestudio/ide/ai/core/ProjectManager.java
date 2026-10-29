package com.aicodestudio.ide.ai.core;

import android.content.Context;
import android.os.Build;

import com.aicodestudio.ide.ai.compile.ApkInstaller;
import com.aicodestudio.ide.ai.compile.ArtifactManager;

import java.io.File;
import java.io.FileWriter;

/**
 * 项目创建核心：欢迎界面与 AI（aics create-project）共用同一套模板生成逻辑。
 * 创建完整项目模板（Android/Java/Kotlin/Python/JavaScript），返回项目路径。
 */
public class ProjectManager {

    public static File getProjectsRoot(android.content.Context ctx) {
        // 优先公共目录 /storage/emulated/0/AI Code Studio/projects
        // （SD 卡、用户可在文件管理器查看、与编译产物同目录，便于统一管理）
        try {
            if (Build.VERSION.SDK_INT < 30 || ApkInstaller.hasAllFilesAccess(ctx)) {
                File root = new File(ArtifactManager.getExportDir(), "projects");
                if (!root.exists()) root.mkdirs();
                return root;
            }
        } catch (Throwable ignored) {
            // 环境异常（如测试环境 isExternalStorageManager 不可用）时降级到应用专属目录
        }
        // 无"所有文件访问"权限：回退到应用专属目录（老版本路径，保证项目仍可用）
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        File root = new File(dir, "projects");
        if (!root.exists()) root.mkdirs();
        return root;
    }

    public static String create(android.content.Context ctx, String name, String desc, String language) {
        try {
            File dir = new File(getProjectsRoot(ctx), name);
            if (!dir.exists() && !dir.mkdirs()) return null;

            String lang = language.toLowerCase();
            String pkg = "com.example." + safePkg(name);

            if (lang.startsWith("android")) {
                // 完整 Android Gradle 项目模板（标准目录结构，可直接 gradlew assembleDebug 构建）
                createAndroidProject(dir, name, desc, pkg);
            } else if (lang.startsWith("java")) {
                // 标准 Java 项目结构
                writeFile(new File(dir, "README.md"), readme(name, desc, lang));
                writeFile(new File(dir, "src/com/example/" + safePkg(name) + "/Main.java"),
                        "package com.example." + safePkg(name) + ";\n\n"
                                + "public class Main {\n"
                                + "    public static void main(String[] args) {\n"
                                + "        System.out.println(\"Hello " + name + "\");\n"
                                + "    }\n"
                                + "}\n");
            } else if (lang.startsWith("kotlin")) {
                writeFile(new File(dir, "README.md"), readme(name, desc, lang));
                writeFile(new File(dir, "src/main/kotlin/Main.kt"),
                        "fun main() {\n    println(\"Hello " + name + "\")\n}\n");
            } else if (lang.startsWith("python")) {
                writeFile(new File(dir, "README.md"), readme(name, desc, lang));
                writeFile(new File(dir, "main.py"),
                        "#!/usr/bin/env python3\n"
                                + "# " + name + "\n\n"
                                + "def main():\n"
                                + "    print(\"Hello " + name + "\")\n\n\n"
                                + "if __name__ == \"__main__\":\n"
                                + "    main()\n");
            } else if (lang.startsWith("javascript")) {
                writeFile(new File(dir, "README.md"), readme(name, desc, lang));
                writeFile(new File(dir, "package.json"),
                        "{\n"
                                + "  \"name\": \"" + safePkg(name) + "\",\n"
                                + "  \"version\": \"1.0.0\",\n"
                                + "  \"description\": \"" + esc(name) + "\",\n"
                                + "  \"main\": \"index.js\",\n"
                                + "  \"scripts\": {\n"
                                + "    \"start\": \"node index.js\"\n"
                                + "  }\n"
                                + "}\n");
                writeFile(new File(dir, "index.js"),
                        "// " + name + "\n"
                                + "function main() {\n"
                                + "    console.log('Hello " + name + "');\n"
                                + "}\n\n"
                                + "main();\n");
            } else if (lang.startsWith("c++") || lang.startsWith("c")) {
                writeFile(new File(dir, "README.md"), readme(name, desc, lang));
                writeFile(new File(dir, "CMakeLists.txt"),
                        "cmake_minimum_required(VERSION 3.10)\n"
                                + "project(" + safePkg(name) + ")\n\n"
                                + "add_executable(${PROJECT_NAME} src/main.cpp)\n");
                writeFile(new File(dir, "src/main.cpp"),
                        "#include <iostream>\n\n"
                                + "int main() {\n"
                                + "    std::cout << \"Hello " + name + "\" << std::endl;\n"
                                + "    return 0;\n"
                                + "}\n");
            } else {
                writeFile(new File(dir, "README.md"), readme(name, desc, lang));
                writeFile(new File(dir, "main.txt"), name + "\n" + desc + "\n");
            }

            return dir.getAbsolutePath();
        } catch (Throwable t) {
            android.util.Log.e("Welcome", "createProject failed", t);
            return null;
        }
    }

    static String readme(String name, String desc, String lang) {
        return "# " + name + "\n\n"
                + (desc.length() > 0 ? desc + "\n\n" : "")
                + "项目语言：" + lang + "\n\n"
                + "由 AI Code Studio 创建\n";
    }

    static void createAndroidProject(File dir, String name, String desc, String pkg) {
        // 应用显示名直接用 name（manifest label @string/app_name，可中文）；
        // 主题资源名必须 ASCII 安全（资源名不允许中文）
        String themeName = "Theme." + cap(safePkg(name));
        String pkgPath = pkg.replace('.', '/');

        // ---- 根目录 Gradle 配置 ----
        writeFile(new File(dir, "README.md"),
                "# " + name + "\n\n"
                        + (desc.length() > 0 ? desc + "\n\n" : "")
                        + "项目语言：Android\n\n"
                        + "## 构建\n\n"
                        + "```bash\n./gradlew assembleDebug\n```\n\n"
                        + "APK 输出：`app/build/outputs/apk/debug/app-debug.apk`\n\n"
                        + "由 AI Code Studio 创建\n");

        writeFile(new File(dir, "settings.gradle"),
                "pluginManagement {\n"
                        + "    repositories {\n"
                        + "        google()\n"
                        + "        mavenCentral()\n"
                        + "        gradlePluginPortal()\n"
                        + "    }\n"
                        + "}\n"
                        + "dependencyResolutionManagement {\n"
                        + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
                        + "    repositories {\n"
                        + "        google()\n"
                        + "        mavenCentral()\n"
                        + "    }\n"
                        + "}\n"
                        + "rootProject.name = \"" + esc(name) + "\"\n"
                        + "include ':app'\n");

        writeFile(new File(dir, "build.gradle"),
                "plugins {\n"
                        + "    id 'com.android.application' version '7.4.2' apply false\n"
                        + "}\n");

        writeFile(new File(dir, "gradle.properties"),
                "# Gradle 全局配置\n"
                        + "org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\n"
                        + "android.useAndroidX=true\n"
                        + "android.nonTransitiveRClass=true\n");

        writeFile(new File(dir, ".gitignore"),
                "*.iml\n.gradle\n/local.properties\n/.idea\n.DS_Store\n/build\n"
                        + "captures\n.externalNativeBuild\n.cxx\napp/build\n");

        // ---- app 模块 ----
        writeFile(new File(dir, "app/build.gradle"),
                "plugins {\n"
                        + "    id 'com.android.application'\n"
                        + "}\n\n"
                        + "android {\n"
                        + "    namespace '" + pkg + "'\n"
                        + "    compileSdk 33\n\n"
                        + "    defaultConfig {\n"
                        + "        applicationId \"" + pkg + "\"\n"
                        + "        minSdk 24\n"
                        + "        targetSdk 33\n"
                        + "        versionCode 1\n"
                        + "        versionName \"1.0\"\n"
                        + "    }\n\n"
                        + "    buildTypes {\n"
                        + "        release {\n"
                        + "            minifyEnabled false\n"
                        + "            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'\n"
                        + "        }\n"
                        + "    }\n"
                        + "    compileOptions {\n"
                        + "        sourceCompatibility JavaVersion.VERSION_1_8\n"
                        + "        targetCompatibility JavaVersion.VERSION_1_8\n"
                        + "    }\n"
                        + "}\n\n"
                        + "dependencies {\n"
                        + "    implementation 'androidx.appcompat:appcompat:1.6.1'\n"
                        + "    implementation 'com.google.android.material:material:1.9.0'\n"
                        + "    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'\n"
                        + "}\n");

        writeFile(new File(dir, "app/proguard-rules.pro"),
                "# 混淆规则（默认不开启 minifyEnabled）\n"
                        + "# 如需混淆，取消 app/build.gradle 中 minifyEnabled 的注释并按需添加规则\n");

        // ---- AndroidManifest ----
        writeFile(new File(dir, "app/src/main/AndroidManifest.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n\n"
                        + "    <application\n"
                        + "        android:allowBackup=\"true\"\n"
                        + "        android:icon=\"@mipmap/ic_launcher\"\n"
                        + "        android:label=\"@string/app_name\"\n"
                        + "        android:supportsRtl=\"true\"\n"
                        + "        android:resizeableActivity=\"true\"\n"
                        + "        android:maxAspectRatio=\"2.4\"\n"
                        + "        android:theme=\"@style/" + themeName + "\">\n"
                        + "        <activity\n"
                        + "            android:name=\".MainActivity\"\n"
                        + "            android:exported=\"true\"\n"
                        + "            android:windowLayoutInDisplayCutoutMode=\"shortEdges\"\n"
                        + "            android:configChanges=\"orientation|screenSize|screenLayout|smallestScreenSize|uiMode\">\n"
                        + "            <intent-filter>\n"
                        + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                        + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                        + "            </intent-filter>\n"
                        + "        </activity>\n"
                        + "    </application>\n\n"
                        + "</manifest>\n");

        // ---- 主 Activity ----
        writeFile(new File(dir, "app/src/main/java/" + pkgPath + "/MainActivity.java"),
                "package " + pkg + ";\n\n"
                        + "import android.os.Bundle;\n"
                        + "import androidx.appcompat.app.AppCompatActivity;\n\n"
                        + "/**\n"
                        + " * " + name + " 主界面\n"
                        + (desc.length() > 0 ? " * " + desc + "\n" : "")
                        + " */\n"
                        + "public class MainActivity extends AppCompatActivity {\n\n"
                        + "    @Override\n"
                        + "    protected void onCreate(Bundle savedInstanceState) {\n"
                        + "        super.onCreate(savedInstanceState);\n"
                        + "        setContentView(R.layout.activity_main);\n"
                        + "    }\n"
                        + "}\n");

        // ---- 布局 ----
        writeFile(new File(dir, "app/src/main/res/layout/activity_main.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<androidx.constraintlayout.widget.ConstraintLayout\n"
                        + "    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n"
                        + "    android:layout_width=\"match_parent\"\n"
                        + "    android:layout_height=\"match_parent\">\n\n"
                        + "    <TextView\n"
                        + "        android:id=\"@+id/text_hello\"\n"
                        + "        android:layout_width=\"wrap_content\"\n"
                        + "        android:layout_height=\"wrap_content\"\n"
                        + "        android:text=\"@string/app_name\"\n"
                        + "        android:textSize=\"24sp\"\n"
                        + "        app:layout_constraintBottom_toBottomOf=\"parent\"\n"
                        + "        app:layout_constraintEnd_toEndOf=\"parent\"\n"
                        + "        app:layout_constraintStart_toStartOf=\"parent\"\n"
                        + "        app:layout_constraintTop_toTopOf=\"parent\" />\n\n"
                        + "</androidx.constraintlayout.widget.ConstraintLayout>\n");

        // ---- 资源值 ----
        writeFile(new File(dir, "app/src/main/res/values/strings.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<resources>\n"
                        + "    <string name=\"app_name\">" + esc(name) + "</string>\n"
                        + "</resources>\n");

        writeFile(new File(dir, "app/src/main/res/values/colors.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<resources>\n"
                        + "    <color name=\"purple_200\">#FFBB86FC</color>\n"
                        + "    <color name=\"purple_500\">#FF6200EE</color>\n"
                        + "    <color name=\"purple_700\">#FF3700B3</color>\n"
                        + "    <color name=\"teal_200\">#FF03DAC5</color>\n"
                        + "    <color name=\"teal_700\">#FF018786</color>\n"
                        + "    <color name=\"black\">#FF000000</color>\n"
                        + "    <color name=\"white\">#FFFFFFFF</color>\n"
                        + "</resources>\n");

        writeFile(new File(dir, "app/src/main/res/values/themes.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<resources>\n"
                        + "    <style name=\"" + themeName + "\" parent=\"Theme.MaterialComponents.DayNight.DarkActionBar\">\n"
                        + "        <item name=\"colorPrimary\">@color/purple_500</item>\n"
                        + "        <item name=\"colorPrimaryVariant\">@color/purple_700</item>\n"
                        + "        <item name=\"colorOnPrimary\">@color/white</item>\n"
                        + "        <item name=\"colorSecondary\">@color/teal_200</item>\n"
                        + "        <item name=\"colorSecondaryVariant\">@color/teal_700</item>\n"
                        + "        <item name=\"colorOnSecondary\">@color/black</item>\n"
                        + "    </style>\n"
                        + "</resources>\n");

        writeFile(new File(dir, "app/src/main/res/values-night/themes.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<resources>\n"
                        + "    <style name=\"" + themeName + "\" parent=\"Theme.MaterialComponents.DayNight.DarkActionBar\">\n"
                        + "        <item name=\"colorPrimary\">@color/purple_200</item>\n"
                        + "        <item name=\"colorPrimaryVariant\">@color/purple_700</item>\n"
                        + "        <item name=\"colorOnPrimary\">@color/black</item>\n"
                        + "        <item name=\"colorSecondary\">@color/teal_200</item>\n"
                        + "        <item name=\"colorSecondaryVariant\">@color/teal_200</item>\n"
                        + "        <item name=\"colorOnSecondary\">@color/black</item>\n"
                        + "    </style>\n"
                        + "</resources>\n");

        // ---- 自适应启动图标 ----
        writeFile(new File(dir, "app/src/main/res/drawable/ic_launcher_background.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:shape=\"rectangle\">\n"
                        + "    <solid android:color=\"#FF6200EE\" />\n"
                        + "</shape>\n");

        writeFile(new File(dir, "app/src/main/res/drawable/ic_launcher_foreground.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:width=\"108dp\"\n"
                        + "    android:height=\"108dp\"\n"
                        + "    android:viewportWidth=\"108\"\n"
                        + "    android:viewportHeight=\"108\">\n"
                        + "    <path\n"
                        + "        android:fillColor=\"#FFFFFFFF\"\n"
                        + "        android:pathData=\"M54,30 L74,54 L54,78 L34,54 Z\" />\n"
                        + "</vector>\n");

        writeFile(new File(dir, "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                        + "    <background android:drawable=\"@drawable/ic_launcher_background\" />\n"
                        + "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n"
                        + "</adaptive-icon>\n");

        writeFile(new File(dir, "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                        + "    <background android:drawable=\"@drawable/ic_launcher_background\" />\n"
                        + "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n"
                        + "</adaptive-icon>\n");
    }

    static String safePkg(String name) {
        // 只保留 ASCII 字母数字下划线：
        // Character.isLetterOrDigit 会把中文当 letter 保留进包名，
        // 导致 com.example.中文 包名 → 编译报"中文包名错误"（Android 包名只允许 ASCII）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_') sb.append(ch);
        }
        if (sb.length() == 0) sb.append("app");
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, '_');
        return sb.toString().toLowerCase();
    }

    static void writeFile(File f, String content) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileWriter fw = new FileWriter(f);
            fw.write(content);
            fw.flush();
            fw.close();
        } catch (Throwable t) {
            android.util.Log.e("Welcome", "write file failed: " + f.getName(), t);
        }
    }

    static String cap(String s) {
        if (s == null || s.length() == 0) return "App";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
