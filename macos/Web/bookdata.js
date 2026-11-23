/* 弦歌 Mac 版 · 内容合并（纯逻辑，浏览器与 Node 双用）
 *
 * 把共用内容资产合并成阅读页可用的 Book 对象（不改动源数据结构之外的内容）：
 *   content/<book>.json        原文/拼音/时间轴
 *   notes/<book>_notes.json    注释
 *   content/gd/<book>.json     古读标红清单
 * 三本书的资产出处见 ../README.md「内容引用方式」。
 *
 * 合并语义：
 *   gd：按句 id 把标红字位下标数组挂到句子 .gd 上（与安卓同口径）。
 *   注块：每家注以「块首句 + span 句数」存于注文件；一句被多块覆盖时取
 *   起点最晚的那块（与安卓 ContentRepo.attachNotes 同判定）。结果挂到
 *   句子 .nt：{zj|jz|gf|dd: {a, b}} 为块归属（a/b 为拉平下标），注文 t
 *   只落在该块连续覆盖段的末尾句——Mac v1 定版：注块只在句组末尾
 *   显示一次（四家统一；与安卓导读 dd 的注块模型同，而安卓直解/集注/
 *   耕夫仍逐句显示，此处按 Mac v1 范围统一为只显一次）。
 *   导读（dd，《论语》）的 span 为篇内口径（与安卓 attachDaoduToDoc
 *   一致），按章内处理后再换算成拉平下标。
 */
(function (global) {
  'use strict';

  // [注文件键, 短键]；版本可用性由数据决定（大学三家、中庸两家、论语导读）
  var VERS = [['zhijie', 'zj'], ['jizhu', 'jz'], ['gengfu', 'gf'], ['daodu', 'dd']];

  /* 在一组句子（拉平数组或章内数组）上判定某家的注块归属与落点。
     base 为这组句子在全书拉平下标中的起始偏移。 */
  function attachVersion(sentences, notes, key, shortKey, base) {
    var total = sentences.length;
    var starts = [], ends = [], texts = [];
    for (var i = 0; i < total; i++) {
      var e = notes[sentences[i].id];
      if (!e) continue;
      var t = e[key];
      if (t == null || t.length === 0) continue;
      var span = Math.max(1, e[key + 'Span'] | 0 || 1);
      starts.push(i);
      ends.push(Math.min(total - 1, i + span - 1));
      texts.push(t);
    }
    if (!starts.length) return false;
    var bestFor = new Array(total);
    for (var j = 0; j < total; j++) {
      var best = -1;
      for (var k = 0; k < starts.length; k++) {
        if (starts[k] <= j && j <= ends[k] && (best < 0 || starts[k] > starts[best])) best = k;
      }
      bestFor[j] = best;
    }
    // 每句所属块的连续覆盖段末尾（run end）：t 只落在那里
    var runEnd = new Array(total);
    for (var r = total - 1; r >= 0; r--) {
      runEnd[r] = (r < total - 1 && bestFor[r + 1] === bestFor[r] && bestFor[r] >= 0)
        ? runEnd[r + 1] : r;
    }
    for (var m = 0; m < total; m++) {
      var b = bestFor[m];
      if (b < 0) continue;
      var s = sentences[m];
      if (!s.nt) s.nt = {};
      // e＝段末句的组内下标（换算拉平下标时加 base）；竖排注栏按 e 取注文
      var one = { a: base + starts[b], b: base + ends[b], e: base + runEnd[m] };
      if (runEnd[m] === m) one.t = texts[b];
      s.nt[shortKey] = one;
    }
    return true;
  }

  function buildBook(content, notes, gd) {
    var book = {
      bookId: content.bookId,
      title: content.title,
      audioDurationMs: content.audioDurationMs || 0,
      chapters: content.chapters,
      flat: [],
      noteVers: [] // 本书实际有数据的注释版本短键，按 VERS 次序
    };
    book.chapters.forEach(function (ch) {
      ch.sentences.forEach(function (s) {
        s._i = book.flat.length;
        book.flat.push(s);
      });
    });

    if (gd && gd.sentences) {
      book.flat.forEach(function (s) {
        var g = gd.sentences[s.id];
        if (g && g.gd && g.gd.length) s.gd = g.gd;
      });
    }

    if (notes) {
      VERS.forEach(function (v) {
        var key = v[0], shortKey = v[1], ok = false;
        if (shortKey === 'dd') {
          // 导读 span 为篇内口径：逐章处理
          var base = 0;
          book.chapters.forEach(function (ch) {
            if (attachVersion(ch.sentences, notes, key, shortKey, base)) ok = true;
            base += ch.sentences.length;
          });
        } else {
          ok = attachVersion(book.flat, notes, key, shortKey, 0);
        }
        if (ok) book.noteVers.push(shortKey);
      });
    }
    return book;
  }

  var api = { buildBook: buildBook, VERS: VERS };
  global.XGBookData = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
