/* 弦歌 Mac 版 · 页面装配（仅浏览器/WKWebView 环境）
 * v0.1.0 纯阅读：选书选章、横排/竖排阅读、拼音与古读标红开关、
 * 注释版本切换、注块只在句组末尾显示一次。无播放、无遍数、无笔记。
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

  // ---------- 左栏 ----------

  function renderBookList() {
    $('bookList').innerHTML = BOOKS.map(function (b) {
      var book = cache[b.id];
      var cnt = book ? book.chapters.length + ' 章 · ' + book.flat.length + ' 句' : '';
      return '<button class="book' + (b.id === state.bookId ? ' on' : '') +
        '" data-book="' + b.id + '"><span class="bt">' + b.name + '</span>' +
        '<span class="bc">' + cnt + '</span></button>';
    }).join('');
  }

  function renderChapList(book) {
    $('chapHead').textContent = '篇章 · ' + book.title;
    $('chapList').innerHTML = book.chapters.map(function (ch, i) {
      return '<button class="chap' + (i === state.curChap ? ' on' : '') +
        '" data-chap="' + i + '">' + XGRender.esc(ch.title) + '</button>';
    }).join('');
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
    }).catch(function (e) {
      txtEl.innerHTML = '<div class="loading">载入失败：' + XGRender.esc(e.message) + '</div>';
    });
  }

  // ---------- 交互 ----------

  $('bookList').addEventListener('click', function (e) {
    var b = e.target.closest('[data-book]');
    if (b && b.dataset.book !== state.bookId) openBook(b.dataset.book);
  });

  $('chapList').addEventListener('click', function (e) {
    var b = e.target.closest('[data-chap]');
    if (!b) return;
    var i = +b.dataset.chap;
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

  /* 点句：高亮本句；竖排时在注栏显示它所在注块的注文 */
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
    if (state.vert) {
      var n = XGRender.noteFor(book.flat[i], state.ver[book.bookId]);
      var body = $('notePanelBody');
      var text = null, f = '';
      if (n) {
        // 注文在段末句那份条目上（e 为段末句拉平下标）
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

  openBook('daxue');
})();
