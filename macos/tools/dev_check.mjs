/* 弦歌 Mac 版 · 本机验证（Node，无需 macOS）
 * 用三本书的真实内容源跑 bookdata 合并与 render 渲染断言：
 *   node macos/tools/dev_check.mjs
 * 内容源路径与 build.sh 同口径（BOOKSTORE_DIR 可覆盖书库位置）。
 */
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const XGBookData = require('../Web/bookdata.js');
const XGRender = require('../Web/render.js');
const XGStats = require('../Web/stats.js');
const XGPlayCore = require('../Web/playcore.js');

const REPO = new URL('../../', import.meta.url).pathname;
const ASSETS = REPO + 'app/src/main/assets/';
const STORE = process.env.BOOKSTORE_DIR || REPO + '../bookstore/repos/';

const load = (p) => JSON.parse(readFileSync(p, 'utf8'));
let fails = 0;
function ok(cond, msg) {
  console.log((cond ? '  ✓ ' : '  ✗ ') + msg);
  if (!cond) fails++;
}

/* 只显一次不变量（run 口径，与安卓注块模型一致）：同一块的「连续覆盖段」
   恰有一份带 t 且落在段末，段内其余句不带 t；每句的 e 都指向段末句。
   注意：块被嵌套小块打断时会分成两段、两段各落一次（集注有此情形）。 */
function checkShowOnce(book, ver) {
  const starts = new Set();
  let good = true, runs = 0, i = 0;
  const flat = book.flat;
  while (i < flat.length) {
    const n = flat[i].nt && flat[i].nt[ver];
    if (!n) { i++; continue; }
    starts.add(n.a);
    let j = i;
    while (j + 1 < flat.length) {
      const nx = flat[j + 1].nt && flat[j + 1].nt[ver];
      if (!nx || nx.a !== n.a) break;
      j++;
    }
    runs++;
    for (let k = i; k <= j; k++) {
      const e = flat[k].nt[ver];
      if (e.e !== j) good = false;
      if (k < j && e.t !== undefined) good = false;
      if (k === j && typeof e.t !== 'string') good = false;
    }
    i = j + 1;
  }
  return { good, blocks: starts.size, runs };
}

function gdCount(book) {
  let n = 0;
  book.flat.forEach((s) => { if (s.gd) n += s.gd.length; });
  return n;
}

// ---------- 《大学》 ----------
console.log('《大学》');
const daxue = XGBookData.buildBook(
  load(ASSETS + 'content/daxue.json'),
  load(ASSETS + 'notes/daxue_notes.json'),
  load(ASSETS + 'content/gd/daxue.json'));
ok(daxue.chapters.length === 12, '12 章');
ok(daxue.flat.length === 143, '143 句');
ok(JSON.stringify(daxue.noteVers) === '["zj","jz","gf"]', '注释版本 zj/jz/gf');
ok(gdCount(daxue) === 60, '古读标红 60 字次');
for (const v of ['zj', 'jz', 'gf']) {
  const r = checkShowOnce(daxue, v);
  ok(r.good, v + ' 注块只显一次不变量（' + r.blocks + ' 块）');
}
const zjLand = daxue.flat.filter((s) => s.nt?.zj?.t).length;
const jzLand = daxue.flat.filter((s) => s.nt?.jz?.t).length;
ok(zjLand === 61, '直解 61 块 61 落点（实测 ' + zjLand + '）');
// 集注 65 块、66 落点：块 [78,82] 被块首 80 的嵌套小块打断为两段，各落一次
ok(checkShowOnce(daxue, 'jz').blocks === 65 && jzLand === 66,
  '集注 65 块 66 落点（实测 ' + jzLand + '，含一处嵌套打断）');
// s4（拉平下标 3）直解 span=7：块 [3,9]，t 只在下标 9
ok(daxue.flat[3].nt.zj.a === 3 && daxue.flat[3].nt.zj.b === 9 && !daxue.flat[3].nt.zj.t,
  's4 直解块归属 [3,9] 且本句不带注文');
