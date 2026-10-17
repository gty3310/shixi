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
- **诵读音频不在本仓库中**：录音另有版权与授权安排。App 预置曲目引用的音频文件名为 `preset_daxue_full.mp3`、`preset_zhongyong_full.mp3`、`lunyu_01.mp3` … `lunyu_20.mp3`（位于 `app/src/main/res/raw/`），放入同名文件即可构建完整版本；也可以直接在 App 里添加本地音频使用。
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

当前版本 v1.8.4。v1.8.4：跟读停止点提前 150ms 触发，补偿轮询与位置上报延迟造成的停止过冲（段末不再多播、下一段开头不再被吃掉一两个字）。v1.8.3：修复覆盖安装后预置音频不随版本更新的问题——预置音频改为随应用版本自动重新释放，v1.8.2 起《中庸》已切除章节名念白的新音频这下能真正生效（旧版覆盖安装后内部存储里的旧音频不会被替换、仍会播旧文件）。v1.8.2：大学、中庸全本按逗号级校验切点（逐字对齐＋静音吸附，含 SRT 时间链连续的新逗号级字幕）；中庸切除录音中 32 处章节名念白；阅读页播放高亮细化到小句级；首页新增「添加书籍」（可导入音频＋简体／繁体字幕成书）与内置帮助页。v1.8.1：倍速新增 4 倍速；跟读句间切点与下一句起点完全重合（同一小节内不再跳过句间音频，小节之间停顿保持原样）。v1.8.0：中庸按原书 24 节重组并补回漏掉的一句、大学按原书 12 节重分，两本文字拼音全面按书校正；竖版阅读改分页式（一屏一页、读完一页才翻页）。应用显示名与项目名同为「时习 Shixi」（v1.7.1 起由「诵读」更名），包名 `com.ti.songdu`。本项目以 CC BY-NC-SA 4.0 许可共享（见仓库根目录 LICENSE）。
