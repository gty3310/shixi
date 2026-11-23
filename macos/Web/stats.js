/* 弦歌 Mac 版 · 章遍数账（纯逻辑，浏览器与 Node 双用）
 *
 * 移植安卓 ChapterStatsStore / BookCountFormat / ChapterTodayEdit 与
 * Store 的日期口径，Mac 与安卓记同一本账：
 *   结构与安卓 chapter_stats.json 逐字对齐（扁平键）：
 *     { "bookId|chapterIndex|yyyy-MM-dd": {"a": 自动, "m": 手动} }
 *   条目在 a、m 都 <= 0 时删除；落盘由装配层经桥写 Application Support
 *   （浏览器预览回退 localStorage），本模块只管数据与口径。
 *
 * 日期口径（同安卓 Store）：本周＝本周一至今日，上周＝上周一至上周日，
 * 本月＝本月 1 日至今日；日期一律本地 'yyyy-MM-dd' 字符串。
 * 整本折算（同 BookCountFormat.wholeBook）：Σ各章 ÷ 章数，一位小数、
 * 四舍五入（HALF_UP，整数运算避免浮点误差）。
 */
(function (global) {
  'use strict';

  var INT_MAX = 2147483647;

  // ---------- 日期 ----------

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  function fmt(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }

  function todayStr() { return fmt(new Date()); }

  function parse(s) {
    var p = s.split('-');
    return new Date(+p[0], +p[1] - 1, +p[2]);
  }

  function addDays(s, n) {
    var d = parse(s);
    d.setDate(d.getDate() + n);
    return fmt(d);
  }

  /* 本周一（周一起始；周日回退到上周一，同 Java DayOfWeek.MONDAY 语义） */
  function mondayOf(s) {
    var dow = parse(s).getDay(); // 0=周日..6=周六
    return addDays(s, -((dow + 6) % 7));
  }

  function monthFirst(s) { return s.slice(0, 8) + '01'; }

  /* 五档区间 [from, to]（含两端）；total 为 null 表示全部日期 */
  function ranges(today) {
    var mon = mondayOf(today);
    return {
      today: [today, today],
      week: [mon, today],
      lastWeek: [addDays(mon, -7), addDays(mon, -1)],
      month: [monthFirst(today), today],
      total: null
    };
  }

  // ---------- 整本折算（BookCountFormat 移植） ----------

  function wholeBook(sum, chapterCount) {
    if (chapterCount <= 0) return '0.0';
    var tenths = Math.floor((20 * sum + chapterCount) / (2 * chapterCount));
    return Math.floor(tenths / 10) + '.' + (tenths % 10);
  }

  // ---------- 编辑校验（ChapterTodayEdit 移植） ----------

  /* 解析直接输数：0 或正整数（int 范围内）返回其值；空/非数字/负数/超上限返回 null */
  function parseEditInput(raw) {
    if (raw == null) return null;
    var s = String(raw).trim();
    if (!s || !/^[+-]?\d+$/.test(s)) return null;
    var v = Number(s);
    if (!isFinite(v) || v < 0 || v > INT_MAX) return null;
    return v;
  }

  /* 步进：cur + delta，下限 0、上限 INT_MAX */
  function stepped(cur, delta) {
    var v = cur + delta;
    if (v < 0) return 0;
    if (v > INT_MAX) return INT_MAX;
    return v;
  }

  // ---------- 账本（ChapterStatsStore 移植） ----------

  function key(bookId, chapterIndex, date) {
    return bookId + '|' + chapterIndex + '|' + date;
  }

  function createStore(initial) {
    var data = {};
    if (initial) {
      Object.keys(initial).forEach(function (k) {
        var e = initial[k];
        if (e && (e.a > 0 || e.m > 0)) data[k] = { a: e.a | 0, m: e.m | 0 };
      });
    }

    function entryVal(k, field) {
      var e = data[k];
      return e ? (e[field] | 0) : 0;
    }

    function putOrRemove(k, a, m) {
      if (a <= 0 && m <= 0) delete data[k];
      else data[k] = { a: a, m: m };
    }

    function add(bookId, idx, date, n, auto) {
      if (!bookId || idx < 0 || !n) return;
      var k = key(bookId, idx, date);
      var a = entryVal(k, 'a'), m = entryVal(k, 'm');
      if (auto) a += n; else m += n;
      putOrRemove(k, a, m);
    }

    var store = {
      addAuto: function (bookId, idx, date, n) { add(bookId, idx, date, n, true); },
      addManual: function (bookId, idx, date, n) { add(bookId, idx, date, n, false); },

      /* 以今日合计（a+m）为口径、下限 0：增加入 m；减少先扣 m 的正数
         部分、不足再扣 a（安卓 adjustChapterToday 原口径） */
      adjustChapterToday: function (bookId, idx, date, delta) {
        if (!bookId || idx < 0 || !delta) return;
        var k = key(bookId, idx, date);
        var a = entryVal(k, 'a'), m = entryVal(k, 'm');
        var cur = a + m;
        var next = Math.max(0, cur + delta);
        var diff = next - cur;
        if (!diff) return;
        if (diff > 0) {
          m += diff;
        } else {
          var cut = -diff;
          var fromM = Math.min(Math.max(m, 0), cut);
          m -= fromM;
          cut -= fromM;
          a -= cut;
        }
        putOrRemove(k, a, m);
      },

      chapterToday: function (bookId, idx, date) {
        var k = key(bookId, idx, date);
        return entryVal(k, 'a') + entryVal(k, 'm');
      },

      chapterTotal: function (bookId, idx) {
        var prefix = bookId + '|' + idx + '|', sum = 0;
        Object.keys(data).forEach(function (k) {
          if (k.indexOf(prefix) === 0) sum += entryVal(k, 'a') + entryVal(k, 'm');
        });
        return sum;
      },

      /* 某书在日期区间 [from, to] 内的全部章合计（含两端） */
      bookSumBetween: function (bookId, from, to) {
        if (!bookId || !from || !to || from > to) return 0;
        var prefix = bookId + '|', sum = 0;
        Object.keys(data).forEach(function (k) {
          if (k.indexOf(prefix) !== 0) return;
          var d = k.substring(k.lastIndexOf('|') + 1);
          if (d >= from && d <= to) sum += entryVal(k, 'a') + entryVal(k, 'm');
        });
        return sum;
      },

      bookTotal: function (bookId) {
        var prefix = bookId + '|', sum = 0;
        Object.keys(data).forEach(function (k) {
          if (k.indexOf(prefix) === 0) sum += entryVal(k, 'a') + entryVal(k, 'm');
        });
        return sum;
      },

      /* 五档整本折算字符串：{today, week, lastWeek, month, total} */
      bookCardStats: function (bookId, chapterCount, today) {
        var r = ranges(today);
        return {
          today: wholeBook(store.bookSumBetween(bookId, r.today[0], r.today[1]), chapterCount),
          week: wholeBook(store.bookSumBetween(bookId, r.week[0], r.week[1]), chapterCount),
          lastWeek: wholeBook(store.bookSumBetween(bookId, r.lastWeek[0], r.lastWeek[1]), chapterCount),
          month: wholeBook(store.bookSumBetween(bookId, r.month[0], r.month[1]), chapterCount),
          total: wholeBook(store.bookTotal(bookId), chapterCount)
        };
      },

      dump: function () { return JSON.stringify(data); }
    };
    return store;
  }

  function loadStore(jsonText) {
    var obj = null;
    if (jsonText) {
      try { obj = JSON.parse(jsonText); } catch (e) { obj = null; }
    }
    return createStore(obj);
  }

  var api = {
    todayStr: todayStr, fmt: fmt, addDays: addDays, mondayOf: mondayOf,
    monthFirst: monthFirst, ranges: ranges,
    wholeBook: wholeBook,
    parseEditInput: parseEditInput, stepped: stepped,
    createStore: createStore, loadStore: loadStore, key: key
  };
  global.XGStats = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