ok(typeof daxue.flat[9].nt.zj.t === 'string', '块末句（下标 9）带注文');
// 块内句（下标 3）在直解口径下无落点；耕夫在该句自成一块有落点——安卓
// noteEndFor 的跨家回退会如实渲染耕夫那份并标其页脚；直解自身那份不在此句渲染
ok(XGRender.noteEndFor(daxue.flat[3], 'zj').ver === 'gf' &&
   XGRender.noteHTML(daxue.flat[3], 'zj').includes('耕夫讲经典'),
  '块内句不渲染直解注块（回退命中的是耕夫、页脚如实标明）');
ok(XGRender.noteHTML(daxue.flat[9], 'zj').includes('本注管第 4–10 句'), '块末注块页脚「本注管第 4–10 句」');
// 渲染：s1 十六字全注音
const s1html = XGRender.sentHTML(daxue.flat[0], true);
ok((s1html.match(/<ruby/g) || []).length === 16, 's1 渲染 16 个 ruby 注音');
// 全书渲染中 gd 类 ruby 数 == 60
let gdRuby = 0, allHtml = '';
daxue.flat.forEach((s) => { allHtml += XGRender.sentHTML(s, true); });
gdRuby = (allHtml.match(/<ruby class="gd"/g) || []).length;
ok(gdRuby === 60, '全书渲染古读标红 ruby 60 个（实测 ' + gdRuby + '）');

// ---------- 《中庸》 ----------
console.log('《中庸》');
const zhongyong = XGBookData.buildBook(
  load(STORE + 'zhongyong/content/zhongyong.json'),
  load(STORE + 'zhongyong/notes/zhongyong_notes.json'),
  load(ASSETS + 'content/gd/zhongyong.json'));
ok(zhongyong.chapters.length === 24, '24 小节');
ok(zhongyong.flat.length === 301, '301 句');
ok(JSON.stringify(zhongyong.noteVers) === '["zj","jz"]', '注释版本 zj/jz（无耕夫，与数据一致）');
ok(gdCount(zhongyong) === 138, '古读标红 138 字次');
for (const v of ['zj', 'jz']) ok(checkShowOnce(zhongyong, v).good, v + ' 注块只显一次不变量');

// ---------- 《论语》 ----------
console.log('《论语》');
const lunyu = XGBookData.buildBook(
  load(STORE + 'lunyu/content/lunyu.json'),
  load(ASSETS + 'notes/lunyu_notes.json'),
  load(ASSETS + 'content/gd/lunyu.json'));
ok(lunyu.chapters.length === 20, '20 篇');
ok(lunyu.flat.length === 1585, '1585 句');
ok(JSON.stringify(lunyu.noteVers) === '["dd"]', '注释版本只有 dd（lunyu.ai 白话导读）');
ok(gdCount(lunyu) === 237, '古读标红 237 字次');
const ddCovered = lunyu.flat.filter((s) => s.nt?.dd).length;
const ddBlocks = lunyu.flat.filter((s) => s.nt?.dd?.t).length;
ok(ddCovered === 1523, '导读覆盖 1523 句（实测 ' + ddCovered + '）');
ok(ddBlocks === 499, '导读 499 块（实测 ' + ddBlocks + '）');
ok(checkShowOnce(lunyu, 'dd').good, 'dd 注块只显一次不变量');
// 子罕第九（篇序 8）第 53–54 句（篇内下标 52、53）共用一条注、只在 54 句落点
const ziHan = lunyu.chapters[8];
ok(ziHan.sentences[52].nt.dd && !ziHan.sentences[52].nt.dd.t &&
   ziHan.sentences[53].nt.dd && typeof ziHan.sentences[53].nt.dd.t === 'string' &&
   ziHan.sentences[52].nt.dd.a === ziHan.sentences[53].nt.dd.a,
  '子罕 53–54 句共用一注、只在第 54 句显示');
ok(XGRender.noteHTML(ziHan.sentences[53], 'dd').includes('譬如为山') ||
   XGRender.noteEndFor(ziHan.sentences[53], 'dd') !== null,
  '子罕第 54 句可渲染导读注块');
ok(XGRender.noteFooter({ ver: 'dd', a: 0, b: 1 }) === '', '导读注块不出页脚行');

