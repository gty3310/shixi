package com.ti.songdu;

/**
 * v1.31.0 念注触发指针（纯逻辑、无 Android 依赖，桌面 harness 直驱本类验收）。
 *
 * 持有一张按 fireMs 升序的触发表与「下一条待发」指针：
 *  - dueIndex(pos, early)：下一条的 fireMs − early <= pos 即到点（early 为
 *    按倍速现算的提前量，与跟读停止点同公式，调用方传入）；只查不消费。
 *  - markFired(idx)：消费该条（须恰是当前指针处，防错位消费）。
 *  - resync(pos)：seek/回绕/切章后按落点重定指针——落点及身后的 cue 一律
 *    视为已消费（seek 越过不补念），落点之前的 cue 重新待发（拖回去会再念）。
 * 位置单调前进的正常播放下，feed 语义等价于逐条 dueIndex＋markFired。
 */
public final class NoteCueTracker {

    private long[] fires = new long[0];
    private int next = 0;

    public void setCues(long[] fireMs) {
        fires = fireMs != null ? fireMs : new long[0];
        next = 0;
    }

    public void reset() {
        next = 0;
    }

    public int size() {
        return fires.length;
    }

    /** 下一条待发 cue 的下标；已全部消费返回 -1 */
    public int nextIndex() {
        return next < fires.length ? next : -1;
    }

    /** 下一条待发 cue 的 fireMs；无返回 -1 */
    public long nextFireMs() {
        return next < fires.length ? fires[next] : -1;
    }

    /** 下一条是否已到点：到点返回其下标（不消费），未到点/无待发返回 -1 */
    public int dueIndex(long posMs, long earlyMs) {
        if (next >= fires.length) return -1;
        return posMs >= fires[next] - earlyMs ? next : -1;
    }

    /** 消费一条（须为当前指针处；错位调用忽略，防状态机竞态把表吃乱） */
    public void markFired(int idx) {
        if (idx == next && next < fires.length) next++;
    }

    /** 消费到第 idx 条为止（含 idx）：指针移到 idx+1（idx 之前的未发条一并
     *  视为已消费——轨尾补念末注时跳过中途来不及建表的旧 cue 用）；idx 已
     *  在指针身后时不动 */
    public void consumeUpTo(int idx) {
        if (idx >= next && idx < fires.length) next = idx + 1;
    }

    /** seek 语义重定指针：第一个 fireMs > posMs 的 cue 成为下一条待发 */
    public void resync(long posMs) {
        int i = 0;
        while (i < fires.length && fires[i] <= posMs) i++;
        next = i;
    }
}
