package com.ti.songdu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 更新日志（2026-10-24 用户定版规矩，v1.18.0 首次在时习落地）：
 * 新版首次打开弹一次（可下滑翻看最近至少 5 版，每版含版本号、发布日期、
 * 改了什么、试用路径、为什么改）；设置页常驻「更新日志」入口随时可开。
 * 版本条目随发版在本类追加；仓库 CHANGELOG.md 同步维护。
 */
public final class WhatsNew {

    public static final class Entry {
        public final String version, date;
        public final String[] changes, tryPaths;
        public final String why;

        Entry(String v, String d, String[] c, String[] t, String w) {
            version = v; date = d; changes = c; tryPaths = t; why = w;
        }
    }

    /** 最新在前；至少保留最近 5 版 */
    public static final Entry[] ENTRIES = {
            new Entry("1.20.4", "2026-10-28",
                    new String[]{
                            "修好高级版 AI 模型下不动的问题：之前点下载会先在 0% 干等、最后报一个看不懂的错——问题出在下载清单的核对顺序上，已经改对，高级版能正常下全套了",
                            "下载刚开始时，对话框会先显示「正在获取文件清单…」，清单拿到才切到字节进度；万一网络不通，最多等一分半就会明确告诉你失败原因，不再无限转圈",
                            "识读可以只用基础版 AI，不下那个 239 MB 的大模型：选过高级版也没关系，下载框里有「只用基础版」可以选，选了就不再催你下大模型；日后想用高级版，设置里随时能再下",
                    },
                    new String[]{
                            "设置 → 识读引擎 → 选「高级版 AI」→ 对话框里点下载 → 先看到「正在获取文件清单…」，随后进度条开始走数字，直到下完",
                            "已经下过一部分的不会白下：重试时接着上次的进度续下",
                            "不想下大模型：点识读弹出的下载框里选「只用基础版 AI」；或基础版已下好时选「改用基础版」，立刻就能识读",
                    },
                    "上一版修下载时只验了通道，没验到 App 组装下载清单这一步本身有个顺序错误："
                            + "它先按整文件名去仓里找校验值，可大文件在仓里是拆成一百多片存的，根本没有整文件，"
                            + "必然失败。这一版把这一步抽出来重写，并用真实仓库、真实清单在电脑上把 App 自己的"
                            + "组装逻辑完整跑了一遍验收才发布。"),
            new Entry("1.20.3", "2026-10-27",
                    new String[]{
                            "桌面图标换新了：橄榄绿底、白色椭圆里是《说文解字》小篆的「弦歌」二字竖排——照青山书院标志的式样，就是你选定的那一稿",
                    },
                    new String[]{
                            "看手机桌面：图标已是篆字新图；圆形、圆角方形等不同桌面形状下，椭圆和字都完整、不出框",
                    },
                    "「弦歌」值得一个像样的标记。字形没有让 AI 凭空画：取汉典《说文》小篆原形，"
                            + "再用另外两个来源逐字核对过笔画才排的版，笔画一笔未改。"),
            new Entry("1.20.2", "2026-10-27",
                    new String[]{
                            "修掉加速播放的毛刺声：播放途中改倍速时，播放器会自己在原地快速重播一次再接着读，同样速度、声音干净（AI 朗读最明显，原声轨也一并受益）",
                            "App 改名了：「时习」更名为「弦歌」，取《论语》武城弦歌之意；桌面图标下面的名字、主页标题、帮助页都已换新",
                    },
                    new String[]{
                            "播放中点倍速钮换速度：留意切换那一下，毛刺应当没了",
                            "看桌面图标与主页标题：已显示「弦歌」",
                    },
                    "毛刺只在播放中途改速度时才出现，暂停一下再播就没有——这是按你的真机发现定位的："
                            + "播放中途直接改参数会让声音对不齐，新做法是你一改速，播放器就替你完成"
                            + "「暂停→按新速度→回到原地重播」，你照常点倍速、不用自己先停。改名按你的新决定；"
                            + "包名和数据都没动，原来的书、记录、设置都还在。"),
            new Entry("1.20.1", "2026-10-27",
                    new String[]{
                            "修好识读模型下不下来的问题：高级版 239 MB 模型改走更稳的下载通道，被中断也不用重头再来——下好的部分保留，重试接着下",
                            "下载对话框重做：要下哪一版，按钮直接列出来；点完立刻显示下载进度（已下多少 / 总共多少 · 百分比），能取消；下失败了对话框里会写明原因，可以直接重试",
                            "设置页「模型状态」在下载时同步显示百分比",
                    },
                    new String[]{
                            "设置 → 识读引擎 → 选「高级版 AI」（或点「模型状态」行）→ 对话框里点「下载高级版 AI」→ 看进度走完",
                            "下载中途关掉对话框也没事：到设置页看「模型状态」的百分比，再点它能回到进度对话框",
                    },
                    "旧版弹窗在你的手机上只剩标题和取消、没有可点的下载项；而且旧下载通道"
                            + "对模型的一个分片每次都传残缺，重试多少次都过不去，又没有断点续传，"
                            + "所以 239 MB 永远下不完。这版把通道、续传和对话框一起修了。"),
            new Entry("1.20.0", "2026-10-27",
                    new String[]{
                            "《大学》的 AI 朗读换了新声音：沉稳男声逐句整本重录，字字均匀有分量",
                            "《中庸》也有 AI 朗读了：法师原声与 AI 朗读可选，首播先选、按书记住（要先在书库下载《中庸》，这个选项才会出现）",
                    },
                    new String[]{
                            "播放《中庸》→ 首播选声页选「AI 朗读」；或到「设置 → 朗读声音」点《中庸》重选",
                            "《大学》原先选了 AI 朗读的，更新后直接就是新声音，不用重选",
                    },
                    "旧 AI 声轻重不均、不够庄重，换成你选定的新声音一次录齐两本书；"
                            + "《中庸》自此也能换着声音读，位置照样按句对得上。"),
            new Entry("1.19.0", "2026-10-27",
                    new String[]{
                            "阅读跟随规则重做：暂停时页面绝不自动跳回播放位置，你自己读到哪算哪",
                            "播放中也能自由滑动看别处：一滑动就脱离跟随，不会再被自动拉回去",
                            "想回到正在读的那句，只有两个办法：点顶栏「⇓ 跟随」，或者暂停后再点播放",
                    },
                    new String[]{
                            "打开一本书 → 播到一半暂停 → 在阅读页自己往下读：页面不再跳回去",
                            "播放中用手滑动页面 → 跟随脱离；点顶栏「⇓ 跟随」立即跳回正在读的那句",
                    },
                    "旧规则是手动滚动后 6 秒就自动恢复跟随，你暂停后自己读、或播放中想看别处，"
                            + "总被页面拽回播放位置。跟随改成只听你的：你不点，它不跟。"),
            new Entry("1.18.0", "2026-10-27",
                    new String[]{
                            "识读变双引擎：可在设置里选「基础版 AI」或「高级版 AI」作主引擎",
                            "两个引擎都下载时，没在用的那个会复核每句：两个都报同一个字读错才更可信",
                            "判定改加权口径：常用字单台机器报错不再算错；确定错才标红",
                            "复习页分档：确定错、请你确认的双报字、机器可能听错的、漏读，各自分开",
                            "旧识读记录作废重建（旧文件留着没删，新版不再显示旧数字）",
                            "新增更新日志：新版首次打开弹一次，设置里随时能看",
                    },
                    new String[]{
                            "设置 → 识读 → 选「高级版 AI」→ 按提示下载 → 阅读页开「识读」照常领读",
                            "复习页底部三栏 → 复习：看「请确认」区，点转正或勾销",
                            "设置 → 关于 → 更新日志：翻看历版说明",
                    },
                    "旧判定太冤：常用字被机器听错一大片，复习页全是机器的错不是你的错。"
                            + "双引擎互核实＋加权判定后，确定错回到个位数，复习只练真会错的字。"),
            new Entry("1.17.1", "2026-10-25",
                    new String[]{"《大学》AI 朗读音轨换声重录：换成中文原生男声逐句全本重录"},
                    new String[]{"播放《大学》→ 首播选声时选 AI 朗读，或设置 → 朗读声音重选"},
                    "上一版 AI 声是外腔/尖锐，听着不舒服，按合成回执核对新声重录。"),
            new Entry("1.17.0", "2026-10-25",
                    new String[]{"《大学》双声音：法师原声与 AI 朗读可选，首播先选声，按书记住选择"},
                    new String[]{"播放《大学》→ 选声页两选项各带 15 秒试听"},
                    "同一本书想换个声音读时不用换音频文件，位置还能跨声音对上。"),
            new Entry("1.16.2", "2026-10-24",
                    new String[]{"识读模型下载源切到弦歌自己的模型仓，失败自动回退备用源"},
                    new String[]{"阅读页开「识读」→ 按提示下载即可，流程不变"},
                    "原下载源在国内不稳，换到自己的仓更可靠。"),
            new Entry("1.16.1", "2026-10-24",
                    new String[]{"帮助页新增「识读：它听你读」一节；设置页可隐藏阅读页的识读按钮"},
                    new String[]{"主页 → 帮助 → 识读一节；设置 → 阅读 → 「识读」按钮显隐"},
                    "识读是新功能，得有地方讲清它怎么判错、什么不算错。"),
            new Entry("1.16.0", "2026-10-24",
                    new String[]{
                            "识读上线：你出声领读，App 听着跟随，读错音节的字拼音标红",
                            "底部改三栏：听／历史记录／复习；复习页收录读不熟的字",
                            "点句可写笔记、三色标记",
                    },
                    new String[]{"阅读页开「识读」领读；读完去底部「复习」看错字"},
                    "遍数之外还要知道哪些字真没读对，识读就是为此而建。"),
    };

