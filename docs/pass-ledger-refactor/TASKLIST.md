# 遍数系统性重构 · 任务清单

> 决策依据：`docs/adr/0001-single-source-of-truth-for-practice-stats.md`（单一事实源＋读时派生＋单写入口＋幂等去重）；红线：`AGENTS.md` 红线一、红线二（系统性修复、禁止补丁止血）。
> 本文件只做设计与清单，代码按阶段另行派工。总工作量约 3 个版本，每阶段可独立成版、可独立回退；发版粒度由主助理与用户定。
> 验证约束（先说清）：本 VM 无 JDK、无 KVM，桌面 harness 与安卓模拟器都跑不了；harness 在 Mac 上跑，修 bug 的版本按用户规矩须模拟器验证——模拟器不可得时须明说并由用户亲令豁免，不许拿代码推理冒充验证。

## 现状盘点（2026-11-26 代码实查，重构的出发点）

**两本账**：`records.json`（`Store.java`：按日期×曲目，`increment` 在 :445、`setTrackCountOn` 在 :465、历史偏移 `baseTotal` 在 :206）；`chapter_stats.json`（`ChapterStatsStore.java`：按书×章×日期，`addAuto` :40、`addManual` :44、`adjustChapterToday` :67）。第三份相关数据：`PlanData.manualEntries`（计划手动达标，与遍数账部分重叠，阶段三单独定口径）。

**全部写入点**（基线文件 `tools/single_writer_baseline.txt`，共 9 条聚合项）：

| 账 | 写入点 | 路径性质 |
|---|---|---|
| records | `PlayerService.java` `increment` ×3（:928 跟读完成、:1748 整轨完成·章级分支、:1769 整轨完成·普通分支） | 自动 |
| records | `ReaderActivity.java:1591` `increment`（`finishRead` 已读到末尾） | 自动（手动触发） |
| records | `MainActivity.java:926` `setTrackCountOn`（步进器 `adjustToday`）、`TrackSettingsActivity.java:175` `setTrackCountOn` | 手动（绝对值 set） |
| chapter_stats | `PlayerService.java:234` `addAuto`（`chapterStep` 采样跨章） | 自动 |
| chapter_stats | `MainActivity.java:1001` `adjustChapterToday`（篇行 chip 编辑） | 手动 |
| chapter_stats | `ChapterListActivity.java` `adjustChapterToday` ×3（:104、:215、:221）＋ `addManual` ×1（:208） | 手动 |
| plan 手动账 | `PlanStore.java` `addManual` ×3（:153 手动达标入口；:260/:266 为读档重建，一并在基线内看守） | 手动 |

**展示面（阶段三逐个改派生）**：主页普通卡（records）、《论语》组头与整本大字（分章账，`BookCountFormat.wholeBookToday`）、《论语》篇行（**混读两账**：chip 读分章账 `MainActivity.java:1394`、统计小字读 records `cardStatText` :1674 一带）、章列表页（分章账）、计划页（三账拼，`PlanStore.java:91`）、复制群消息（records，`Store.buildProgressText`）、通知栏统计串（records，`PlayerService.java:1606`）。

**目标形态**：事件账 `pass_events.json` 为唯一事实源——`{"version":1, "legacyBase":{trackId:数}, "events":[{id, date, bookId, chapterIndex, trackId, kind, source, dedupKey}]}`；`chapterIndex = -1` 表示「整曲桶」（整曲播完但无法细分到章的部分，使 Σ 口径可定义）；`legacyBase` 只进累计口径、不进任何日期窗口。运行时新事件恒为一遍一条；统计全部由唯一的 `PassStats` 聚合函数读时派生。

---

## 阶段 0 · 录表征快照（先录像，再动刀）

- [ ] 新建 `tools/passledger_harness/`：以现有 `completion_harness`、`bookcount_harness` 为底座，把四条自动路径（整轨播完／采样跨章／跟读完成／阅读页 finishRead）与四处手动改数（步进器／chip 编辑／章列表补记与 ±1／计划手动达标）建模为可脚本驱动的操作序列。
- [ ] 录黄金快照：一组固定操作序列 → 记录两本账的全部输出（各展示面的数字），存 `tools/passledger_harness/golden/`。**快照只用合成数据，严禁把用户真机数据文件放进仓库**（WORKFLOW 红线）。
- [ ] 人工过快照：把已知矛盾（「今日 2、本周 0」型、单章回绕只涨分章账型、跟读不写分章账型）逐条标注为 `EXPECTED-FIX`，其余标为「重构后必须不变」。
- **验收**：harness 在 Mac 上可跑通并复现至少三类已知分叉；golden 文件与标注齐全。
- **风险与回退**：纯新增工具文件，不动 App 代码，零风险，无需回退。

## 阶段 1 · 事件账与数据迁移

