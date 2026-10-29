package com.aicodestudio.ide.ai.voice;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用侧热词纠正（官方 native 热词在 Android 存在已知 mutex 崩溃 bug：
 * k2-fsa/sherpa-onnx issue #3065，无法使用）。识别完成后用代码词词典做
 * 模糊纠正：编辑距离 <=1 的英文词/短语替换为热词，保留热词的实际效果且不闪退。
 */
public class HotwordCorrector {
    private static List<String> hotwords = null; // 小写热词（单词）

    public static synchronized void ensureLoaded(Context ctx) {
        if (hotwords != null) return;
        hotwords = new ArrayList<>();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("asr/hotwords.txt"), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String w = line.trim().toLowerCase();
                // 只收英文单热词（无空格短语、纯字母），纠正精度高、误伤少
                if (w.length() >= 3 && w.length() <= 18 && w.matches("[a-z]+")) {
                    hotwords.add(w);
                }
            }
            br.close();
        } catch (Throwable t) {
            VoiceLog.input("HotwordCorrector load fail: " + t);
        }
    }

    public static String correct(String text) {
        if (text == null || text.trim().isEmpty() || hotwords == null || hotwords.isEmpty()) {
            return text;
        }
        String trimmed = text.trim();
        StringBuilder sb = new StringBuilder();
        for (String word : trimmed.split("\\s+")) {
            if (word.isEmpty()) continue;
            String wl = word.toLowerCase();
            String best = null;
            int bestD = 2; // 只接受编辑距离 <=1
            for (String hw : hotwords) {
                int d = levenshtein(wl, hw, 2);
                if (d < bestD) { bestD = d; best = hw; }
                if (bestD == 0) break;
            }
            if (best != null && bestD == 1) {
                sb.append(best);
            } else {
                sb.append(word);
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    /** 编辑距离（限制上限 maxDist，提前退出） */
    private static int levenshtein(String a, String b, int maxDist) {
        int m = a.length(), n = b.length();
        if (Math.abs(m - n) > maxDist) return maxDist + 1;
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= n; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > maxDist) return maxDist + 1;
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[n];
    }
}
