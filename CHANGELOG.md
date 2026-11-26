# 更新日志

本项目按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 维护，
版本号遵循语义化版本。每版条目记发布提交短 hash 与对比链接。

## [1.31.0] - 2026-11-26

发布提交：e989a6c · 对比：https://github.com/gty3310/shixi/compare/v1.30.0...v1.31.0 · 标签 v1.31.0

### 新增
- 听法「正文＋注释」（念注）：阅读页底部第二行新增「念注」钮（与注释钮同款朱砂 pill 呈现；设置页「阅读」段可显隐，隐藏前先关听法防困死），听法二选一持久化于 reader_prefs/listenNotes、默认关（只听正文＝旧行为）。开启后：正文逐句播，播到带注句（注块末句，与阅读页「只显一次」同口径）句末暂停正文，前导 600ms 停顿区分正文与注，随后念该注全文，念完自动续播；触发口径与阅读页 displayNote 完全一致——选定注释版本优先、缺则按直解→集注→耕夫→导读回退，注块的实际版本决定取哪个包。覆盖三本书全部注释版本（大学 直解/集注/耕夫，中庸 直解/集注，论语 导读），无注句直过。触发框架为纯逻辑 NoteCuePlan（触发表：触发句=注块末句、触发点=该句在整轨时间轴上的句末，倍速经时间轴 scale 统一缩放；无音频句的触发点借相邻有声句界）＋NoteCueTracker（到点查询不消费、错位消费忽略、seek 后按位置 resync、轨尾 consumeUpTo 补念）；PlayerService 以 50ms 巡查驱动（提前量与跟读同公式），念注走独立 MediaPlayer、PlaybackParams 按 store.speed 施加（音频包为自然语速、缓存/包与倍速无关）
- 念注引擎＝云端预生成音频包（引擎选型经用户拍板：本地 sherpa-onnx TTS 方案取消）：NoteTtsEngine 接口输入「书＋实际版本＋注块键」输出本地可播放文件，注块键＝注数据条目键（块首句 id，与服务端打包线 blocks 清单、包内 manifest 的 key 同口径）。实现 PackNoteTtsEngine＋TtsPackStore：包按书＋版本一份，存 Gitee 私有仓 shixi-models 的 tts-packs/（与识读 ASR 模型同仓、同令牌、同 git blob 通道），索引 packs.json{bookId,version,file,size,md5,parts}，zip 内含 manifest.json{bookId,version,entries[{key,file,durationMs}]}＋mp3；下载支持整件与 2MiB 分片双路，逐件验 size＋md5（按落盘文件现算），ZipExtract 解压带路径越界（zip-slip）防护，staging 区验 manifest（书/版本对得上、条目非空）后整目录换入 App 私有目录 files/tts-packs/<bookId>/<version>/，失败不动已就绪旧包。包未下载时引擎回 ERR_NOT_DOWNLOADED，服务置 ncPackMissing 停发全部后续触发——正文播放零空停顿；包在但单条注不在包内只跳过该条
- 阅读页下载引导：开念注前本书＋当前版本的包未就绪→查索引（转圈不可取消）→确认框写明书名、版本全名与包大小→带进度下载→成功才真正打开听法并 toast；包未上架如实提示「还没备好」、查询/下载失败给「重试」、取消或失败保持关闭；念注开着时切换注释版本同理走引导（新包未下不静默失声）。设置页选注释版本只写 prefs，服务靠触发表绑定键（轨 id＋版本＋声线）自动重建
- 念注视觉标出：PlayerService.noteSpeakingFlat 暴露正在念的注块末句下标，阅读页轮询同步——横排 reader.html setNoteCue 给该注块元素加 .cue（朱砂底＋左边框）并在跟随开启时带入可视区，竖排/无注块元素时亮该句；竖排原生注栏在念注时钉住被念块（注栏关着也临时亮起）、页脚加「正在朗读注释」

### 口径不动
- 遍数判定仍只看正文音频进度（章监测在念注状态下整体让位、恢复后按新基线续测，念注停顿不产生遍数也不误判）；章界处理（单章循环回章首/章末停）与整轨完成在「正在念注」时押后——由章监测与 onCompletion 在执行前拦截、改走念注状态机，注念完后由 concludeNote 按押后类型（RESUME/CHAPTER/COMPLETION）续行，注不被掐断；轨尾押后的注只取 cue 表中触发点已过轨尾的最后一条，中途旧注不补念；听读并行双层循环不变；跟读模式、识读判读中不触发念注；用户暂停/切轨/切声/seek/关听法一律先收念注再走原路径

### 验收
- 桌面 harness：tools/notecue_harness/NoteCueHarness 34 项全过（输出存 harness_output_framework.txt）——块末触发与版本优先/回退、嵌套块（集注）内外块各自触发、无音频句块末借界定位、scale 缩放、空注跳过、tracker 到点/错位消费/resync/consumeUpTo、注块键＝块首句 id 对齐、ZipExtract 正常解压与 ../ 越界条目拒绝（原合成缓存层用例随 NoteAudioCache 删除撤下：包式引擎无逐句合成缓存，包本身即本地存量）
- 构建与包身份：bash build-manual.sh 全量通过；aapt badging 实测 versionCode 55 / versionName 1.31.0；BuildInfo 构建短号＝发布提交 e989a6c（dex 内实查）；dex 内实查 TtsPackStore、PACK_NOT_DOWNLOADED、tts-packs/packs.json 在位。APK size 43029663、md5 8000fbcca1ae328d0e4c66f62a27a9c0、sha256 c519c6166b71f1f0aec94a83024dbda70f054a922370bd18bddbebe3984848be
- 通道回读：GitHub Release v1.31.0 下载回读 APK md5 与本地逐字一致、update.json 与本地逐字节相同；Drive「安装文件」弦歌-v1310.apk（file/d/1ptwNFYW-X5o5R1g1knvRaUHTbSDplIvh）回读 size/md5 一致、旧版 v1.30 已移回收站（夹内只留最新版）；Gitee 发行仓 Release（id 1370418）已建、说明已换本版文案，update.json（1010 B）与 APK 整件（43,029,663 B）均上传成功、附件清单回读 size 一致（私有仓附件直链回读 403 系既有现象，与 v1.30 同口径以 size 级核对，App 更新走 GitHub 兜底不受影响）
- 行为层未验：本版为新功能、非修 bug，按规矩未过模拟器（本 VM 无 KVM 起不了）；念注状态机与 Android 胶水层只有代码复核＋编译级验证。真机待验：暂停/续播衔接听感、600ms 前导手感、章界/轨尾押后实况、视觉标出滚动、包下载真机速度与音质
- 音频包上架状态：六个包（大学直解/集注/耕夫、中庸直解/集注、论语导读）由服务端合成线按本版契约（shixi-models 仓 tts-packs/packs.json 索引＋zip＋manifest、key＝注块键）陆续上架；截至发版时包尚未上架，App 内开念注会如实提示未备好/走下载引导，不影响只听正文

## [1.30.0] - 2026-11-19

发布提交：37bab0d · 对比：https://github.com/gty3310/shixi/compare/v1.29.0...v1.30.0 · 标签 v1.30.0

### 新增
- 《论语》主页主卡改与《大学》《中庸》同式样：折叠头由旧汇总行（今日读了 X 篇 · 共 Y 遍）改为书名＋今日大字遍数（朱砂 30sp）＋统计行（沿用户勾选时段，默认今日·本周·上周·本月·总计）。各档口径：均为分章账（chapter_stats.json，与 v1.26 分章遍数同一本账）逐篇求和后 ÷20 篇折算、一位小数四舍五入（BookCountFormat.wholeBook 与 wholeBookToday 共用同一实现）；本周＝本周一至今日、上周＝上周一至上周日、本月＝本月 1 日至今日、总计＝分章账全部日期（沿 Store 既有日期口径）；ChapterStatsStore 新增 bookSumBetween / chapterSumBetween 区间合计。「本月进度 N/20 篇」缩为次要信息保留在今日行右端。无分章账的书组维持旧式样不变；大学/中庸整本书卡未动
- 篇行「今日 X 遍」可点改：展开后每篇行首行右端新增「今日 X 遍」小标（数出分章账今日合计）；点击弹编辑框（− ＋ 步进＋直接输数），以「目标值−当前值」走既有 adjustChapterToday 写回同一本分章账（增入手动份、减先扣手动份再扣自动份、下限 0 的既有语义不变），存后整表刷新、主卡即时跟变；不进章节页也能改。输入校验抽纯逻辑类 ChapterTodayEdit（空/非数字/负数/超 int 上限拦截、步进下限 0 上限封顶）。播放键、「阅读」键、点行其他区域行为保持现状不动

