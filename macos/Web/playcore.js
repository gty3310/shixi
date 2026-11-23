/* 弦歌 Mac 版 · 播放纯逻辑（浏览器与 Node 双用）
 *
 * ChapterTracker：移植安卓 ChapterProgressTracker 的章界自动计数判定——
 * 播放位置自然越过某章 endMs 即该章计一遍；进入某章时位置须曾处于
 * 「章界 − 阈值」之前（armed）才认随后越界，拖到章尾附近再播完不计；
 * 阈值 min(2000ms, max(500ms, 章长/10))。整轨自然播完时 flushAtTrackEnd
 * 兜底计当前章。计数与循环去向互不牺牲（同安卓口径）。
 *
 * chapterBounds：按安卓 PlayerService.setupChapterMonitor 同法从句子
 * 时间轴推章界——章首＝本章首个有音频句的 startMs，章末＝本章有音频句
 * 的最大 endMs。Mac 绑定的是整本一个音频文件、时间轴即内容原始口径
 * （安卓多声音有 scale 换算，Mac 无此层）。
 * 无音频句＝ startMs 缺失/<0 或 noAudio（与安卓 ContentRepo 同判定）。
 */
(function (global) {
  'use strict';

  function thresholdFor(startMs, endMs) {
    var len = Math.max(0, endMs - startMs);
    return Math.min(2000, Math.max(500, Math.floor(len / 10)));
  }

  function createTracker() {
    var starts = [], ends = [], cur = -1, armed = false, lastPos = -1;

    function chapterFor(pos) {
      for (var i = 0; i < ends.length; i++) if (pos < ends[i]) return i;
      return ends.length - 1;
    }

    var tracker = {
      setChapters: function (s, e) {
        starts = s || []; ends = e || [];
        tracker.reset();
      },
      reset: function () { cur = -1; armed = false; lastPos = -1; },
      /* 换章/回绕后重置到某章起点：该章按已 armed 起步 */
      resetToChapter: function (chapterIndex, posMs) {
        cur = chapterIndex; lastPos = posMs;
        armed = chapterIndex >= 0 && chapterIndex < ends.length &&
          posMs <= ends[chapterIndex] - thresholdFor(starts[chapterIndex], ends[chapterIndex]);
      },
      /* 喂入位置样本，返回本次应计数的章下标数组（通常为空）。
         playing=false 时只同步位置、不判定。 */
      feed: function (posMs, playing) {
        var out = [];
        if (!ends.length) return out;
        if (!playing) { lastPos = posMs; return out; }
        if (cur < 0) {
          cur = chapterFor(posMs);
          armed = posMs <= ends[cur] - thresholdFor(starts[cur], ends[cur]);
          lastPos = posMs;
          return out;
        }
        if (armed && lastPos >= 0 && lastPos < ends[cur] && posMs >= ends[cur]) {
          out.push(cur);
          if (cur + 1 < ends.length) {
            cur = cur + 1;
            armed = posMs <= ends[cur] - thresholdFor(starts[cur], ends[cur]);
          } else {
            armed = false; // 末章已计，停在章界外不再重复计
          }
          lastPos = posMs;
          return out;
        }
        var c = chapterFor(posMs);
        if (c !== cur) {
          cur = c;
          armed = posMs <= ends[cur] - thresholdFor(starts[cur], ends[cur]);
        } else if (posMs <= ends[cur] - thresholdFor(starts[cur], ends[cur])) {
          armed = true; // 本章内回到基线之前（重读）重新 armed
        }
        lastPos = posMs;
        return out;
      },
      /* 整轨自然播完兜底：当前章 armed 且位置已进入本章则计一遍 */
      flushAtTrackEnd: function () {
        if (cur >= 0 && armed && lastPos >= 0 && lastPos < ends[cur]) {
          var c = cur;
          armed = false;
          return c;
        }
        return -1;
      },
      currentChapter: function () { return cur; },
      isArmed: function () { return armed; }
    };
    return tracker;
  }

  function hasAudio(s) {
    return !s.noAudio && s.startMs != null && s.startMs >= 0 && s.endMs != null;
  }

  /* 章界表：[{start, end}]，下标与 book.chapters 对齐。
     极端情形某章无有音频句时以「上一章末」占位（零长），保持下标对齐；
     现有三本书数据无此情形（dev_check 对账）。 */
  function chapterBounds(book) {
    var out = [], prevEnd = 0;
    book.chapters.forEach(function (ch) {
      var st = -1, en = -1;
      ch.sentences.forEach(function (s) {
        if (!hasAudio(s)) return;
        if (st < 0) st = s.startMs;
        if (s.endMs > en) en = s.endMs;
      });
      if (st < 0 || en <= st) { st = prevEnd; en = prevEnd; }
      out.push({ start: st, end: en });
      prevEnd = en;
    });
    return out;
  }

  /* 位置所在句（拉平下标）：最后一个 startMs <= pos 的有音频句；
     pos 在首句之前返回首个有音频句，找不到返回 -1 */
  function sentenceIndexAt(book, posMs) {
    var flat = book.flat, lo = 0, hi = flat.length - 1, cand = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      var sm = flat[mid].startMs;
      if (sm != null && sm <= posMs) { cand = mid; lo = mid + 1; }
      else if (sm != null && sm > posMs) { hi = mid - 1; }
      else { lo = mid + 1; } // startMs 为空：视作排在前面
    }
    for (var i = cand; i >= 0; i--) if (hasAudio(flat[i])) return i;
    for (var j = Math.max(cand, 0); j < flat.length; j++) if (hasAudio(flat[j])) return j;
    return -1;
  }

  /* 拉平下标所属章序（按章句数累加） */
  function chapterOfFlat(book, flatIdx) {
    var acc = 0;
    for (var i = 0; i < book.chapters.length; i++) {
      acc += book.chapters[i].sentences.length;
      if (flatIdx < acc) return i;
    }
    return book.chapters.length - 1;
  }

  var api = {
    thresholdFor: thresholdFor, createTracker: createTracker,
    hasAudio: hasAudio, chapterBounds: chapterBounds,
    sentenceIndexAt: sentenceIndexAt, chapterOfFlat: chapterOfFlat
  };
  global.XGPlayCore = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
