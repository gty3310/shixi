package com.ti.songdu;

public class Track {
    /** 曲目来源类型：content＝用户经系统文件选择器添加的 content:// URI（v1.0–v1.2 的唯一类型） */
    public static final String KIND_CONTENT = "content";
    /** file＝App 内部存储里的本地文件（v1.3 起的预置完整版音频，uri 字段存绝对路径） */
    public static final String KIND_FILE = "file";

    public String id;
    /** content 类型存 content:// URI 字符串；file 类型存内部存储绝对路径 */
    public String uri;
    /** 来源类型，见 KIND_ 常量；v1.2 及更早的 tracks.json 无此字段，读取时默认 content 自动迁移 */
    public String kind;
    /** 显示名称：卡片标题、通知标题、复制进度文本都用它；记录按 id 关联，改名不丢数据 */
    public String title;
    /** 添加时的来源文件名（标题的原始推导来源），设置页展示用；预置音频记为「内置音频」 */
    public String sourceName;
    public long durationMs;
    public long positionMs;
    /** v1.5 分组标记（如「论语」）：同组曲目在首页收拢为可折叠组；null＝不分组。
     *  老 tracks.json 无此字段，读取时为 null，行为与旧版一致 */
    public String group;
    /** v1.8.2 用户导入字幕生成的内容包 id（"user-<曲目id>"，包体存内部存储
     *  usercontent/ 目录，结构与 assets/content 相同）；null＝无自带文字。
     *  老 tracks.json 无此字段，读取时为 null，行为与旧版一致 */
    public String contentId;
    /** 软件记录之外、此前已读的遍数（偏移量）：总计 = 本软件按天记录合计 + baseTotal */
    public int baseTotal;

    public Track(String id, String uri, String title, long durationMs, long positionMs) {
        this(id, uri, title, title, durationMs, positionMs, 0, KIND_CONTENT);
    }

    public Track(String id, String uri, String title, String sourceName,
                 long durationMs, long positionMs, int baseTotal) {
        this(id, uri, title, sourceName, durationMs, positionMs, baseTotal, KIND_CONTENT);
    }

    public Track(String id, String uri, String title, String sourceName,
                 long durationMs, long positionMs, int baseTotal, String kind) {
        this.id = id;
        this.uri = uri;
        this.title = title;
        this.sourceName = sourceName;
        this.durationMs = durationMs;
        this.positionMs = positionMs;
        this.baseTotal = baseTotal;
        this.kind = kind != null ? kind : KIND_CONTENT;
    }

    public boolean isFile() {
        return KIND_FILE.equals(kind);
    }
}
