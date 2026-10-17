package com.ti.songdu;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.8.2 宽容版 SRT 解析（用户导入字幕用）：标准时间 HH:MM:SS,mmm（点号亦可）、
 * cue 文本多行合并、剥 HTML 标签与样式覆盖码；解析不出任何 cue 时返回空表，
 * 由调用方提示「这个字幕文件没读出来」且不建书。
 */
public class SrtParser {

    public static class Cue {
        public long startMs;
        public long endMs;
        public String text;
    }

    private static final Pattern TS = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*"
                    + "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");

    private static long ts(Matcher m, int base) {
        long h = Long.parseLong(m.group(base));
        long mi = Long.parseLong(m.group(base + 1));
        long s = Long.parseLong(m.group(base + 2));
        String f = m.group(base + 3);
        long ms = Long.parseLong(f) * (f.length() == 1 ? 100 : f.length() == 2 ? 10 : 1);
        return ((h * 60 + mi) * 60 + s) * 1000 + ms;
    }

    public static List<Cue> parse(String content) {
        List<Cue> out = new ArrayList<>();
        if (content == null) return out;
        String norm = content.replace("\r\n", "\n").replace("\r", "\n");
        if (norm.startsWith("\uFEFF")) norm = norm.substring(1); // BOM（U+FEFF）
        String[] blocks = norm.split("\n[ \t]*\n");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            int tsLine = -1;
            Matcher m = null;
            for (int i = 0; i < lines.length; i++) {
                Matcher mm = TS.matcher(lines[i]);
                if (mm.find()) {
                    tsLine = i;
                    m = mm;
                    break;
                }
            }
            if (m == null) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = tsLine + 1; i < lines.length; i++) {
                String ln = lines[i]
                        .replaceAll("<[^>]*>", "")
                        .replaceAll("\\{[^}]*\\}", "")
                        .trim();
                if (!ln.isEmpty()) sb.append(ln);
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) continue;
            Cue c = new Cue();
            c.startMs = ts(m, 1);
            c.endMs = ts(m, 5);
            if (c.endMs <= c.startMs) c.endMs = c.startMs + 500;
            c.text = text;
            out.add(c);
        }
        out.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }
}
