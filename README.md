# 时习 Shixi

**一款帮你把经典读熟、背熟的 AI 阅读器。**
*An AI reader that helps you truly master the classics — by reading, listening, and reciting.*

「学而时习之，不亦说乎。」——《论语·学而》

时习是一款为经典诵读而生的 Android 阅读器，帮助你读熟、听熟、背熟经典，先从《大学》《中庸》《论语》等中文经典开始。为书院与自学者而作：离线可用，数据不出手机。

Shixi is an AI reader built for mastering classical texts through recitation, starting with the Chinese classics — *The Great Learning*, *The Doctrine of the Mean*, and *The Analects*. Made for academies and self-learners: offline-first, and your data never leaves your phone.

## 功能 Features

- **跟读模式**：播一段、停一段等你出声复诵；停顿是所播时长的 1.5 倍（跟读者不熟练、复诵比录音慢）；每段字数可调（10 / 15 / 20 / 25 字或整句），切分点只落在句读标点上
- **厘秒级时间轴**：每句、每个标点都有逐字对齐＋静音吸附得到的时间戳（不是按字数估算的）
- **自动遍数统计**：完整读完/听完计一遍，按今日 / 昨日 / 本周 / 上周 / 本月统计；日历视图回看每一天
- **阅读模式**：繁简切换、字顶拼音、横竖排、字号可调、点句跳转
- **关屏续播**：前台服务＋唤醒锁，锁屏和下拉通知栏可控制
- **本地添加音频**：自己的录音也能加进来计遍数
- **群打卡消息**：一键复制按模板生成的读经记录

**开发中（设计已完成，见仓库讨论）**：AI 背诵陪练——端侧离线语音识别听你背诵，背错出声更正、卡壳逐级提示、按句间隔复习；以及朗读检漏。

## 内容与音频 Content & Audio

- `app/src/main/assets/content/` 是三本的内容包：逐句文本（繁/简）、逐字拼音、句级与标点级时间戳，由 `gen_*.py` 在电脑端生成。
- **诵读音频不在本仓库中**：录音另有版权与授权安排。v1.14.0 起 App 只预置《大学》：把 `preset_daxue_full.mp3` 放入 `app/src/main/res/raw/` 即可构建；《中庸》《论语》等其余经典由 App 在线书库下载（维护方法见仓库根目录 WORKFLOW.md）；也可以直接在 App 里添加本地音频使用。
- 《大学》内容包为《礼记》古本序（8 小节、无章题），文字经多版本校勘并与录音逐字核对。

## 构建 Build

零第三方依赖，纯 Android 框架（Java）。工程备有手动构建管线（aapt2 → javac → d8 → zipalign → apksigner）：

```sh
# 需要 JDK 17 与 Android SDK build-tools 34
./build-manual.sh
```

也可以用 Android Studio 打开工程以 Gradle 构建（`build.gradle` 齐备）。

## 结构 Structure

- 界面层：`MainActivity`（列表/日历）、`ReaderActivity`（阅读/跟读）——只发指令、订状态
- 播放层：`PlayerService`——全 App 唯一的播放者（MediaPlayer＋MediaSession＋前台服务），跟读状态机也在此
- 数据层：`Store`——`tracks.json` 与 `records.json` 是唯一的账，统计全部现算、原子写
- 内容层：`ContentRepo` 读 assets 内容包（只读）

## 版本 Version

当前版本 v1.21.0。v1.21.0：新增「阅读计划 / 9621 读经大挑战」——底栏改三栏（听读 / 复习 / 阅读计划，原历史记录迁入计划页作「诵读日历」）；计划只有每本总量与截止日两个硬参数，每天早上按剩余量动态算好今日建议（整本给整数遍、章级给「读 K 章 · 每章 N 遍」），照着读正好按期读完，少读多读自动在后续建议里补平，不记欠账；9621 预置六本（大学、中庸、论语、孟子、六祖坛经、道德经，每本 21 遍、九个月）与自定计划同构，编辑页没有每日量字段；每章升格为小书——章卡片带各自的遍数账（播到章尾自动计、也可手动补记）、可只播一章，循环新增「单章循环」范围与「整书循环」并列；还没下载的书照常排进计划、手动记进度，下载后自动转自动统计；首页书卡上方新增今日进度带，一行看清今日建议与还差多少。本项目以 CC BY-NC-SA 4.0 许可共享（见仓库根目录 LICENSE）。
