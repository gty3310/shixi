package com.ti.songdu;

/**
 * 念注「这一条念过没有、现在念到哪」的唯一状态（纯逻辑、无 Android 依赖）。
 *
 * 一条注在这一遍里只有四个状态：待发 → 前导 → 正在念 → 已念过。
 * 进入前导只发生一次；播放器准备好之前反复询问，不会再开第二个播放器。
 * 已念过的注不会因为「正文还停在触发点前面几毫秒」被重新待发——触发本来就
 * 比句末提前几十毫秒，念完接回正文时位置仍在触发点之前。只有用户明确把
 * 进度拖回到触发点之前一段（超过提前量能解释的范围），这一条才重新待发。
 * 换章、单章循环回到章首，落点远在触发点之前，笔记自然重新待发，那是新的一遍。
 */
public final class NoteCueTracker {

    /**
     * 比「提前触发」和进度抖动都大、又小于普通一句的长度。
     * 落点比触发点早过这么多，才算用户真的倒回去，这条注可以再念。
     */
    public static final long REARM_SLOP_MS = 400;

    private static final int IDLE = 0;
    private static final int LEADIN = 1;
    private static final int SPEAKING = 2;

    private long[] fires = new long[0];
    /** 下一条还没开始的 cue */
    private int next = 0;
    /** 真正开始念过的右边界（不含）。只由 begin/markFired 推进，resync 往前跳不计入。
     *  这样「seek 越过」可以再拖回来补念，而「已经开口念过」不会被提前触发的落点重开。 */
    private int begun = 0;
    private int phase = IDLE;
    private int active = -1;
    private boolean playerClaimed;

    public void setCues(long[] fireMs) {
        fires = fireMs != null ? fireMs : new long[0];
        next = 0;
        begun = 0;
        phase = IDLE;
        active = -1;
        playerClaimed = false;
    }

    public void reset() {
        next = 0;
        begun = 0;
        phase = IDLE;
        active = -1;
        playerClaimed = false;
    }

    public int size() {
        return fires.length;
    }

    /** 下一条待发 cue 的下标；已全部念过、或正有一条在念，返回 -1 */
    public int nextIndex() {
        if (phase != IDLE) return -1;
        return next < fires.length ? next : -1;
    }

    /** 下一条待发 cue 的 fireMs；无返回 -1 */
    public long nextFireMs() {
        if (phase != IDLE || next >= fires.length) return -1;
        return fires[next];
    }

    /**
     * 下一条是否已到点。只查不开始。正有一条在念时返回 -1，
     * 避免前导还没结束又去认下一条。
     */
    public int dueIndex(long posMs, long earlyMs) {
        if (phase != IDLE || next >= fires.length) return -1;
        return posMs >= fires[next] - earlyMs ? next : -1;
    }

    /**
     * 开始第 idx 条（须还没开始，且不能比下一条还早）。
     * 成功后进入前导，这一条算这一遍已经占用，重复调用返回 false。
     * idx 比下一条更后时，中间没来得及念的一并视为跳过（轨尾只补最后一条）。
     */
    public boolean begin(int idx) {
        if (phase != IDLE || idx < next || idx >= fires.length) return false;
        active = idx;
        next = idx + 1;
        begun = next;
        phase = LEADIN;
        playerClaimed = false;
        return true;
    }

    /**
     * 这一条的播放器只许建一次。前导期间轮询会反复走到这里，
     * 第二次起返回 false，调用方不得拆掉已经在准备的播放器再造一个。
     */
    public boolean claimPlayer() {
        if (phase != LEADIN || playerClaimed) return false;
        playerClaimed = true;
        return true;
    }

    /** 音频准备好、真正开口。重复调用无效果。 */
    public void markSpeaking() {
        if (phase == LEADIN) phase = SPEAKING;
    }

    /** 念完、跳过或被用户停掉：前导/在念结束。这一条保持「已占用」，不退回待发。 */
    public void finish() {
        phase = IDLE;
        active = -1;
        playerClaimed = false;
    }

    /** 消费一条（须为当前待发；错位调用忽略）。已有一条在念时不另开。 */
    public void markFired(int idx) {
        if (phase != IDLE) return;
        if (idx == next && next < fires.length) {
            next++;
            begun = next;
        }
    }

    /** 消费到第 idx 条为止（含）。已在指针身后则不动。正有一条在念时不跳。 */
    public void consumeUpTo(int idx) {
        if (phase != IDLE) return;
        if (idx >= next && idx < fires.length) {
            next = idx + 1;
            begun = next;
        }
    }

    /**
     * 按落点重定「下一条」。落点已经过了的注视为念过；
     * 已经开始念的注，落点只比触发点早一点点（提前触发、进度抖动、
     * 念完接回正文）时保持已念过，不会马上再念一遍。
     * 落点明显早于触发点（拖回前面、单章循环回到章首）才重新待发。
     */
    public void resync(long posMs) {
        int byPos = 0;
        while (byPos < fires.length && fires[byPos] <= posMs) byPos++;
        int floor = begun;
        while (floor > 0 && posMs < fires[floor - 1] - REARM_SLOP_MS) floor--;
        if (phase != IDLE && active >= 0) floor = Math.max(floor, active + 1);
        next = Math.max(byPos, floor);
        if (phase != IDLE && active >= 0 && next <= active) {
            phase = IDLE;
            active = -1;
            playerClaimed = false;
        }
    }

    public boolean isIdle() { return phase == IDLE; }
    public boolean isLeadIn() { return phase == LEADIN; }
    public boolean isSpeaking() { return phase == SPEAKING; }
}
