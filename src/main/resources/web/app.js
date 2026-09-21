'use strict';

const state = { experiments: [], current: null, simulation: null };

const $ = (id) => document.getElementById(id);

async function api(method, path, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
  return data;
}

function esc(s) {
  return String(s ?? '').replace(/[&<>]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
}

async function loadExperiments(selectId) {
  state.experiments = await api('GET', '/api/experiments');
  const sel = $('experiments');
  sel.innerHTML = '';
  if (state.experiments.length === 0) {
    const exp = await api('POST', '/api/experiments', { name: '实验 1' });
    state.experiments.push(exp);
  }
  for (const e of state.experiments) {
    const o = document.createElement('option');
    o.value = e.id; o.textContent = e.name + ' (rev ' + e.revision + ')';
    sel.appendChild(o);
  }
  if (state.current && state.experiments.find((e) => e.id === state.current.id)) {
    sel.value = state.current.id;
  } else {
    sel.value = state.experiments[0].id;
  }
  await selectExperiment(sel.value);
}

async function selectExperiment(id) {
  state.current = await api('GET', '/api/experiments/' + id);
  renderScript();
  syncPlanForm();
  refreshMediaOnly();
}

function opBadge(op) {
  if (op.kind === 'CHECKPOINT') return '<span class="tag ckpt">CKPT</span>';
  return '<span class="tag txn">' + esc(op.txn) + '</span>';
}

function opText(op) {
  switch (op.kind) {
    case 'BEGIN': return 'begin';
    case 'PUT': return 'put ' + esc(op.key) + ' = ' + esc(op.value);
    case 'DELETE': return 'delete ' + esc(op.key);
    case 'COMMIT': return 'commit';
    case 'CHECKPOINT': return 'checkpoint';
    default: return op.kind;
  }
}

function renderScript() {
  const exp = state.current;
  const ol = $('script');
  ol.innerHTML = '';
  exp.script.forEach((op, i) => {
    const li = document.createElement('li');
    li.innerHTML = '<span class="idx">' + i + '</span>' + opBadge(op) +
      '<span>' + opText(op) + '</span>' +
      '<button data-i="' + i + '">删</button>';
    li.querySelector('button').onclick = async () => {
      exp.script.splice(i, 1);
      await saveScript();
    };
    ol.appendChild(li);
  });
  $('revInfo').textContent = '共 ' + exp.script.length +
    ' 个事件 · revision=' + exp.revision +
    (exp.materialisedRevision === exp.revision ? ' · 已落盘' : ' · 有未执行修改');

  const es = $('eventSelect');
  const ft = $('faultTarget');
  const ev = es.value, fv = ft.value;
  es.innerHTML = ''; ft.innerHTML = '';
  exp.script.forEach((op, i) => {
    for (const sel of [es, ft]) {
      const o = document.createElement('option');
      o.value = i; o.textContent = i + ': ' + op;
      sel.appendChild(o);
    }
  });
  es.value = ev; ft.value = fv;
}

function readPlan() {
  const p = state.current.plan || {};
  return {
    crash: $('crash').checked,
    anchor: $('anchor').value,
    eventIndex: parseInt($('eventSelect').value || '-1', 10),
    value: parseInt($('numValue').value || '0', 10),
    stage: $('stage').value,
    fault: $('fault').value,
    faultTarget: parseInt($('faultTarget').value || '-1', 10),
    faultParam: parseInt($('faultParam').value || '0', 10)
  };
}

function syncPlanForm() {
  const p = state.current.plan || {};
  $('crash').checked = !!p.crash;
  $('anchor').value = p.anchor || 'NONE';
  if (p.eventIndex >= 0) $('eventSelect').value = p.eventIndex;
  $('numValue').value = p.value ?? 0;
  $('stage').value = p.stage || 'TEMP_SYNCED';
  $('fault').value = p.fault || 'NONE';
  if (p.faultTarget >= 0) $('faultTarget').value = p.faultTarget;
  $('faultParam').value = p.faultParam ?? 24;
}

async function saveScript() {
  state.current = await api('PUT', '/api/experiments/' + state.current.id + '/script', {
    name: state.current.name,
    script: state.current.script,
    plan: readPlan()
  });
  renderScript();
}

async function appendOp() {
  const kind = $('op').value;
  const txn = $('txn').value.trim() || 't1';
  const op = { kind, txn };
  if (kind === 'PUT') { op.key = $('key').value; op.value = $('value').value; }
  if (kind === 'DELETE') { op.key = $('key').value; }
  state.current = await api('POST',
    '/api/experiments/' + state.current.id + '/append', op);
  renderScript();
}

function frameLine(rec) {
  if (rec.outcome !== 'VALID') {
    return '<span class="r">偏移 ' + rec.offset + ' → ' + rec.outcome +
      (rec.declaredPayloadLength !== undefined
        ? '（长度字段=' + rec.declaredPayloadLength + '）' : '') + '</span>';
  }
  let detail = '';
  if (rec.type === 'PUT' || rec.type === 'DELETE') {
    // payload decoded server-side only in recovery; here show type/seq.
  }
  return '<span class="g">偏移 ' + rec.offset + ' ✓ ' + rec.type +
    '</span> txn=' + rec.txnId + ' seq=' + rec.seqNo + ' 共 ' + rec.size + ' 字节' + detail;
}

function renderMedia(media) {
  const box = $('frames');
  box.innerHTML = '';
  const names = Object.keys(media).sort();
  if (names.length === 0) {
    box.innerHTML = '<div class="muted">尚未落盘。点击“模拟并落盘”。</div>';
    return;
  }
  for (const name of names) {
    const f = media[name];
    const d = document.createElement('details');
    d.open = true;
    const summary = name.endsWith('.seg')
      ? '日志段 '
      : name.endsWith('.tmp') ? '临时快照 ' : '快照 ';
    let scanHtml = '';
    if (f.scan && f.scan.length) {
      scanHtml = f.scan.map((r) => '· ' + frameLine(r)).join('<br>');
    }
    d.innerHTML = '<summary>' + summary + esc(name) +
      ' <span class="pill">' + f.size + ' B</span></summary>' +
      (scanHtml ? '<div class="muted" style="margin:4px 0">' + scanHtml + '</div>' : '') +
      '<pre>' + esc(f.hex) + '</pre>';
    box.appendChild(d);
  }
}

async function refreshMediaOnly() {
  try {
    const media = await api('GET', '/api/experiments/' + state.current.id + '/media');
    renderMedia(media);
  } catch (e) {
    $('frames').textContent = e.message;
  }
}

function kvTable(kv, highlight) {
  const entries = Object.entries(kv || {});
  if (entries.length === 0) return '<span class="muted">∅ 空</span>';
  let html = '<table><tr><th>键</th><th>值</th></tr>';
  for (const [k, v] of entries) {
    const cls = highlight && !Object.prototype.hasOwnProperty.call(highlight, k)
      ? ' class="err"' : '';
    html += '<tr' + cls + '><td>' + esc(k) + '</td><td>' + esc(v) + '</td></tr>';
  }
  return html + '</table>';
}

function renderRecovery(view) {
  const r = view.report;
  const diag = []
    .concat(r.diagnostics, r.segmentDecisions, r.txnDecisions);
  $('diag').innerHTML = esc(diag.join('\n')).replace(/\n/g, '<br>');
  $('recoveredKv').innerHTML = kvTable(r.kv, view.expectedKv);
  $('expectedKv').innerHTML = kvTable(view.expectedKv, r.kv);
  const match = view.matchesExpected
    ? '<span class="ok">✓ 恢复状态与预期一致</span>'
    : '<span class="err">✗ 与预期不同：掉电导致部分已提交事务丢失（红色行为多出/缺失项）</span>';
  $('matchInfo').innerHTML = match +
    ' · 重做事务 ' + r.redoneTransactions + '，丢弃 ' + r.droppedTransactions;
}

async function runSimulation() {
  await savePlanOnly();
  try {
    const view = await api('POST',
      '/api/experiments/' + state.current.id + '/materialise');
    state.simulation = view.simulation;
    renderMedia(view.media);
    $('trace').innerHTML = colorTrace(view.simulation.trace);
    const s = view.simulation;
    $('crashInfo').innerHTML = s.crashed
      ? '<span class="warn">⚡ ' + esc(s.crashReason) + '</span>'
      : '<span class="ok">无掉电，全部记录已 fsync。</span>';
  } catch (e) {
    alert(e.message);
  }
}

async function savePlanOnly() {
  state.current = await api('PUT', '/api/experiments/' + state.current.id + '/script', {
    name: state.current.name,
    script: state.current.script,
    plan: readPlan()
  });
}

function colorTrace(lines) {
  return esc((lines || []).join('\n'))
    .replace(/短写|垃圾|位翻转|未知类型|掉电|中断|截断/g, '<span class="r">$1</span>')
    .replace(/阶段[0-9]\/5|CHECKPOINT|快照/g, '<span class="y">$1</span>')
    .replace(/校验通过|重做|完整/g, '<span class="g">$1</span>')
    .replace(/\n/g, '<br>');
}

async function recover() {
  try {
    const view = await api('POST',
      '/api/experiments/' + state.current.id + '/recover');
    renderRecovery(view);
  } catch (e) {
    alert(e.message);
  }
}

async function doExport() {
  const url = '/api/experiments/' + state.current.id + '/export';
  const a = $('exportLink');
  a.href = url; a.download = state.current.id + '.zip';
  a.click();
}

function wire() {
  $('newExp').onclick = async () => {
    const exp = await api('POST', '/api/experiments', { name: '新实验' });
    await loadExperiments();
    $('experiments').value = exp.id;
    await selectExperiment(exp.id);
  };
  $('experiments').onchange = (e) => selectExperiment(e.target.value);
  $('deleteExp').onclick = async () => {
    if (!confirm('删除该实验及其原始字节？')) return;
    await api('DELETE', '/api/experiments/' + state.current.id);
    state.current = null;
    await loadExperiments();
  };
  $('appendOne').onclick = appendOp;
  $('appendAll').onclick = runSimulation;
  $('run').onclick = runSimulation;
  $('recover').onclick = recover;
  $('exportBtn').onclick = doExport;
  $('importBtn').onclick = () => $('importFile').click();
  $('importFile').onchange = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    const res = await fetch('/api/import', { method: 'POST', body: fd });
    if (!res.ok) { alert('导入失败: ' + res.status); return; }
    const exp = await res.json();
    await loadExperiments();
    $('experiments').value = exp.id;
    await selectExperiment(exp.id);
    e.target.value = '';
  };
  $('op').onchange = () => {
    const k = $('op').value;
    $('keyRow').style.display = (k === 'PUT' || k === 'DELETE') ? 'flex' : 'none';
    $('valRow').style.display = (k === 'PUT') ? 'flex' : 'none';
  };
}

wire();
loadExperiments();
