# 弦歌 Mac 版

弦歌（时习）的 macOS 版，与安卓版同仓（github.com/gty3310/shixi）。
定位就是安卓版的「横版」：同样的书、同样的阅读口径，界面横过来排——
左栏书目与篇章、右阅读页。

## 版本路线

- **v0.1.0（本版）：纯阅读。** 三本书（《大学》《中庸》《论语》）可选书选章
  阅读：原文（繁/简切换）、拼音（可开关）、古读标红（可开关）、注释版本
  切换、注块只在句组末尾显示一次、横排/竖排阅读（竖排带点句注栏）。
- 后续版本（未排期）：听读版——本地添加音频、底部播放条、遍数记录
  （口径与安卓 chapter_stats.json 对齐：`bookId|chapterIndex|yyyy-MM-dd`
  记 `{a, m}` 两路，整本折算＝各章之和 ÷ 章数、一位小数四舍五入），
  再往后才考虑两端同步。
- 不做（v0.1.0）：点句笔记/标记、阅读遍数、跟读、识读、音频播放。

## 选型：Swift 薄壳 + WKWebView 复用 Web 阅读核心

- 安卓阅读页的核心本来就是 WebView 里的 reader.html（注音、标红、注块
  判定都在 Web 层）。Mac 版沿用同一形态：原生壳只负责开窗口与承载，
  界面与内容渲染由 Web 核心承担——阅读口径（注块判定、跨家回退、页脚
  规则）直接移植安卓实现，不另发明一套，避免两端行为漂移。
- 壳用自定义 URL scheme（`xiange://app/…`）把 .app 资源里的 Web 文件
  供出来，使页面内的 `fetch('data/…')` 在本地也能工作（file:// 下
  fetch 受限）。
- 不用 Electron/Tauri：体积与依赖都不划算；不用纯原生重写阅读排版：
  ruby 注音与竖排在 Web 里是现成的，原生重写成本高且易与安卓漂移。

## 内容引用方式（只有一份源）

三本书的内容是两端共用的活数据，**macos/ 目录内不存内容副本**；
build.sh 在构建时从源位置拷入 .app 资源（`Contents/Resources/Web/data/`）：

| 数据 | 源位置 |
|---|---|
| 《大学》正文、注释、古读清单 | 本 repo `app/src/main/assets/content/daxue.json`、`notes/daxue_notes.json`、`content/gd/daxue.json` |
| 《中庸》正文、注释 | 书库工作副本 `bookstore/repos/zhongyong/{content,notes}/`（安卓在线书库的源） |
| 《论语》正文 | 书库工作副本 `bookstore/repos/lunyu/content/lunyu.json` |
| 《论语》注释（lunyu.ai 白话导读）、《中庸》古读清单 | 本 repo `app/src/main/assets/notes/lunyu_notes.json`、`content/gd/` |

书库工作副本默认在本 repo 同级的 `../bookstore/repos`（相对 macos/ 为
`../../bookstore/repos`），可用环境变量 `BOOKSTORE_DIR` 覆盖；找不到时
build.sh 会报错并说明，不会静默缺书。

注释版本可用性由数据决定：《大学》直解/集注/耕夫，《中庸》直解/集注
（数据里无耕夫），《论语》只有 lunyu.ai 白话导读。

注块口径（Mac v0.1.0 定版）：四家统一「只在句组末尾显示一次」——
注文只落在该块连续覆盖段的末尾句；块被嵌套小块打断时分段、各段各落
一次（与安卓导读的注块模型同）。注意这与安卓现状不同：安卓直解/集注/
耕夫仍逐句显示（用户在安卓侧的定版），Mac 侧按本版范围统一为只显一次。
导读注块不出页脚行，出处只在选版处标明（与安卓 v1.30 口径同）。

## 构建与预览

在 Mac（M3 等 Apple Silicon / Intel 均可，需 Xcode 命令行工具）上：

```sh
cd macos
./build.sh            # 产出 build/弦歌.app，open "build/弦歌.app" 运行
```

只预览 Web 界面（任意系统，本机 Linux 亦可）：

```sh
cd macos
./build.sh --web-only
python3 -m http.server -d build/web-preview 8077
# 浏览器打开 http://127.0.0.1:8077/
```

本机验证（Node，数据对账＋渲染断言，不依赖 macOS）：

```sh
node macos/tools/dev_check.mjs
```

## 目录

```
macos/
  App/main.swift     薄壳：窗口 + WKWebView + xiange:// 资源服务
  App/Info.plist
  Web/index.html     横版外壳：工具栏 + 左栏 + 阅读区（竖排时加注栏）
  Web/app.css        样式（视觉口径同安卓 reader：宣纸米白/墨色/朱砂）
  Web/app.js         页面装配与交互（仅浏览器环境）
  Web/bookdata.js    内容合并：gd 注入 + 注块判定（浏览器/Node 双用）
  Web/render.js      句/注渲染：ruby 注音、古读标红、注块落点（双用）
  build.sh           一条命令构建 / --web-only 组装预览
  tools/dev_check.mjs 本机验证脚本
  CHANGELOG.md       Mac 版独立版本记录（与安卓 CHANGELOG 分开）
```

## 红线

- 诵读音频永不进 repo、永不进 .app（版权口径同安卓；听读版将走本地
  添加）。
- 推送前敏感信息扫描、只按文件名 add，禁 `git add -A`。
- 安卓工程文件、版本号与 CHANGELOG 不因 Mac 版而动。
