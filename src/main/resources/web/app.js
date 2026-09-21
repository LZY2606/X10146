let experiments = [];
let currentId = null;
let current = null;
let recovery = null;
let materialization = null;

async function api(path, method = 'GET', body) {
  const opts = { method, headers: {} };
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) {
    throw new Error(data.error || (method + ' ' + path + ' -> ' + res.status));
  }
  return data;
}

function flash(msg, isErr) {
  const el = document.getElementById('flash');
  el.textContent = msg;
  el.style.borderColor = isErr ? 'var(--red)' : 'var(--accent)';
  el.style.display = 'block';
  clearTimeout(flash._t);
  flash._t = setTimeout(() => { el.style.display = 'none'; }, 3200);
}

async function loadExperiments() {
  const data = await api('/api/experiments');
  experiments = data.experiments || [];
  const list = document.getElementById('expList');
  list.innerHTML = '';
  for (const e of experiments) {
    const div = document.createElement('div');
    div.className = 'exp-item' + (e.id === currentId ? ' active' : '');
    div.innerHTML = '<span class="name">' + escapeHtml(e.name) + '</span>'
      + '<span class="hint">' + e.events.length + ' 条</span>';
    div.onclick = () => { currentId = e.id; loadExperiment(); };
    list.appendChild(div);
  }
  if (!currentId && experiments.length) {
    currentId = experiments[0].id;
  }
}

async function createExp() {
  const name = document.getElementById('newExpName').value || '未命名实验';
  const e = await api('/api/experiments', 'POST', { name });
  currentId = e.id;
  document.getElementById('newExpName').value = '';
  await loadExperiments();
  await loadExperiment();
  flash('已创建实验 ' + e.id);
}

async function loadExperiment() {
  if (!currentId) {
    current = null;
    render();
    return;
  }
  current = await api('/api/experiments/' + currentId);
  materialization = current.materialized || null;
  recovery = current.recovered ? true : null;
  if (recovery) {
    try {
      const files = await api('/api/experiments/' + currentId + '/files');
      window._files = files.files;
    } catch (_) { /* ignore */ }
  }
  await loadRecoveryIfAny();
  await loadFiles();
  render();
}

async function loadRecoveryIfAny() {
  if (!current || !current.recovered) {
    recoveryData = null;
    return;
  }
  try {
    const res = await fetch('/api/experiments/' + currentId + '/recover', { method: 'POST' });
    recoveryData = res.ok ? await res.json() : null;
  } catch (_) {
    recoveryData = null;
  }
}
let recoveryData = null;

function onTypeChange() {
  const t = document.getElementById('recType').value;
  document.getElementById('keyWrap').style.display =
    (t === 'PUT' || t === 'DELETE') ? '' : 'none';
  document.getElementById('valWrap').style.display = (t === 'PUT') ? '' : 'none';
}

function faultUi() {
  const k = document.getElementById('faultKind').value;
  document.getElementById('fBytesWrap').style.display =
    (k === 'SHORT_WRITE' || k === 'GARBAGE') ? '' : 'none';
  document.getElementById('fOffWrap').style.display = (k === 'BIT_FLIP') ? '' : 'none';
  document.getElementById('fBitWrap').style.display = (k === 'BIT_FLIP') ? '' : 'none';
  document.getElementById('fPhaseWrap').style.display =
    (k === 'CP_INTERRUPT') ? '' : 'none';
}

function crashUi() {
  const m = document.getElementById('crashMode').value;
  document.getElementById('crashEventWrap').style.display =
    (m === 'AFTER_EVENT') ? '' : 'none';
  document.getElementById('crashByteWrap').style.display =
    (m === 'AT_BYTE') ? '' : 'none';
}