// ---------- v0.2.0 遍数账（stats.js，安卓口径移植） ----------
console.log('遍数账（v0.2.0）');
// 日期边界：2026-11-22 是周日、11-23 是周一
ok(XGStats.mondayOf('2026-11-22') === '2026-11-16', '周日的本周一为 11-16');
ok(XGStats.mondayOf('2026-11-23') === '2026-11-23', '周一的本周一为当日');
{
  const r = XGStats.ranges('2026-11-23');
  ok(r.week[0] === '2026-11-23' && r.week[1] === '2026-11-23', '本周区间 [本周一, 今日]');
  ok(r.lastWeek[0] === '2026-11-16' && r.lastWeek[1] === '2026-11-22', '上周区间 [上周一, 上周日]');
  ok(r.month[0] === '2026-11-01' && r.month[1] === '2026-11-23', '本月区间 [1 日, 今日]');
}
// 整本折算（BookCountFormat 例：论语 7 章→0.4、9 章→0.5）
ok(XGStats.wholeBook(7, 20) === '0.4', '折算 7/20 → 0.4');
ok(XGStats.wholeBook(9, 20) === '0.5', '折算 9/20 → 0.5（0.45 进位）');
ok(XGStats.wholeBook(0, 20) === '0.0', '折算 0 → 0.0');
ok(XGStats.wholeBook(25, 12) === '2.1', '折算 25/12 → 2.1');
ok(XGStats.wholeBook(5, 0) === '0.0', '折算章数 0 → 0.0');
// 编辑校验（ChapterTodayEdit 移植）
ok(XGStats.parseEditInput('3') === 3, '输数解析「3」');
ok(XGStats.parseEditInput(' 12 ') === 12, '输数解析带空格「12」');
ok(XGStats.parseEditInput('') === null, '输数空 → 拦截');
ok(XGStats.parseEditInput('-1') === null, '输数负数 → 拦截');
ok(XGStats.parseEditInput('abc') === null, '输数非数字 → 拦截');
ok(XGStats.parseEditInput('9999999999999') === null, '输数超 int 上限 → 拦截');
ok(XGStats.stepped(0, -1) === 0 && XGStats.stepped(5, 1) === 6, '步进钳制下限 0');
// 写回语义：auto/manual 分路、adjust 先扣 m 再扣 a、下限 0、条目清理
{
  const st = XGStats.createStore();
  const D = '2026-11-23';
  st.addAuto('daxue', 0, D, 2);
  st.addManual('daxue', 0, D, 1);
  ok(st.chapterToday('daxue', 0, D) === 3, '今日合计 a2+m1 = 3');
  st.adjustChapterToday('daxue', 0, D, -2); // 先扣 m(1) 再扣 a(1) → a=1,m=0
  ok(st.chapterToday('daxue', 0, D) === 1, '减 2 后今日 = 1（先扣手动）');
  st.adjustChapterToday('daxue', 0, D, -5); // 下限 0、条目删除
  ok(st.chapterToday('daxue', 0, D) === 0 && !('daxue|0|' + D in JSON.parse(st.dump())),
    '减到 0 条目清理');
  st.adjustChapterToday('daxue', 0, D, 3); // 增加入 m 路
  const e = JSON.parse(st.dump())['daxue|0|' + D];
  ok(e && e.a === 0 && e.m === 3, '增加入 manual 路');
  // 落盘往返
  const st2 = XGStats.loadStore(st.dump());
  ok(st2.chapterToday('daxue', 0, D) === 3, '序列化往返一致');
}
// 五档聚合：跨周/月种子数据（今日 = 2026-11-23 周一）
{
  const st = XGStats.createStore();
  for (let i = 0; i < 7; i++) st.addAuto('lunyu', i, '2026-11-23', 1); // 今日 7 章
  st.addAuto('lunyu', 0, '2026-11-20', 3);  // 上周（11-16~11-22）
  st.addAuto('lunyu', 1, '2026-11-05', 2);  // 本月内、上周之前
  st.addAuto('lunyu', 2, '2026-10-31', 5);  // 上月（只入总计）
  const card = st.bookCardStats('lunyu', 20, '2026-11-23');
  ok(card.today === '0.4', '书卡今日 7/20 → 0.4（实测 ' + card.today + '）');
  ok(card.week === '0.4', '书卡本周 = 今日（周一）');
  ok(card.lastWeek === '0.2', '书卡上周 3/20 → 0.2（实测 ' + card.lastWeek + '）');
  ok(card.month === '0.6', '书卡本月 12/20 → 0.6（实测 ' + card.month + '）');
  ok(card.total === '0.9', '书卡总计 17/20 → 0.9（实测 ' + card.total + '）');
}