- [ ] 新建 `PassLedger.java`：事件账的读写（只追加）、事件结构与 `legacyBase` 如上「目标形态」；落盘沿用仓内既有原子写惯例。
- [ ] 写一次性迁移 `PassMigration.java`（带 `migrationDone` 标记防重入），口径如下，不许临场改：
  - 分章账 a 路 → auto 事件逐条展开（source=`migration-chapter-a`）；m 路 → manual 事件逐条展开（source=`migration-chapter-m`，保留手动来源）。
  - records 与分章账同日同篇都有自动数时**取较大者**：单曲单章书（《论语》，曲目与篇 1:1）同日同篇 records 数大于分章账 a 时，差额补 auto 事件（source=`migration-records-topup`）；小于等于时不补（视为已含在分章账内，避免双计）。
  - 单曲多章书（《大学》《中庸》）：records 是整曲数，与同日分章账各章 a 之和比——和 ≥ records 以分章账为准；和 < records 时差额记入**整曲桶**（chapterIndex=-1）auto 事件。
  - records 中由 `setTrackCountOn` 写过的绝对值无法与自动数区分：按上面「取大」口径并入，并在迁移报告中逐曲列明哪些曲目受此影响（人工可复核）。
  - `Track.baseTotal` → `legacyBase` 按 trackId 原样搬入，只进累计。
  - 迁移完成后：`records.json`、`chapter_stats.json` 改名为 `*.legacy-<迁移日期>` 留档不删；App 启动时检测到留档且事件账存在则不再迁移。
- [ ] 迁移自检：迁移前后逐书逐日对账表（旧两账口径 vs 事件账派生口径），差异只能出现在「取大」与「整曲桶」两类已声明口径内，逐条可解释。
- **涉及文件**：新增 `PassLedger.java`、`PassMigration.java`；`MainActivity`／启动路径挂迁移触发点（只触发，不改记账）。
- **验收**：用阶段 0 的合成数据＋一份人工构造的「两账打架」数据跑迁移，对账表全绿；迁移重复执行结果不变（幂等）。
- **风险与回退**：风险＝迁移口径误伤历史数。回退＝删事件账、留档文件改回原名、回退到阶段 0 版本即可完全复原（旧账在迁移期只读不写坏）。**本阶段旧账仍是运行时事实源，事件账只影子生成、先不对用户展示。**

## 阶段 2 · 单写入口收敛（PassCounter）

- [ ] 新建 `PassCounter.java` 唯一记账入口：`recordChapterPass(bookId, chapterIndex, trackId, kind, source, dedupKey)` 与 `recordTrackPass(...)`；内部只写事件账，按 dedupKey 去重（已存在即跳过）。dedupKey 口径：自动事件＝`播放会话ID|bookId|chapterIndex`；手动事件＝每次操作一个 UUID（操作本身即事实，不合并）。
- [ ] 完成判定单点化：章完成只认 `ChapterProgressTracker` 采样判定一处（含整轨播完时以「位置达章长 98% 且曾 armed」统一结算，取代 `flushAtTrackEnd` 的严格不等式兜底）；曲目完成由章完成聚合派生。MediaPlayer 播完事件只作触发器，不再独立记账。
- [ ] 四条自动路径逐条改接入口，**记账与去向解耦**：念注押后只押后去向、永不押后计数（拆 `PlayerService.tryNoteCueAtTrackEnd` :1410 一带的押后逻辑，`cancelNoteCue` 不再作废已记账的完成）；单章回绕（`wrapToChapterStart`）在回绕点记账；跟读完成（`finishFollowPass` :926）改接入口并同时产生章事件；阅读页 `finishRead`（`ReaderActivity.java:1591`）改接入口。
- [ ] 四处手动改数改接入口：绝对值 set（`setTrackCountOn`、`adjustChapterToday`）一律改成「追加 ±Δ 修正事件」，禁止再写绝对值。
- [ ] 收紧适应度检查：按 `tools/check_single_writer.sh` 脚本头注释的三步，把规则从基线模式收紧为「旧账写 API 只许出现在 `PassCounter` 与账本实现文件内」，基线文件随之更新或退役。
- **涉及文件**：`PlayerService.java`、`ChapterProgressTracker.java`、`ReaderActivity.java`、`MainActivity.java`、`ChapterListActivity.java`、`TrackSettingsActivity.java`、`PlanStore.java`（手动达标入口）、新增 `PassCounter.java`。
- **验收**：`check_single_writer.sh` 收紧后绿灯；阶段 0 harness 的操作序列在新入口下重跑，除 `EXPECTED-FIX` 项外输出与 golden 一致，分叉三类全部消失。
- **风险与回退**：本阶段是全重构风险最高点（播放主链路）。回退＝整阶段一个版本，git revert 该版＋事件账保留不读（数据无损）。影子对账（阶段 5 的 rebuildStats 提前在此以日志形态接入）随版观察一个周期再进阶段 3。

## 阶段 3 · 展示面改派生