    private static final String PREFS = "app_prefs";
    private static final String KEY_SEEN = "whatsnew_seen_code";

    private WhatsNew() {}

    /** 新版首次打开弹一次（MainActivity 冷启动调用） */
    public static void maybeShowOnLaunch(Activity act) {
        SharedPreferences sp = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getInt(KEY_SEEN, 0) < BuildInfo.VERSION_CODE) {
            sp.edit().putInt(KEY_SEEN, BuildInfo.VERSION_CODE).apply();
            show(act);
        }
    }

    public static void show(Activity act) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : ENTRIES) {
            sb.append("v").append(e.version).append(" · ").append(e.date).append("\n");
            sb.append("更新了哪里\n");
            for (String c : e.changes) sb.append("• ").append(c).append("\n");
            sb.append("想试这些功能，点哪里\n");
            for (String t : e.tryPaths) sb.append("• ").append(t).append("\n");
            sb.append("为什么改\n").append(e.why).append("\n\n");
        }
        TextView tv = new TextView(act);
        tv.setText(sb.toString().trim());
        tv.setTextSize(14);
        tv.setPadding(dp(act, 20), dp(act, 12), dp(act, 20), dp(act, 8));
        tv.setLineSpacing(0, 1.35f);
        ScrollView sv = new ScrollView(act);
        sv.addView(tv);
        new AlertDialog.Builder(act)
                .setTitle("更新日志")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