### 改进
- 注释改「注块」模型（导读一家）：ContentRepo 挂载口径改——导读（daodu）的 nt 全量 {t,a,b} 只落在解析覆盖段末尾句、段内其余句只写 {a,b} 块归属标记（attachNotes 整本路径与 attachDaoduToDoc 单篇路径同口径）；reader.html 新增 noteEndFor（按版本优先序取第一家在本句带注文者为渲染落点），注元素只在落点渲染，版本切换改 rebuildNotes 整批重建（各家块界不同、落点集合随之变），markNote 按当前句的块归属亮块尾那一份（同块内换句亮同一份）。效果：一条导读管连续几句时，原句逐句连排（拼音/标红/点句/跟随高亮照旧按句），导读只在块末显示一次，不再逐句重复
- 注块范围如实：直解/集注/耕夫三家维持旧口径逐句全量（挂载层对三家恒写全量、排版与旧版分毫不差）。三家在现数据里同为块注（《大学》直解 61 块、集注 65 块、耕夫 111 块，均覆盖全书 143 句），若同套「只显一次」其排版必变，与用户定版的「排版与现状分毫不差」冲突，故只显一次只对导读落地；竖排原生注栏按当前句取所在块的注（Sentence 字段仍逐句存覆盖块 NoteRef），语义与呈现不变

### 移除
- 导读注底来源行：reader.html 的 noteFooter 对 dd 返回空串、.ntf 加 :empty 整行隐藏；ReaderActivity.noteVerLabel 对 daodu 返回空串、注栏页脚改经 setNoteFooter 统一落字（空文案整行 GONE）。横排注块与竖排固定注栏两处「导读来源：lunyu.ai · 本注管第 X–Y 句」整行删除（含范围字样）。选版窗「lunyu.ai 白话导读」版本名及其说明（含「导读来源：lunyu.ai。」）保留不动——出处只在选版处出现一次（用户定版）。help.html 注释说明同步改口径；v1.29.0 的 WhatsNew 历史条目不动

### 验收
- 计数 harness：新增 tools/chapteredit_harness/ChapterEditHarness 36 项全过——输入解析（空/负数/非数字/超 int 上限拦截、大数 9999 通过）、步进下限 0/上限封顶、模拟分章账改篇后整本今日一位小数（5÷20＝0.3，HALF_UP）与五档聚合（今日/本周/上周/本月/总计）逐项对、负数目标拦截、清零后整本归零；BookCountHarness 11 项、CompletionHarness 13 项回归全过。如实：harness 的区间求和以镜像账本复刻 ChapterStatsStore 的键与 Store 日期口径（真实类依赖 org.json、桌面无直跑路径），真实类以 diff 逐行复核覆盖
- 注块数据对账（真实书数据离线模拟新挂载）：《论语》导读 499 块＝499 个全量落点、覆盖 1523 句，与 v1.29 生成报告（tools/lunyu_daodu_report.md）逐篇块数全对；子罕篇块 [52,53]（第 53–54 句）：第 53 句只有 {a,b} 标记、第 54 句为唯一全量落点
- 模板实跑：tools/reader_note_harness/note_template_test.js（node 抽 reader.html 真实函数＋真实子罕数据，数据文件同目录 zihan_flat.json）20 项全过——子罕 81 句只渲染 30 个注元素（＝块数）、第 53 句无注元素、第 54 句含该块导读且全文只出现一次、dd 页脚空串、markNote 块内两句亮同一份块尾元素、三家页脚文案与逐句渲染/markNote 旧口径不变
- 构建与包身份、通道：aapt badging 实测 versionCode 54 / versionName 1.30.0；BuildInfo 构建短号＝发布提交 37bab0d（dex 内实查）；包内 reader.html 实查 noteEndFor 在位、dd 页脚返回空串、「导读来源」零出现，help.html 同为零出现；dex 内「导读来源：lunyu.ai」仅存选版窗/设置说明与 v1.29 历史条目共 3 处（非注页脚路径）。APK size 43013279、md5 3aa8e1ebb6eb66ff910fb60d89d1494f、sha256 5ca9092c76ee274bad9e2f379ce1d1571b3dfc26db2a1f2ee36578a13603e909。通道：GitHub Release 下载回读 size/md5 逐字一致；Drive「安装文件」新版 弦歌-v1300.apk（file/d/11EAT0KE7NeJh88jFVbMGp78vwjLdQs9e）回读 size/md5 一致、旧版 v1.29 已移回收站；Gitee 发行仓已建 Release（id 1325942）并挂 update.json（875 B，与本地逐字同 size），且 APK 整件本次上传成功落地（43,013,279 B，与本地同 size）——v1.27–v1.29 连续三版被网络掐断的 Gitee 附件缺口在本版补齐（三版旧附件仍缺，补传脚本各自留存、仍列待办）
- 行为层未验：本版为功能改动、非修 bug，按规矩不过模拟器；主页新卡、篇行编辑弹框、注块排版在真机上的呈现待用户真机验收

## [1.29.0] - 2026-11-18

发布提交：52dac03 · 对比：https://github.com/gty3310/shixi/compare/v1.28.0...v1.29.0 · 标签 v1.29.0

### 新增
- 《论语》第一家注释「lunyu.ai 白话导读」：逐章把经文译成现代白话的导读（站方自称「审校白话导读」，以 1948 年《白话论语读本》——张兆瑢、沈元起编译、公有领域——为底本，经站方现代改写审校）。此前《论语》无任何注文件、阅读页点「注释」无内容，本版补上。版本名按用户定版直接用站名「lunyu.ai 白话导读」；每处注的页脚（横排注块）与竖排注栏页脚写明「导读来源：lunyu.ai」。阅读页选版窗新增第四家、设置「注释版本」分段同步加「白话导读」；默认版本仍为张居正直解，不动
- 数据与对齐（assets/notes/lunyu_notes.json，499 块）：由 tools/gen_lunyu_daodu.py 离线生成（脚本与对齐报告 tools/lunyu_daodu_report.md 同入仓库）。App 底本（注疏本系统，501 节）与站方底本（Legge 本，499 章）章划分有出入（公冶长、子罕两篇合/分不同）、个别字句亦不同，故不按序号硬套：逐篇以导读原文与 App 句子（textS 归一化）做序列 DP 文本对齐（章序句序不倒置、每章至少落一句、允许章后留白）。结果 499/499 章全落位，落位相似度无一低于 0.80；抽 6 章（含两章节选章）导读与源文本逐字比对一致、块界原文相似 0.988–1.000。生成中发现并修复一处上限截断：微子 18.6 全章 17 句超过初版单章上限 16 句、被迫丢首句，上限提至 24 后仅此一块变化、其余 498 块两次生成逐一相同
- 覆盖与缺口如实：导读覆盖 1523/1585 句（96.1%）。未覆盖 62 句全部有出处——站方对长章只给开头部分导读：先进 11.25 侍坐章后 25 句、季氏 16.1 后 21 句、季氏 16.13 后 8 句、宪问 14.26 后 4 句、卫灵公 15.41 后 3 句（这些句选导读一家时显示「本句暂无注释」，《论语》无其他家可回退）；另 1 句是注疏本编者案语片段（季氏 16.12 章首括号句，Legge 本无此句、站方导读自然不覆盖）
- 挂载与呈现：ContentRepo.attachNotes 版本表加第四家 daodu/dd（Sentence.noteDd）；《论语》单篇 rawJson 拼自 lunyuDoc、与 parseBook 是两棵树，仿 v1.27 古读标红先例另设 attachDaoduToDoc 对 lunyuDoc 挂 nt.dd，且 a/b 用篇内下标（单篇渲染句序从本篇起算，页脚「本注管第 X–Y 句」按篇内计才对）。reader.html 的 NOTE_VER_KEYS 与页脚标签、ReaderActivity 的 displayNote 回退次序（改程序化：选定家优先，其余按直解/集注/耕夫/导读）、竖排注栏页脚、选版窗、SettingsActivity 分段、help.html 说明同步接入；既有三家行为与默认版本未动

### 验收
- 数据自验（生成器对账）：499/499 章落位、零低相似落位；抽查 6 章导读逐字一致、块界吻合；两次生成差异仅微子 18.6 一块，截断面已清
- 模板实跑：node 直跑 reader.html 的 noteFor/noteFooter——选 dd 取 dd 且页脚为「导读来源：lunyu.ai · 本注管第 X–Y 句」；选 zj 仍取 zj（无回归）；只有 dd 时回退 dd 且页脚如实；无 nt 返回 null
- 构建与包身份：aapt badging 实测 versionCode 53 / versionName 1.29.0；BuildInfo 构建短号＝发布提交 52dac03（dex 内实查）；APK 内 assets/notes/lunyu_notes.json 回读可解析（499 块）；dex 内实查 daodu / daoduSpan / 「导读来源：lunyu.ai」/「lunyu.ai 白话导读」字符串齐备；APK size 43009183、md5 d8c1720a53e2e96dad76ddc332829403、sha256 307f5381944f59abf4cd95ce7fb00566d1276d2dd04c5002397b4844abd6e687。通道：GitHub Release 下载回读 size/md5 逐字一致；Drive「安装文件」新版 弦歌-v1290.apk（file/d/1FM1dN1HtijMFamPIPRMmUcf_2dErRgAP）回读 size/md5 一致、旧版 v1.28 已移回收站；Gitee 发行仓已建 Release（id 1322806）并挂 update.json（896 B），但 APK 整件流式上传约 6.5 分钟后被网络掐断（RemoteDisconnected）、附件未落地（无残件，附件列表只有 update.json）——与 v1.27/v1.28 同一症结，App 更新取不到 Gitee 包时自动走 GitHub 兜底、不受影响，补传脚本 ~/workspace/xiange-release-v1290/gitee_apk_v1290.py 幂等、列入补传待办（连同 v1.27/v1.28 两版）
- 行为层未验：本版为新功能、非修 bug，按规矩不过模拟器；真机上选版窗第四家、横排注块、竖排注栏的实际呈现待用户真机验收
- 版权口径如实记：导读系 lunyu.ai 站方编辑成果、无开放授权；站方全站无公开邮箱/联系表单、匿名运营，授权申请信已拟好但无处投递。入 App 系用户 2026-11-18 知悉上述状态后的明示决定（此前已定「先抓供个人参考」并拟信问授权，本日改定直接入 App、以站名作版本名）；版本名与每处来源行即署名。若站方日后提出异议，按用户承诺口径处理（配合撤下）