async function addEvent() {
  if (!currentId) { flash('请先新建实验', true); return; }
  const type = document.getElementById('recType').value;
  const key = document.getElementById('recKey').value;
  const value = document.getElementById('recValue').value;
  try {
    await api('/api/experiments/' + currentId + '/events', 'POST',
      { type, key: key || null, value: value || null });
    document.getElementById('recKey').value = '';
    document.getElementById('recValue').value = '';
    await loadExperiment();
  } catch (e) { flash(e.message, true); }
}

function faultBody() {
  const kind = document.getElementById('faultKind').value;
  return {
    kind,
    bytes: parseInt(document.getElementById('faultBytes').value || '0', 10),
    byteOffset: parseInt(document.getElementById('faultOffset').value || '0', 10),
    bitIndex: parseInt(document.getElementById('faultBit').value || '0', 10),
    cpPhase: document.getElementById('faultPhase').value
  };
}

async function applyFault() {
  const eventId = parseInt(document.getElementById('faultEvent').value || '0', 10);
  if (!eventId) { flash('请选择目标事件', true); return; }
  try {
    await api('/api/experiments/' + currentId + '/faults', 'POST',
      { eventId, fault: faultBody() });
    await loadExperiment();
    flash('故障已挂到事件 #' + eventId);
  } catch (e) { flash(e.message, true); }
}

async function sendCrashConfig() {
  const mode = document.getElementById('crashMode').value;
  const body = { mode };
  if (mode === 'AFTER_EVENT') {
    body.eventId = parseInt(document.getElementById('crashEvent').value || '0', 10);
  } else if (mode === 'AT_BYTE') {
    body.value = parseInt(document.getElementById('crashByte').value || '0', 10);
  }
  await api('/api/experiments/' + currentId + '/crash', 'POST', body);
}

async function materialize() {
  try {
    await sendCrashConfig();
    const mat = await api('/api/experiments/' + currentId + '/materialize', 'POST');
    materialization = mat;
    await loadExperiment();
    flash(mat.crashed ? '已模拟掉电：' + mat.crashReason : '磁盘已按完整日志重建');
  } catch (e) { flash(e.message, true); }
}

async function recover() {
  try {
    recoveryData = await api('/api/experiments/' + currentId + '/recover', 'POST');
    await loadFiles();
    renderRecovery();
    renderKv();
    flash('恢复完成：' + recoveryData.status);
  } catch (e) { flash(e.message, true); }
}

async function resetPower() {
  try {
    materialization = await api('/api/experiments/' + currentId + '/reset', 'POST');
    recoveryData = null;
    await loadExperiment();
    flash('故障与崩溃点已清空，磁盘为完整日志');
  } catch (e) { flash(e.message, true); }
}

async function exportExp() {
  if (!currentId) { flash('请先选择实验', true); return; }
  window.location.href = '/api/experiments/' + currentId + '/export';
}

async function importExp() {
  const file = document.getElementById('importFile').files[0];
  if (!file) { flash('请选择 ZIP', true); return; }
  const res = await fetch('/api/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/zip' },
    body: file
  });
  if (!res.ok) { flash('导入失败', true); return; }
  const data = await res.json();
  currentId = data.id;
  await loadExperiments();
  await loadExperiment();
  flash('已导入 ' + data.id + '，可直接点恢复验证轨迹一致');
}

async function loadFiles() {
  window._files = [];
  if (!currentId) { renderFiles(); return; }
  try {
    const data = await api('/api/experiments/' + currentId + '/files');
    window._files = data.files || [];
  } catch (_) { window._files = []; }
  renderFiles();
}

async function showHex(fileName) {
  const data = await api('/api/experiments/' + currentId + '/files/'
    + encodeURIComponent(fileName) + '/hex');
  document.getElementById('hexArea').innerHTML =
    '<label style="color:var(--text)">' + escapeHtml(data.fileName) + '（'
    + data.size + ' 字节）</label><pre class="dump">' + escapeHtml(data.hex) + '</pre>';
}

