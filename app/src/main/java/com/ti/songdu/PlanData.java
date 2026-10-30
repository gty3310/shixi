package com.ti.songdu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21.0 阅读计划的纯数据模型（无 Android 依赖，桌面 harness 可直驱）。
 * 结构按《阅读计划与9621挑战-设计方案》第三节：Plan = 两个硬参数（总量、截止日）
 * + 有序阶段；manualEntries 按日期存手动入账（单位随该阶段 unit）。
 * 落盘由 PlanStore 负责（files/plan.json），本类只管内存形态。
 */
public final class PlanData {

    public static final String UNIT_WHOLE = "wholePass";
    public static final String UNIT_ZHANG = "zhangBian";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_FINISHED = "finished";

    public static final class Stage {
        public String bookId = "";
        public String bookTitle = "";
        /** wholePass 整本遍 | zhangBian 章·遍 */
        public String unit = UNIT_WHOLE;
        /** 章级单位名：节 / 篇 / 卷 / 品 / 章（整本阶段为空） */
        public String unitName = "";
        public int chapterCount = 0;
        public int totalPasses = 21;
        /** 总工作量：整本 = totalPasses；章级 = chapterCount × totalPasses */
        public int totalUnits = 21;
        /** 配比权重（原表天数或按时长估算） */
        public int shareDays = 1;
        /** 章级每章遍数 N（9621：论语 5、孟子 3、坛经 5、道德经 2；自定义默认 3） */
        public int passesPerChapter = 0;
        /** 激活日 yyyy-MM-dd；null = 尚未激活（由引擎按上一阶段完成日 +1 推导） */
        public String startDate = null;

        public boolean isZhang() { return UNIT_ZHANG.equals(unit); }

        public Stage copy() {
            Stage s = new Stage();
            s.bookId = bookId; s.bookTitle = bookTitle; s.unit = unit; s.unitName = unitName;
            s.chapterCount = chapterCount; s.totalPasses = totalPasses; s.totalUnits = totalUnits;
            s.shareDays = shareDays; s.passesPerChapter = passesPerChapter; s.startDate = startDate;
            return s;
        }
    }

    public String id = "";
    public String name = "";
    /** preset9621 | custom */
    public String source = "custom";
    public String startDate = "";
    public int termMonths = 9;
    public String deadline = "";
    public String status = STATUS_ACTIVE;
    public final List<Stage> stages = new ArrayList<>();
    /** 手动入账：日期 -> (stageIndex -> units)。与方案的 {date:{stageIndex,units}} 同义，
     *  存成 stageIndex 键的映射是为同一天跨阶段（收尾日 + 提前开始日）两笔都能落账 */
    public final Map<String, Map<Integer, Integer>> manualEntries = new LinkedHashMap<>();
    public long createdAt = 0;

    public int manualOn(String date, int stageIndex) {
        Map<Integer, Integer> m = manualEntries.get(date);
        if (m == null) return 0;
        Integer v = m.get(stageIndex);
        return v == null ? 0 : v;
    }

    public void addManual(String date, int stageIndex, int units) {
        if (units == 0) return;
        Map<Integer, Integer> m = manualEntries.get(date);
        if (m == null) { m = new LinkedHashMap<>(); manualEntries.put(date, m); }
        Integer cur = m.get(stageIndex);
        int nv = (cur == null ? 0 : cur) + units;
        if (nv == 0) { m.remove(stageIndex); if (m.isEmpty()) manualEntries.remove(date); }
        else m.put(stageIndex, nv);
    }

    public int manualTotalForStage(int stageIndex) {
        int sum = 0;
        for (Map<Integer, Integer> m : manualEntries.values()) {
            Integer v = m.get(stageIndex);
            if (v != null) sum += v;
        }
        return sum;
    }
}