## [1.28.0] - 2026-11-17

发布提交：210600d · 对比：https://github.com/gty3310/shixi/compare/v1.27.0...v1.28.0 · 标签 v1.28.0

### 修复
- 阅读读完弹框四选项自 v1.26 起从未显示：ReaderActivity.Bridge.finishRead 的 AlertDialog 同时 setMessage 与 setItems——Android 的 AlertController 在设有正文（message）时只渲染正文、永不把选项列表（ListView）挂进视图树，列表被创建却静默丢失，用户点底部「已读到末尾 · 点此记一遍」只看到标题＋计数正文那张小卡片（记遍因在弹框前执行、照常 +1）。v1.26 提交 ce3e91a 把三按钮换成 setItems 时原样保留了 setMessage，即为引入点；全 App 仅此一处弹框中招。改法只动弹框构造段：删 setMessage，计数行改由 setCustomTitle 承载（两行 TextView：第一行「已读完一遍」、第二行小字「{track.title} 今日 +1 遍（今日共 {today} 遍）」，信息一字不丢）；setItems 四选项（再读一遍／回到第一页／留在本页／下一章）与各自动作、store.increment 在弹框前执行的记遍时机、finishDialogShowing 置位/复位逻辑全部原样不动；reader.html 不动

### 改进
- 古读标红加重：reader.html 的 `body.gdon ruby.gd rt` 样式由 `color:#9E3B2B` 改为 `color:#C8401F;font-weight:bold`。原 #9E3B2B 偏棕暗、且每页标红本就稀疏，用户真机感知为「拼音全黑」（排查实证标红链无缺陷：五份运行时书数据逐句离线模拟全数挂载零拒绝、node 实跑旗舰句位点全对、用户截图中《论语》夫 fú 本已标红）。#C8401F 取朱砂红区间（#C8401F～#D94B3A）饱和度最高、明度适中一端：在宣纸米白底上小字拼音也一眼可辨，同时与识读错字样式（#B03A2A、更深沉）的层级关系不变——两类同现时仍错字样式优先（规则特异性未动）。只改这一条规则，普通拼音不碰；整句与小句两条渲染路径共用此规则

### 验收
- 未经模拟器／真机验证（未验先发）：本机 VM 无 KVM、安卓模拟器未能启动，用户 2026-11-17 亲令未验先发（照 v1.26.0 先例）；弹框四选项与标红加重在真机上的呈现待用户验收
- 代码级验证：构建后 dexdump 复查发布 APK——finishRead 的 runOnUiThread lambda（lambda$finishRead$7）中 setMessage／setTitle 调用已消失，setCustomTitle／setItems／setOnCancelListener／show 各一处在位，两行标题的 TextView.setText 与弹框前的 store.increment 均在，四选项与计数行字符串常量齐备；node 实跑包内 reader.html 的 sentHTML 与 clauseHTML 两条路径渲染旗舰句「如惡惡臭，如好好色，此之謂自謙」，均恰含 3 个 class="gd"（惡 wù、好 hào、謙 qiè，位点与 v1.27 清单一致），包内 CSS 已为新色值＋粗体；改动面仅 ReaderActivity.java 与 reader.html 两文件（另版本/日志/README/WhatsNew 例行项），清单数据、遍数／循环／播放逻辑未动
- aapt badging 实测 versionCode 52 / versionName 1.28.0；BuildInfo 构建短号＝发布提交 210600d（dex 内实查）；APK size 42964052、sha256 8fd346fdf7a99a44d090009410060f37b1accbc4505dde9f88583176cbf22658、md5 2c9350e8e9436e1a90c11b3b593b8bb8。通道：GitHub Release 下载回读 size/md5/sha256 逐字一致、update.json 内容回读正确；Drive「安装文件」回读 size/md5 一致、旧版已移回收站；Gitee 发行仓已建 Release（id 1320666）并挂 update.json（795 B），但 APK 整件两次流式上传均在约 5 分钟时被网络掐断（RemoteDisconnected）、附件未落地（无残件），App 更新取不到 Gitee 包时自动走 GitHub 兜底、不受影响，补传待办（~/workspace/xiange-release-v1280/gitee_apk_v1280.py 幂等）；v1.27.0 的 Gitee APK 补传亦未成——第三次脱离会话重试以 SSLEOFError 告终、附件列表至今只有 update.json，同列补传待办

## [1.27.0] - 2026-11-17

发布提交：608c50c · 对比：https://github.com/gty3310/shixi/compare/v1.26.0...v1.27.0 · 标签 v1.27.0

### 新增
- 古读标红：阅读（含跟读）时，读音不是现代常用音的字，其拼音标朱砂红（#9E3B2B，只红拼音、字本身不变色，reader.html 的 ruby.gd 样式）；设置「阅读」组新增开关「古读标红」（reader_prefs/gdRed，默认开），阅读页打开时读取、以 body 的 gdon 类控制呈现，关掉后拼音全部恢复常色；与识读错字同现时错字样式优先
- 标红清单（assets/content/gd/<bookId>.json）：《大学》60 字次／35 句、《中庸》138 字次／101 句、《论语》237 字次／205 句。由 tools/gen_gd_marks.py 离线生成（脚本与清单同入仓库，可复现）：逐字以书中拼音经 zhengyin.audit.classify_one 对 zhengyin-dict v0.3.1 现代读音字段（modern_readings／is_polyphonic／unique_reading／default）分类，D 类（多音字不读默认音／非多音字不读唯一现代读音；一、不变调按 audit 归一）才标；查字典用繁体字形（与正音审计同口径，避简繁字典行个别不一致，如 盡／尽）；字典未收的字不标（本批三本未收字次为 0）
- 挂载：ContentRepo.parseBook 与《论语》lunyuDoc 两路把清单写进句子对象的 gd 字段（句内汉字序号数组）；挂载带句内汉字数对账指纹，书库文本日后修订致句长变化时整句不挂、防错位标红；无清单的书（用户导入书等）零回退。既有遍数／循环／播放逻辑未动

### 验收
- 清单自验（纯逻辑）：三本逐句汉字数＝拼音数、零错位（1753／3569／15929）；生成所用《中庸》《论语》输入与在线书库现行文件 md5 逐字一致（b45734d9…／3fd8670c…），《大学》为 APK 内置文件
- 与正音审计集逐位交叉核对：《大学》标红 60 ＝ 钉音交集 48 ＋ 替字处 12（於 wu1、弟 ti4、乘 sheng4 等——审计按替字验收为 A，书中原字原音仍是古读，故标红）；只钉音不标红 112 处全为 B 类（多音字读现代默认音，TTS 为防语境漂移钉音，读音本身常用，故不标）。《中庸》标红 138 ＝ 交集 124 ＋ 替字处 14（知→智 8、大→太 2、於→嗚 2、帑→孥 1、既→餼 1，与 v0.4.1 语境审批准单逐一对上）；只钉音不标红 200 处全为 B 类
- 例字抽查：《大学》好 hào 10、恶 wù 7、乐 yào 1（「如恶恶臭，如好好色，此之谓自谦」句内：恶 wù、好 hào、自谦 qiè 标红，恶 è、好 hǎo 不标）；《中庸》知 zhì 8、辟 bì 2、好 hào 4、恶 wù 4；《论语》好 hào 14、说 yuè 3、恶 wù 3、知 zhì 1。《论语》「知者乐水，仁者乐山」书中拼音作 lè（非 yào），清单忠实于书中拼音、未标
- 行为层未验：真机上标红呈现、开关开闭效果、跟读模式同步呈现，待用户真机验收（本版为新功能、非修 bug，按规矩不过模拟器；已验为清单数据自验＋构建与包身份＋通道回读）
- aapt badging 实测 versionCode 51 / versionName 1.27.0；BuildInfo 构建短号＝发布提交 608c50c（dex 内实查）；reader.html 脚本过 node 语法检查；APK size 42964052、sha256 5ac0c872e36fb389674bf33fae3f2e224c3352dc84793be59f6f10e53c4b6b19、md5 8465bd0a9dc6594a7380e7e94281f497；包内含三份 gd 清单资产（assets/content/gd/daxue.json、zhongyong.json、lunyu.json）与识读原生库

## [1.26.0] - 2026-11-17

发布提交：32a63f4 · 对比：https://github.com/gty3310/shixi/compare/v1.25.0...v1.26.0 · 标签 v1.26.0

