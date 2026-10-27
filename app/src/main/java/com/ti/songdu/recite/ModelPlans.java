package com.ti.songdu.recite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 识读模型下载计划构建（纯逻辑，无 Android 依赖）：AsrModelStore 与桌面
 * harness（tools/model_harness）共用同一份实现，harness 连真实仓＋真实
 * 清单＋真实令牌直接驱动本类验收——禁止在 harness 里另写一份枚举逻辑
 * 冒充 App 逻辑。
 *
 * v1.20.4 修复（真机实证根因）：旧实现对每个文件先按整文件名查 git tree
 * 的 sha、之后才读 delivery.parts——以分片存放的文件仓里根本没有整文件，
 * 必然抛「仓内找不到文件」：高级版 model.int8.onnx 因此必挂（用户真机实
 * 报）；基础版 encoder 分片同病，被 ensureSet 的 catch＋HF 静默兜底掩盖。
 * 现统一顺序：先读 delivery 判定整件/分片——分片文件跳过整文件 sha，逐
 * 片校验 parts[i].path 全部命中 shaByPath、片大小合计＝内置整件大小，缺
 * 片报错点名具体分片；整件文件才查整文件 sha。校验不放松：清单 size/md5
 * 与内置校验值逐项核对、基础版 assembled_size/md5 核对全保留，分片拼合
 * 后仍有整件 size＋md5 兜底（AsrModelStore.fetchSplit）。
 */
public final class ModelPlans {

    private ModelPlans() {
    }

    /** 与 AsrModelStore.ModelFile 同构的最小校验三元组 */
    public static final class Spec {
        public final String name;
        public final long size;
        public final String md5;

        public Spec(String name, long size, String md5) {
            this.name = name;
            this.size = size;
            this.md5 = md5;
        }
    }

    public static final class Plan {
        public Spec f;
        public String repoPath;
        public String sha; // 整文件 git blob sha；分片文件为 null（逐片查 shaByPath）
        public Map<String, String> shaByPath;
        public JSONArray parts; // null＝整文件
    }

    /** 基础版计划：清单 files 按 basename 索引，逐件与内置校验核对 */
    public static List<Plan> basicPlans(Spec[] files, Set<String> readyNames,
                                        JSONObject manifest,
                                        Map<String, String> shaByPath) throws Exception {
        Map<String, JSONObject> byName = new HashMap<>();
        JSONArray arr = manifest.getJSONArray("files");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String path = o.getString("path");
            byName.put(path.substring(path.lastIndexOf('/') + 1), o);
        }
        JSONObject delivery = manifest.optJSONObject("delivery");
        List<Plan> out = new ArrayList<>();
        for (Spec f : files) {
            if (readyNames.contains(f.name)) continue;
            JSONObject fe = byName.get(f.name);
            if (fe == null || fe.getLong("size") != f.size
                    || !f.md5.equalsIgnoreCase(fe.optString("md5", ""))) {
                throw new IllegalStateException("模型清单与内置校验值不一致：" + f.name);
            }
            JSONObject del = delivery == null ? null : delivery.optJSONObject(f.name);
            if (del != null
                    && "split_parts_contents_api".equals(del.optString("method"))) {
                long assembledSize = del.getLong("assembled_size");
                String assembledMd5 = del.getString("assembled_md5");
                if (assembledSize != f.size || !f.md5.equalsIgnoreCase(assembledMd5)) {
                    throw new IllegalStateException("模型清单与内置校验值不一致：" + f.name);
                }
            }
            out.add(buildPlan(f, fe.getString("path"), del, shaByPath));
        }
        return out;
    }

    /** 高级版计划：清单 sensevoice 键驱动，逐件与内置校验核对 */
    public static List<Plan> svPlans(Spec[] files, Set<String> readyNames,
                                     JSONObject manifest,
                                     Map<String, String> shaByPath) throws Exception {
        JSONObject sv = manifest.optJSONObject("sensevoice");
        if (sv == null) throw new IllegalStateException("模型清单缺少高级版条目");
        Map<String, JSONObject> byName = new HashMap<>();
        JSONArray arr = sv.getJSONArray("files");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            byName.put(o.getString("name"), o);
        }
        List<Plan> out = new ArrayList<>();
        for (Spec f : files) {
            if (readyNames.contains(f.name)) continue;
            JSONObject fe = byName.get(f.name);
            if (fe == null || fe.getLong("size") != f.size
                    || !f.md5.equalsIgnoreCase(fe.optString("md5", ""))) {
                throw new IllegalStateException("模型清单与内置校验值不一致：" + f.name);
            }
            out.add(buildPlan(f, fe.getString("repo_path"),
                    fe.optJSONObject("delivery"), shaByPath));
        }
        return out;
    }

    /**
     * 单文件计划：先看 delivery 定整件/分片，再查 sha。
     * 分片：逐片 path 必须全部命中 shaByPath（缺片点名该片），片大小合计
     * 必须等于内置整件大小；整件：整文件 path 必须命中 shaByPath。
     */
    private static Plan buildPlan(Spec f, String repoPath, JSONObject delivery,
                                  Map<String, String> shaByPath) throws Exception {
        Plan p = new Plan();
        p.f = f;
        p.repoPath = repoPath;
        p.shaByPath = shaByPath;
        if (delivery != null
                && "split_parts_contents_api".equals(delivery.optString("method"))) {
            JSONArray parts = delivery.getJSONArray("parts");
            long sum = 0;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                String partPath = part.getString("path");
                if (shaByPath.get(partPath) == null) {
                    throw new IllegalStateException("仓内找不到分片：" + partPath);
                }
                sum += part.getLong("size");
            }
            if (sum != f.size) {
                throw new IllegalStateException(
                        "模型清单与内置校验值不一致（分片大小合计）：" + f.name);
            }
            p.parts = parts;
        } else {
            p.sha = shaByPath.get(repoPath);
            if (p.sha == null) {
                throw new IllegalStateException("仓内找不到文件：" + repoPath);
            }
        }
        return p;
    }
}
