/* ============================================================
   音乐下载器 Android —— 前端逻辑
   与原生通过 window.Android（@JavascriptInterface）通信：
     同步方法直接返回 JSON 字符串；异步结果走 window.__push
   ============================================================ */

const A = window.Android;

/* ---------------- 图标 ---------------- */
const I = {
  music: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>`,
  down: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12m0 0l-4.5-4.5M12 15l4.5-4.5M4 20h16"/></svg>`,
  check: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>`,
  alert: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v4.5M12 16h.01"/></svg>`,
  warn: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/></svg>`,
  info: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/></svg>`,
};

/* ---------------- 工具 ---------------- */
const $ = s => document.querySelector(s);
const $$ = s => Array.from(document.querySelectorAll(s));
const esc = s => String(s ?? '').replace(/[&<>"']/g,
  c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function fmtBytes(n) {
  if (!n) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB']; let i = 0, v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return v.toFixed(v < 10 && i > 0 ? 1 : 0) + ' ' + u[i];
}
const fmtSpeed = b => b ? fmtBytes(b) + '/s' : '—';
function fmtEta(s) {
  if (!s || s < 1) return '';
  return s < 60 ? Math.round(s) + ' 秒'
                : Math.floor(s / 60) + ' 分 ' + Math.round(s % 60) + ' 秒';
}
function fmtDur(ms) {
  if (!ms) return '';
  const t = Math.round(ms / 1000);
  return String(Math.floor(t / 60)).padStart(2, '0') + ':' + String(t % 60).padStart(2, '0');
}
const baseName = p => String(p || '').split(/[\\/]/).pop();

function toast(msg, kind = '', ms = 3000) {
  const el = document.createElement('div');
  el.className = 'toast ' + kind;
  el.innerHTML = (kind === 'ok' ? I.check : kind === 'err' ? I.alert : I.info) +
    '<span>' + esc(msg) + '</span>';
  $('#toasts').appendChild(el);
  setTimeout(() => {
    el.style.transition = 'opacity .25s,transform .25s';
    el.style.opacity = '0'; el.style.transform = 'translateY(8px)';
    setTimeout(() => el.remove(), 260);
  }, ms);
}

/* ---------------- 状态 ---------------- */
const S = {
  tab: 'search',
  settings: null,
  results: [],
  jobs: new Map(),
  mine: [],
  elsewhere: [],
  selected: new Set(),
  busy: false,
};

/* ============================================================
   初始化
   ============================================================ */
function init() {
  if (!A) {
    document.body.innerHTML =
      '<div class="empty" style="padding-top:120px"><b>没有检测到原生桥</b>' +
      '请通过「音乐下载器」App 打开本页面</div>';
    return;
  }
  bindEvents();
  S.settings = call('getSettings');
  syncUI();
  updateStatus();
  renderResults();
}

function call(method, ...args) {
  try {
    const r = A[method](...args);
    return typeof r === 'string' ? JSON.parse(r) : r;
  } catch (e) {
    return { ok: false, error: { message: '原生调用失败：' + e.message } };
  }
}

/* ============================================================
   事件（来自原生）
   ============================================================ */
window.__push = function (evt) {
  switch (evt.type) {
    case 'results': onResults(evt); break;
    case 'cookieTest': onCookieTest(evt); break;
    case 'moved':
      if (evt.ok) { toast(`已移动 ${evt.count} 个文件`, 'ok');
        if (evt.fails) console.warn(evt.fails); refreshFiles(); }
      else toast(evt.error?.message || '移动失败', 'err');
      break;
    case 'deleted':
      if (evt.ok) { toast(`已删除 ${evt.count} 个文件`, 'ok'); refreshFiles(); }
      else toast(evt.error?.message || '删除失败', 'err');
      break;
    case 'rescanned': toast('已请求系统重新扫描媒体库', 'ok'); break;
    case 'folderPicked':
      if (evt.cancelled) break;
      if (evt.ok) {
        S.settings = call('getSettings'); syncUI(); updateStatus();
        toast('保存位置已改为：' + evt.target, 'ok', 3600);
        if (!evt.writable) toast('这个目录可能没有写入权限，建议换一个', 'err', 4200);
      } else toast(evt.error?.message || '选择目录失败', 'err');
      break;
    case 'configPicked':
      if (evt.cancelled) break;
      if (evt.ok) {
        S.settings = call('getSettings'); syncUI(); updateStatus();
        toast('配置已导入', 'ok');
      } else toast(evt.error?.message || '导入失败', 'err');
      break;
    case 'permission':
      if (!evt.granted) toast('没拿到文件权限，「文件」页可能读不到别处的音频', '', 4000);
      break;
    default:
      if (evt.jobId) onJobEvent(evt);
  }
};

/* ============================================================
   Tab 切换
   ============================================================ */
function switchTab(name) {
  S.tab = name;
  $$('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
  ['search', 'files', 'settings'].forEach(t => {
    $('#page-' + t).hidden = t !== name;
  });
  $('#pageTitle').textContent =
    name === 'search' ? '音乐下载器' : name === 'files' ? '文件' : '设置';
  $('#main').scrollTop = 0;
  if (name === 'files') refreshFiles();
  if (name === 'settings') { S.settings = call('getSettings'); syncUI(); }
}

window.__onBack = function () {
  if (!$('#actionBar').hidden) { clearSelection(); return true; }
  if (S.tab !== 'search') { switchTab('search'); return true; }
  return false;
};

/* ============================================================
   搜索
   ============================================================ */
function doSearch() {
  const q = $('#q').value.trim();
  if (!q) { toast('请输入歌名或粘贴链接', 'err'); return; }
  if (S.busy) return;
  S.busy = true;
  const b = $('#btnSearch');
  b.disabled = true;
  b.querySelector('.spin').hidden = false;
  b.querySelector('.lbl').textContent = '搜索中';
  $('#results').innerHTML = `<div class="empty">正在搜索…</div>`;
  call('search', q);
}

function onResults(evt) {
  S.busy = false;
  const b = $('#btnSearch');
  b.disabled = false;
  b.querySelector('.spin').hidden = true;
  b.querySelector('.lbl').textContent = '搜索';

  if (!evt.ok) {
    S.results = [];
    $('#results').innerHTML = errorBox(evt.error);
    return;
  }
  S.results = evt.results || [];
  renderResults();
}

function renderResults() {
  if (!S.results.length) {
    $('#results').innerHTML = S.jobs.size ? '' :
      `<div class="empty"><div class="ea">${I.music}</div>
       <b>搜一首歌试试</b>输入歌名、歌手，或直接粘贴网易云歌曲链接。<br>
       下载好的文件会直接存进系统「Music」目录，<br>绝大多数播放器打开就能看到。</div>`;
    return;
  }
  $('#results').innerHTML =
    `<div class="sec-head"><span>搜索结果 ${S.results.length} 条</span>
       <button class="link" id="btnClearRes">清空</button></div>` +
    S.results.map((r, i) => `
    <div class="res ${r._best ? 'best' : ''}">
      ${r.cover ? `<img class="cover" src="${esc(r.cover)}" alt="" data-ph="${i}">`
                : `<div class="cover">${I.music}</div>`}
      <div class="res-main">
        <div class="res-title">${esc(r.title)}${r.vip ? '<span class="tag tag-vip">VIP</span>' : ''}${r._best ? '<span class="tag tag-best">最匹配</span>' : ''}</div>
        <div class="res-sub">${esc(r.artist)}${r.album ? ' · ' + esc(r.album) : ''}</div>
      </div>
      <span class="dur">${fmtDur(r.duration)}</span>
      <button class="btn btn-primary btn-sm" data-dl="${i}">下载</button>
    </div>`).join('');

  $('#btnClearRes')?.addEventListener('click', () => {
    S.results = []; renderResults();
  });
  $$('#results img.cover').forEach(img => {
    img.onerror = () => {
      const d = document.createElement('div');
      d.className = 'cover'; d.innerHTML = I.music;
      img.replaceWith(d);
    };
  });
  $$('#results [data-dl]').forEach(b => {
    b.onclick = () => startDownload(S.results[+b.dataset.dl]);
  });
}

function errorBox(err, jobId) {
  err = err || {};
  const detail = err.detail
    ? `<details><summary>技术详情</summary><pre>${esc(err.detail)}</pre></details>` : '';
  const retry = jobId
    ? `<div class="btn-row btn-row-end" style="margin-top:10px">
         <button class="btn btn-ghost btn-sm" data-retry="${esc(jobId)}">重试</button></div>` : '';
  return `<div class="err-box">
      <div class="err-title">${I.alert}<span>${esc(err.message || '出错了')}</span></div>
      ${err.hint ? `<div class="err-hint">${esc(err.hint)}</div>` : ''}
      ${detail}${retry}</div>`;
}

/* ============================================================
   任务
   ============================================================ */
function startDownload(song) {
  const r = call('startDownload', JSON.stringify(song));
  if (!r.ok) { toast(r.error?.message || '启动失败', 'err'); return; }
  createJob(r.jobId, song);
  switchTab('search');
  toast('已开始下载', 'ok', 1600);
}

function createJob(id, song) {
  const el = document.createElement('div');
  el.className = 'job running';
  el.innerHTML = `
    <div class="job-top">
      <div class="cover">${I.music}</div>
      <div class="job-main">
        <div class="job-title">${esc(song.title || '')}</div>
        <div class="job-sub">${esc(song.artist || '')}</div>
      </div>
      <span class="st st-run"><span class="mini-spin"></span>准备中</span>
    </div>
    <div class="stage" hidden><span class="mini-spin"></span><span class="stg"></span></div>
    <div class="bar" hidden><div class="bar-fill"></div></div>
    <div class="metrics" hidden></div>
    <div class="notes"></div>
    <div class="files"></div>`;
  const refs = {
    el,
    title: el.querySelector('.job-title'),
    sub: el.querySelector('.job-sub'),
    side: el.querySelector('.st'),
    stage: el.querySelector('.stage'),
    stg: el.querySelector('.stg'),
    bar: el.querySelector('.bar'),
    fill: el.querySelector('.bar-fill'),
    metrics: el.querySelector('.metrics'),
    notes: el.querySelector('.notes'),
    files: el.querySelector('.files'),
    cover: el.querySelector('.cover'),
  };
  $('#jobs').insertBefore(el, $('#jobs').firstChild);

  const data = {
    id, song, status: 'running', stage: '', progress: {},
    notes: [], files: [], error: null,
  };
  S.jobs.set(id, { data, refs });
  if (song.cover) {
    const img = new Image();
    img.className = 'cover'; img.src = song.cover;
    img.onload = () => refs.cover.replaceWith(img);
  }
  $('#tabBadge').hidden = false;
}

const badgeOf = s => /下载/.test(s) ? '下载中'
  : /标签/.test(s) ? '写入标签'
  : /封面/.test(s) ? '取封面'
  : /歌词/.test(s) ? '取歌词'
  : /音源|解析/.test(s) ? '解析音源'
  : /信息/.test(s) ? '读取信息' : '处理中';

function onJobEvent(evt) {
  const j = S.jobs.get(evt.jobId);
  if (!j) return;
  const { data, refs } = j;
  switch (evt.type) {
    case 'status':
      if (evt.status === 'done') { data.status = 'done'; renderDone(j); }
      else if (evt.status === 'error') { data.status = 'error'; data.error = evt.error; renderError(j); }
      else if (evt.status === 'cancelled') { data.status = 'cancelled'; refs.el.remove(); S.jobs.delete(data.id); }
      break;
    case 'stage':
      data.stage = evt.text;
      refs.stage.hidden = false;
      refs.stg.textContent = evt.text;
      if (refs.side.classList.contains('st-run')) {
        refs.side.innerHTML = `<span class="mini-spin"></span>${badgeOf(evt.text)}`;
      }
      if (!data.progress.total) { refs.bar.hidden = false; refs.fill.classList.add('indet'); }
      break;
    case 'meta':
      refs.title.textContent = evt.meta.title;
      refs.sub.textContent = [evt.meta.artist, evt.meta.album,
        fmtDur(evt.meta.duration)].filter(Boolean).join(' · ');
      if (evt.meta.cover) {
        const img = new Image();
        img.className = 'cover'; img.src = evt.meta.cover;
        img.onload = () => refs.cover.replaceWith(img);
      }
      break;
    case 'progress':
      data.progress = evt;
      refs.bar.hidden = false;
      refs.fill.classList.remove('indet');
      const pct = evt.total ? Math.min(100, evt.done * 100 / evt.total) : 0;
      refs.fill.style.width = pct.toFixed(1) + '%';
      refs.metrics.hidden = false;
      const m = [];
      if (evt.total) m.push(`<span><b>${pct.toFixed(0)}%</b> ${fmtBytes(evt.done)}/${fmtBytes(evt.total)}</span>`);
      else m.push(`<span>已下载 <b>${fmtBytes(evt.done)}</b></span>`);
      if (evt.speed) m.push(`<span>${fmtSpeed(evt.speed)}</span>`);
      const eta = fmtEta(evt.eta);
      if (eta) m.push(`<span>剩余 ${eta}</span>`);
      refs.metrics.innerHTML = m.join('');
      break;
    case 'file':
      data.files.push(evt);
      renderFileRows(j);
      break;
    case 'warn':
      data.notes.push(evt);
      refs.notes.innerHTML = data.notes.map(n =>
        `<div class="warn-row">${I.warn}<span>${esc(n.text)}</span></div>`).join('');
      break;
  }
}

function renderFileRows(j) {
  const { data, refs } = j;
  refs.files.innerHTML = data.files.map(f => `
    <div class="file-row">
      <span class="ext ${esc(f.ext)}">${esc(f.ext.toUpperCase())}</span>
      <div class="fr-info">
        <div class="fr-name">${esc(baseName(f.path))}</div>
        <div class="fr-tags">${fmtBytes(f.size)} · ${esc(f.level || '')} · 封面 ${esc(f.cover || '—')} · 歌词 ${f.lyricLines || 0} 行</div>
      </div>
    </div>`).join('');
}

function renderDone(j) {
  const { data, refs } = j;
  refs.el.className = 'job done';
  refs.stage.hidden = true;
  refs.bar.hidden = false;
  refs.fill.classList.remove('indet');
  refs.fill.style.width = '100%';
  refs.bar.classList.add('done');
  refs.side.className = 'st st-done';
  refs.side.innerHTML = I.check + '完成';
  renderFileRows(j);
  toast('下载完成，已存入 ' + (S.settings?.target || ''), 'ok', 3600);
  setTimeout(() => { $('#tabBadge').hidden = true; }, 4000);
}

function renderError(j) {
  const { data, refs } = j;
  refs.el.className = 'job error';
  refs.stage.hidden = true;
  refs.bar.hidden = true;
  refs.side.className = 'st st-err';
  refs.side.innerHTML = I.alert + '失败';
  const box = document.createElement('div');
  box.innerHTML = errorBox(data.error, data.id);
  refs.notes.appendChild(box.firstElementChild);
  refs.notes.querySelector('[data-retry]')?.addEventListener('click', () => {
    refs.el.remove(); S.jobs.delete(data.id); startDownload(data.song);
  });
}

/* ============================================================
   文件
   ============================================================ */
function refreshFiles() {
  const r = call('listFiles');
  if (r.ok) {
    S.mine = r.files || [];
    if (r.target) { S.settings = S.settings || {}; S.settings.target = r.target; }
    $('#filesTarget').textContent = r.target || '—';
  } else {
    S.mine = [];
    $('#mineList').innerHTML = errorBox(r.error);
  }
  renderMine();
  clearSelection();
}

function renderMine() {
  const list = $('#mineList');
  $('#mineCount').textContent = `此处的文件（${S.mine.length}）`;
  $('#btnSelAll').hidden = S.mine.length === 0;
  if (!S.mine.length) {
    list.innerHTML = `<div class="empty"><div class="ea">${I.music}</div>
      <b>这里还没有文件</b>去「搜索」页下载一首试试</div>`;
    return;
  }
  list.innerHTML = S.mine.map((f, i) => fileItem(f, 'mine', i)).join('');
  bindItems('#mineList', S.mine);
}

function fileItem(f, src, i) {
  const sel = S.selected.has(src + ':' + i);
  const ext = (f.name.split('.').pop() || '').toLowerCase();
  return `<div class="fitem ${sel ? 'sel' : ''}" data-src="${src}" data-i="${i}">
      <span class="ck">${I.check}</span>
      <div class="fi-main">
        <div class="fi-name">${esc(f.name)}</div>
        <div class="fi-sub">${fmtBytes(f.size)} · ${esc(f.display)}</div>
      </div>
    </div>`;
}

function bindItems(sel, arr) {
  $$(sel + ' .fitem').forEach(el => {
    el.onclick = () => {
      const key = el.dataset.src + ':' + el.dataset.i;
      if (S.selected.has(key)) S.selected.delete(key); else S.selected.add(key);
      el.classList.toggle('sel');
      updateSelBar();
    };
  });
}

function clearSelection() {
  S.selected.clear();
  $$('.fitem.sel').forEach(e => e.classList.remove('sel'));
  updateSelBar();
}

function updateSelBar() {
  const n = S.selected.size;
  $('#actionBar').hidden = n === 0;
  $('#selText').textContent = `已选 ${n} 项`;
}

function selectedItems() {
  const out = [];
  for (const k of S.selected) {
    const [src, i] = k.split(':');
    const arr = src === 'mine' ? S.mine : S.elsewhere;
    if (arr[+i]) out.push(arr[+i]);
  }
  return out;
}

function scanElsewhere() {
  $('#elseHead').hidden = false;
  $('#elseList').innerHTML = `<div class="empty">正在扫描…</div>`;
  const r = call('listElsewhere');
  if (!r.ok) { $('#elseList').innerHTML = errorBox(r.error); return; }
  S.elsewhere = (r.files || []).filter(f => !S.mine.some(m => m.name === f.name));
  $('#elseList').innerHTML = S.elsewhere.length
    ? S.elsewhere.map((f, i) => fileItem(f, 'else', i)).join('')
    : `<div class="empty">没有在别处发现音频文件</div>`;
  $('#elseHint').hidden = S.elsewhere.length === 0;
  bindItems('#elseList', S.elsewhere);
}

/* ============================================================
   设置
   ============================================================ */
function syncUI() {
  const d = S.settings || {};
  $('#targetText').textContent = d.target || '…';
  $('#filesTarget').textContent = d.target || '…';
  $('#setTargetDesc').textContent = d.targetDesc || d.target || '…';
  $('#setDirName').value = d.dirName || '音乐下载';
  setSeg('#segFormat', d.format || 'both');
  setSeg('#setFormat', d.format || 'both');
  $('#setSource').value = d.sourceOrder || 'official_first';
  $('#setCoverMax').value = d.coverMax ?? 1200;
  $('#setCover').checked = d.embedCover !== false;
  $('#setLyrics').checked = d.embedLyrics !== false;
  $('#setLrc').checked = d.writeLrc !== false;
  $('#aboutText').textContent =
    `音乐下载器 Android 版 v${d.version || '1.0'}　(Android API ${d.sdkInt || '?'})`;
  renderCookies();
}

function setSeg(sel, v) {
  $$(sel + ' button').forEach(b => b.classList.toggle('active', b.dataset.v === v));
}
const getSeg = sel => $(sel + ' button.active')?.dataset.v || 'both';

function updateStatus() {
  const d = S.settings || {};
  const n = (d.cookies || []).filter(c => c.enabled && c.hasValue).length;
  const el = $('#sourceChip');
  el.classList.remove('ok', 'warn');
  if (d.sourceOrder === 'aggregator_only') {
    el.classList.add('warn'); $('#sourceText').textContent = '仅用聚合接口';
  } else if (n > 0) {
    el.classList.add('ok'); $('#sourceText').textContent = `官方通道就绪`;
  } else {
    el.classList.add('warn'); $('#sourceText').textContent = '未配账号 · 用聚合接口';
  }
}

function renderCookies() {
  const list = $('#cookieList');
  const d = S.settings || {};
  const cks = d.cookies || [];
  if (!cks.length) {
    list.innerHTML = `<div class="empty" style="padding:18px 0">还没有配置账号</div>`;
    return;
  }
  list.innerHTML = cks.map(c => `
    <div class="ck-row ${c.id === d.activeCookieId ? 'active' : ''}" data-id="${esc(c.id)}">
      <span class="ck-radio"></span>
      <div class="ck-info">
        <div class="ck-name">${esc(c.name)}${c.enabled ? '' : ' <span class="dim">（停用）</span>'}</div>
        <div class="ck-meta" data-meta="${esc(c.id)}">${esc(c.masked)}</div>
      </div>
      <button class="btn btn-ghost btn-sm" data-test="${esc(c.id)}">测试</button>
      <button class="btn btn-ghost btn-sm" data-del="${esc(c.id)}">删</button>
    </div>`).join('');

  list.querySelectorAll('.ck-row').forEach(row => {
    row.querySelector('.ck-radio').onclick = () => {
      call('setActiveCookie', row.dataset.id);
      S.settings.activeCookieId = row.dataset.id;
      renderCookies(); updateStatus();
      toast('已切换账号', 'ok', 1400);
    };
  });
  list.querySelectorAll('[data-test]').forEach(b => b.onclick = () => {
    const meta = list.querySelector(`[data-meta="${b.dataset.test}"]`);
    meta.className = 'ck-meta'; meta.textContent = '测试中…';
    S.testTarget = b.dataset.test;
    call('testCookie', '');
  });
  list.querySelectorAll('[data-del]').forEach(b => b.onclick = () => {
    call('removeCookie', b.dataset.del);
    S.settings = call('getSettings'); syncUI(); updateStatus();
    toast('已删除', 'ok', 1500);
  });
}

function onCookieTest(evt) {
  const t = evt.result || {};
  // 「添加账号」里的「先测一下」→ 结果显示在表单里
  if (S.testTarget === '__new__') {
    S.testTarget = null;
    $('#testResult').innerHTML = `<div class="test-out ${t.ok ? 'ok' : 'err'}">
      <b>${t.ok ? '✓ 这个账号可以用' : '✗ 这个账号不能用'}</b><br>
      ${esc(t.nickname || '—')}　${esc(t.vipText || '')}<br>
      ${esc(t.reason || '')}${t.hint ? '<br><span class="dim">' + esc(t.hint) + '</span>' : ''}
    </div>`;
    return;
  }
  const meta = document.querySelector(`[data-meta="${S.testTarget}"]`);
  if (meta) {
    meta.className = 'ck-meta ' + (t.ok ? 'ok' : 'err');
    meta.textContent = `${t.nickname || ''} ${t.vipText || ''} · ${t.reason || ''}`;
  }
  S.testTarget = null;
  if (!t.ok) toast(t.reason + (t.hint ? '：' + t.hint : ''), 'err', 5000);
  else toast(t.reason || '账号可用', 'ok', 3000);
}

function saveSettings() {
  const patch = {
    dirName: $('#setDirName').value.trim() || '音乐下载',
    format: getSeg('#setFormat'),
    sourceOrder: $('#setSource').value,
    coverMax: parseInt($('#setCoverMax').value || '1200', 10) || 0,
    embedCover: $('#setCover').checked,
    embedLyrics: $('#setLyrics').checked,
    writeLrc: $('#setLrc').checked,
  };
  const r = call('saveSettings', JSON.stringify(patch));
  if (!r.ok) { toast(r.error?.message || '保存失败', 'err'); return; }
  S.settings = call('getSettings');
  syncUI(); updateStatus();
  toast('设置已保存', 'ok', 1600);
}

/* ============================================================
   事件绑定
   ============================================================ */
function bindEvents() {
  $$('.tab').forEach(t => t.onclick = () => switchTab(t.dataset.tab));

  $('#btnSearch').onclick = doSearch;
  $('#q').addEventListener('keydown', e => { if (e.key === 'Enter') doSearch(); });
  $('#q').addEventListener('input', () => { $('#btnClear').hidden = !$('#q').value; });
  $('#btnClear').onclick = () => {
    $('#q').value = ''; $('#btnClear').hidden = true; $('#q').focus();
  };

  $('#segFormat').addEventListener('click', e => {
    const b = e.target.closest('button'); if (!b) return;
    setSeg('#segFormat', b.dataset.v);
    setSeg('#setFormat', b.dataset.v);
    S.settings.format = b.dataset.v;
    call('saveSettings', JSON.stringify({ format: b.dataset.v }));
  });
  $('#setFormat').addEventListener('click', e => {
    const b = e.target.closest('button'); if (!b) return;
    setSeg('#setFormat', b.dataset.v); setSeg('#segFormat', b.dataset.v);
  });

  $('#sourceChip').onclick = () => switchTab('settings');
  $('#btnTarget').onclick = () => switchTab('settings');

  // 文件页
  $('#btnRefresh').onclick = refreshFiles;
  $('#btnRescan').onclick = () => call('rescan');
  $('#btnPickDir').onclick = $('#btnPickDir2').onclick = () => call('pickFolder');
  $('#btnScanElse').onclick = scanElsewhere;
  $('#btnSelAll').onclick = () => {
    const all = S.selected.size < S.mine.length;
    clearSelection();
    if (all) S.mine.forEach((_, i) => S.selected.add('mine:' + i));
    $$('#mineList .fitem').forEach((e, i) => e.classList.toggle('sel', all));
    updateSelBar();
  };
  $('#btnMove').onclick = () => {
    const items = selectedItems();
    if (!items.length) return;
    toast('正在移动…', '', 1500);
    call('moveFiles', JSON.stringify(items));
  };
  $('#btnDelete').onclick = () => {
    const items = selectedItems();
    if (!items.length) return;
    call('deleteFiles', JSON.stringify(items));
  };

  // 设置页
  $('#btnDefaultDir').onclick = () => {
    call('usePublicDir');
    S.settings = call('getSettings'); syncUI(); updateStatus();
    toast('已改为默认 Music 目录', 'ok');
  };
  ['#setDirName', '#setCoverMax'].forEach(s =>
    $(s).addEventListener('change', saveSettings));
  $('#setSource').onchange = saveSettings;
  ['#setCover', '#setLyrics', '#setLrc'].forEach(s =>
    $(s).addEventListener('change', saveSettings));

  $('#btnAddCookie').onclick = () => {
    const name = $('#ckName').value.trim() || '未命名账号';
    const value = $('#ckValue').value.trim();
    if (!value) { toast('请填入 MUSIC_U 的值', 'err'); return; }
    const r = call('addCookie', name, value);
    if (!r.ok) { toast(r.error?.message || '保存失败', 'err'); return; }
    $('#ckName').value = ''; $('#ckValue').value = '';
    $('#addCookieBox').open = false;
    S.settings = call('getSettings'); syncUI(); updateStatus();
    toast('账号已保存，正在测试…', 'ok', 2200);
    S.testTarget = r.id;
    call('testCookie', value);
  };
  $('#btnTestNew').onclick = () => {
    const v = $('#ckValue').value.trim();
    if (!v) { toast('先填入 Cookie 内容', 'err'); return; }
    $('#testResult').innerHTML = `<div class="test-out">正在测试…</div>`;
    S.testTarget = '__new__';
    call('testCookie', v);
  };

  $('#btnExport').onclick = () => {
    const r = call('exportConfig');
    if (r.ok) toast('已导出到：' + r.path, 'ok', 5000);
    else toast(r.error?.message || '导出失败', 'err');
  };
  $('#btnImport').onclick = () => call('pickConfigFile');
  $('#btnReset').onclick = () => {
    call('resetSettings');
    S.settings = call('getSettings'); syncUI(); updateStatus();
    toast('已恢复默认设置', 'ok');
  };
}

/* ---------------- 启动 ---------------- */
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