### 修复
- 全本循环失效（多曲目书每篇同篇重播）：根因是普通整本播放整轨播完走 CompletionPolicy.decide(loopEnabled, hasNextPlayable)，decide 无循环范围参数、循环开一律 RESTART，论语 20 篇每篇播完同篇重播。CompletionPolicy 新增纯逻辑 loopTarget(scopeChapter, hasNextInBook, multiTrackBook)：单章范围→RESTART_SELF、整书＋有下一曲→NEXT_IN_BOOK、整书＋书末＋多曲目→FIRST_OF_BOOK、整书＋书末＋单曲目或无书→RESTART_SELF；decide() 与 chapterBoundary() 一字不动。PlayerService 新增本书内助手 nextTrackInBook／firstTrackOfBook／currentBookMultiTrack（PlanBooks.bookIdOfTrackFull／tracksOfBook 口径，下一曲或首曲未选声 needsChoice 时返回 null），onCompletion 末尾与 finishFollowPass 两处 decide 开关同改：循环开改走 loopTarget（NEXT_IN_BOOK→playTrack 下一曲；FIRST_OF_BOOK→playTrack 书首曲、为 null 回退 replayFromStart；RESTART_SELF→replayFromStart），循环关保持原 decide 开关一字不动；两处开关之前的计数、chapterPlayback 分支与章界路径一律不动。听读并行双层循环口径随之成立：单章循环章末自动回绕不需点已读完、整书循环读完自动回书首、阅读模式不阻碍播放循环

### 新增
- 阅读模式「下一章」：ReaderActivity.finishRead 弹框由三按钮改选项列表（标题「已读完一遍」与文案不变、计数仍在弹框前完成），前三项「再读一遍／回到第一页／留在本页」行为不变（前两项 goTop、第三项仅关框）；仅当所属书内下一曲存在且 ContentRepo.hasContent 时追加第四项「下一章」，点之打开下一曲阅读页并 finish 当前页——单曲目书（大学／中庸，整书即一曲）与全书最后一篇均无此项
- 分章书遍数：ChapterStatsStore 新增 adjustChapterToday（今日合计 a+m 口径、下限 0 与 MainActivity.adjustToday 惯例一致，增入 m、减先扣 m 正数部分不足再扣 a，落盘与条目清理仿 add）；新纯逻辑类 BookCountFormat.wholeBookToday（Σ各章今日 ÷ 章数、BigDecimal 一位小数 HALF_UP，章数≤0 返回 "0.0"）；章列表页顶加今日整本大号遍数（28sp 加粗墨色）＋「今日整本」标签＋整本 −／＋ 小钮（整本 ±1 ＝逐章各 ±1、逐章下限 0），每张章卡片动作行加本章 −／＋ 小钮，均点后 buildList 刷新（页顶数字在 buildList 内按 Σ各章今日 ÷ 章数一并刷新）；既有「补记 +1 遍」等按钮与文案不动

### 验收
- 桌面 harness：CompletionHarness 13 项全过（原 decide 4 项不回退＋loopTarget 全组合 9 项：单章恒 RESTART_SELF、整书有下一曲 NEXT_IN_BOOK、书末多曲目 FIRST_OF_BOOK、书末单曲目与无书 RESTART_SELF）；BookCountHarness 11 例全过（(1,20)→0.1、(7,20)→0.4、(8,20)→0.4、(9,20)→0.5、(10,20)→0.5、(0,20)→0.0、(20,20)→1.0、(21,20)→1.1、(1,30)→0.0、(15,30)→0.5、(2,30)→0.1）
- 未经模拟器／真机验证（未验先发）：本机 VM 无 KVM、安卓模拟器 TCG 软件模拟未能启动（等待窗已过、无启动成功证据），用户 2026-11-17 亲令未验先发，照知行 v1.87.0／v1.88.0 未验先发先例；已完成的验证仅代码审查＋上述两套纯逻辑 harness（13＋11 项），全本循环、「下一章」、分章遍数的行为层待用户真机验收
- aapt badging 实测 versionCode 50 / versionName 1.26.0，包内含识读原生库（libsherpa-onnx-jni.so、libonnxruntime.so）与《大学》正音版音轨资产（daxue_aizz.mp3／.json）；BuildInfo 构建短号＝发布提交 32a63f4（dex 内实查）；APK size 42959730、sha256 b3b42677309ca136572f326f7219d07138ccba7cf71af73306a49cdf6b26243e、md5 2d2a1c827ba972f918a86bf98de59685

## [1.25.0] - 2026-11-17

发布提交：4c2f63c · 对比：https://github.com/gty3310/shixi/compare/v1.24.0...v1.25.0 · 标签 v1.25.0

### 新增
- 《大学》第三声「AI 朗读·正音版」：原声／AI 二元模型泛化为第三声 aizz——VoicePref 新增 VOICE_AIZZ、hasAizzVoice、voiceAudioAsset、isAssetVoice，偏好与位置归属（pos_voice）接受 aizz，voiceName 显示「AI 朗读·正音版」；ContentRepo 新增 loadVoice／loadAizz 泛化分发（bookForTrack、bookForVoice、scaleFor 与 ChapterListActivity 章轴同口径），缓存键 daxue#aizz 与旧 #ai 分开；PlayerService 的数据源、总时长（effDurationMs）与 activeVoice 按所选 assets 声音（ai／aizz）分发，切声仍走既有 mapPosition 按句对位；VoiceChoiceActivity 仅在有 aizz 资产时出第三张选项卡（名字＋试听），旧 AI 音轨与法师原声保留不删，其他书不出现该选项（《中庸》仍为原声／AI 两声）
- 音轨资产按 daxue_ai 既有方式入工程：assets/audio_ai/daxue_aizz.mp3（6,411,789 B，1 倍速，534.23 秒）＋daxue_aizz.json（93,709 B，143 句时间轴），普通 git add 入库（非 force-add），与 daxue_ai.mp3 同目录同引用法；包内实测 mp3 为 STORED 未压缩存储，openFd 直读成立

### 如实口径（不许改写）
- 本轨由正音终检过的纯文本版合成——SSML 钉音经小样实测未被云大围 402002 执行（判别句轉寫与纯文本一致），故按纪律走纯文本兜底；160 处钉音在音频里无锁、由引擎按语境读，属已知残余风险。选声页显示名仍按定案「AI 朗读·正音版」

### 验收
- 切声对位桌面断言全过：aizz 时间轴 143 句与《大学》原声内容包及旧 AI 轴逐句对位——句 id 序完全一致、textS／textT 逐句一致、章结构与各章句数一致、时间单调递增、总时长 534,229 ms、末句 endMs＝534,229；资产入工程后 md5 与源文件逐字一致（mp3 a5d4dea56ad4f68669e1823c39a83e5c、json f7228142e1685db8014549f84c81426f）
- aapt2 badging 实测 versionCode 49 / versionName 1.25.0，包内含识读原生库（libsherpa-onnx-jni.so、libonnxruntime.so）与三条《大学》音轨资产（daxue_aizz.mp3 STORED）；BuildInfo 构建短号＝发布提交 4c2f63c（dex 内实查）；APK size 42955634、sha256 1839de1dd10b39fc11949f9836e000bb6b251f2c4e5f99e5435ac00131472170、md5 192ed6dc29f5185fed5d3032379e8b68
- 真机未验：选声页第三选项的呈现与试听、正音版实际播放与切声对位听感、从 v1.24.0 应用内更新升到本版，待用户实测

## [1.24.0] - 2026-11-01

发布提交：3933af1 · 对比：https://github.com/gty3310/shixi/compare/v1.23.0...v1.24.0 · 标签 v1.24.0

### 新增
- 更新改「自动下载、点一次安装」：设置「应用更新」组新增「自动下载更新」开关（update_prefs 新键 auto_download，缺省视为开、默认开），与既有「仅 Wi-Fi 下载更新」「自动检查更新」并列
- 分流决策（纯逻辑 UpdateLogic.decideAutoDownload，自检与手动检查共用）：查到未被忽略的新版后——自动下载关 → v1.22.0 旧弹框流程（更新/以后再说/忽略此版本，点了才下）一字未动；自动下载开但「仅 Wi-Fi」开着且当前非 Wi-Fi → 本次不下、退回旧弹框；其余 → 立刻后台下载、不弹更新框，下载中只一条低优先级（IMPORTANCE_LOW）进度通知、带「取消」钮（经广播置位取消标志），不高打扰
- 就绪状态持久化：下载完成且 size+sha256 验过后写 update_prefs（ready_code、ready_version_name、ready_path、ready_notes，另存 ready_size/ready_sha256 供复查）；App 在前台弹「新版已备好」框（版本号＋说明＋安装/稍后，稍后不清除就绪），不在前台发通知「弦歌新版 vX 已下载 · 点此安装」，点之经新增透明落点页 UpdateInstallActivity 复用既有 install 入口（UpdateFileProvider＋ACTION_VIEW）调系统安装，不另起一套；下载失败/取消不留残件、不留假就绪、自检场景静默收尾下次再试
- 就绪健壮性：冷启动后台全校验复查就绪件（文件不存在/大小或 sha256 不过 → 作废清键删坏件、可重新自动下载；就绪版本已装上亦作废）；已有就绪件又查到更高版本 → 以下载更高版为准、新件验过后删旧件换就绪记录；手动「检查更新」时已有就绪件且就是最新版 → 直接呈现「安装」不重复下载，就绪件低于远端新版 → 走下载新版（自动关则弹框）的常规分流；清单取不到但手头有就绪件时手动检查亦直接呈现安装
- 忽略此版本语义不变：被忽略的 versionCode 不自动下载、不弹框、不发就绪通知（决策函数 ignored 判定在就绪判定之前）；手动检查仍可见（手动路径传 ignored=false）

