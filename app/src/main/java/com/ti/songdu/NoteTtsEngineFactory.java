package com.ti.songdu;

/**
 * v1.31.0 念注引擎工厂：引擎选型已拍板——云端预生成音频包
 * （PackNoteTtsEngine＋TtsPackStore），本地 TTS 方案取消、不再接入。
 * PlayerService 的触发/暂停/续播框架只经 NoteTtsEngine 接口与引擎打交道。
 */
public final class NoteTtsEngineFactory {

    private NoteTtsEngineFactory() {
    }

    /** 当前接入的引擎（云端预生成音频包） */
    public static NoteTtsEngine create() {
        return new PackNoteTtsEngine();
    }
}