- [ ] 新建唯一聚合函数 `PassStats.java`：入参只有事件账；提供按曲目／按章／按书的今日、本周、本月、累计、整本遍数（整本＝Σ各章÷章数，HALF_UP 一位小数，口径不变）；今日与本周只差窗口过滤，共用同一时间字段与周起始常量。
- [ ] 五个展示面逐个切换到 `PassStats`：主页卡片与组头（`MainActivity`）、《论语》篇行（chip 与统计小字**同源**，混读消灭）、章列表页（`ChapterListActivity`）、复制群消息（`Store.buildProgressText` 改读派生或迁入 `PassStats`）、通知栏统计串（`PlayerService.java:1606`）。
- [ ] 计划页口径定案并落地：遍数型阶段改读 `PassStats` 派生；**手动达标（manualEntries）是计划域自己的单一事实源、不并入事件账**，原样保留，但 `PlanStore` 不许再拼读旧两本账。
- [ ] 旧两本账的运行时读取点清零后，`Store`／`ChapterStatsStore` 的统计读写代码下线（留档文件仍不动）；`check_single_writer.sh` 基线随之清空旧条目。
- **验收**：任取两个展示面交叉对数恒一致；构造「今日有数」场景时本周、本月、累计单调不减（不变式 1 的界面级验证）；golden 对照除 `EXPECTED-FIX` 外全等。
- **风险与回退**：风险＝某个展示面口径理解错位（如整本遍数取整）。回退＝展示层按面逐个提交，任一面对数异常可单独 revert 回读旧账（旧账在阶段 3 完成前仍由阶段 2 的入口双写事件账时**影子维护**——注意：影子维护只许由 `PassCounter` 一处做，这是双写禁令的唯一临时豁口，阶段 3 结束即关闭，并在本文件勾掉此条）。

## 阶段 4 · 不变式与随机序列测试

- [ ] 四条不变式写成可执行检查（harness＋debug 构建启动自检各一份）：① 今日 ≤ 本周 ≤ 累计（任意书／章／曲目）；② Σ各章 == 曲目总数（整曲桶计入曲目侧）；③ 任一派生统计 == 从事件账即时重算；④ 计数 ≥ 0 且 dedupKey 全局唯一。
- [ ] 随机序列性质测试：在 `tools/passledger_harness/` 加随机模式——随机交错四条自动路径、重复触发同一完成回调、随机手动 ±1，每步后跑四条不变式；失败时输出最短复现序列（参照 Hypothesis stateful testing 的 shrink 思想，手写实现即可，不引框架）。
- [ ] 把两条审查规矩固化：给 AI 的任务描述模板加固定句「改动后四条不变式测试必须全绿；若需改不变式本身，先改 ADR」；`AGENTS.md` 自查四问已在仓内，不重复。
- **验收**：随机测试连续 10 万步无不变式违反；人为注入一个「绕过入口直写」的改动时，测试或 `check_single_writer.sh` 至少有一处变红（护栏有效性自证）。
- **风险与回退**：纯测试与工具，零运行时风险。

## 阶段 5 · 对账与验收收尾

- [ ] `rebuildStats()` 对账函数：从事件账全量重算并与任何残留缓存／派生快照逐项比对（若阶段 3 后无缓存，此函数退化为「事件账自洽检查」：不变式 ③④ 的运行期版本）；触发点＝App 启动时，差异只记日志计数、不静默改数（事实源是事件账本身，无可覆盖它的第二方）。
- [ ] 真机验收清单（用户设备）：整本播完逐篇 +1（含末句恰等于时长的篇，如《论语》第 17 篇）；阅读页单章循环回绕两本口径一致；跟读一遍后篇行与统计行同涨；念注途中打断已播完的遍不丢；手动改任一面数字后其余四面同步。
- [ ] 发版说明向用户交代口径变化：哪些篇的历史数因「取大／整曲桶」跳变、跳变幅度逐篇列明；`CHANGELOG.md` 与 App 内更新日志按仓内既有规矩办。
- [ ] 收尾：更新 `docs/adr/0001` 的 Status 为「已落地」并注明版本；本清单全勾后归档。
- **验收**：真机清单全过；对账日志连续一周零差异（或差异全有解释）；用户确认数字口径。
- **风险与回退**：若真机验收出现新的分叉，按红线二处理——回阶段 2 的判定单点化找系统性原因，禁止在展示层或单条路径上打补丁。

---

## 附：与调研 10 条清单的对照

定唯一事实源→阶段 1；统计一律派生→阶段 3；单写入口→阶段 2；幂等去重→阶段 2；四条不变式自动跑→阶段 4；随机序列性质测试→阶段 4；保留缓存才做对账→阶段 5（本重构默认不留缓存，对账退化为自洽检查）；约束文件 3 行红线→已落地（`AGENTS.md` 红线一）；ADR＋grep 检查→已落地（`docs/adr/0001`、`tools/check_single_writer.sh`，阶段 2 收紧）；重构前表征快照→阶段 0。