### 验收
- 桌面 harness（tools/update_harness）直驱 UpdateLogic 本体 53 项全过：原 v1.22.0 的 39 项一条不回退（版本比较/忽略名单/双源回退/清单容错/分片/sha256 与校验四结论/24 小时节流）＋新增决策矩阵 14 项（自动开+网络允许→AUTO_DOWNLOAD、自动关→SHOW_DIALOG、仅-Wi-Fi 非 Wi-Fi→SHOW_DIALOG、被忽略→NONE、无新版/远端不高于本地→NONE、就绪==远端→READY_INSTALL 且自动关亦然、就绪低于远端按自动开关分流、就绪不高于本地按未就绪处理、就绪但被忽略→NONE），输出留档 tools/update_harness/harness_output_v1240.txt
- aapt2 badging 实测 versionCode 48 / versionName 1.24.0，包内含识读原生库（libsherpa-onnx-jni.so、libonnxruntime.so），构建日志无残缺警告；BuildInfo 构建短号＝发布提交 3933af1；APK size 36528857、sha256 224520a6cbe671b971ca7cf4fc8d92acc8808d065240c3e67d6c278ea6a70031、md5 5ab8d8423c7493c5bdc5c227a3ac5374
- 代码走查：下载核心抽为 downloadAndVerify 供对话框旧流程与后台新流程共用（逐源、校验不过删文件换源、落位同一口径）；前台/后台呈现判定走 Application.ActivityLifecycleCallbacks 追踪 resumedActivity；通知渠道分下载（LOW）/就绪（DEFAULT）两条，通知发送失败被吞掉不影响下载与就绪本身；update_prefs 与 cacheDir/updates/ 之外不碰任何用户数据
- 真机未验：自检触发自动下载、下载通知与取消、就绪弹框、通知点装、重启后就绪复查、从 v1.23.0 经新链路升到本版，待用户实测

## [1.23.0] - 2026-11-01

发布提交：1a218c3 · 对比：https://github.com/gty3310/shixi/compare/v1.22.0...v1.23.0 · 标签 v1.23.0

### 新增
- 复习页双页签：顶部原生分段切换「笔记 / 待熟」（样式与设置页状态钮同口径：朱砂实心白字＝选中、透明描边灰字＝未选）；上次页签持久化（ui_prefs/review_tab_weak），下次进复习页落回该页签；唯一覆盖入口＝识读流程结束——ReaderActivity.stopRecite 写 ui_prefs/force_review_weak_once 标记，MainActivity.onResume 消费后强制落「待熟」一次（另有 EXTRA_OPEN_REVIEW_WEAK intent 入口），不覆盖持久化记忆
- 「笔记」页签：直读 NotesStore（files/notes.json），只汇 note 正文非空条目（纯标记条目不汇）；聚合抽纯逻辑类 NotesDigest（按书分组，9621 六书序在前、其余按首次出现序；组内按 flat 句序升序），每条显示书名分组、章/句位置标签、整句原文与笔记全文；点条目复用既有 jumpToSentence（ReaderActivity.EXTRA_TRACK_ID + EXTRA_SCROLL_SENT_ID）落句，不另写跳转
- 「待熟」页签：原 review.html 四档内容（确定错/请确认/机器可能听错/漏读，含转正、勾销、长按删除等既有交互与统计口径）原样搬入，数据与行为零变化；WebView 按 DATA.tab 只渲染当前页签

### 修复
- PlanEngine 预计完成日错数（现场：计划 2026-11-01 启用、截止 2027-08-01 剩 274 天、总进度 1%、第 1 天《大学》读 1 遍，状态行报「慢于节奏 · 预计晚 601 天」）。601 来历：旧外推把近 7 天折算量固定除以 7——第 1 天 1 遍折算 1.0、假日均 0.143；剩余折算总量 125（大学剩 20 + 其余五本各 21），还需 ceil(125/0.143)=875 天，预计完成 = 今天+874 天，距截止日（今天+273 天）恰晚 601 天。新口径：数据不足（启用未满 7 天，或窗口内无有效进度的日子占多数）→ 中性档、预计完成日＝截止日、不报晚天数；窗口只数启用后的日子（至多 7 天），日均按窗口实际天数折算；护栏：预计晚天数 ≤ 剩余天数（预计完成日最远＝截止日＋剩余天数），任何输入不产出与剩余天数不成比例的数
- 未下载书手动记录入口缺失：v1.21.0 的手动入账只挂在计划页「今日」块、且只对当前阶段（「今日已按建议读完」仅当前阶段缺书时出现＋「记一笔」），阶段表里的阶段行没有任何点击监听，未轮到的未下载阶段无入口。本版：未下载阶段整行可点开手动记录弹层——现状一行（单位与总量取自阶段数据，如《道德经》共 81 章 · 已记 X / 1701 章·遍）、「今日已按建议读完」（当前阶段用引擎今日建议整额入账，与今日块旧钮同口径；非当前阶段引擎无今日建议，按 PlanEngine.stageDailySuggestion 每日基准量入账：分得天数＝剩余天数×本阶段 shareDays÷未完成阶段 shareDays 之和，建议＝剩余量÷分得天数取整、章级化为 K×N，如道德经基准 52 章·遍/日）、自定义「读了 X 章 · 每章 Y 遍」记一笔、最近记录列表（标「手动」）＋删最近一笔；入账统一走 PlanStore.addManualOn → plan.json manualEntries，记完即重建计划页与首页进度带。已下载阶段行点进 ChapterListActivity 章卡片（阶段行此前无点击，此为同书卡「章」入口一致的去处）

### 验收
- 桌面 harness：PlanHarness 43 项全过（原 37 项不回退＋新增 6 项：601 现场复刻→中性档且预计＝截止日、逐日照建议读预计不晚于截止日、两倍速度预计提前、极慢时护栏 lateDays≤remainingDays、道德经基准量 52、剩余天数/总进度现场值），NotesHarness 6 项全过（跨书分组、六书序、组内 flat 句序、空/空白笔记过滤、mark-only 排除、空/ null 输入），输出留档 tools/plan_harness/harness_output_v1230.txt 与 tools/notes_harness/harness_output_v1230.txt
- aapt2 badging 实测 versionCode 47 / versionName 1.23.0，包内含识读原生库（libsherpa-onnx-jni.so、libonnxruntime.so），无残缺警告；BuildInfo 构建短号＝发布提交 1a218c3；APK size 36524761、sha256 dac1d0462bd0daacc6996d7cb7e0754749d8b12791f5ac7fd159337471266b7b、md5 2eeef6f2677b18e3e6428f3ab3f3b9de
- 代码走查：阶段行点击分发（未下载→弹层 / 已下载→章卡片 / 已完成→无点击）、弹层三条入账路径均写 manualEntries 且返回计划页细账即变、笔记条目点击与识读复习条目共用 jumpToSentence、页签强制标记只消费一次
- 真机未验：页签切换观感、笔记跳转落点精度、手动记录弹层操作手感、应用内更新从 v1.22.0 升到本版的实际链路，待用户实测

## [1.22.0] - 2026-11-01

发布提交：0279c05 · 对比：https://github.com/gty3310/shixi/compare/v1.21.0...v1.22.0 · 标签 v1.22.0

### 新增
- 应用内更新（本版是最后一次需要去 Drive 手动装的版本）：冷启动节流自检（距上次检查满 24 小时才查、失败静默不打扰）＋设置页「应用更新」组手动「检查更新」（失败明示原因）；查到新版弹更新框（新版本号＋更新说明＋「更新／以后再说／忽略此版本」，忽略后该 versionCode 不再自动弹、手动检查仍能查到）
- 下载与安装：点「更新」同框转下载进度（百分比＋可取消）；下载源优先 Gitee 发行仓（公开仓 xiange-releases，只放 release 不放源码）、失败自动换 GitHub 本仓 Releases 兜底再试一次；下完先验 size 与 sha256，不符删文件并明示失败原因；验过后经框架 ContentProvider（工程零第三方依赖、无 androidx，故不用 androidx FileProvider 类，改用自有 UpdateFileProvider 实现等价的 content:// 临时授权共享）＋ ACTION_VIEW 调起系统安装；无「安装未知应用」授权时先弹引导去系统页一次性开启（新增权限 REQUEST_INSTALL_PACKAGES，仅此一条）
- 设置项（「应用更新」组）：「检查更新」行（带当前版本＋构建短号，沿用 BuildInfo）、「仅 Wi-Fi 下载更新」开关（默认关＝移动流量也可下载）、「自动检查更新」开关（默认开）
- 双源发行通道：update.json 清单（versionCode/versionName/apkFileName/size/sha256/notes/sources）随版同挂 Gitee 与 GitHub 两边 release；App 取数顺序先 Gitee latest release API、失败换 GitHub latest release API。通道形态实测：Gitee 发行附件单文件上限 100 MB（官方口径，连接器文档 2026-10-24 读数），本版 APK 整件小于上限 → 直挂整件、不分片；清单与 App 均保留分片形态支持（sources 某侧可为 parts 数组按序拼合）
- 纯逻辑类 UpdateLogic（无 Android 依赖）：清单解析、版本比较、忽略名单判定、双源回退判定、节流判定、size/sha256 校验判定，App 与桌面 harness 共用同一份源码；更新流程只读写 update_prefs 与 cacheDir/updates/，不碰 plan.json/records/tracks/chapter_stats 等任何用户数据

