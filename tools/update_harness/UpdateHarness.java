import com.ti.songdu.UpdateLogic;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * v1.22.0 应用内更新桌面 harness：直驱 App 的 UpdateLogic 本体（清单解析、
 * 版本比较、忽略名单、双源回退、校验判定、节流、sha256）。
 * 运行：javac -cp <org.json jar> -sourcepath app/src/main/java \
 *         tools/update_harness/UpdateHarness.java（见同目录 run.sh）
 */
public class UpdateHarness {

    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }

    static final String FULL = "{"
            + "\"versionCode\":46,\"versionName\":\"1.22.0\","
            + "\"apkFileName\":\"xiange-v1220.apk\",\"size\":36500000,"
            + "\"sha256\":\"AAAA\",\"notes\":\"应用内更新上线\","
            + "\"sources\":{"
            + "\"gitee\":{\"apk\":\"https://gitee.example/apk\"},"
            + "\"github\":{\"apk\":\"https://github.example/apk\"}}}";

    public static void main(String[] args) throws Exception {
        // ---------- 版本比较 ----------
        check("remote 46 > local 45 -> hasUpdate", UpdateLogic.hasUpdate(46, 45));
        check("remote 46 == local 46 -> no update", !UpdateLogic.hasUpdate(46, 46));
        check("remote 44 < local 46 -> no update", !UpdateLogic.hasUpdate(44, 46));
        check("compareCodes sign", UpdateLogic.compareCodes(47, 46) > 0
                && UpdateLogic.compareCodes(46, 46) == 0
                && UpdateLogic.compareCodes(45, 46) < 0);

        // ---------- 清单解析（完整） ----------
        UpdateLogic.Manifest m = UpdateLogic.parseManifest(FULL);
        check("full manifest parses", m != null);
        check("fields: code/name/size/sha/notes", m != null && m.versionCode == 46
                && "1.22.0".equals(m.versionName) && m.size == 36500000L
                && "AAAA".equals(m.sha256) && "应用内更新上线".equals(m.notes));
        check("source order gitee->github", m != null && m.sources.size() == 2
                && "gitee".equals(m.sources.get(0).name)
                && "github".equals(m.sources.get(1).name));
        check("whole-file sources not sharded", m != null
                && !m.sources.get(0).sharded() && !m.sources.get(1).sharded());

        // ---------- 忽略名单 ----------
        check("ignored hit suppresses auto-show",
                !UpdateLogic.shouldAutoShow(m, 45, 46));
        check("ignored miss still auto-shows",
                UpdateLogic.shouldAutoShow(m, 45, 99));
        check("ignored=0 never suppresses",
                UpdateLogic.shouldAutoShow(m, 45, 0));
        check("manual path ignores the ignore-list (hasUpdate still true)",
                UpdateLogic.hasUpdate(m.versionCode, 45));
        check("no update -> no auto-show even when not ignored",
                !UpdateLogic.shouldAutoShow(UpdateLogic.parseManifest(
                        FULL.replace("46", "45")), 45, 0));

        // ---------- 双源回退判定 ----------
        check("fallback on NETWORK", UpdateLogic.shouldFallback(UpdateLogic.Failure.NETWORK));
        check("fallback on HTTP", UpdateLogic.shouldFallback(UpdateLogic.Failure.HTTP));
        check("fallback on IO", UpdateLogic.shouldFallback(UpdateLogic.Failure.IO));
        check("fallback on VERIFY", UpdateLogic.shouldFallback(UpdateLogic.Failure.VERIFY));
        check("no fallback on CANCELLED",
                !UpdateLogic.shouldFallback(UpdateLogic.Failure.CANCELLED));
        check("no fallback on NO_WIFI",
                !UpdateLogic.shouldFallback(UpdateLogic.Failure.NO_WIFI));

        // ---------- 清单字段缺失容错 ----------
        String noNotes = FULL.replace(",\"notes\":\"应用内更新上线\"", "");
        UpdateLogic.Manifest mn = UpdateLogic.parseManifest(noNotes);
        check("missing notes tolerated (empty string)",
                mn != null && "".equals(mn.notes) && mn.versionCode == 46);
        String noGithub = "{"
                + "\"versionCode\":46,\"versionName\":\"1.22.0\",\"size\":1,\"sha256\":\"x\","
                + "\"sources\":{\"gitee\":{\"apk\":\"https://g.example/a\"}}}";
        UpdateLogic.Manifest mg = UpdateLogic.parseManifest(noGithub);
        check("missing github source tolerated (gitee only)",
                mg != null && mg.sources.size() == 1
                        && "gitee".equals(mg.sources.get(0).name));
        String noCode = "{\"versionName\":\"1.22.0\",\"sources\":"
                + "{\"gitee\":{\"apk\":\"https://g.example/a\"}}}";
        check("missing versionCode -> null (fatal, no guessing)",
                UpdateLogic.parseManifest(noCode) == null);
        check("garbage json -> null", UpdateLogic.parseManifest("{not json") == null);
        check("no sources -> null", UpdateLogic.parseManifest(
                "{\"versionCode\":46}") == null);
        check("null input -> null", UpdateLogic.parseManifest(null) == null);

        // ---------- 分片清单 ----------
        String sharded = "{"
                + "\"versionCode\":47,\"versionName\":\"1.23.0\",\"size\":10,\"sha256\":\"y\","
                + "\"sources\":{\"gitee\":{\"parts\":[{\"url\":\"https://g/p0\"},"
                + "{\"url\":\"https://g/p1\"},{\"url\":\"https://g/p2\"}]},"
                + "\"github\":{\"apk\":\"https://h/a\"}}}";
        UpdateLogic.Manifest ms = UpdateLogic.parseManifest(sharded);
        check("sharded manifest parses, 3 parts in order", ms != null
                && ms.sources.get(0).sharded() && ms.sources.get(0).urls.length == 3
                && ms.sources.get(0).urls[2].endsWith("p2"));
        check("fake-newer manifest (47 > local 46) -> hasUpdate",
                ms != null && UpdateLogic.hasUpdate(ms.versionCode, 46));

        // ---------- 校验判定 ----------
        String shaAbc = UpdateLogic.sha256Hex(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        check("sha256 known vector (abc)", "ba7816bf8f01cfea414140de5dae2223"
                .concat("b00361a396177a9cb410ff61f20015ad").equals(shaAbc));
        check("verify OK", UpdateLogic.verifyFile(3, 3, shaAbc, shaAbc)
                == UpdateLogic.Verify.OK);
        check("verify OK case-insensitive sha", UpdateLogic.verifyFile(
                3, 3, shaAbc, shaAbc.toUpperCase()) == UpdateLogic.Verify.OK);
        check("verify size mismatch", UpdateLogic.verifyFile(4, 3, shaAbc, shaAbc)
                == UpdateLogic.Verify.SIZE_MISMATCH);
        check("verify sha mismatch", UpdateLogic.verifyFile(3, 3, shaAbc,
                "0000000000000000000000000000000000000000000000000000000000000000")
                == UpdateLogic.Verify.SHA_MISMATCH);
        check("verify sha missing in manifest -> SHA_MISSING (not installed)",
                UpdateLogic.verifyFile(3, 3, shaAbc, "")
                        == UpdateLogic.Verify.SHA_MISSING);
        check("verify size skipped when manifest size absent (-1)",
                UpdateLogic.verifyFile(999, -1, shaAbc, shaAbc)
                        == UpdateLogic.Verify.OK);

        // ---------- 自动检查节流 ----------
        long now = 1_700_000_000_000L;
        check("auto-check: never checked -> true",
                UpdateLogic.shouldAutoCheck(0, now, true));
        check("auto-check: 25h ago -> true",
                UpdateLogic.shouldAutoCheck(now - 25L * 3600 * 1000, now, true));
        check("auto-check: 1h ago -> false (throttled)",
                !UpdateLogic.shouldAutoCheck(now - 3600L * 1000, now, true));
        check("auto-check: exactly 24h -> true",
                UpdateLogic.shouldAutoCheck(now - UpdateLogic.CHECK_INTERVAL_MS, now, true));
        check("auto-check: disabled -> false",
                !UpdateLogic.shouldAutoCheck(0, now, false));


        // ---------- v1.24.0 自动下载决策矩阵 ----------
        // 基线：远端 48、本地 47、有新版、未忽略、无就绪件
        check("decide: auto on + wifi ok -> AUTO_DOWNLOAD",
                UpdateLogic.decideAutoDownload(true, false, true, false, false, 0, 48, 47)
                        == UpdateLogic.AutoDecision.AUTO_DOWNLOAD);
        check("decide: auto on + wifiOnly + on wifi -> AUTO_DOWNLOAD",
                UpdateLogic.decideAutoDownload(true, false, true, true, true, 0, 48, 47)
                        == UpdateLogic.AutoDecision.AUTO_DOWNLOAD);
        check("decide: auto off -> SHOW_DIALOG (old dialog flow)",
                UpdateLogic.decideAutoDownload(false, false, true, false, true, 0, 48, 47)
                        == UpdateLogic.AutoDecision.SHOW_DIALOG);
        check("decide: auto on + wifiOnly + not on wifi -> SHOW_DIALOG",
                UpdateLogic.decideAutoDownload(true, false, true, true, false, 0, 48, 47)
                        == UpdateLogic.AutoDecision.SHOW_DIALOG);
        check("decide: auto off + wifiOnly + not wifi -> SHOW_DIALOG",
                UpdateLogic.decideAutoDownload(false, false, true, true, false, 0, 48, 47)
                        == UpdateLogic.AutoDecision.SHOW_DIALOG);
        check("decide: ignored -> NONE (no download, no dialog, no ready notice)",
                UpdateLogic.decideAutoDownload(true, true, true, false, true, 0, 48, 47)
                        == UpdateLogic.AutoDecision.NONE);
        check("decide: no update -> NONE",
                UpdateLogic.decideAutoDownload(true, false, false, false, true, 0, 47, 47)
                        == UpdateLogic.AutoDecision.NONE);
        check("decide: remote <= local -> NONE even if hasUpdate flag wrong",
                UpdateLogic.decideAutoDownload(true, false, true, false, true, 0, 46, 47)
                        == UpdateLogic.AutoDecision.NONE);
        check("decide: ready == remote -> READY_INSTALL (even auto off)",
                UpdateLogic.decideAutoDownload(false, false, true, true, false, 48, 48, 47)
                        == UpdateLogic.AutoDecision.READY_INSTALL);
        check("decide: ready == remote + auto on -> READY_INSTALL (no re-download)",
                UpdateLogic.decideAutoDownload(true, false, true, false, false, 48, 48, 47)
                        == UpdateLogic.AutoDecision.READY_INSTALL);
        check("decide: ready older than remote + auto on -> AUTO_DOWNLOAD (newer wins)",
                UpdateLogic.decideAutoDownload(true, false, true, false, true, 48, 49, 47)
                        == UpdateLogic.AutoDecision.AUTO_DOWNLOAD);
        check("decide: ready older than remote + auto off -> SHOW_DIALOG",
                UpdateLogic.decideAutoDownload(false, false, true, false, true, 48, 49, 47)
                        == UpdateLogic.AutoDecision.SHOW_DIALOG);
        check("decide: ready not above local (stale) + auto on -> AUTO_DOWNLOAD",
                UpdateLogic.decideAutoDownload(true, false, true, false, true, 47, 48, 47)
                        == UpdateLogic.AutoDecision.AUTO_DOWNLOAD);
        check("decide: ready == remote but ignored -> NONE (ignore wins)",
                UpdateLogic.decideAutoDownload(true, true, true, false, true, 48, 48, 47)
                        == UpdateLogic.AutoDecision.NONE);

        System.out.println("TOTAL passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
