/* v1.30.0 reader.html 注块模板实跑（node，无浏览器）：
   从 reader.html 抽取真实函数（esc/noteFor/noteEndFor/noteFooter/noteHTML/markNote），
   喂真实子罕篇数据（zihan_flat.json：ContentRepo 新挂载口径，导读全量只在块末句、
   块内其余句只有 {a,b}），验：一条导读只渲染一次且落在块尾、dd 页脚为空、
   markNote 块内换句亮同一份块尾元素、三家页脚与逐句渲染旧口径不变。 */
const fs = require('fs');
const path = require('path');
const html = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/reader.html'), 'utf8');
function extract(name) {
  const m = html.match(new RegExp('function ' + name + '\\([\\s\\S]*?\\n\\}'));
  if (!m) throw new Error('not found: ' + name);
  return m[0];
}
let src = '';
src += html.match(/var NOTE_VER_KEYS = [^\n]*/)[0] + '\n';
src += "var notesVer = 'daodu';\nvar cur = -1;\nvar curNoteEl = null;\nvar flat = [];\n";
src += `
var __els = {};
var document = {
  getElementById: function(id){ return __els[id] || null; },
  querySelectorAll: function(sel){ return []; }
};
function __mkEl(id){
  var el = { id: id, __cls: {},
    classList: { add: function(c){ el.__cls[c] = 1; }, remove: function(c){ delete el.__cls[c]; },
                 contains: function(c){ return !!el.__cls[c]; } },
    remove: function(){ delete __els[id]; },
    insertAdjacentHTML: function(){},
    querySelector: function(){ return { textContent: '' }; }
  };
  __els[id] = el; return el;
}
`;
for (const f of ['esc', 'noteFor', 'noteEndFor', 'noteFooter', 'noteHTML', 'markNote']) src += extract(f) + '\n';
eval(src);

let passed = 0, failed = 0;
function eq(name, got, want) {
  if (got === want) { passed++; console.log('  PASS ' + name); }
  else { failed++; console.log('  FAIL ' + name + ' got=' + JSON.stringify(got) + ' want=' + JSON.stringify(want)); }
}
function ok(name, cond) { eq(name, !!cond, true); }

flat = JSON.parse(fs.readFileSync(path.join(__dirname, 'zihan_flat.json'), 'utf8'));
let rendered = 0; const texts = [];
for (let i = 0; i < flat.length; i++) {
  const h = noteHTML(flat[i]);
  if (h) { rendered++; texts.push(h); __mkEl('nt' + i); }
}
eq('子罕渲染注元素数 == 块数 30', rendered, 30);
eq('idx52 是标记（无 t）', 't' in flat[52].nt.dd, false);
eq('idx52 块归属 a/b', flat[52].nt.dd.a + '-' + flat[52].nt.dd.b, '52-53');
ok('idx53 带 t（落点）', typeof flat[53].nt.dd.t === 'string');
eq('第 53 句 noteHTML 为空（块内不重复）', noteHTML(flat[52]), '');
ok('第 54 句 noteHTML 含该块导读', noteHTML(flat[53]).includes('堆土成山'));
eq('该块导读全文只出现一次', texts.filter(h => h.includes(flat[53].nt.dd.t.substring(0, 12))).length, 1);
eq('dd 页脚为空串', noteFooter({ver: 'dd', a: 52, b: 53}), '');
markNote(52);
ok('markNote(52) 亮 nt53', !!(__els['nt53'] && __els['nt53'].__cls['cur'] === 1));
markNote(53);
ok('markNote(53) 仍亮 nt53', !!(__els['nt53'] && __els['nt53'].__cls['cur'] === 1));
markNote(0);
ok('markNote(0) 亮本块尾 nt0（块 0-0）', !!(__els['nt0'] && __els['nt0'].__cls['cur'] === 1));
ok('markNote(0) 后 nt53 不再亮', !__els['nt53'].__cls['cur']);
eq('zj 跨句页脚', noteFooter({ver: 'zj', a: 0, b: 2}), '张居正的版本 · 本注管第 1–3 句');
eq('zj 单句页脚', noteFooter({ver: 'zj', a: 1, b: 1}), '张居正的版本');
eq('jz 页脚', noteFooter({ver: 'jz', a: 0, b: 0}), '朱熹的版本');
eq('gf 页脚', noteFooter({ver: 'gf', a: 0, b: 1}), '耕夫讲经典 · 本注管第 1–2 句');
const fakeZj = {_i: 7, nt: {zj: {t: '直解注文', a: 5, b: 9}}};
ok('zj 句逐句渲染（块中句也有元素）', noteHTML(fakeZj).includes('直解注文'));
__mkEl('nt7');
flat[7] = fakeZj;
markNote(7);
ok('zj 当前句亮自己那份 nt7（非块尾 nt9）', !!__els['nt7'].__cls['cur']);
const mix = {_i: 3, nt: {dd: {a: 3, b: 6}, zj: {t: '集注文', a: 3, b: 3}}};
eq('noteFor 优先选定 dd（标记）', noteFor(mix).ver, 'dd');
eq('noteEndFor 落到带 t 的 zj', noteEndFor(mix).ver, 'zj');
console.log('note template test: ' + passed + ' passed, ' + failed + ' failed');
process.exit(failed ? 1 : 0);