### 验收
- 桌面 harness（tools/update_harness）直驱 UpdateLogic 本体 39 项断言全过：版本比较三态、忽略名单命中/未命中/手动检查不受忽略影响、主源失败→兜底判定（含取消与 Wi-Fi 限制不回退）、清单字段缺失容错（缺 notes/缺单侧源可忍、缺 versionCode/无源判无效）、分片清单按序解析、sha256 已知向量与校验四结论（OK/大小不符/sha 不符/清单缺 sha 判失败）、24 小时节流四分支（harness_output_v1220.txt 留档）
- 发布后回读核对：GitHub 侧无令牌全程实测（latest API → 取回 update.json 解析出 versionCode 46 → 整件 APK 下载回读 size 与 sha256 与本地构建逐字一致）；Gitee 侧附件经令牌 API 回读 size 一致。已知限制（2026-11-01 发版当日查实）：Gitee 发行仓 xiange-releases 建仓时公开参数未生效、现为私有仓，且 Gitee 以「帐号安全评级较低」拒绝改公开（要求帐号先完成 2FA 或绑定可靠第三方帐号，属帐号本人操作）——在此之前 App 的 Gitee 源取不到清单，会自动走 GitHub 兜底源（功能不受影响）；帐号处理完把该仓设为公开后，无需改 App 即恢复 Gitee 优先
- 真机未验：冷启动自检弹窗实际触发、真实下载全程、调起系统安装与覆盖安装、忽略此版本与 Wi-Fi 开关的实际行为，待用户装 v1.22.0 后验（自 v1.22.0 起的下一版即可用应用内更新自验）

## [1.21.0] - 2026-10-30

发布提交：547ca91 · 对比：https://github.com/gty3310/shixi/compare/v1.20.7...v1.21.0 · 标签 v1.21.0

### 新增
- 阅读计划 / 9621 读经大挑战（一期全量，按设计方案 v2.2 定稿落地）：底栏改三栏「听读 / 复习 / 阅读计划」，原「历史记录」整块（月历 + 日明细，口径不改）迁入阅读计划页作「诵读日历」，复习页原样不动
- 期限驱动计划引擎（纯逻辑类 PlanEngine）：硬参数只有每本总量与截止日（默认开始当天起算、按日历月推 9 个月到同日序号、无同日序号取月末，含当天）；每日建议按剩余量 ÷ 配比分得天数动态重算并取整（整本整数遍、章级化为「读 K 章 · 每章 N 遍」），分母为 1 天或分得天数 ≤ 0 时建议即全部剩余；状态三档温和话术（比节奏快 / 正好在节奏上 / 慢于节奏 · 预计晚 X 天）+ 预计完成日，近 7 天无数据时预计 = 截止日；阶段按序推进、完成次日激活、窗口 [阶段开始日, 下一阶段开始日)，设置页可提前开始下一本
- 9621 预置六阶段（大学/中庸整本遍 21，share 4/7；论语篇 20×21=420 篇·遍 share 80 N=5；孟子卷 14×21=294 share 98 N=3；坛经品 10×21=210 share 40 N=5；道德经章 81×21=1701 share 32 N=2）与自定计划同构编辑（无每日量字段、实时互算一行、shareDays 按总遍数×单遍时长估算可微调）；PlanStore 存 files/plan.json（仿 NotesStore 原子写，删书不丢），暂停/恢复、改截止日即全盘重算、改未开始阶段遍数、结束计划（历史保留）、重新开始（只清计划进度，不动 records 与 chapter_stats）
- 章即小书：大学/中庸/论语章卡片列表（编号 + 章题、累计/今日遍数、上次日期、约时长），章账 chapter_stats.json 按 书×章×日期 记 auto/manual 两路；章级自动计数由纯逻辑类 ChapterProgressTracker 判定（自然越过章界才计、进入章时基线防拖到章尾附近误计、整本连播逐章计、单章循环每满一遍计；跟读与识读会话期间暂停监测；整轨播完 flush 兜底末章）
- 循环范围 loopScope ∈ {整书, 单章}（reader_prefs 持久化、默认整书）：章卡片与阅读页均可切换；播完去向经 CompletionPolicy.chapterBoundary 判定（章级播放 + 单章范围 + 循环开 → 回绕本章；章级播放 + 整书范围 → 停在章尾；整本播放 + 单章范围 + 循环开 → 到章末回绕）；播放条既有循环开关行为不变
- 缺书阶段手动记账：今日块「今日已按建议读完」（今日达标线整额入 manualEntries）与「记一笔」（章数 × 每章遍数或直接遍数）；已下载书阶段同样可手动补记，章卡片补记 +1 遍标手动
- 首页今日进度带（候选 A）：书卡列表上方一行建议/已读/还差 + 极细进度线，仅 active 计划时渲染，点按跳阅读计划页；当前阶段是缺书时照样显示
- 计划页有计划态四块：头部（计划名、截止 + 还剩天数、总进度条、三档状态行）、今日块（整本阶段每单位一圆满者朱砂、章级读法话术、达标后只显示「今日建议已完成」、阶段将满且有未读章时安静提示）、阶段表（六行固定 + 章级细账注记）、原表建议节奏小字留底、近况热力格（单色低饱和、今日描边）

### 验收
- 桌面 harness（tools/plan_harness）直驱 PlanEngine、ChapterProgressTracker 与 CompletionPolicy.chapterBoundary 本体 37 项断言全过：截止日默认推算（含 1 月 31 日 +1 月到 2 月末、闰年）、剩余天数含两端、配比分天后逐日模拟照建议读在截止日前读完且末日建议 = 全部剩余、整数化（整本整数遍、章级 K×N ≥ 取整建议）、改截止日逆推变化、三档三分支、无数据预计 = 截止日、总进度折算、阶段完成次日激活与窗口、提前开始关窗保量、章界计数四案（正常播过计 1 / 拖到章尾附近播完不计 / 整本连播过两章计 2 / 单章循环两圈计 2）与章界去向矩阵 6 项（harness_output_v1210.txt 留档）
- 代码走查：三 Tab 切换与诵读日历原 records 数据可达、计划页两态渲染、章卡片双通道入账、单章/整书循环互斥与持久化、缺书阶段一键入账进 manualEntries 且汇总正确、编辑页无每日量字段、删书后 plan.json 与 chapter_stats.json 仍在（独立文件、不随删书路径清理）
- aapt2 badging 实测 versionCode 45 / versionName 1.21.0，构建含识读原生库、无残缺警告，BuildInfo 构建短号＝发布提交 547ca91。真机未验：章界计数在真实播放的触发时机、单章循环回绕听感、Tab 切换与页面渲染观感，待用户实测

## [1.20.7] - 2026-10-29

发布提交：dd35a15 · 对比：https://github.com/gty3310/shixi/compare/v1.20.6...v1.20.7 · 标签 v1.20.7

### 修复
- 循环播放失效（用户真机实报：播放条显示单曲循环开、一本读完却停）：根因一＝主界面播放条的「单曲循环 开」自 v1.7.0 起是写死文字（布局里无 id、无点击处理、从不读真实开关），标题「正在循环播放」也只要在播就无条件显示；真实开关（reader_prefs/loop → PlayerService.loopEnabled）只在阅读页与总设置页可见可改——开关在别处关掉时，主界面照样显示开，播完却按关的口径自动接下一本或停下，两处脱节正是用户怀疑的「某个功能冲突」。修：该文字加 id 并成真开关——显示只认真源（服务字段，服务不在时读同一份持久化）、点它经 setLoopEnabled 修改并持久化；标题按真状态分「正在循环播放／正在播放／已暂停」
- 根因二＝播完路径两处静默停播：其一，replayFromStart 的 catch 全吞异常，播完重播装载一失败就无声停在结尾、界面还显示在播——现失败时兜底走一次 playTrack(current) 完整装载（一次性标记防死循环，每次播完判定前与切歌时清零），再失败才如实落回暂停态。其二，循环关自动接播时 playTrack 若撞上双声音书的「未选声」门会直接返回不播、advanceToNextTrack 却报成功（息屏时选声页还会被系统后台启动限制拦下）——新增 nextPlayableTrack：未选声的书不算可接播，播完如实停下，等用户手动点开时选声
- 播完去向抽成纯逻辑类 CompletionPolicy（decide(循环开关, 有无可接下一本) → RESTART／ADVANCE／STOP），普通播放的 onCompletion 与跟读的 finishFollowPass 共用同一判定；遍数计数仍在判定前统一完成（口径不变），计数与循环不再互相牺牲。排查排除项：SpeedRestarter 只由改速触发、FollowGate 只在阅读页边沿判定，均与播完路径无涉；跟读模式与声音轨（原声／AI）不改变去向矩阵

