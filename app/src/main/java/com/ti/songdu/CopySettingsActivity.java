package com.ti.songdu;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 「复制群消息」设置页（全屏）：从复制弹窗的「设置」按钮打开。
 * 设置即时持久化到 settings.json，下次生成文本立即生效。
 * v1.5：含标题句、打卡天数（开关＋锚点数）、日期行开关三节；
 * 旧「时段多选」已被固定的打卡模板句式取代，整节移除。
 */
public class CopySettingsActivity extends Activity {

    private Store store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.paper));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("复制群消息 · 设置");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("这里设置好后，点首页「复制群消息」生成的文本就按这个来。可随时回来改。");
        hint.setTextSize(13);
        hint.setTextColor(getColor(R.color.muted));
        hint.setPadding(0, dp(8), 0, dp(10));
        root.addView(hint);

        // (0) v1.5 标题句：复制文本的第一行，可自定义（如填自己的名字）；清空则不显示
        TextView labTitle = new TextView(this);
        labTitle.setText("标题句");
        labTitle.setTextSize(16);
        labTitle.setTypeface(null, Typeface.BOLD);
        labTitle.setTextColor(getColor(R.color.ink));
        labTitle.setPadding(0, dp(16), 0, dp(4));
        root.addView(labTitle);

        android.widget.EditText etTitle = new android.widget.EditText(this);
        etTitle.setSingleLine(true);
        etTitle.setText(store.copyTitle);
        etTitle.setTextSize(15);
        etTitle.setTextColor(getColor(R.color.ink));
        etTitle.setBackgroundResource(R.drawable.edit_bg);
        etTitle.setPadding(dp(12), dp(10), dp(12), dp(10));
        etTitle.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                store.copyTitle = s.toString();
                store.saveSettings(); // 与本页其他设置一致：改完即存
            }
        });
        root.addView(etTitle);

        TextView hintTitle = new TextView(this);
        hintTitle.setText("会出现在复制文本的第一行，清空则不显示。三个下划线处换成你的名字即可，例如「郭天宇读经的记录」。");
        hintTitle.setTextSize(12);
        hintTitle.setTextColor(getColor(R.color.muted));
        hintTitle.setPadding(0, dp(6), 0, dp(4));
        root.addView(hintTitle);

        addDivider(root);

        // (0.5) v1.5 打卡天数：显示开关 + 算法说明 + 可编辑的当前天数（锚点语义保存）
        TextView labCheckin = new TextView(this);
        labCheckin.setText("打卡天数");
        labCheckin.setTextSize(16);
        labCheckin.setTypeface(null, Typeface.BOLD);
        labCheckin.setTextColor(getColor(R.color.ink));
        labCheckin.setPadding(0, dp(16), 0, dp(4));
        root.addView(labCheckin);

        LinearLayout rowCheckinSw = new LinearLayout(this);
        rowCheckinSw.setOrientation(LinearLayout.HORIZONTAL);
        rowCheckinSw.setGravity(android.view.Gravity.CENTER_VERTICAL);
        rowCheckinSw.setPadding(0, dp(8), 0, dp(8));
        TextView labSw = new TextView(this);
        labSw.setText("显示打卡天数");
        labSw.setTextSize(16);
        labSw.setTextColor(getColor(R.color.ink));
        labSw.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rowCheckinSw.addView(labSw);
        Switch swCheckin = new Switch(this);
        swCheckin.setChecked(store.checkinShow);
        swCheckin.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            store.checkinShow = checked;
            store.saveSettings();
        });
        rowCheckinSw.addView(swCheckin);
        root.addView(rowCheckinSw);

        TextView hintCheckin = new TextView(this);
        hintCheckin.setText("天数这样算：先在下面填一个今天的天数；之后哪天用软件读了经典、记上遍数，"
                + "那天就自动加一天，没读的日子不涨。数字随时可以改，改完就以今天为新起点。"
                + "打卡天数显示在复制文本的日期行后面（日期行关掉时也不显示）。");
        hintCheckin.setTextSize(12);
        hintCheckin.setTextColor(getColor(R.color.muted));
        hintCheckin.setPadding(0, 0, 0, dp(8));
        root.addView(hintCheckin);

        LinearLayout rowCheckinNum = new LinearLayout(this);
        rowCheckinNum.setOrientation(LinearLayout.HORIZONTAL);
        rowCheckinNum.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView labCur = new TextView(this);
        labCur.setText("当前打卡天数");
        labCur.setTextSize(16);
        labCur.setTextColor(getColor(R.color.ink));
        rowCheckinNum.addView(labCur);
        final int effNow = store.effectiveCheckinDays();
        android.widget.EditText etCheckin = new android.widget.EditText(this);
        etCheckin.setSingleLine(true);
        etCheckin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etCheckin.setText(String.valueOf(effNow));
        etCheckin.setTextSize(15);
        etCheckin.setTextColor(getColor(R.color.ink));
        etCheckin.setBackgroundResource(R.drawable.edit_bg);
        etCheckin.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        etLp.leftMargin = dp(12);
        etCheckin.setLayoutParams(etLp);
        etCheckin.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                try {
                    int v = Integer.parseInt(s.toString().trim());
                    if (v >= 0) {
                        // 手动改数即重新锚定：锚点值＝输入的数，锚点日期＝今天
                        store.checkinCount = v;
                        store.checkinAnchor = Store.dateKey(Store.today());
                        store.saveSettings();
                    }
                } catch (Exception ignored) {
                    // 输入中间态（清空/非法）不保存，保留上一个有效值
                }
            }
        });
        rowCheckinNum.addView(etCheckin);
        TextView labDay = new TextView(this);
        labDay.setText(" 天");
        labDay.setTextSize(15);
        labDay.setTextColor(getColor(R.color.ink));
        rowCheckinNum.addView(labDay);
        root.addView(rowCheckinNum);

        addDivider(root);

        // (a) 是否包含日期行
        LinearLayout rowDate = new LinearLayout(this);
        rowDate.setOrientation(LinearLayout.HORIZONTAL);
        rowDate.setGravity(android.view.Gravity.CENTER_VERTICAL);
        rowDate.setPadding(0, dp(14), 0, dp(14));
        TextView labDate = new TextView(this);
        labDate.setText("包含日期行");
        labDate.setTextSize(16);
        labDate.setTextColor(getColor(R.color.ink));
        labDate.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rowDate.addView(labDate);
        Switch swDate = new Switch(this);
        swDate.setChecked(store.copyIncludeDate);
        swDate.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            store.copyIncludeDate = checked;
            store.saveSettings();
        });
        rowDate.addView(swDate);
        root.addView(rowDate);

        addDivider(root);

        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextSize(15);
        back.setTextColor(getColor(R.color.accent));
        back.setPadding(0, dp(24), 0, 0);
        back.setOnClickListener(v -> finish());
        root.addView(back);
    }

    private void addDivider(LinearLayout root) {
        android.view.View div = new android.view.View(this);
        div.setBackgroundColor(getColor(R.color.border));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        div.setLayoutParams(lp);
        root.addView(div);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