// ---------- v0.2.0 播放纯逻辑（playcore.js，ChapterProgressTracker 移植） ----------
console.log('播放逻辑（v0.2.0）');
ok(XGPlayCore.thresholdFor(0, 10000) === 1000, '阈值 章长 10s → 1000ms');
ok(XGPlayCore.thresholdFor(0, 3000) === 500, '阈值下限 500ms');
ok(XGPlayCore.thresholdFor(0, 100000) === 2000, '阈值上限 2000ms');
{
  const tr = XGPlayCore.createTracker();
  tr.setChapters([0, 10000, 25000], [10000, 25000, 40000]);
  ok(JSON.stringify(tr.feed(0, true)) === '[]', '进入首章不计数');
  ok(JSON.stringify(tr.feed(5000, true)) === '[]', '章内推进不计数');
  ok(JSON.stringify(tr.feed(10050, true)) === '[0]', '自然越过章界计第 0 章');
  ok(tr.currentChapter() === 1, '越界后当前章 = 1');
}
{
  // 拖到章尾附近再播完：未 armed 不计（防误计基线）
  const tr = XGPlayCore.createTracker();
  tr.setChapters([0, 10000, 25000], [10000, 25000, 40000]);
  tr.feed(24000, true); // 进入第 1 章时已过基线（25000-1500）
  ok(JSON.stringify(tr.feed(25100, true)) === '[]', '章尾附近进入、越界不计');
  ok(tr.currentChapter() === 2, '不计但当前章推进到 2');
}
{
  // 暂停喂拍只同步位置；整轨播完 flush 兜底；resetToChapter 从章首 armed
  const tr = XGPlayCore.createTracker();
  tr.setChapters([0, 10000, 25000], [10000, 25000, 40000]);
  tr.feed(26000, false);
  ok(tr.currentChapter() === -1, '未播放喂拍不判定');
  tr.feed(26000, true);
  tr.feed(30000, true);
  ok(tr.flushAtTrackEnd() === 2, '播完 flush 计末章');
  ok(tr.flushAtTrackEnd() === -1, 'flush 不重复计');
  tr.resetToChapter(1, 10000);
  ok(tr.isArmed(), 'resetToChapter 章首起步 armed');
  ok(JSON.stringify(tr.feed(25100, true)) === '[1]', '章首播起越界计第 1 章');
}
// 章界表与句定位（真实数据）
{
  const b = XGPlayCore.chapterBounds(daxue);
  ok(b.length === 12, '大学章界表 12 章');
  ok(b[0].start === daxue.flat[0].startMs && b[11].end <= daxue.audioDurationMs,
    '大学章界首尾与时间轴对齐');
  let mono = true;
  for (let i = 1; i < b.length; i++) if (b[i].start < b[i - 1].end) mono = false;
  ok(mono, '大学章界单调不重叠');
  const bl = XGPlayCore.chapterBounds(lunyu);
  ok(bl.length === 20 && bl[19].end <= lunyu.audioDurationMs, '论语章界表 20 篇');
  ok(XGPlayCore.sentenceIndexAt(daxue, daxue.flat[0].startMs) === 0, '句定位 首句');
  ok(XGPlayCore.sentenceIndexAt(daxue, daxue.flat[142].startMs) === 142, '句定位 末句');
  ok(XGPlayCore.sentenceIndexAt(daxue, daxue.flat[10].startMs + 10) === 10, '句定位 句中');
  ok(XGPlayCore.chapterOfFlat(lunyu, 0) === 0 && XGPlayCore.chapterOfFlat(lunyu, 1584) === 19,
    '拉平下标所属章（论语首尾）');
}

console.log(fails === 0 ? '\n全部通过' : '\n失败 ' + fails + ' 项');
process.exit(fails === 0 ? 0 : 1);