### 验收
- 桌面 harness（tools/completion_harness）直驱 App 的 CompletionPolicy 本体 4 项断言全过：循环开×有/无下一本→RESTART；循环关×有可接→ADVANCE；循环关×无可接→STOP（harness_output_v1207.txt 留档）
- 代码走查：主界面循环显示与点击、服务真源、持久化三者同键（reader_prefs/loop）；onCompletion 与 finishFollowPass 均已改走同一判定；aapt2 badging 实测 versionCode 44／versionName 1.20.7，构建含识读原生库，BuildInfo 构建短号＝发布提交 dd35a15。真机未验：开循环拖到结尾听回绕接续与计数 +1、关循环时标题与接播行为、回绕间隙听感，待用户实测

## [1.20.6] - 2026-10-28

发布提交：2dacb35 · 对比：https://github.com/gty3310/shixi/compare/v1.20.5...v1.20.6 · 标签 v1.20.6

### 变更
- 播放条把手「︿ 上拉展开字幕」改「进入阅读界面」（用户审定方案一）：点按与上滑（fling）两手势都打开当前播放音频的阅读页——手势判定抽成纯逻辑类 HandleGesture（点按恒 OPEN_READER；上滑须位移明显向上、带向上速度、纵向主导三门槛齐备，其余手势一律不触发），两路与卡片/篇行「阅读」钮最终都汇到同一 openReader（同一函数、同一参数），落点（当前句高亮、跟随状态机口径）与从卡片进阅读页且正在播放时完全一致，播放全程不中断。原字幕展开层早已无实体（v1.14.1 已摘其上滑手势、把手改开阅读页），本版清掉其最后残留：把手旧文案、无当前曲目时提示里的「展开字幕」字样
- 未触碰：播放条其余部分（进度条、±15 秒、单曲循环、倍速 chips、悬浮字幕钮）、主界面卡片长按拖动排序；尚无当前曲目时播放条的显隐沿用现状逻辑，无新增空态入口

### 验收
- 桌面 harness（tools/handle_harness）直驱 App 的 HandleGesture 本体 8 项断言全过：点按→OPEN_READER；典型上滑、带小横偏上滑→OPEN_READER；下滑、横滑、位移过短、慢拖、零速度→NONE（harness_output_v1206.txt 留档）
- 代码走查：MainActivity 中点按（onSingleTapUp）与上滑（onFling）都只经 HandleGesture 判定后调 openReaderForCurrent→openReader，与卡片入口同函数同参数；「展开字幕」在布局与代码中已无用户可见残留（仅存版本沿革注释与更新日志的历史记述）。真机把手可点范围、进页落点听感待用户实测

## [1.20.5] - 2026-10-28

发布提交：aa01e75 · 对比：https://github.com/gty3310/shixi/compare/v1.20.4...v1.20.5 · 标签 v1.20.5

### 新增
- 《大学》第三家注解《耕夫讲经典》（用户定稿并入）：与张居正《四书直解》、朱熹《四书章句集注》并列——注释选版窗新增第三块、设置页「注释版本」同步三选，默认仍为张居正。显注口径与既有两家完全一致、未为耕夫开任何特例分支：选定家优先、无块覆盖时按固定次序回退其他家、页脚如实标实际显示的那家与块范围；横排页内注块（reader.html）与竖排原生注栏（ReaderActivity）同口径，ContentRepo 挂载只在版本表里多一行 {"gengfu","gf"}
- 注解数据按句挂载（与既有两家同文件同构）：直接挂注 110 句、计入跨句 span 覆盖 139/143 句；未覆盖的 4 句（s33、s41、s103、s106）均为引文起句碎片或短句，其讲解随相邻句的注解呈现
- 第五章 12 段专题发挥（无我、缘起性空、内圣为体外王为相、修身见效周期、易经三义等，在定稿中集中位于第五章首句之后、无逐句对应句）按用户裁定（甲案）整体附于第五章末句 s56「此謂知本。」的耕夫注：备数中 s56 本注为空（其句由 s53 听讼注 span 覆盖），12 段作为 s56 自有注块写入，在既有「多块覆盖取起点最晚」规则下恰于 s56 处显示，s53 听讼注对 s53–s55 的覆盖与页脚范围不变；各段以空行分隔、段内文字与定稿逐字一致（不易／变易／简易的加粗领字在纯文本注中即段首「不易：」等，引号体例从定稿）

### 验收
- 数据合并断言全过（38 项）：143 键齐全；原 zhijie/jizhu 四字段逐字未变、两家注块覆盖判定与合并前逐句一致；耕夫非空 111 句＝备数 110 句＋s56 附录，除 s56 外与备数逐字一致；s56 含 12 段专题（与定稿 docx 及备数对齐报告第七节全录逐段逐字对照、顺序与空行分隔均断言）；覆盖模拟（与 ContentRepo 同规则）139/143、未覆盖清单与对齐报告一致；并入数据全文「张老师」「郭天宇」各 0 处（他人名郭路生 1 处在 s58、与备数一致）
- 桌面抽验：s1、s47（span＝6 的跨句注）的耕夫注逐段均为定稿 docx 原段落；阅读页切换逻辑 harness（Node 直跑自 reader.html 抽出的 noteFor/noteFooter 本体）9 项断言全过——耕夫可选可达、默认张居正不变、三家优先级与回退次序、span 页脚范围文案、空注返回 null。真机注释切换观感（选版窗第三块、竖排注栏页脚「耕夫讲经典」）待用户实测

## [1.20.4] - 2026-10-28

发布提交：72b1f91 · 对比：https://github.com/gty3310/shixi/compare/v1.20.3...v1.20.4 · 标签 v1.20.4

### 修复
- 高级版识读模型下载必失败（v1.20.1 引入，用户真机实报：先在 0.0/0.0 假死、最终报「仓内找不到文件：sensevoice/model.int8.onnx」）：根因在下载计划构建顺序——旧代码对每个文件先按整文件名查 git tree 的 sha、之后才读 delivery 判定分片；仓里只有 115 个分片、没有整文件，查 sha 必然落空。基础版 encoder（11 片）同病，此前被 ensureSet 的 catch＋HuggingFace 静默兜底掩盖。修法：计划构建抽成纯逻辑类 ModelPlans（App 与桌面 harness 共用同一份），统一先读 delivery 定整件/分片——分片文件跳过整文件 sha、逐片校验 parts 路径全部命中树索引、片大小合计＝内置整件大小，缺片报错点名具体分片；整件文件才查整文件 sha。校验不放松：清单 size/md5 对内置值逐项核对、基础版 assembled_size/md5 核对、拼合后整件 size＋md5 兜底全保留
- 错误不再被兜底掩盖：基础版 Gitee 路线失败改走 HF 前，原始原因记入下载状态（DlState.giteeError）与日志；HF 兜底最终失败时，失败提示包含 Gitee 侧原始原因，不再只报 HF 的错
- 前置阶段不再假死：取文件索引/清单/建计划期间，对话框显示「正在获取文件清单…」＋不确定型进度条；前置阶段重试总时长上限 90 秒，到时必进失败态

### 新增
- 「只用基础版」出口（用户追加）：引擎偏好为高级版而高级模型未下载时，识读触发的下载框恒有一项基础版出口——基础版未下载时是「只用基础版 AI（约 25 MB）」（只下基础版、下完即可识读），已下载时是「改用基础版 AI（已下载，立即识读）」；点选即把引擎偏好切回基础版并持久化（RecitePrefs），此后点识读不再弹高级版下载提示。设置页模型区同享此出口（引擎切换与模型状态行弹出的同一对话框），且基础版已就绪、高级版未下载时状态行显示「已下载 · 高级版未下载 ›」、点开仍可日后自取高级版——拒绝不封死；引擎分段控件随偏好回刷保持一致

### 验收
- 新增桌面 harness（tools/model_harness）作发版门禁：连真实仓（trees 索引 132 条）＋真实清单＋真实令牌，直接驱动 App 的 ModelPlans 本体跑计划构建——基础版 4 件＋高级版 2 件计划全部构建成功、高级版 115 片逐片 sha 全命中且片大小合计＝内置整件大小、只用基础版路径只含基础版 4 件、基础版已就绪时计划为空、两条缺片负例（SV part037／基础版 encoder part03）均报错点名该片，共 14 项断言全过（harness_output_v1204.txt 留档）。真机下载全程与出口交互待用户实测

## [1.20.3] - 2026-10-27

发布提交：47ff8cd · 对比：https://github.com/gty3310/shixi/compare/v1.20.2...v1.20.3 · 标签 v1.20.3

### 变更
- 启动图标换新（用户 2026-10-27 选定「甲·书院式」定稿）：深橄榄绿底、白色竖椭圆、橄榄色「弦歌」竖排。字形取汉典《说文解字》小篆矢量原形（弦、歌各经另外两个独立来源交叉核对结构一致），只换色排版、笔画未动；非 AI 凭空绘制（此前 AI 稿一稿歌字画错、余稿皆现代体冒充，已全数作废）
- 图标工程化：自适应图标（背景＝采样自定稿的纯橄榄绿 #6E6F3E 层；前景＝定稿图等比缩至 66% 居中，白色椭圆高约 61dp、收进 66dp 安全圆，前景自带的橄榄色边与背景同色无缝），另备各密度传统方形图标与圆形裁切图标（定稿椭圆整体落在内切圆内，圆裁不伤字形），Manifest 补 roundIcon
- 本版仅换图标：未动任何功能代码、数据结构与包名；构建前逐密度渲染预览（圆形/圆角方形遮罩＋96px 小图）自视核对构图不破

