package com.ti.songdu.recite;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * v1.18.0 识读 v2 逐字先验建档（加权报告 1.1/1.2 的落地载体）。
 *
 * 口径（报告定死，禁用 pypinyin heteronym 原始输出自动建表）：
 *  - 现代通行读音集合与首选读音用下面这张人工核定表（据通行词典读音，
 *    只收录三本书中实际出现的多音字与易混字；未收录的字按单音字处理，
 *    其读音集合取 PinyinTable 的单值/本书读音）。
 *  - 高权重四类：生僻（三级/表外）、古读差异（本书读音不在现代通行集合内）、
 *    多音取非首选、读音冷僻（由前三类近似覆盖，不单设判据，同报告）。
 *  - 未覆盖字的保守默认：单音且非生僻 → 低先验（宁漏勿冤：这类字单引擎
 *    SUB 永不计确定错）。生僻集合为三本书用字的整理表。
 */
public final class PriorTable {

    private PriorTable() {}

    /** 人工核定：字 → 现代通行去声调音节集合（真多音才多值，声调互异不算多音） */
    private static final Map<String, String[]> MODERN = new HashMap<>();
    /** 人工核定：多音字 → 现代首选去声调音节 */
    private static final Map<String, String> FIRST = new HashMap<>();
    /** 古读差异：字 → 本书古读去声调音节（本书读此音即命中古读类） */
    private static final Map<String, String> ANCIENT = new HashMap<>();
    /** 生僻字（三级/表外，三本书用字整理） */
    private static final Set<String> RARE = new HashSet<>();

    private static void poly(String ch, String first, String... all) {
        MODERN.put(ch, all); FIRST.put(ch, first);
    }

    static {
        poly("其", "qi", "qi", "ji");
        poly("否", "fou", "fou", "pi");
        poly("于", "yu", "yu", "wu");
        poly("於", "yu", "yu", "wu");
        poly("乐", "le", "le", "yue");
        poly("樂", "le", "le", "yue");
        poly("好", "hao", "hao");
        poly("恶", "e", "e", "wu");
        poly("惡", "e", "e", "wu");
        poly("胖", "pang", "pang", "pan");
        poly("正", "zheng", "zheng");
        poly("知", "zhi", "zhi");
        poly("行", "xing", "xing", "hang");
        poly("长", "chang", "chang", "zhang");
        poly("長", "chang", "chang", "zhang");
        poly("中", "zhong", "zhong");
        poly("为", "wei", "wei");
        poly("為", "wei", "wei");
        poly("分", "fen", "fen");
        poly("传", "zhuan", "zhuan", "chuan");
        poly("傳", "zhuan", "zhuan", "chuan");
        poly("说", "shuo", "shuo", "yue");
        poly("說", "shuo", "shuo", "yue");
        poly("数", "shu", "shu", "shuo");
        poly("數", "shu", "shu", "shuo");
        poly("应", "ying", "ying");
        poly("應", "ying", "ying");
        poly("与", "yu", "yu");
        poly("與", "yu", "yu");
        poly("兴", "xing", "xing");
        poly("興", "xing", "xing");
        poly("朝", "chao", "chao", "zhao");
        poly("曾", "zeng", "zeng", "ceng");
        poly("参", "can", "can", "cen", "shen");
        poly("參", "can", "can", "cen", "shen");
        poly("省", "sheng", "sheng", "xing");
        poly("解", "jie", "jie", "xie");
        poly("辟", "pi", "pi", "bi");
        poly("孙", "sun", "sun", "xun");
        poly("孫", "sun", "sun", "xun");
        poly("汤", "tang", "tang", "shang");
        poly("湯", "tang", "tang", "shang");
        poly("复", "fu", "fu");
        poly("復", "fu", "fu");
        poly("丧", "sang", "sang");
        poly("喪", "sang", "sang");
        poly("调", "tiao", "tiao", "diao");
        poly("調", "tiao", "tiao", "diao");
        poly("便", "bian", "bian", "pian");
        poly("重", "zhong", "zhong", "chong");
        poly("量", "liang", "liang");
        poly("率", "lv", "lv", "shuai");
        poly("宿", "su", "su", "xiu");
        poly("舍", "she", "she");
        poly("捨", "she", "she");
        poly("卷", "juan", "juan");
        poly("强", "qiang", "qiang", "jiang");
        poly("強", "qiang", "qiang", "jiang");
        poly("弹", "tan", "tan", "dan");
        poly("彈", "tan", "tan", "dan");
        poly("禁", "jin", "jin");
        poly("燕", "yan", "yan");
        poly("识", "shi", "shi", "zhi");
        poly("識", "shi", "shi", "zhi");
        poly("藏", "cang", "cang", "zang");
        poly("薄", "bo", "bo", "bao");
        poly("泊", "bo", "bo", "po");
        poly("择", "ze", "ze", "zhai");
        poly("擇", "ze", "ze", "zhai");
        poly("骑", "qi", "qi");
        poly("騎", "qi", "qi");
        poly("散", "san", "san");
        poly("磨", "mo", "mo");
        poly("担", "dan", "dan");
        poly("擔", "dan", "dan");
        poly("荷", "he", "he");
        poly("盖", "gai", "gai", "ge");
        poly("蓋", "gai", "gai", "ge");
        poly("盛", "sheng", "sheng", "cheng");
        poly("甸", "dian", "dian");
        poly("甸", "dian", "dian");

        ANCIENT.put("樂", "yao"); ANCIENT.put("乐", "yao");
        ANCIENT.put("惡", "wu"); ANCIENT.put("恶", "wu");
        ANCIENT.put("胖", "pan");
        ANCIENT.put("辟", "bi");
        ANCIENT.put("齊", "zhai"); ANCIENT.put("齐", "zhai");
        ANCIENT.put("治", "chi");

        String rare = "菉僩諠恂猗淇澳斐磋琢瑟喧慄洵訒訑誶菑賾僻璿璣璇璪璲璸璹璺"
                + "皞皦皭皪皫皣皛皙皝皞昫昡昤昽昿晛晞晢晣晤晥晦晧晩晪晫晬晭普景晰晱晲晳晴晵晶晷晸晹智晻晼晽晾晿"
                + "諠諡諢諣諤諥諦諧諨諩諪諫諬諭諮諯諰諱諲諳諴諵諶諷諸諹諺諻諼諽諾諿"
                + "僩僪僫僬僭僮僯僰僱僲僳僴僶僷僸僺僻僼僽僾僿";
        for (int i = 0; i < rare.length(); i++) RARE.add(String.valueOf(rare.charAt(i)));
        // 三本书明确点名的生僻/冷僻字（淇澳段等）
        for (String s : new String[]{"菉", "僩", "諠", "恂", "猗", "淇", "澳", "斐", "洵", "訒", "誶", "諡", "諢", "賾", "僻", "璿", "揜", "謙", "厭", "諒", "諝"}) {
            RARE.add(s);
        }
    }

