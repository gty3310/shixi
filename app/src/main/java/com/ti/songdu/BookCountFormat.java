package com.ti.songdu;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * v1.26.0 分章书整本今日遍数折算（纯逻辑、无 Android 依赖，桌面 harness 直驱）：
 * 整本遍数＝各章今日遍数之和 ÷ 本书章数（每章占 1/章数），一位小数、四舍五入。
 * 例（论语 20 篇）：7 章各 1 遍 → 0.35 → 显示 0.4；9 章 → 0.45 → 显示 0.5。
 */
public final class BookCountFormat {

    public static String wholeBookToday(int sumToday, int chapterCount) {
        return wholeBook(sumToday, chapterCount);
    }

    /**
     * v1.30.0 整本折算（通用档位）：各章在某时段的遍数之和 ÷ 章数，一位小数、
     * 四舍五入——与 wholeBookToday 同一口径，供首页组头统计行的本周/上周/
     * 本月/总计各档使用（分章账此前没有整本聚合，按同一口径聚合）。
     */
    public static String wholeBook(int sum, int chapterCount) {
        if (chapterCount <= 0) return "0.0";
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(chapterCount), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private BookCountFormat() {}
}
