/* 弦歌 Mac 版 · 播放控制（仅浏览器/WKWebView 环境）
 *
 * 驱动 <audio> 元素（音频经 xiange://audio/<bookId> 取流，壳侧供文件；
 * 浏览器预览为本地文件对象 URL）。计数判定全在 playcore 的 tracker：
 * 播放中每拍 feed 位置，越章界由装配层写 auto 账；计数与循环去向互不
 * 牺牲——先计数，再按循环模式决定去向（同安卓 CompletionPolicy 口径）：
 *   off     顺序播放：逐章连播，整本播完停在结尾
 *   chapter 单章循环：任一章计满后回绕该章章首续播
 *   book    整本循环：整本播完（含 flush 兜底计数）后回书首续播
 * 跟随高亮：当前位置所在句加 .play 并在滚出视区时自动滚动（横竖排同）。
 */
(function () {
  'use strict';

  var LOOP_LABEL = { off: '顺序播放', chapter: '单章循环', book: '整本循环' };
  var LOOP_NEXT = { off: 'chapter', chapter: 'book', book: 'off' };

  function fmtTime(sec) {
    if (!isFinite(sec) || sec < 0) sec = 0;
    var m = Math.floor(sec / 60), s = Math.floor(sec % 60);
    return m + ':' + (s < 10 ? '0' : '') + s;
  }

  function createPlayer(opts) {
    var audio = opts.audio, els = opts.els;
    var book = null, bounds = [], tracker = XGPlayCore.createTracker();
    var audioUrl = null, loopMode = 'off', dragging = false;
    var curSentPlay = -1, curChapter = -1;

    function hasAudio() { return !!audioUrl; }

    function setPlayIcon() {
      els.play.textContent = audio.paused ? '▶' : '⏸';
    }

    function setChapter(idx, titleOnly) {
      if (idx === curChapter && !titleOnly) return;
      curChapter = idx;
      if (book && idx >= 0 && book.chapters[idx]) {
        els.chap.textContent = book.chapters[idx].title;
      }
      if (opts.onChapterChange) opts.onChapterChange(idx);
    }

    function updateMeta() {
      els.book.textContent = !book ? '' :
        (audioUrl ? book.title : book.title + ' · 未添加音频');
      els.play.disabled = !book;
      els.play.classList.toggle('dim', !audioUrl);
      var dur = audio.duration;
      if (!isFinite(dur) || !dur) dur = book ? book.audioDurationMs / 1000 : 0;
      els.dur.textContent = fmtTime(dur);
      els.seek.max = dur || 0;
    }

    /* seek 后同步 tracker：按落点章 resetToChapter（与安卓 feed 的
       seek 分支同口径：以进入位置定 armed） */
    function syncTrackerTo(posMs) {
      if (!book || !bounds.length) return;
      var si = XGPlayCore.sentenceIndexAt(book, posMs);
      var ch = si >= 0 ? XGPlayCore.chapterOfFlat(book, si) : 0;
      tracker.resetToChapter(ch, posMs);
    }

    function seekToMs(ms) {
      if (!hasAudio()) return;
      audio.currentTime = Math.max(0, ms / 1000);
      syncTrackerTo(ms);
      tick();
    }

    function wrapChapter(c) {
      if (c < 0 || c >= bounds.length) return;
      audio.currentTime = bounds[c].start / 1000;
      tracker.resetToChapter(c, bounds[c].start);
    }

    function follow(posMs) {
      if (!book) return;
      var si = XGPlayCore.sentenceIndexAt(book, posMs);
      if (si < 0) return;
      if (si !== curSentPlay) {
        if (curSentPlay >= 0) {
          var prev = document.getElementById('si' + curSentPlay);
          if (prev) prev.classList.remove('play');
        }
        curSentPlay = si;
        var el = document.getElementById('si' + si);
        if (el) {
          el.classList.add('play');
          if (!audio.paused) {
            var r = el.getBoundingClientRect();
            var c = opts.readerEl.getBoundingClientRect();
            if (r.bottom < c.top + 30 || r.top > c.bottom - 30 ||
                r.right < c.left + 30 || r.left > c.right - 30) {
              el.scrollIntoView({ block: 'center', inline: 'center' });
            }
          }
        }
      }
      setChapter(XGPlayCore.chapterOfFlat(book, si));
    }

    function tick() {
      if (!book) return;
      var posMs = audio.currentTime * 1000;
      if (hasAudio() && !audio.paused && !audio.ended) {
        var counted = tracker.feed(posMs, true);
        for (var i = 0; i < counted.length; i++) {
          opts.onCount(book.bookId, counted[i]);
          if (loopMode === 'chapter') { wrapChapter(counted[i]); break; }
        }
      } else if (hasAudio()) {
        tracker.feed(posMs, false);
      }
      if (!dragging) {
        els.seek.value = audio.currentTime || 0;
        els.cur.textContent = fmtTime(audio.currentTime || 0);
      }
      if (hasAudio()) follow(posMs);
      setPlayIcon();
    }

    audio.addEventListener('timeupdate', tick);
    audio.addEventListener('loadedmetadata', function () {
      audio.playbackRate = parseFloat(els.rate.value) || 1;
      updateMeta();
      tick();
    });
    audio.addEventListener('play', setPlayIcon);
    audio.addEventListener('pause', setPlayIcon);
    audio.addEventListener('ended', function () {
      // 整本自然播完：先 flush 兜底计数，再按循环模式决定去向
      var flushed = tracker.flushAtTrackEnd();
      if (flushed >= 0) opts.onCount(book.bookId, flushed);
      if (loopMode === 'book') {
        tracker.reset();
        audio.currentTime = 0;
        audio.play();
      } else if (loopMode === 'chapter') {
        wrapChapter(flushed >= 0 ? flushed : Math.max(0, curChapter));
        audio.play();
      }
      setPlayIcon();
      tick();
    });
    setInterval(tick, 250);

    els.play.addEventListener('click', function () { api.toggle(); });
    els.back.addEventListener('click', function () {
      if (hasAudio()) seekToMs(audio.currentTime * 1000 - 15000);
    });
    els.fwd.addEventListener('click', function () {
      if (hasAudio()) seekToMs(audio.currentTime * 1000 + 15000);
    });
    els.seek.addEventListener('input', function () {
      dragging = true;
      els.cur.textContent = fmtTime(parseFloat(els.seek.value) || 0);
    });
    els.seek.addEventListener('change', function () {
      dragging = false;
      if (hasAudio()) seekToMs((parseFloat(els.seek.value) || 0) * 1000);
    });
    els.loop.addEventListener('click', function () {
      loopMode = LOOP_NEXT[loopMode];
      els.loop.textContent = LOOP_LABEL[loopMode];
      els.loop.classList.toggle('on', loopMode !== 'off');
    });
    els.rate.addEventListener('change', function () {
      audio.playbackRate = parseFloat(els.rate.value) || 1;
    });
    els.addAudio.addEventListener('click', function () {
      if (book && opts.onAddAudio) opts.onAddAudio(book.bookId);
    });

    var api = {
      /* 切书：停播、按新书重建章界与 tracker；url 为 null 表示未加音频 */
      setBook: function (b, url) {
        audio.pause();
        book = b;
        audioUrl = url || null;
        bounds = book ? XGPlayCore.chapterBounds(book) : [];
        tracker.setChapters(
          bounds.map(function (x) { return x.start; }),
          bounds.map(function (x) { return x.end; }));
        curSentPlay = -1;
        curChapter = -1;
        if (audioUrl) audio.src = audioUrl;
        else audio.removeAttribute('src');
        audio.load();
        els.chap.textContent = book ? book.title : '听读';
        if (book && book.chapters.length) setChapter(0, true);
        updateMeta();
        setPlayIcon();
      },
      setAudioUrl: function (url) {
        audioUrl = url || null;
        if (audioUrl) { audio.src = audioUrl; audio.load(); }
        updateMeta();
      },
      hasAudio: hasAudio,
      toggle: function () {
        if (!book) return;
        if (!hasAudio()) { if (opts.onNeedAudio) opts.onNeedAudio(book.bookId); return; }
        if (audio.paused) {
          if (audio.ended || (audio.duration && audio.currentTime >= audio.duration - 0.05)) {
            tracker.reset();
            audio.currentTime = 0;
          }
          audio.play();
        } else audio.pause();
        setPlayIcon();
      },
      playChapter: function (idx) {
        if (!book || idx < 0 || idx >= bounds.length) return;
        if (!hasAudio()) { if (opts.onNeedAudio) opts.onNeedAudio(book.bookId); return; }
        audio.currentTime = bounds[idx].start / 1000;
        tracker.resetToChapter(idx, bounds[idx].start);
        audio.play();
        setChapter(idx, true);
        tick();
      },
      /* 点句跳播：定位到该句起点续播 */
      playSentence: function (flatIdx) {
        if (!book || flatIdx < 0 || flatIdx >= book.flat.length) return;
        var s = book.flat[flatIdx];
        if (!XGPlayCore.hasAudio(s)) return;
        if (!hasAudio()) { if (opts.onNeedAudio) opts.onNeedAudio(book.bookId); return; }
        seekToMs(s.startMs);
        audio.play();
      },
      currentChapter: function () { return curChapter; },
      refreshMeta: updateMeta
    };
    return api;
  }

  window.XGPlayer = { create: createPlayer, fmtTime: fmtTime };
})();
