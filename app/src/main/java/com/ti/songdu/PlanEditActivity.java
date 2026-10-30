package com.ti.songdu;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21.0 计划编辑三态（同一页，按 EXTRA_MODE 区分）：
 *  - preset：9621 确认页——开始日期默认今天、期限 9 个月、截止日自动推算且可自定，
 *    还没下载的三本一行平常话带过；确认才生效
 *  - custom：自定计划——计划名 → 阶段行（书 + 每本总遍数 + 配比天数）→ 添加一本书
 *    → 开始日期 → 期限月数或直接截止日；下方实时互算一行；绝无每日量字段
 *  - settings：已有计划的轻量设置——改截止日、改未开始阶段总遍数、提前开始下一本、
 *    暂停/恢复、结束计划（二次确认）、重新开始（二次确认）
 */
public class PlanEditActivity extends Activity {

    public static final String EXTRA_MODE = "plan_edit_mode";
    public static final String MODE_PRESET = "preset";
    public static final String MODE_CUSTOM = "custom";
    public static final String MODE_SETTINGS = "settings";

    private LinearLayout box;
    private String mode;

    // custom 态的行控件
    private static final class Row {
        PlanBooks.BookInfo info; // 已知书
        String bookId, title;
        CheckBox check;
        EditText passesEt, shareEt;
    }
    private final List<Row> rows = new ArrayList<>();
    private EditText nameEt, startEt, termEt, deadlineEt;
    private TextView summaryTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plan_edit);
        mode = getIntent() != null ? getIntent().getStringExtra(EXTRA_MODE) : MODE_CUSTOM;
        if (mode == null) mode = MODE_CUSTOM;
        box = findViewById(R.id.peContainer);
        findViewById(R.id.peBack).setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.peTitle);
        if (MODE_PRESET.equals(mode)) {
            title.setText("选用 9621 读经大挑战");
            buildPreset();
        } else if (MODE_SETTINGS.equals(mode)) {
            title.setText("计划设置");
            buildSettings();
        } else {
            title.setText("自定计划");
            buildCustom();
        }
    }

    // ---------- 通用小控件 ----------

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String text, float sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(getColor(R.color.ink));
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView small(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private EditText field(String hint, String value, boolean numeric) {
        EditText et = new EditText(this);
        et.setHint(hint);
        if (value != null) et.setText(value);
        et.setTextSize(15);
        et.setBackgroundResource(R.drawable.edit_bg);
        et.setPadding(dp(12), dp(9), dp(12), dp(9));
        if (numeric) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        et.setLayoutParams(lp);
        return et;
    }

    private TextView primaryBtn(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFFFFFFF);
        tv.setBackgroundResource(R.drawable.pill_ink);
        tv.setPadding(dp(16), dp(11), dp(16), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView outlineBtn(String text) {
        TextView tv = primaryBtn(text);
        tv.setTextColor(getColor(R.color.ink));
        tv.setBackgroundResource(R.drawable.pill_outline);
        return tv;
    }

    private LocalDate parseDate(String s, LocalDate fallback) {
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return fallback; }
    }

    private int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private TextWatcher simpleWatcher(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { r.run(); }
        };
    }

    // ---------- preset 确认页 ----------

    private void buildPreset() {
        LocalDate today = LocalDate.now();
        box.addView(label("9621 读经大挑战", 19, true));
        box.addView(small("九个月内读完 · 六本书 · 每本 21 遍"));
        box.addView(small("大学 · 中庸 · 论语 · 孟子 · 六祖坛经 · 道德经"));
        box.addView(label("开始日期", 14, true));
        final EditText startEt2 = field("yyyy-MM-dd", today.toString(), false);
        box.addView(startEt2);
        box.addView(label("期限（月）", 14, true));
        final EditText termEt2 = field("月数", "9", true);
        box.addView(termEt2);
        box.addView(label("截止日期", 14, true));
        final EditText deadlineEt2 = field("yyyy-MM-dd",
                PlanEngine.defaultDeadline(today, 9).toString(), false);
        box.addView(deadlineEt2);
        final boolean[] deadlineTouched = {false};
        deadlineEt2.addTextChangedListener(simpleWatcher(() -> deadlineTouched[0] = true));
        Runnable syncDeadline = () -> {
            if (deadlineTouched[0]) return;
            LocalDate st = parseDate(startEt2.getText().toString(), today);
            int months = parseInt(termEt2.getText().toString(), 9);
            deadlineEt2.setText(PlanEngine.defaultDeadline(st, months).toString());
        };
        startEt2.addTextChangedListener(simpleWatcher(syncDeadline));
        termEt2.addTextChangedListener(simpleWatcher(syncDeadline));
        box.addView(small("孟子、坛经、道德经还没下载，照读、手动记进度即可"));
        TextView confirm = primaryBtn("开始这个计划");
        confirm.setOnClickListener(v -> {
            LocalDate st = parseDate(startEt2.getText().toString(), today);
            LocalDate dl = parseDate(deadlineEt2.getText().toString(),
                    PlanEngine.defaultDeadline(st, parseInt(termEt2.getText().toString(), 9)));
            if (!dl.isAfter(st.minusDays(1))) {
                Toast.makeText(this, "截止日期要晚于开始日期", Toast.LENGTH_SHORT).show();
                return;
            }
            PlanData p = PlanStore.build9621(st, dl);
            p.termMonths = parseInt(termEt2.getText().toString(), 9);
            PlanStore.get(this).saveNewPlan(p);
            Toast.makeText(this, "计划开始了", Toast.LENGTH_SHORT).show();
            finish();
        });
        box.addView(confirm);
    }

    // ---------- custom 编辑页 ----------

    private void buildCustom() {
        box.addView(label("计划名", 14, true));
        nameEt = field("可空", "", false);
        box.addView(nameEt);
        box.addView(label("选书（每本总遍数 · 配比天数可改）", 14, true));
        Store store = Store.get(this);
        // 候选：已知六本 + 书库/列表里已有的其他书
        Map<String, String> cand = new LinkedHashMap<>();
        for (PlanBooks.BookInfo b : PlanBooks.KNOWN) cand.put(b.bookId, b.title);
        for (Track t : store.tracks) {
            String bid = PlanBooks.bookIdOfTrackFull(t);
            if (bid != null && !cand.containsKey(bid)) {
                String tt = t.group != null ? t.group : t.title.replace("《", "").replace("》", "");
                cand.put(bid, tt);
            }
        }
        for (Map.Entry<String, String> e : cand.entrySet()) {
            Row r = new Row();
            r.info = PlanBooks.infoOf(e.getKey());
            r.bookId = e.getKey();
            r.title = e.getValue();
            LinearLayout rowBox = new LinearLayout(this);
            rowBox.setOrientation(LinearLayout.VERTICAL);
            rowBox.setBackgroundResource(R.drawable.card_bg);
            rowBox.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(8);
            rowBox.setLayoutParams(rlp);
            r.check = new CheckBox(this);
            boolean avail = PlanBooks.isAvailable(this, r.bookId);
            r.check.setText("《" + r.title + "》" + (avail ? "" : " · 还没下载"));
            r.check.setTextSize(15);
            rowBox.addView(r.check);
            LinearLayout nums = new LinearLayout(this);
            nums.setOrientation(LinearLayout.HORIZONTAL);
            nums.setGravity(Gravity.CENTER_VERTICAL);
            TextView pLab = new TextView(this);
            pLab.setText("总遍数 ");
            pLab.setTextSize(13);
            pLab.setTextColor(getColor(R.color.muted));
            nums.addView(pLab);
            r.passesEt = new EditText(this);
            r.passesEt.setText("21");
            r.passesEt.setInputType(InputType.TYPE_CLASS_NUMBER);
            r.passesEt.setTextSize(14);
            r.passesEt.setBackgroundResource(R.drawable.edit_bg);
            r.passesEt.setPadding(dp(8), dp(4), dp(8), dp(4));
            r.passesEt.setLayoutParams(new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            nums.addView(r.passesEt);
            TextView sLab = new TextView(this);
            sLab.setText("  配比天数 ");
            sLab.setTextSize(13);
            sLab.setTextColor(getColor(R.color.muted));
            nums.addView(sLab);
            r.shareEt = new EditText(this);
            r.shareEt.setHint("自动");
            r.shareEt.setInputType(InputType.TYPE_CLASS_NUMBER);
            r.shareEt.setTextSize(14);
            r.shareEt.setBackgroundResource(R.drawable.edit_bg);
            r.shareEt.setPadding(dp(8), dp(4), dp(8), dp(4));
            r.shareEt.setLayoutParams(new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            nums.addView(r.shareEt);
            TextView del = new TextView(this);
            del.setText("  删除");
            del.setTextSize(13);
            del.setTextColor(getColor(R.color.muted));
            del.setOnClickListener(v -> { r.check.setChecked(false); rowBox.setVisibility(View.GONE); r.check.setTag("deleted"); updateSummary(); });
            nums.addView(del);
            rowBox.addView(nums);
            r.check.setOnCheckedChangeListener((b, on) -> updateSummary());
            r.passesEt.addTextChangedListener(simpleWatcher(this::updateSummary));
            box.addView(rowBox);
            rows.add(r);
        }
        box.addView(small("添加一本书：勾选上面任意一本即可 · 还没下载的书照样可选入"));
        box.addView(label("开始日期", 14, true));
        startEt = field("yyyy-MM-dd", LocalDate.now().toString(), false);
        box.addView(startEt);
        box.addView(label("期限（月）", 14, true));
        termEt = field("月数", "9", true);
        box.addView(termEt);
        box.addView(label("截止日期（可直接改）", 14, true));
        deadlineEt = field("yyyy-MM-dd", PlanEngine.defaultDeadline(LocalDate.now(), 9).toString(), false);
        box.addView(deadlineEt);
        final boolean[] dlTouched = {false};
        deadlineEt.addTextChangedListener(simpleWatcher(() -> { dlTouched[0] = true; updateSummary(); }));
        Runnable syncDl = () -> {
            if (!dlTouched[0]) {
                LocalDate st = parseDate(startEt.getText().toString(), LocalDate.now());
                deadlineEt.setText(PlanEngine.defaultDeadline(st, parseInt(termEt.getText().toString(), 9)).toString());
            }
            updateSummary();
        };
        startEt.addTextChangedListener(simpleWatcher(syncDl));
        termEt.addTextChangedListener(simpleWatcher(syncDl));
        summaryTv = small("");
        box.addView(summaryTv);
        TextView save = primaryBtn("保存计划");
        save.setOnClickListener(v -> saveCustom());
        box.addView(save);
        updateSummary();
    }

    private List<Row> selectedRows() {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.check.isChecked() && !"deleted".equals(r.check.getTag())) out.add(r);
        }
        return out;
    }

    /** 自定义 shareDays 默认：总遍数 × 单遍时长加权；未下载用已选书平均；都没有则均分 */
    private Map<Row, Integer> estimateShares(List<Row> sel) {
        Store store = Store.get(this);
        Map<Row, Double> weights = new LinkedHashMap<>();
        double sumKnown = 0;
        int knownCount = 0;
        for (Row r : sel) {
            long durMs = PlanBooks.bookPassDurationMs(store, r.bookId);
            if (durMs > 0) {
                double w = parseInt(r.passesEt.getText().toString(), 21) * (durMs / 60000.0);
                weights.put(r, w);
                sumKnown += w;
                knownCount++;
            }
        }
        double avg = knownCount > 0 ? sumKnown / knownCount : 1.0;
        Map<Row, Integer> out = new LinkedHashMap<>();
        for (Row r : sel) {
            String manual = r.shareEt.getText().toString().trim();
            if (!manual.isEmpty()) {
                out.put(r, Math.max(1, parseInt(manual, 1)));
            } else {
                double w = weights.containsKey(r) ? weights.get(r) : avg;
                out.put(r, Math.max(1, (int) Math.round(w)));
            }
        }
        return out;
    }

    private void updateSummary() {
        if (summaryTv == null) return;
        List<Row> sel = selectedRows();
        LocalDate dl = deadlineEt != null
                ? parseDate(deadlineEt.getText().toString(), LocalDate.now()) : LocalDate.now();
        if (sel.isEmpty()) {
            summaryTv.setText("共 0 本书 · 截止 " + dl.getMonthValue() + " 月 " + dl.getDayOfMonth() + " 日");
            return;
        }
        int passes = parseInt(sel.get(0).passesEt.getText().toString(), 21);
        summaryTv.setText("共 " + sel.size() + " 本书 · 每本 " + passes + " 遍 · 截止 "
                + dl.getYear() + " 年 " + dl.getMonthValue() + " 月 " + dl.getDayOfMonth() + " 日");
    }

    private void saveCustom() {
        List<Row> sel = selectedRows();
        if (sel.isEmpty()) {
            Toast.makeText(this, "先选至少一本书", Toast.LENGTH_SHORT).show();
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDate st = parseDate(startEt.getText().toString(), today);
        LocalDate dl = parseDate(deadlineEt.getText().toString(),
                PlanEngine.defaultDeadline(st, parseInt(termEt.getText().toString(), 9)));
        if (!dl.isAfter(st.minusDays(1))) {
            Toast.makeText(this, "截止日期要晚于开始日期", Toast.LENGTH_SHORT).show();
            return;
        }
        PlanData p = new PlanData();
        String nm = nameEt.getText().toString().trim();
        p.name = nm.isEmpty() ? "我的阅读计划" : nm;
        p.id = "plan-custom-" + st.toString();
        p.source = "custom";
        p.startDate = st.toString();
        p.termMonths = parseInt(termEt.getText().toString(), 9);
        p.deadline = dl.toString();
        p.status = PlanData.STATUS_ACTIVE;
        p.createdAt = System.currentTimeMillis();
        Map<Row, Integer> shares = estimateShares(sel);
        for (Row r : sel) {
            int passes = Math.max(1, parseInt(r.passesEt.getText().toString(), 21));
            PlanData.Stage s = PlanBooks.stageSkeleton(r.bookId, r.title, passes);
            s.shareDays = shares.get(r);
            p.stages.add(s);
        }
        p.stages.get(0).startDate = p.startDate;
        PlanStore.get(this).saveNewPlan(p);
        Toast.makeText(this, "计划保存了", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---------- settings 态 ----------

    private void buildSettings() {
        PlanStore ps = PlanStore.get(this);
        PlanData plan = ps.plan();
        if (plan == null || PlanData.STATUS_FINISHED.equals(plan.status)) {
            box.addView(small("现在没有进行中的计划"));
            return;
        }
        LocalDate today = LocalDate.now();
        PlanEngine.Result res = ps.compute(today);
        box.addView(label(plan.name, 18, true));
        box.addView(label("截止日期", 14, true));
        final EditText dlEt = field("yyyy-MM-dd", plan.deadline, false);
        box.addView(dlEt);
        TextView saveDl = outlineBtn("保存截止日期");
        saveDl.setOnClickListener(v -> {
            LocalDate dl = parseDate(dlEt.getText().toString(), null);
            if (dl == null) {
                Toast.makeText(this, "日期格式不对", Toast.LENGTH_SHORT).show();
                return;
            }
            ps.setDeadline(dl.toString());
            Toast.makeText(this, "已保存 · 建议量按新截止日重算", Toast.LENGTH_SHORT).show();
        });
        box.addView(saveDl);

        if (res != null) {
            box.addView(label("未开始阶段的总遍数", 14, true));
            for (int i = 0; i < plan.stages.size(); i++) {
                LocalDate eff = res.effStartPerStage.get(i);
                if (eff != null) continue; // 已开始的不在此改
                final int idx = i;
                PlanData.Stage st = plan.stages.get(i);
                LinearLayout rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                rowL.setGravity(Gravity.CENTER_VERTICAL);
                TextView nm = new TextView(this);
                nm.setText("《" + st.bookTitle + "》 遍数 ");
                nm.setTextSize(14);
                rowL.addView(nm);
                final EditText pEt = new EditText(this);
                pEt.setText(String.valueOf(st.totalPasses));
                pEt.setInputType(InputType.TYPE_CLASS_NUMBER);
                pEt.setTextSize(14);
                pEt.setBackgroundResource(R.drawable.edit_bg);
                pEt.setPadding(dp(8), dp(4), dp(8), dp(4));
                pEt.setLayoutParams(new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
                rowL.addView(pEt);
                TextView sv = new TextView(this);
                sv.setText("  保存");
                sv.setTextSize(14);
                sv.setTextColor(getColor(R.color.accent));
                sv.setOnClickListener(v -> {
                    ps.setStagePasses(idx, Math.max(1, parseInt(pEt.getText().toString(), st.totalPasses)));
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                });
                rowL.addView(sv);
                box.addView(rowL);
            }
            if (res.currentStage >= 0 && res.currentStage + 1 < plan.stages.size()) {
                TextView early = outlineBtn("提前开始下一本《"
                        + plan.stages.get(res.currentStage + 1).bookTitle + "》");
                final int cur = res.currentStage;
                early.setOnClickListener(v -> {
                    ps.startNextStageEarly(cur, LocalDate.now());
                    Toast.makeText(this, "下一本从今天开始计", Toast.LENGTH_SHORT).show();
                    finish();
                });
                box.addView(early);
            }
        }

        boolean paused = PlanData.STATUS_PAUSED.equals(plan.status);
        TextView pauseBtn = outlineBtn(paused ? "恢复计划" : "暂停计划");
        pauseBtn.setOnClickListener(v -> {
            ps.setPaused(!paused);
            Toast.makeText(this, paused ? "计划恢复了" : "计划已暂停", Toast.LENGTH_SHORT).show();
            finish();
        });
        box.addView(pauseBtn);

        TextView restartBtn = outlineBtn("重新开始");
        restartBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("重新开始会清空这个计划的进度，读过的记录本身还在")
                .setNegativeButton("取消", null)
                .setPositiveButton("重新开始", (d, w) -> {
                    ps.restart(LocalDate.now());
                    Toast.makeText(this, "计划重新开始", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show());
        box.addView(restartBtn);

        TextView endBtn = outlineBtn("结束计划");
        endBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("结束这个计划？进度会保留，以后还能查看")
                .setNegativeButton("取消", null)
                .setPositiveButton("结束计划", (d, w) -> {
                    ps.finishPlan();
                    Toast.makeText(this, "计划已结束", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show());
        box.addView(endBtn);
    }
}