function render() {
  onTypeChange();
  faultUi();
  crashUi();
  renderEvents();
  renderSelectors();
  renderCrashConfig();
  renderMaterialization();
  renderFiles();
  renderKv();
  renderRecovery();
  document.getElementById('txnHint').textContent = current && current.transactionOpen
    ? '进行中事务 txn=' + current.activeTxnId + '（BEGIN 已追加，等待 PUT/DELETE/COMMIT）'
    : '当前没有进行中的事务：追加 BEGIN 开始一笔键值事务。';
}

function renderCrashConfig() {
  if (!current) return;
  const c = current.crash || { mode: 'NONE', value: 0 };
  document.getElementById('crashMode').value = c.mode;
  document.getElementById('crashByte').value = c.value;
  crashUi();
}

function renderSelectors() {
  const opts = ['<option value="0">— 选择事件 —</option>'];
  const opts2 = ['<option value="0">— 选择事件 —</option>'];
  if (current) {
    for (const e of current.events) {
      opts.push('<option value="' + e.id + '">#' + e.id + ' ' + e.description
        + '</option>');
      opts2.push('<option value="' + e.id + '">#' + e.id + ' ' + e.type + '</option>');
    }
  }
  document.getElementById('faultEvent').innerHTML = opts.join('');
  document.getElementById('crashEvent').innerHTML = opts2.join('');
}

function renderEvents() {
  const tbl = document.getElementById('eventTable');
  if (!current || !current.events.length) {
    tbl.innerHTML = '<tr><td class="hint">还没有记录。追加一条 BEGIN 开始。</td></tr>';
    return;
  }
  let html = '<tr><th>#</th><th>类型</th><th>txn/seq</th><th>键值</th><th>帧十六进制（长度前缀·类型·事务号·序号·CRC）</th></tr>';
  for (const e of current.events) {
    const faultTag = (e.fault && e.fault.kind !== 'NONE')
      ? '<span class="tag fault">' + faultLabel(e.fault) + '</span>' : '';
    html += '<tr><td>' + e.id + '</td>'
      + '<td><span class="tag ' + e.type + '">' + e.type + '</span>' + faultTag + '</td>'
      + '<td class="mono">' + (e.txnId == null ? '-' : e.txnId) + '/'
      + (e.seq == null ? '-' : e.seq) + '</td>'
      + '<td class="mono">' + escapeHtml(kvText(e)) + '</td>'
      + '<td class="hex">' + escapeHtml(e.hex) + '</td></tr>';
  }
  tbl.innerHTML = html;
}

function kvText(e) {
  if (e.type === 'PUT') return e.key + ' = ' + e.value;
  if (e.type === 'DELETE') return e.key;
  return '';
}

function faultLabel(f) {
  switch (f.kind) {
    case 'SHORT_WRITE': return '短写-' + f.bytes + 'B';
    case 'GARBAGE': return '垃圾+' + f.bytes + 'B';
    case 'BIT_FLIP': return '位翻转@' + f.byteOffset + '.' + f.bitIndex;
    case 'CP_INTERRUPT': return 'CP中断:' + (f.cpPhase || 'TEMP_SYNCED');
    default: return '';
  }
}

function renderMaterialization() {
  const box = document.getElementById('matBanner');
  if (!materialization) {
    box.innerHTML = '<div class="hint">磁盘尚未按当前编辑重建。点击“应用追加 / 模拟掉电”。</div>';
    return;
  }
  const m = materialization;
  const cls = m.crashed ? 'warn' : 'ok';
  let writes = '';
  if (m.writes && m.writes.length) {
    writes = '<table><tr><th>事件</th><th>段</th><th>段内偏移</th><th>应写/实写</th></tr>';
    for (const w of m.writes) {
      writes += '<tr><td>#' + (w.eventId < 0 ? '垃圾@' + (-w.eventId) : w.eventId)
        + '</td><td>' + w.segmentId + '</td><td>' + w.fileOffset
        + '</td><td>' + w.fullLength + ' / ' + w.writtenLength
        + (w.faultDescription !== '无故障'
          ? ' <span class="tag fault">' + escapeHtml(w.faultDescription) + '</span>' : '')
        + '</td></tr>';
    }
    writes += '</table>';
  }
  box.innerHTML = '<div class="status-banner ' + cls + '"><b>'
    + (m.crashed ? '⚡ 已模拟掉电' : '⚡ 未掉电') + '</b>：' + escapeHtml(m.crashReason || '')
    + '<br>落盘事件 ' + m.eventsApplied + ' 条，持久化前缀 ' + m.durableBytes
    + ' 字节。</div>' + writes;
}

