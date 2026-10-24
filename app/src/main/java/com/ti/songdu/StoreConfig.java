package com.ti.songdu;

/**
 * v1.14.0 在线书库配置（WORKFLOW.md 所说的 StoreConfig）。
 * 所有者与仓库名是公开信息、写在源码里；App 令牌的值不进源码——构建时
 * build-manual.sh 从仓库根目录的 store_token.txt（.gitignore 已忽略）生成
 * StoreSecrets.java 到 build 目录，令牌只随发布版 APK 分发。
 */
public final class StoreConfig {
    /** Gitee 所有者（书库各仓都在此账号下） */
    public static final String OWNER = "gty3310";
    /** 总目录仓：其中的 catalog.json 是可下载书目清单 */
    public static final String CATALOG_REPO = "shixi-catalog";
    /** Gitee Open API v5 根地址 */
    public static final String API_BASE = "https://gitee.com/api/v5";
    /** App 令牌（仓库级，仅限书库各仓）；store_token.txt 缺失时构建出空串、书库不可用 */
    public static final String TOKEN = StoreSecrets.GITEE_TOKEN;

    private StoreConfig() {
    }
}
