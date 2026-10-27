import com.ti.songdu.FollowGate;
import com.ti.songdu.SpeedRestarter;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.20.2 改速事件序列桌面 harness：用真实的 FollowGate＋SpeedRestarter 类，
 * FakeOps 记录播放器原语调用顺序，PollSim 复刻 ReaderActivity.pollOnce 的
 * 暂停→播放边沿判定（含 v1.20.2 内部重启标记隔离）。
 * 运行：javac -d <out> -sourcepath app/src/main/java tools/speed_harness/SpeedHarness.java
 */
public class SpeedHarness {

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

    /** 假播放器：speed 由 storeSpeed 持有（镜像 PlayerService 先存 store.speed 再编排的顺序） */
    static final class FakeOps implements SpeedRestarter.Ops {
        boolean prepared, playing;
        long position;
        float storeSpeed = 1.0f;
        Float appliedSpeed = null;
        final List<String> log = new ArrayList<>();
        SpeedRestarter restarter;
        final List<Boolean> markerDuringOps = new ArrayList<>();

        private void note(String op) {
            log.add(op);
            if (restarter != null) markerDuringOps.add(restarter.isRestarting());
        }

        @Override public boolean isPrepared() { return prepared; }
        @Override public boolean isPlaying() { return playing; }
        @Override public long positionMs() { note("position"); return position; }
        @Override public void pauseRaw() { note("pause"); }
        @Override public void applyParams() { note("applyParams"); appliedSpeed = storeSpeed; }
        @Override public void seekBackTo(long posMs) { note("seek:" + posMs); position = posMs; }
        @Override public void startRaw() { note("start"); }
    }

    /** 复刻 ReaderActivity 边沿判定：内部重启标记期间跳过判定、只同步基线 */
    static final class PollSim {
        final FollowGate gate = new FollowGate();
        boolean prev;
        int playStartedCalls;

        void poll(boolean nowPlayingMine, boolean internalRestarting) {
            if (!internalRestarting && nowPlayingMine && !prev) {
                gate.onPlayStarted();
                playStartedCalls++;
            }
            prev = nowPlayingMine;
        }
    }

    public static void main(String[] args) {
        // ===== 场景一：播放中改速（普通模式，此前手滑退出过跟随） =====
        SpeedRestarter r1 = new SpeedRestarter();
        FakeOps ops = new FakeOps();
        ops.restarter = r1;
        ops.prepared = true;
        ops.playing = true;
        ops.position = 123400;
        PollSim sim = new PollSim();
        sim.poll(true, false);            // 基线：在播（prev 同步为 true）
        sim.playStartedCalls = 0;         // 只计改速期间的边沿调用
        sim.gate.onUserSwipe();           // 用户此前手滑退出跟随
        ops.storeSpeed = 2.0f;            // setSpeed 先存新速度
        boolean restarted = r1.onSpeedChanged(ops);
        for (int i = 0; i < 3; i++) sim.poll(true, true);   // 重启期间轮询（标记在）
        sim.poll(true, r1.isRestarting());                  // 重启后首轮询
        check("1 播放中改速走了内部重启", restarted);
        check("2 动作顺序=position→pause→applyParams→seek→start",
                ops.log.equals(List.of("position", "pause", "applyParams", "seek:123400", "start")));
        check("3 seek 回原位（位置保持）", ops.position == 123400);
        check("4 重启全程标记为 true、结束后为 false",
                !ops.markerDuringOps.isEmpty()
                        && ops.markerDuringOps.stream().allMatch(b -> b)
                        && !r1.isRestarting());
        check("5 新速度在重启中已应用", ops.appliedSpeed != null && ops.appliedSpeed == 2.0f);
        check("6 内部重启未触发 FollowGate 边沿（零调用）", sim.playStartedCalls == 0);
        check("7 FollowGate 改速前后状态不变（仍未跟随）", !sim.gate.isFollowing());

        // ===== 场景二：跟随中改速，跟随不被扰动 =====
        SpeedRestarter r2 = new SpeedRestarter();
        FakeOps ops2 = new FakeOps();
        ops2.restarter = r2;
        ops2.prepared = true;
        ops2.playing = true;
        ops2.position = 5000;
        PollSim sim2 = new PollSim();
        sim2.gate.onFollowButtonTap();    // 跟随中
        sim2.poll(true, false);
        sim2.playStartedCalls = 0;
        ops2.storeSpeed = 1.5f;
        r2.onSpeedChanged(ops2);
        sim2.poll(true, true);
        sim2.poll(true, false);
        check("8 跟随中改速后仍在跟随、边沿零调用",
                sim2.gate.isFollowing() && sim2.playStartedCalls == 0);

        // ===== 场景三：暂停时改速不起播 =====
        SpeedRestarter r3 = new SpeedRestarter();
        FakeOps ops3 = new FakeOps();
        ops3.restarter = r3;
        ops3.prepared = true;
        ops3.playing = false;
        ops3.position = 42000;
        ops3.storeSpeed = 3.0f;
        boolean r3res = r3.onSpeedChanged(ops3);
        check("9 暂停改速不走重启、零播放器动作", !r3res && ops3.log.isEmpty());
        check("10 暂停改速未应用参数（留待起播前应用）", ops3.appliedSpeed == null);
        ops3.applyParams();               // 模拟起播入口：start 前先 applySpeed（resume 口径）
        ops3.startRaw();
        check("11 起播时先应用新速度再 start",
                ops3.log.equals(List.of("applyParams", "start"))
                        && ops3.appliedSpeed != null && ops3.appliedSpeed == 3.0f);

        // ===== 场景四：未就绪改速 =====
        SpeedRestarter r4 = new SpeedRestarter();
        FakeOps ops4 = new FakeOps();
        ops4.restarter = r4;
        ops4.prepared = false;
        ops4.playing = false;
        check("12 未就绪改速不起播、零动作", !r4.onSpeedChanged(ops4) && ops4.log.isEmpty());

        // ===== 场景五：真实用户边沿不被隔离误伤 =====
        PollSim sim5 = new PollSim();
        sim5.poll(false, false);
        sim5.poll(true, false);           // 用户暂停→播放
        check("13 真实暂停→播放边沿仍进跟随",
                sim5.playStartedCalls == 1 && sim5.gate.isFollowing());
        sim5.gate.onUserSwipe();
        check("14 手滑仍可退出跟随", !sim5.gate.isFollowing());

        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