function renderFiles() {
  const box = document.getElementById('fileList');
  const files = window._files || [];
  if (!files.length) {
    box.innerHTML = '<div class="hint">data 目录为空（尚未重建）。</div>';
    return;
  }
  let html = '<table><tr><th>文件</th><th>大小</th><th></th></tr>';
  for (const f of files) {
    const badge = f.snapshot ? ' <span class="tag CHECKPOINT">快照</span>'
      : f.tmp ? ' <span class="tag fault">临时</span>'
      : f.segment ? ' <span class="tag BEGIN">段</span>' : '';
    html += '<tr><td>' + f.name + badge + '</td><td>' + f.size
      + ' B</td><td><button class="small" onclick="showHex(\''
      + f.name + '\')">hex</button></td></tr>';
  }
  html += '</table>';
  box.innerHTML = html;
}

function renderKv() {
  const rec = document.getElementById('recoveredKv');
  const exp = document.getElementById('expectedKv');
  const recovered = recoveryData ? recoveryData.kv : null;
  const expected = current ? current.expectedKv : {};
  rec.innerHTML = recovered ? kvList(recovered, expected)
    : '<span class="hint">运行恢复后显示</span>';
  exp.innerHTML = kvList(expected, recovered || {});
}

function kvList(kv, other) {
  const keys = Object.keys(kv);
  if (!keys.length) return '<span class="hint">(空)</span>';
  return keys.map(k => {
    const missing = other && !(k in other) && kv[k] !== other[k];
    const diff = other && other[k] !== kv[k] && (k in other);
    const cls = (missing || diff) ? 'miss' : '';
    return '<div class="' + cls + '">' + escapeHtml(k) + ' = '
      + escapeHtml(kv[k]) + '</div>';
  }).join('');
}

function renderRecovery() {
  const box = document.getElementById('recoverySteps');
  if (!recoveryData) {
    box.innerHTML = '<div class="hint">尚未运行恢复。</div>';
    return;
  }
  const r = recoveryData;
  const cls = r.status === 'CLEAN' ? 'ok'
    : (r.status.indexOf('MISSING') >= 0 || r.status.indexOf('BROKEN') >= 0) ? 'err'
    : 'warn';
  let html = '<div class="status-banner ' + cls + '"><b>终止状态：' + r.status
    + '</b><br>' + escapeHtml(r.terminalReason)
    + '<br>快照: ' + (r.snapshotUsed ? ('gen=' + r.snapshotGeneration
      + '，起始段=' + r.startSegmentId) : '无')
    + '；已接受帧 ' + r.framesAccepted + '，拒绝帧 ' + r.framesRejected
    + '；提交事务 [' + (r.committedTxns || []).join(', ')
    + ']，丢弃事务 [' + (r.discardedTxns || []).join(', ') + ']</div>';
  for (const s of r.steps) {
    html += '<div class="step ' + s.kind + '"><div class="t">#' + s.index + ' '
      + escapeHtml(s.title) + '</div><div class="d">' + escapeHtml(s.detail) + '</div>'
      + (s.kvAfter && Object.keys(s.kvAfter).length
        ? '<div class="kvchip">' + escapeHtml(JSON.stringify(s.kvAfter)) + '</div>'
        : '') + '</div>';
  }
  box.innerHTML = html;
}

function escapeHtml(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}

onTypeChange();
faultUi();
crashUi();
loadExperiments().then(loadExperiment).catch(e => flash(e.message, true));
