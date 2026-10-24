package com.ti.songdu.recite;

/**
 * v1.16.0-dev 识读：拼音音节工具（纯 Java，无 Android 依赖，可桌面单测）。
 *
 * 判定口径（用户定案）：按「去声调拼音音节」比对，不按汉字字形——
 * 同音字互换不算错，声调差别不标；只标内容硬错（读错音节、漏字、多字）。
 */
public final class Syllables {

    private Syllables() {}

    /** 带声调拼音 → 去声调音节（ǖǘǚǜ/ü 归一为 v，与 ASR 侧映射保持同一口径） */
    public static String toneless(String py) {
        if (py == null) return "";
        StringBuilder sb = new StringBuilder(py.length());
        for (int i = 0; i < py.length(); i++) {
            sb.append(base(py.charAt(i)));
        }
        return sb.toString().trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static char base(char c) {
        switch (c) {
            case 'ā': case 'á': case 'ǎ': case 'à': case 'Ā': case 'Á': case 'Ǎ': case 'À':
                return 'a';
            case 'ē': case 'é': case 'ě': case 'è': case 'Ē': case 'É': case 'Ě': case 'È':
            case 'ê': case 'Ê':
                return 'e';
            case 'ī': case 'í': case 'ǐ': case 'ì': case 'Ī': case 'Í': case 'Ǐ': case 'Ì':
                return 'i';
            case 'ō': case 'ó': case 'ǒ': case 'ò': case 'Ō': case 'Ó': case 'Ǒ': case 'Ò':
                return 'o';
            case 'ū': case 'ú': case 'ǔ': case 'ù': case 'Ū': case 'Ú': case 'Ǔ': case 'Ù':
                return 'u';
            case 'ǖ': case 'ǘ': case 'ǚ': case 'ǜ': case 'ü':
            case 'Ǖ': case 'Ǘ': case 'Ǚ': case 'Ǜ': case 'Ü':
                return 'v';
            case 'ń': case 'ň': case 'ǹ':
                return 'n';
            case 'ḿ':
                return 'm';
            default:
                return c;
        }
    }

    /** 与 OverlayController/reader.html 同口径的 CJK 判定（内容包的拼音数组按此计数对齐） */
    public static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF) || (cp >= 0x2A700 && cp <= 0x2B73F)
                || (cp >= 0x2B740 && cp <= 0x2B81F) || (cp >= 0x2B820 && cp <= 0x2CEAF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