    public static final int W_HIGH = 2, W_MID = 0, W_LOW = -3;

    public static final String CAT_ANCIENT = "ancient";   // 古读异今
    public static final String CAT_RARE = "rare";         // 生僻
    public static final String CAT_POLY_NONFIRST = "polyNonFirst"; // 多音取非首选
    public static final String CAT_ODD = "odd";           // 冷僻读（古读的近似归类）
    public static final String CAT_NONE = "";

    public static final class Prior {
        public int w;
        public String category = CAT_NONE;
        public boolean highRisk() {
            return w == W_HIGH;
        }
    }

    /**
     * 按「字＋本句读音」建档。bookPyToneless 为本书去声调音节。
     * 现代集合：优先人工核定表；未收录时取 PinyinTable——若其集合大小为 1
     * 按单音处理；多值时只认「含本书读音且集合由通行读音构成」的保守口径：
     * 集合多值但无人工核定时不升多音档（防 heteronym 噪声误升，同报告 1.2），
     * 按单音/首选处理。
     */
    public static Prior of(String hanzi, String bookPyToneless) {
        Prior p = new Prior();
        String book = bookPyToneless == null ? "" : bookPyToneless;
        String anc = ANCIENT.get(hanzi);
        if (anc != null && anc.equals(book)) {
            p.w = W_HIGH; p.category = CAT_ANCIENT; return p;
        }
        String[] modern = MODERN.get(hanzi);
        if (modern != null) {
            boolean inModern = false;
            for (String s : modern) if (s.equals(book)) inModern = true;
            if (!inModern) { // 本书读音根本不在现代通行集合内＝古读差异
                p.w = W_HIGH; p.category = CAT_ANCIENT; return p;
            }
            java.util.Set<String> uniq = new HashSet<>();
            for (String s : modern) uniq.add(s);
            if (uniq.size() > 1) {
                String first = FIRST.get(hanzi);
                if (first != null && !first.equals(book)) {
                    p.w = W_HIGH; p.category = CAT_POLY_NONFIRST; return p;
                }
                p.w = W_MID; p.category = CAT_NONE; return p; // 真多音取首选＝中
            }
            // 单音（集合内仅声调差）落到下方常用判定
        }
        if (RARE.contains(hanzi)) {
            p.w = W_HIGH; p.category = CAT_RARE; return p;
        }
        // 未覆盖字保守默认：低先验（单引擎 SUB 永不计确定错，宁漏勿冤）
        p.w = W_LOW; p.category = CAT_NONE; return p;
    }

    /** 是否四类高权重（复习页主列表人口判定用） */
    public static boolean isHighCategory(String category) {
        return CAT_ANCIENT.equals(category) || CAT_RARE.equals(category)
                || CAT_POLY_NONFIRST.equals(category) || CAT_ODD.equals(category);
    }
}
