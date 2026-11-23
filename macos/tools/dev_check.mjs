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

console.log(fails === 0 ? '\n全部通过' : '\n失败 ' + fails + ' 项');
process.exit(fails === 0 ? 0 : 1);
