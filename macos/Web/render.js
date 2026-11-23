/* 弦歌 Mac 版 · 阅读渲染（纯字符串逻辑，浏览器与 Node 双用）
 *
 * 移植自安卓 app/src/main/assets/reader.html 的句渲染与注块渲染：
 *   - 每字 ruby 注音（拼音在字上），古读字加 .gd 类（拼音标红加粗）
 *   - 注块为句后同级元素；落点＝句子 .nt 中带 t 的那份（bookdata 只在
 *     覆盖段末尾句写 t），故一条注只在句组末尾显示一次
 *   - 显示版本优先用户所选，该版本无覆盖才按固定次序回退其他家，
 *     页脚如实标实际显示的那家；导读（dd）不出页脚行（出处只在选版处）
 */
(function (global) {
  'use strict';

  var VER_ORDER = ['zj', 'jz', 'gf', 'dd'];
  var VER_LABEL = {
    zj: '直解',
    jz: '集注',
    gf: '耕夫',
    dd: 'lunyu.ai 白话导读'
  };
  var VER_FOOTER = {
    zj: '张居正的版本',
    jz: '朱熹的版本',
    gf: '耕夫讲经典',
    dd: ''
  };

  function isCJK(c) {
    var n = c.codePointAt(0);
    return (n >= 0x4E00 && n <= 0x9FFF) || (n >= 0x3400 && n <= 0x4DBF) ||
           (n >= 0x20000 && n <= 0x323AF);
  }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /* trad=true 用繁体 textT，否则简体 textS */
  function sentHTML(s, trad) {
    var text = trad ? s.textT : s.textS;
    var pi = 0, out = '';
    for (var ci = 0; ci < text.length;) {
      // 按码点遍历：扩展区字是 UTF-16 代理对，不能按下标拆
      var cp = text.codePointAt(ci);
      var c = String.fromCodePoint(cp);
      ci += c.length;
      if (isCJK(c)) {
        var py = (s.pinyin && pi < s.pinyin.length) ? s.pinyin[pi] : '';
        var gd = s.gd && s.gd.indexOf(pi) >= 0;
        pi++;
        out += py ? '<ruby' + (gd ? ' class="gd"' : '') + '>' + c + '<rt>' + esc(py) + '</rt></ruby>' : c;
      } else {
        out += esc(c);
      }
    }
    return '<span class="s" id="si' + s._i + '" data-i="' + s._i + '">' + out + '</span>';
  }

  /* 按版本优先序（所选在前，其余按固定次序）找本句的注条目 */
  function pickNote(s, ver, needText) {
    if (!s || !s.nt) return null;
    var order = [ver].concat(VER_ORDER.filter(function (k) { return k !== ver; }));
    for (var i = 0; i < order.length; i++) {
      var n = s.nt[order[i]];
      if (n && (!needText || n.t)) return { t: n.t, a: n.a, b: n.b, e: n.e, ver: order[i] };
    }
    return null;
  }

  function noteFor(s, ver) { return pickNote(s, ver, false); }
  function noteEndFor(s, ver) { return pickNote(s, ver, true); }

  function noteFooter(n) {
    var lab = VER_FOOTER[n.ver];
    if (!lab) return '';
    if (n.b > n.a) lab += ' · 本注管第 ' + (n.a + 1) + '–' + (n.b + 1) + ' 句';
    return lab;
  }

  function noteHTML(s, ver) {
    var n = noteEndFor(s, ver);
    if (!n) return '';
    var f = noteFooter(n);
    return '<span class="ntb" id="nt' + s._i + '"><span class="ntt">' + esc(n.t) + '</span>' +
           (f ? '<span class="ntf">' + esc(f) + '</span>' : '') + '</span>';
  }

  var api = {
    VER_ORDER: VER_ORDER, VER_LABEL: VER_LABEL,
    isCJK: isCJK, esc: esc, sentHTML: sentHTML,
    noteFor: noteFor, noteEndFor: noteEndFor, noteFooter: noteFooter, noteHTML: noteHTML
  };
  global.XGRender = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
