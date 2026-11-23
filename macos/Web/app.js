/* 弦歌 Mac 版 · 页面装配（仅浏览器/WKWebView 环境）
 * v0.1.0 阅读：选书选章、横排/竖排、拼音与古读标红开关、注释版本切换、
 * 注块只在句组末尾显示一次。
 * v0.2.0 听读：原生桥（选音频/书签、遍数账落盘）或浏览器回退
 * （文件选择 + localStorage）；书卡五档统计、篇章行「今日 X 遍」可点改、
 * 播放器（player.js）计数回调写 auto 账。
 */
(function () {
  'use strict';

  var BOOKS = [
    { id: 'daxue', name: '大學' },
    { id: 'zhongyong', name: '中庸' },
    { id: 'lunyu', name: '論語' }
  ];
  var cache = {}; // bookId -> Book
  var state = {
    bookId: 'daxue',
    ver: {},       // bookId -> 所选注释版本短键
    trad: true,
    vert: false,
    curChap: 0,
    curSent: -1
  };

  var $ = function (id) { return document.getElementById(id); };
  var txtEl = $('txt'), readerEl = $('reader');

  // ---------- 原生桥 / 浏览器回退 ----------

  var hasNative = !!(window.webkit && window.webkit.messageHandlers &&
                     window.webkit.messageHandlers.xiange);
  function post(obj) {
    if (hasNative) window.webkit.messageHandlers.xiange.postMessage(obj);
  }

  var audioState = {}; // bookId -> {bound, name, url?}（url 仅浏览器回退）
  var stats = XGStats.loadStore(null);

  function persistStats() {
    var text = stats.dump();
    if (hasNative) post({ cmd: 'saveStats', json: text });
    else {
      try { localStorage.setItem('xiange.chapter_stats', text); } catch (e) {}
    }
  }

  function audioUrlFor(bookId) {
    var st = audioState[bookId];
    if (!st || !st.bound) return null;
    if (hasNative) return 'xiange://audio/' + bookId;
    return st.url || null;
  }

  window.XGNative = {
    onStats: function (text) {
      stats = XGStats.loadStore(text || null);
      refreshCounts();
    },
    onAudioState: function (st) {
      audioState = st || {};
      applyAudioState();
    }
  };

  // 浏览器回退：用文件选择给当前书绑定音频（对象 URL，仅本次会话）
  var pendingPick = null;
  var fileInput = document.createElement('input');
  fileInput.type = 'file';
  fileInput.accept = 'audio/*';
  fileInput.style.display = 'none';
  document.body.appendChild(fileInput);
  fileInput.addEventListener('change', function () {
    var f = fileInput.files && fileInput.files[0];
    if (f && pendingPick) {
      audioState[pendingPick] = {
        bound: true, name: f.name, url: URL.createObjectURL(f)
      };
      applyAudioState();
    }
    pendingPick = null;
    fileInput.value = '';
  });

  function requestAudio(bookId) {
    if (hasNative) post({ cmd: 'pickAudio', bookId: bookId });
    else { pendingPick = bookId; fileInput.click(); }
  }

  function applyAudioState() {
    renderBookList();
    if (player) {
      player.setAudioUrl(audioUrlFor(state.bookId));
      player.refreshMeta();
    }
  }

  // ---------- 内容加载 ----------

  function fetchJSON(url) {
    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error(url + ' ' + r.status);
      return r.json();
    });
  }

  function loadBook(bookId) {
    if (cache[bookId]) return Promise.resolve(cache[bookId]);
    return Promise.all([
      fetchJSON('data/' + bookId + '.json'),
      fetchJSON('data/' + bookId + '_notes.json').catch(function () { return null; }),
      fetchJSON('data/gd_' + bookId + '.json').catch(function () { return null; })
    ]).then(function (parts) {
      var book = XGBookData.buildBook(parts[0], parts[1], parts[2]);
      cache[bookId] = book;
      if (!state.ver[bookId]) state.ver[bookId] = book.noteVers[0] || 'zj';
      return book;
    });
  }

  // ---------- 左栏：书卡（今日大字 + 五档统计行）与篇章行 ----------

  function renderBookList() {
    var today = XGStats.todayStr();
    $('bookList').innerHTML = BOOKS.map(function (b) {
      var book = cache[b.id];
      var audio = audioState[b.id];
      var audioTxt = audio && audio.bound ? '已加音频' : '未加音频';
      var html = '<button class="book' + (b.id === state.bookId ? ' on' : '') +
        '" data-book="' + b.id + '"><span class="bk-head"><span class="bt">' + b.name +
        '</span><span class="bk-audio' + (audio && audio.bound ? ' has' : '') + '">' +
        audioTxt + '</span></span>';
      if (book) {
        var cs = stats.bookCardStats(b.id, book.chapters.length, today);
        html += '<span class="bk-today"><b>' + cs.today + '</b>' +
          '<span class="bk-unit">遍 · 今日</span></span>' +
          '<span class="bk-stats">今日 ' + cs.today + ' · 本周 ' + cs.week +
          ' · 上周 ' + cs.lastWeek + ' · 本月 ' + cs.month + ' · 总计 ' + cs.total +
          ' 遍</span>' +
          '<span class="bc">' + book.chapters.length + ' 章 · ' + book.flat.length + ' 句</span>';
      }
      return html + '</button>';
    }).join('');
  }

  function renderChapList(book) {
    var today = XGStats.todayStr();
    $('chapHead').textContent = '篇章 · ' + book.title;
    $('chapList').innerHTML = book.chapters.map(function (ch, i) {
      var n = stats.chapterToday(book.bookId, i, today);
      return '<div class="chap-row">' +
        '<button class="chap main' + (i === state.curChap ? ' on' : '') +
        '" data-chap-main="' + i + '">' + XGRender.esc(ch.title) + '</button>' +
        '<button class="chap-play" data-chap-play="' + i + '" title="从本章播放">▶</button>' +
        '<button class="chap-chip" data-chap-chip="' + i + '" title="点改今日遍数">今日 ' +
        n + ' 遍</button></div>';
    }).join('');
    markPlayingChapter(player ? player.currentChapter() : -1);
  }

  function markPlayingChapter(idx) {
    var rows = $('chapList').querySelectorAll('.chap-row');
    rows.forEach(function (row, i) {
      row.classList.toggle('playing', i === idx);
    });
  }

  /* 遍数变化后重画左栏（书卡统计 + 篇章行今日数） */
  function refreshCounts() {
    renderBookList();
    var book = cache[state.bookId];
    if (book) renderChapList(book);
  }

  // ---------- 阅读页 ----------

  function renderReader(book) {
    var ver = state.ver[book.bookId];
    var html = '';
    book.chapters.forEach(function (ch, ci) {
      html += '<section class="ch" id="ch' + ci + '"><h2>' + XGRender.esc(ch.title) + '</h2>';
      if (ch.note) html += '<div class="ch-note">' + XGRender.esc(ch.note) + '</div>';
      html += '<p>';
      ch.sentences.forEach(function (s) {
        html += XGRender.sentHTML(s, state.trad);
        html += XGRender.noteHTML(s, ver);
      });
      html += '</p></section>';
    });
    txtEl.innerHTML = html;
    readerEl.scrollTop = 0;
    readerEl.scrollLeft = 0;
    state.curSent = -1;
  }

  function renderVerOptions(book) {
    var sel = $('optVer');
    sel.innerHTML = book.noteVers.map(function (k) {
      return '<option value="' + k + '"' +
        (k === state.ver[book.bookId] ? ' selected' : '') + '>' +
        XGRender.VER_LABEL[k] + '</option>';
    }).join('');
    sel.disabled = book.noteVers.length === 0;
  }

  function openBook(bookId) {
    state.bookId = bookId;
    state.curChap = 0;
    renderBookList();
    txtEl.innerHTML = '<div class="loading">载入中…</div>';
    loadBook(bookId).then(function (book) {
      renderBookList();
      renderChapList(book);
      renderVerOptions(book);
      renderReader(book);
      player.setBook(book, audioUrlFor(bookId));
    }).catch(function (e) {
      txtEl.innerHTML = '<div class="loading">载入失败：' + XGRender.esc(e.message) + '</div>';
    });
  }

  // ---------- 播放器 ----------

  var player = XGPlayer.create({
    audio: $('audioEl'),
    els: {
      play: $('plPlay'), back: $('plBack'), fwd: $('plFwd'),
      seek: $('plSeek'), cur: $('plCur'), dur: $('plDur'),
      chap: $('plChap'), book: $('plBook'),
      loop: $('plLoop'), rate: $('plRate'), addAudio: $('plAddAudio')
    },
    readerEl: readerEl,
    onCount: function (bookId, chIdx) {
      stats.addAuto(bookId, chIdx, XGStats.todayStr(), 1);
      persistStats();
      refreshCounts();
    },
    onNeedAudio: function () {
      var btn = $('plAddAudio');
      btn.classList.add('attn');
      setTimeout(function () { btn.classList.remove('attn'); }, 1600);
    },
    onAddAudio: function (bookId) { requestAudio(bookId); },
    onChapterChange: function (idx) { markPlayingChapter(idx); }
  });

  // ---------- 篇章行「今日 X 遍」编辑 ----------

  var editCtx = null; // {bookId, chapterIdx}

  function openEdit(bookId, idx) {
    var book = cache[bookId];
    if (!book) return;
    editCtx = { bookId: bookId, chapterIdx: idx };
    $('editTitle').textContent = book.chapters[idx].title + ' · 今日遍数';
    $('editInput').value = stats.chapterToday(bookId, idx, XGStats.todayStr());
    $('editErr').textContent = '';
    $('editMask').hidden = false;
    $('editInput').focus();
    $('editInput').select();
  }

  function closeEdit() {
    editCtx = null;
    $('editMask').hidden = true;
  }

  function editStep(delta) {
    var base = XGStats.parseEditInput($('editInput').value);
    if (base === null && editCtx) {
      base = stats.chapterToday(editCtx.bookId, editCtx.chapterIdx, XGStats.todayStr());
    }
    $('editInput').value = XGStats.stepped(base || 0, delta);
    $('editErr').textContent = '';
  }

  $('editMinus').addEventListener('click', function () { editStep(-1); });
  $('editPlus').addEventListener('click', function () { editStep(1); });
  $('editCancel').addEventListener('click', closeEdit);
  $('editMask').addEventListener('click', function (e) {
    if (e.target === $('editMask')) closeEdit();
  });
  $('editSave').addEventListener('click', function () {
    if (!editCtx) return;
    var v = XGStats.parseEditInput($('editInput').value);
    if (v === null) {
      $('editErr').textContent = '请输入 0 或正整数';
      return;
    }
    var today = XGStats.todayStr();
    var delta = v - stats.chapterToday(editCtx.bookId, editCtx.chapterIdx, today);
    stats.adjustChapterToday(editCtx.bookId, editCtx.chapterIdx, today, delta);
    persistStats();
    refreshCounts();
    closeEdit();
  });
  $('editInput').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') $('editSave').click();
    if (e.key === 'Escape') closeEdit();
  });

  // ---------- 交互 ----------

  $('bookList').addEventListener('click', function (e) {
    var b = e.target.closest('[data-book]');
    if (b && b.dataset.book !== state.bookId) openBook(b.dataset.book);
  });

  $('chapList').addEventListener('click', function (e) {
    var chip = e.target.closest('[data-chap-chip]');
    if (chip) { openEdit(state.bookId, +chip.dataset.chapChip); return; }
    var play = e.target.closest('[data-chap-play]');
    if (play) { player.playChapter(+play.dataset.chapPlay); return; }
    var b = e.target.closest('[data-chap-main]');
    if (!b) return;
    var i = +b.dataset.chapMain;
    state.curChap = i;
    renderChapList(cache[state.bookId]);
    var el = $('ch' + i);
    if (el) {
      if (state.vert) el.scrollIntoView({ inline: 'start', block: 'nearest' });
      else el.scrollIntoView({ block: 'start' });
    }
  });

  $('optPinyin').addEventListener('change', function (e) {
    document.body.classList.toggle('nopy', !e.target.checked);
  });
  $('optGd').addEventListener('change', function (e) {
    document.body.classList.toggle('gdon', e.target.checked);
  });
  $('optNotes').addEventListener('change', function (e) {
    document.body.classList.toggle('noteson', e.target.checked);
  });
  $('optVer').addEventListener('change', function (e) {
    state.ver[state.bookId] = e.target.value;
    renderReader(cache[state.bookId]);
  });
  $('optTrad').addEventListener('click', function () {
    state.trad = !state.trad;
    $('optTrad').textContent = state.trad ? '繁體' : '简体';
    $('optTrad').classList.toggle('on', state.trad);
    renderReader(cache[state.bookId]);
  });
  $('optVert').addEventListener('click', function () {
    state.vert = !state.vert;
    document.body.classList.toggle('vert', state.vert);
    $('optVert').classList.toggle('on', state.vert);
    $('notePanel').hidden = !state.vert;
    readerEl.scrollTop = 0;
    readerEl.scrollLeft = 0;
  });

  /* 点句：高亮本句；竖排时在注栏显示它所在注块的注文；
     已加音频时点句跳播到该句（v0.2.0） */
  txtEl.addEventListener('click', function (e) {
    var el = e.target.closest('.s');
    if (!el) return;
    var book = cache[state.bookId];
    var i = +el.dataset.i;
    if (state.curSent >= 0) {
      var prev = $('si' + state.curSent);
      if (prev) prev.classList.remove('cur');
    }
    state.curSent = i;
    el.classList.add('cur');
    if (player.hasAudio()) player.playSentence(i);
    if (state.vert) {
      var n = XGRender.noteFor(book.flat[i], state.ver[book.bookId]);
      var body = $('notePanelBody');
      var text = null, f = '';
      if (n) {
        var endEntry = book.flat[n.e] && book.flat[n.e].nt[n.ver];
        if (endEntry && endEntry.t) { text = endEntry.t; f = XGRender.noteFooter(n); }
      }
      if (text) {
        body.innerHTML = '<span class="ntt">' + XGRender.esc(text) + '</span>' +
          (f ? '<span class="ntf">' + XGRender.esc(f) + '</span>' : '');
      } else {
        body.textContent = '本句暂无注释。';
      }
    }
  });

  // ---------- 启动 ----------

  if (hasNative) {
    post({ cmd: 'loadStats' });
    post({ cmd: 'getAudioState' });
  } else {
    try {
      stats = XGStats.loadStore(localStorage.getItem('xiange.chapter_stats'));
    } catch (e) {}
  }
  renderBookList();
  // 三本书都先载入（书卡统计行需要各书章数），再打开默认书
  Promise.all(BOOKS.map(function (b) {
    return loadBook(b.id).catch(function () { return null; });
  })).then(function () {
    openBook('daxue');
  });
})();