## [1.20.2] - 2026-10-27

发布提交：a9c0876 · 对比：https://github.com/gty3310/shixi/compare/v1.20.1...v1.20.2 · 标签 v1.20.2

### 修复
- 播放中途改倍速出现毛刺声（用户真机定位：只在播放中改速时出现，暂停后以同样速度重播则干净；AI 轨高频齿音丰富只是让毛刺更明显，离线定速渲染测不出这一扳机）：扳机为 MediaPlayer 播放中 setPlaybackParams 改速。改法：setSpeed 在播放中改走内部重启——记精确位置 → 暂停 → 应用新速度 → seekTo(SEEK_CLOSEST_SYNC) 回原位 → 重播，全程不翻转 playing 字段，通知/悬浮窗/当前句锚点无可观测中断；暂停或未就绪时只记参数，各起播入口在 start 前本就重新 applySpeed，起播即定速。编排抽成纯逻辑类 SpeedRestarter（Ops 接口落地播放器原语），并以内部重启标记与 v1.19.0 FollowGate 隔离：ReaderActivity 的暂停→播放边沿检测在标记期间跳过判定、只同步基线，内部重启绝不误入跟随；跟读/识读逐段起播本就每段新起播，未动其状态机，改速与其共用 setSpeed 入口、重启只碰播放器原语不触其字段。桌面 harness（tools/speed_harness，真实 FollowGate＋SpeedRestarter 类）14 项断言全过：重启路径与动作顺序、位置保持、标记生命周期、改速前后 FollowGate 状态不变、跟随中改速不被扰动、暂停改速不起播且参数待起播生效、真实用户边沿仍生效

### 变更
- 应用更名「时习」→「弦歌」（出《论语·阳货》武城弦歌，用户当日语音定名，xián 非 xuán）：启动器 label、主页标题、帮助页（含旧名出处句改述为武城弦歌）、通知标题兜底、悬浮窗占位名、通知渠道描述、权限弹窗正文等可见字样全数更换；包名 com.ti.songdu、GitHub 仓库名与全部数据结构（书库/记录/偏好/令牌）不动，覆盖升级数据无损；启动图标本版未换（新 logo 另行定稿）
- 顺带对齐 BuildInfo.VERSION_CODE 与清单 versionCode（v1.20.1 构建脚本里 BuildInfo 滞留 37，可能使更新日志弹框不按版触发；本版起两处同为 39）

## [1.20.1] - 2026-10-27

发布提交：929d47c · 对比：https://github.com/gty3310/shixi/compare/v1.20.0...v1.20.1 · 标签 v1.20.1

### 修复
- 识读模型下载失败（v1.18.0 引入，真机必现于高级版）：三层根因叠加——①下载对话框用 setMessage＋setItems 拼选项，在用户机上选项未呈现，只剩标题与「取消」，下载无从点起（入口全量追踪确认：设置页引擎切换、设置页模型状态行、阅读页识读首启、ReciteSession 缺模型四处全部汇到这一个对话框，无漏改的旧流程）；②模型仓 contents API 对高级版第 8 个分片（part007）定点截断：桌面按 App 相同请求方式实测，三次尝试都恰好少 187,678 字节（1,909,474/2,097,152），属确定性失败、重试无用；同一分片经 git blobs API 完整通过（size 与 md5 全对），线上清单与代码预期逐项一致、排除清单问题；③旧下载无断点续传：拼合文件每次尝试从第 0 片截断重来，任一片耗尽重试即整单作废
- 模型下载改走 git trees＋git blobs 通道（同一令牌、同一流式 base64 解码、逐片＋整件 md5 校验不变；书库小文件仍走 contents，不受影响）；基础版保留「Gitee 路线失败→HuggingFace 兜底」
- 分片断点续传：已验合格的分片落盘保留（parts-<文件名>/），失败、取消、重试都只补缺片，不再从头重下
- 下载对话框改自定义视图：选项为真实按钮；点选后同对话框切下载态——确定型进度条（已下载/总字节＋百分比）、可取消；失败切失败态（原因明示＋重试＋关闭），告别只闪一下的失败 Toast 与不可取消的转圈框；已有下载在跑时打开对话框直接挂其进度
- 设置页「模型状态」行在下载中同步显示百分比

## [1.20.0] - 2026-10-27

发布提交：d6cf2fb · 对比：https://github.com/gty3310/shixi/compare/v1.19.0...v1.20.0 · 标签 v1.20.0

### 新增
- 《中庸》双声音：法师原声之外新增 AI 朗读（音轨与逐句时间轴随 APK 内置），选声页、首播门、设置页重选、switchVoice 按句切声与《大学》同一套机制；《中庸》文字与法师音轨仍走书库下载，互不影响，未下载《中庸》的用户不出现声音入口

### 改进
- 《大学》AI 朗读音轨换声：改用腾讯云语音合成的沉稳男声逐句整本生成，替换旧声；1 倍速母带＋逐句实测时间轴（breaks 为估算），双书共 444 句合成回执逐句核对为同一音色
- 设置页「朗读声音」按书分行：《大学》常驻；《中庸》行以该书已下载（预置曲目在列表中）为显现条件

## [1.19.0] - 2026-10-27

发布提交：8b5f67b · 对比：https://github.com/gty3310/shixi/compare/v1.18.0...v1.19.0 · 标签 v1.19.0

### 改进
- 阅读跟随规则重做（用户定稿状态机 FollowGate）：进入跟随只有两路——点顶栏「⇓ 跟随」钮、或播放器由暂停变为播放；退出只有手滑动一路，脱离后绝不自动恢复；暂停且不跟随时阅读器绝不自动跳转，播放且不跟随时句进度照常推进、视图不动
- 横排手动滑动判定改 12 px 位移阈值：点按抖动与点句触摸不误触退出；程序滚动（scrollIntoView/scrollTo）不产生 touchmove/wheel 事件，天然不会误判为手滑
- 竖排在布局变化（字号/旋转/繁简）重分页时按跟随态选锚点：跟随中回当前句所在页，不跟随时留在用户正看的页

### 修复
- 书播到一半暂停后进阅读模式自己读，页面不断自动跳回暂停处那一句
- 播放中手动滑动看别处，6 秒后又被自动跟随拉回（旧「手动滚动后暂停跟随、6 秒自动恢复」口径普通播放已废除；跟读模式保留旧口径不变，识读不受影响）

## [1.18.0] - 2026-10-27

发布提交：91198a2 · 对比：https://github.com/gty3310/shixi/compare/v1.17.1...v1.18.0 · 标签 v1.18.0

### 新增
- 识读双引擎：设置页「识读」组可选主引擎——基础版 AI（约 25 MB）／高级版 AI（约 239 MB）；界面只用这两个名字，真实模型名只出现在 ⓘ 说明弹窗与帮助页
- 高级版离线大模型接入：按停顿分块（单块 ≤25 s）识别＋伪 partial 递进喂对齐器，跟随延迟沿用 1200 ms
- 双引擎复核：两引擎都在位时，副引擎逐块复核主引擎产出，产出双报一致证据
- 加权判定 v2：S＝W（先验）＋E（音素距离）＋C（双引擎一致，同字同错音 +2）＋K（雪崩折扣），阈值 2 定确定错；低先验单引擎 SUB 永不计确定错
- 复习页分档：主列表（确定错＋四类高权重被报字）／「请确认」人工确认区（一键转正或勾销（字,音）组合，持久化）／「机器可能听错了」折叠区／漏读折叠区
- 更新日志机制：新版首次打开弹一次（可下滑看最近 6 版，含试用路径与为什么改），设置页「关于」常驻入口；版本显示带构建提交短号（构建时注入）

### 改进
- 模型下载二选一：只下载基础版／基础版＋高级版一起下载；已下载不重复下，进度与重试沿用既有下载器

### 修复
- 对齐器段首 DEL 锚点卡死（离线只喂 final 时整段停摆）
- 对齐器重锚回退导致的同一错字重复提交计数（epoch 内事件去重）

### 移除
- 旧识读统计口径作废：新建 recite_stats_v2.json 重新累计；旧 recite_stats.json 原样保留在设备上，App 不再读取/展示（不删用户数据）

## [1.17.1] - 2026-10-25

### 改进
- 《大学》AI 朗读音轨换声重录：改用中文原生男声逐句全本重录，合成回执逐句核对

## [1.17.0] - 2026-10-25

### 新增
- 《大学》双声音（法师原声／AI 朗读）与首播选声页，VoicePref 按书记忆，位置跨声音映射

## [1.16.2] - 2026-10-24

### 改进
- 识读模型下载源切 Gitee 模型仓（清单驱动、分片拼合验 md5），失败回退 HuggingFace 主源/镜像

## [1.16.1] - 2026-10-24

### 新增
- 帮助页「识读：它听你读」一节；设置页阅读组给识读钮补显隐开关

## [1.16.0] - 2026-10-24

### 新增
- 识读核心（端侧流式识别＋音节对齐跟随＋错字标红）＋底部三栏＋复习页＋点句笔记/三色标记
