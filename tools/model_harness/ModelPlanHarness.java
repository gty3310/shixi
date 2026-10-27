import com.ti.songdu.recite.AsrModelStore;
import com.ti.songdu.recite.ModelPlans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * v1.20.4 识读模型下载计划桌面验收 harness（发版门禁，不过不发）。
 *
 * 直接驱动 App 的计划构建逻辑本体 ModelPlans.basicPlans/svPlans（App 与
 * 本 harness 共用同一份实现，见 AsrModelStore 的委托）——喂给它的是真实
 * Gitee 仓 shixi-models 的 git trees 索引与真实 model-manifest.json（由
 * fetch_data.py 用真实 App 令牌按 App 相同端点取到本地；取数通道不是被
 * 测对象，计划构建的判定——整件/分片顺序、逐片 sha 命中、大小合计——
 * 全部走 App 本体）。禁止在本类里另写一份分片枚举逻辑冒充验收
 * （v1.20.1 的教训）。
 *
 * 断言：基础版 4 件＋高级版 2 件计划全部构建成功；分片计划逐片 sha 全
 * 部命中 shaByPath；高级版大模型 115 片、片大小合计＝内置整件大小；负
 * 例：人为删掉一片映射（SV part037／基础版 encoder part03）→ 构建必须
 * 报错且点名该片。
 *
 * 运行：tools/model_harness/run.sh（先 build-manual.sh 出 build/manual/
 * classes，本类编入其中已构建的 App 类运行）。
 */
public class ModelPlanHarness {

    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        File dataDir = new File(args.length > 0 ? args[0] : "tools/model_harness/out");
        Map<String, String> shaByPath = loadShaByPath(
                new File(dataDir, "sha_by_path.json"));
        System.out.println("git trees 索引：" + shaByPath.size() + " 条（真实仓快照）");
        check("trees 索引非空且含清单", shaByPath.size() > 100
                && shaByPath.containsKey("model-manifest.json"));

        JSONObject manifest = new JSONObject(new String(
                Files.readAllBytes(new File(dataDir, "manifest.json").toPath()),
                StandardCharsets.UTF_8));
        check("清单获取并解析成功", manifest != null);

        ModelPlans.Spec[] basicSpecs = specs(AsrModelStore.FILES);
        ModelPlans.Spec[] svSpecs = specs(AsrModelStore.SV_FILES);

        // ---- 正例：基础版 4 件计划 ----
        List<ModelPlans.Plan> basic = ModelPlans.basicPlans(
                basicSpecs, new HashSet<>(), manifest, shaByPath);
        check("基础版计划构建成功、共 4 件", basic.size() == 4);
        for (ModelPlans.Plan p : basic) {
            if (p.parts != null) {
                check("基础版分片文件 " + p.f.name + "：11 片、逐片 sha 全命中",
                        p.parts.length() == 11 && allPartsHit(p, shaByPath));
            } else {
                check("基础版整文件 " + p.f.name + "：sha 命中", p.sha != null);
            }
        }

        // ---- 正例：高级版 2 件计划 ----
        List<ModelPlans.Plan> sv = ModelPlans.svPlans(
                svSpecs, new HashSet<>(), manifest, shaByPath);
        check("高级版计划构建成功、共 2 件", sv.size() == 2);
        for (ModelPlans.Plan p : sv) {
            if (p.parts != null) {
                long sum = 0;
                for (int i = 0; i < p.parts.length(); i++) {
                    sum += p.parts.getJSONObject(i).getLong("size");
                }
                check("高级版分片文件 " + p.f.name + "：115 片、逐片 sha 全命中、"
                                + "片大小合计＝内置整件大小",
                        p.parts.length() == 115 && allPartsHit(p, shaByPath)
                                && sum == p.f.size);
            } else {
                check("高级版整文件 " + p.f.name + "：sha 命中", p.sha != null);
            }
        }

        // ---- 「只用基础版」路径（v1.20.4 追加需求）：只构建基础版计划 ----
        boolean basicOnlyClean = basic.size() == 4;
        for (ModelPlans.Plan p : basic) {
            if (p.f.name.startsWith("sensevoice")) basicOnlyClean = false;
        }
        check("只用基础版路径：计划只含基础版 4 件、无任何高级版文件", basicOnlyClean);
        List<ModelPlans.Plan> noneLeft = ModelPlans.basicPlans(
                basicSpecs, new HashSet<>(java.util.Arrays.asList(
                        "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                        "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")),
                manifest, shaByPath);
        check("基础版已就绪时计划为空（点识读直接开工、不再弹下载）", noneLeft.isEmpty());

        // ---- 负例：删 SV part037 映射 → 必报错且点名该片 ----
        Map<String, String> brokenSv = new HashMap<>(shaByPath);
        String svPart = "sensevoice/model.int8.onnx.part037";
        brokenSv.remove(svPart);
        String svErr = null;
        try {
            ModelPlans.svPlans(svSpecs, new HashSet<>(), manifest, brokenSv);
        } catch (Exception e) {
            svErr = e.getMessage();
        }
        check("负例 SV 缺 part037：报错且点名该片（实际：" + svErr + "）",
                svErr != null && svErr.contains("part037"));

        // ---- 负例：删基础版 encoder part03 映射 → 必报错且点名该片 ----
        Map<String, String> brokenBasic = new HashMap<>(shaByPath);
        String basicPart = "asr-zh-14m/encoder-epoch-99-avg-1.int8.onnx.part03";
        brokenBasic.remove(basicPart);
        String basicErr = null;
        try {
            ModelPlans.basicPlans(basicSpecs, new HashSet<>(), manifest, brokenBasic);
        } catch (Exception e) {
            basicErr = e.getMessage();
        }
        check("负例基础版缺 encoder part03：报错且点名该片（实际：" + basicErr + "）",
                basicErr != null && basicErr.contains("part03"));

        System.out.println("== ModelPlanHarness: " + passed + " passed, " + failed + " failed ==");
        if (failed > 0) System.exit(1);
    }

    private static boolean allPartsHit(ModelPlans.Plan p, Map<String, String> shaByPath)
            throws Exception {
        JSONArray parts = p.parts;
        for (int i = 0; i < parts.length(); i++) {
            if (shaByPath.get(parts.getJSONObject(i).getString("path")) == null) return false;
        }
        return true;
    }

    private static ModelPlans.Spec[] specs(AsrModelStore.ModelFile[] files) {
        ModelPlans.Spec[] out = new ModelPlans.Spec[files.length];
        for (int i = 0; i < files.length; i++) {
            out[i] = new ModelPlans.Spec(files[i].name, files[i].size, files[i].md5);
        }
        return out;
    }

    // ---------- 载入真实数据快照（fetch_data.py 落盘；非被测对象） ----------

    private static Map<String, String> loadShaByPath(File f) throws Exception {
        JSONObject o = new JSONObject(new String(
                Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        Map<String, String> map = new HashMap<>();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            map.put(k, o.getString(k));
        }
        return map;
    }
}
