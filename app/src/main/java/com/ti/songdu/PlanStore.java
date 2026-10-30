package com.ti.songdu;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;

/**
 * v1.21.0 阅读计划存储（files/plan.json，仿 NotesStore 单例 + 原子写）。
 * 删书不丢计划；暂停时引擎不推建议（页面显示已暂停）；结束计划保留 status=finished
 * 的历史对象，重新开始清空计划自身进度、不动 records 与 chapter_stats 本身。
 */
public final class PlanStore {

    private final Context ctx;
    private final File file;
    private PlanData plan;

    private static PlanStore instance;

    public static synchronized PlanStore get(Context ctx) {
        if (instance == null) instance = new PlanStore(ctx);
        return instance;
    }

    private PlanStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        file = new File(this.ctx.getFilesDir(), "plan.json");
        load();
    }

    public synchronized PlanData plan() { return plan; }

    public synchronized boolean hasActivePlan() {
        return plan != null && !PlanData.STATUS_FINISHED.equals(plan.status);
    }

    // ---------- 9621 预置 ----------

    /** 9621 六阶段（顺序与数值按设计方案第〇节/任务书，不许错） */
    public static PlanData build9621(LocalDate start, LocalDate deadline) {
        PlanData p = new PlanData();
        p.id = "plan-9621-" + start.toString();
        p.name = "9621 读经大挑战";
        p.source = "preset9621";
        p.startDate = start.toString();
        p.termMonths = 9;
        p.deadline = deadline.toString();
        p.status = PlanData.STATUS_ACTIVE;
        p.createdAt = System.currentTimeMillis();
        Object[][] rows = {
                {"daxue", "大学", PlanData.UNIT_WHOLE, "节", 12, 21, 4, 0},
                {"zhongyong", "中庸", PlanData.UNIT_WHOLE, "节", 24, 21, 7, 0},
                {"lunyu", "论语", PlanData.UNIT_ZHANG, "篇", 20, 420, 80, 5},
                {"mengzi", "孟子", PlanData.UNIT_ZHANG, "卷", 14, 294, 98, 3},
                {"tanjing", "六祖坛经", PlanData.UNIT_ZHANG, "品", 10, 210, 40, 5},
                {"daodejing", "道德经", PlanData.UNIT_ZHANG, "章", 81, 1701, 32, 2},
        };
        for (Object[] r : rows) {
            PlanData.Stage s = new PlanData.Stage();
            s.bookId = (String) r[0];
            s.bookTitle = (String) r[1];
            s.unit = (String) r[2];
            s.unitName = (String) r[3];
            s.chapterCount = (Integer) r[4];
            s.totalPasses = 21;
            s.totalUnits = (Integer) r[5];
            s.shareDays = (Integer) r[6];
            s.passesPerChapter = (Integer) r[7];
            p.stages.add(s);
        }
        p.stages.get(0).startDate = p.startDate;
        return p;
    }

    public synchronized void saveNewPlan(PlanData p) {
        plan = p;
        save();
    }

    // ---------- 引擎对接 ----------

    /** done 数据源：records 型按书聚合 / 章账型读 chapter_stats / 手动读 manualEntries */
    public PlanEngine.Provider provider() {
        final Store store = Store.get(ctx);
        final ChapterStatsStore chapters = ChapterStatsStore.get(ctx);
        final PlanData snapshot = plan;
        return new PlanEngine.Provider() {
            @Override
            public int doneOn(int stageIndex, LocalDate date) {
                if (snapshot == null || stageIndex < 0 || stageIndex >= snapshot.stages.size()) return 0;
                PlanData.Stage st = snapshot.stages.get(stageIndex);
                int v = 0;
                if (st.isZhang()) {
                    if (PlanBooks.isAvailable(ctx, st.bookId)) {
                        v += chapters.bookTotalOn(st.bookId, date);
                    }
                } else {
                    for (Track t : PlanBooks.tracksOfBook(store, st.bookId)) {
                        v += store.trackCountOn(t.id, date);
                    }
                }
                v += snapshot.manualOn(date.toString(), stageIndex);
                return v;
            }
        };
    }

    public synchronized PlanEngine.Result compute(LocalDate today) {
        if (plan == null) return null;
        return PlanEngine.compute(plan, provider(), today);
    }

    /**
     * 把引擎推导出的阶段开始日/整体完成落盘（次日自动激活下一阶段的持久形态）。
     * 只补空缺的 startDate，不覆盖显式值（提前开始）。
     */
    public synchronized void persistDerived(PlanEngine.Result r) {
        if (plan == null || r == null) return;
        boolean changed = false;
        for (int i = 0; i < plan.stages.size() && i < r.effStartPerStage.size(); i++) {
            LocalDate eff = r.effStartPerStage.get(i);
            PlanData.Stage st = plan.stages.get(i);
            if (eff != null && (st.startDate == null || st.startDate.isEmpty())) {
                st.startDate = eff.toString();
                changed = true;
            }
        }
        if (r.finished && !PlanData.STATUS_FINISHED.equals(plan.status)) {
            plan.status = PlanData.STATUS_FINISHED;
            changed = true;
        }
        if (changed) save();
    }

    // ---------- 设置动作 ----------

    public synchronized void addManualToday(int stageIndex, int units, LocalDate today) {
        if (plan == null || units == 0) return;
        plan.addManual(today.toString(), stageIndex, units);
        save();
    }

    public synchronized void setDeadline(String deadline) {
        if (plan == null) return;
        plan.deadline = deadline;
        save();
    }

    /** 改未开始阶段的总遍数（totalUnits 随 unit 折算重算） */
    public synchronized void setStagePasses(int stageIndex, int passes) {
        if (plan == null || stageIndex < 0 || stageIndex >= plan.stages.size() || passes <= 0) return;
        PlanData.Stage st = plan.stages.get(stageIndex);
        st.totalPasses = passes;
        st.totalUnits = st.isZhang() ? st.chapterCount * passes : passes;
        save();
    }

    /** 提前开始下一本：下一阶段 startDate = 今天，本阶段窗口就此关闭、已有量保留 */
    public synchronized void startNextStageEarly(int currentStage, LocalDate today) {
        if (plan == null) return;
        int next = currentStage + 1;
        if (next < 0 || next >= plan.stages.size()) return;
        plan.stages.get(next).startDate = today.toString();
        save();
    }

    public synchronized void setPaused(boolean paused) {
        if (plan == null) return;
        plan.status = paused ? PlanData.STATUS_PAUSED : PlanData.STATUS_ACTIVE;
        save();
    }

    /** 结束计划：对象保留（历史可查），页面回到无计划态 */
    public synchronized void finishPlan() {
        if (plan == null) return;
        plan.status = PlanData.STATUS_FINISHED;
        save();
    }

    /** 重新开始：清空计划进度（阶段开始日、手动账），records 与 chapter_stats 不动；
     *  新阶段窗口从新开始日起算，旧账按窗口自然不计入 */
    public synchronized void restart(LocalDate today) {
        if (plan == null) return;
        plan.startDate = today.toString();
        plan.deadline = PlanEngine.defaultDeadline(today, plan.termMonths).toString();
        plan.status = PlanData.STATUS_ACTIVE;
        plan.manualEntries.clear();
        for (PlanData.Stage st : plan.stages) st.startDate = null;
        if (!plan.stages.isEmpty()) plan.stages.get(0).startDate = plan.startDate;
        save();
    }

    // ---------- JSON 落盘 ----------

    private void load() {
        if (!file.exists()) { plan = null; return; }
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            JSONObject o = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
            PlanData p = new PlanData();
            p.id = o.optString("id", "");
            p.name = o.optString("name", "");
            p.source = o.optString("source", "custom");
            p.startDate = o.optString("startDate", "");
            p.termMonths = o.optInt("termMonths", 9);
            p.deadline = o.optString("deadline", "");
            p.status = o.optString("status", PlanData.STATUS_ACTIVE);
            p.createdAt = o.optLong("createdAt", 0);
            JSONArray stages = o.optJSONArray("stages");
            if (stages != null) {
                for (int i = 0; i < stages.length(); i++) {
                    JSONObject so = stages.getJSONObject(i);
                    PlanData.Stage s = new PlanData.Stage();
                    s.bookId = so.optString("bookId", "");
                    s.bookTitle = so.optString("bookTitle", "");
                    s.unit = so.optString("unit", PlanData.UNIT_WHOLE);
                    s.unitName = so.optString("unitName", "");
                    s.chapterCount = so.optInt("chapterCount", 0);
                    s.totalPasses = so.optInt("totalPasses", 21);
                    s.totalUnits = so.optInt("totalUnits", s.totalPasses);
                    s.shareDays = so.optInt("shareDays", 1);
                    s.passesPerChapter = so.optInt("passesPerChapter", 0);
                    s.startDate = so.has("startDate") && !so.isNull("startDate")
                            ? so.optString("startDate", null) : null;
                    if (s.startDate != null && s.startDate.isEmpty()) s.startDate = null;
                    p.stages.add(s);
                }
            }
            JSONObject manual = o.optJSONObject("manualEntries");
            if (manual != null) {
                Iterator<String> dit = manual.keys();
                while (dit.hasNext()) {
                    String date = dit.next();
                    JSONObject dv = manual.optJSONObject(date);
                    if (dv == null) continue;
                    if (dv.has("stageIndex")) {
                        // 方案原始形态 {stageIndex, units}
                        p.addManual(date, dv.optInt("stageIndex", 0), dv.optInt("units", 0));
                    } else {
                        Iterator<String> sit = dv.keys();
                        while (sit.hasNext()) {
                            String k = sit.next();
                            try {
                                p.addManual(date, Integer.parseInt(k), dv.optInt(k, 0));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
            plan = p;
        } catch (Exception ignored) {
            plan = null;
        }
    }

    private void save() {
        if (plan == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("id", plan.id);
            o.put("name", plan.name);
            o.put("source", plan.source);
            o.put("startDate", plan.startDate);
            o.put("termMonths", plan.termMonths);
            o.put("deadline", plan.deadline);
            o.put("status", plan.status);
            o.put("createdAt", plan.createdAt);
            JSONArray stages = new JSONArray();
            for (PlanData.Stage s : plan.stages) {
                JSONObject so = new JSONObject();
                so.put("bookId", s.bookId);
                so.put("bookTitle", s.bookTitle);
                so.put("unit", s.unit);
                so.put("unitName", s.unitName);
                so.put("chapterCount", s.chapterCount);
                so.put("totalPasses", s.totalPasses);
                so.put("totalUnits", s.totalUnits);
                so.put("shareDays", s.shareDays);
                so.put("passesPerChapter", s.passesPerChapter);
                if (s.startDate != null) so.put("startDate", s.startDate);
                stages.put(so);
            }
            o.put("stages", stages);
            JSONObject manual = new JSONObject();
            for (Map.Entry<String, Map<Integer, Integer>> e : plan.manualEntries.entrySet()) {
                JSONObject dv = new JSONObject();
                for (Map.Entry<Integer, Integer> se : e.getValue().entrySet()) {
                    dv.put(String.valueOf(se.getKey()), se.getValue());
                }
                manual.put(e.getKey(), dv);
            }
            o.put("manualEntries", manual);
            byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), "plan.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(bytes);
            out.close();
            if (!tmp.renameTo(file)) {
                FileOutputStream out2 = new FileOutputStream(file);
                out2.write(bytes);
                out2.close();
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
