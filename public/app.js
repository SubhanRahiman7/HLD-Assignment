// Frontend logic for Search Typeahead
// - Debounced GET /suggest
// - Renders dropdown with prefix highlighting and active-row keyboard nav
// - Submits via POST /search
// - Trending/Recent chips
// - Cache hit/miss telemetry

const $ = (id) => document.getElementById(id);
const input = $('q');
const submitBtn = $('submit');
const dropdown = $('dropdown');
const telemetry = $('telemetry');
const successEl = $('success');
const errorEl = $('error');
const trendingList = $('trending-list');
const recentWrap = $('recent-wrap');
const recentList = $('recent-list');
const rankNote = $('rank-note');
const modePop = $('mode-pop');
const modeRec = $('mode-rec');
const clearRecentBtn = $('clear-recent');

const fmt = (n) => {
 if (n >= 1_000_000) return (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
 if (n >= 1_000) return Math.round(n / 1_000) + 'K';
 return String(n);
};

const state = {
 query: '',
 activeIndex: -1,
 focused: false,
 loading: false,
 suggestions: [],
 mode: 'popularity',
 submitting: false,
};

let debounceTimer = null;
let lastReqToken = 0;
let lastLatTimer = null;

const escapeHtml = (s) => s.replace(/[&<>"']/g, (c) => ({
 '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[c]));

function setMode(m) {
 state.mode = m;
 modePop.classList.toggle('active', m === 'popularity');
 modeRec.classList.toggle('active', m === 'recency');
 rankNote.textContent = m === 'recency' ? 'recency-weighted score' : 'by all-time search count';
 if (state.query.trim()) fetchSuggest();
 fetchTrending();
}

function renderDropdown() {
 const open = state.focused && state.query.trim().length > 0;
 if (!open) { dropdown.hidden = true; dropdown.innerHTML = ''; return; }
 dropdown.hidden = false;
 if (state.loading) {
 dropdown.innerHTML = `
 <div class="skel-row" style="animation-delay:0s"><span class="skel-text" style="width:46%"></span><span class="skel-num" style="width:34px"></span></div>
 <div class="skel-row" style="animation-delay:.15s"><span class="skel-text" style="width:62%"></span><span class="skel-num" style="width:30px"></span></div>
 <div class="skel-row" style="animation-delay:.3s"><span class="skel-text" style="width:38%"></span><span class="skel-num" style="width:38px"></span></div>
 `;
 return;
 }
 if (state.suggestions.length === 0) {
 dropdown.innerHTML = `<div class="empty">No suggestions for "${escapeHtml(state.query.trim())}"</div>`;
 return;
 }
 const typed = state.query.trim();
 const typedLen = typed.length;
 dropdown.innerHTML = state.suggestions.map((s, i) => {
 const q = s.q;
 const pre = escapeHtml(q.slice(0, typedLen));
 const post = escapeHtml(q.slice(typedLen));
 const active = i === state.activeIndex;
 return `<button class="sugg ${active ? 'active' : ''}" data-i="${i}" type="button">
 <span class="q"><span class="pre">${pre}</span><span class="post">${post}</span></span>
 <span class="count">${fmt(s.c)}</span>
 </button>`;
 }).join('');
 dropdown.querySelectorAll('.sugg').forEach((el) => {
 el.addEventListener('mousedown', (e) => {
 e.preventDefault();
 const i = Number(el.dataset.i);
 pick(state.suggestions[i].q);
 });
 el.addEventListener('mouseenter', () => {
 state.activeIndex = Number(el.dataset.i);
 dropdown.querySelectorAll('.sugg').forEach((b, j) => b.classList.toggle('active', j === state.activeIndex));
 });
 });
}

function renderTelemetry(t) {
 if (!t) { telemetry.hidden = true; telemetry.innerHTML = ''; return; }
 telemetry.hidden = false;
 const stateClass = t.hit ? 'state-hit' : 'state-miss';
 telemetry.innerHTML = `
 <span class="prefix">GET&nbsp;/suggest?q=${escapeHtml(t.prefix)}</span>
 <span class="${stateClass}">${t.hit ? 'HIT' : 'MISS'}</span>
 <span>${t.ms}ms</span>
 <span>node=${t.node}</span>
 `;
}

async function fetchSuggest() {
 const prefix = state.query.trim();
  if (!prefix) {
 state.loading = false;
 state.suggestions = [];
 renderDropdown();
 return;
 }
 state.loading = true;
 renderDropdown();
 const token = ++lastReqToken;
 try {
 const url = `/suggest?q=${encodeURIComponent(prefix)}&mode=${state.mode}`;
 const res = await fetch(url);
 const data = await res.json();
 if (token !== lastReqToken) return;
 state.loading = false;
 state.suggestions = data.suggestions || [];
 state.activeIndex = -1;
 renderDropdown();
 renderTelemetry({
 prefix,
 hit: !!data.hit,
 ms: data.ms,
 node: data.node,
 });
 } catch (err) {
 if (token !== lastReqToken) return;
 state.loading = false;
 state.suggestions = [];
 renderDropdown();
 }
}

function debouncedSuggest() {
 clearTimeout(debounceTimer);
 debounceTimer = setTimeout(fetchSuggest, 220);
}

function pick(q) {
 state.query = q;
 input.value = q;
 state.focused = false;
 state.activeIndex = -1;
 renderDropdown();
 submit(q);
}

async function submit(qRaw) {
 const q = (qRaw || '').trim();
 if (!q) return;
 state.submitting = true;
 submitBtn.disabled = true;
 submitBtn.querySelector('.submit-label').hidden = true;
 submitBtn.querySelector('.submit-spinner').hidden = false;
 state.focused = false;
 state.activeIndex = -1;
 renderDropdown();
 try {
 const res = await fetch('/search', {
 method: 'POST',
 headers: { 'Content-Type': 'application/json' },
 body: JSON.stringify({ q }),
 });
 if (res.status === 503) {
 const data = await res.json().catch(() => ({}));
 showError(data.message || 'Upstream search service did not respond within the latency budget. Please retry.');
 return;
 }
 const data = await res.json();
 showSuccess(data);
 await Promise.all([fetchTrending(), fetchRecent()]);
 } catch (err) {
 showError('Network error.');
 } finally {
 state.submitting = false;
 submitBtn.disabled = false;
 submitBtn.querySelector('.submit-label').hidden = false;
 submitBtn.querySelector('.submit-spinner').hidden = true;
 }
}

function showSuccess(data) {
 errorEl.hidden = true;
 successEl.hidden = false;
 successEl.innerHTML = `
 <div class="result-meta">POST /search · <span class="ok">200 OK</span> · ${data.ms}ms</div>
 <div class="result-body">{ "message": "Searched" }</div>
 <div class="result-note">Recorded <strong>"${escapeHtml(data.query)}"</strong> · query count → <code>${data.count}</code> · cache invalidated</div>
 `;
}

function showError(msg) {
 successEl.hidden = true;
 errorEl.hidden = false;
 errorEl.innerHTML = `
 <div class="result-meta err">POST /search · 503 SERVICE UNAVAILABLE</div>
 <div class="result-note" style="margin-top:8px">${escapeHtml(msg)}</div>
 `;
}

async function fetchTrending() {
 try {
 const res = await fetch(`/trending?mode=${state.mode}`);
 const data = await res.json();
 trendingList.innerHTML = (data.trending || []).map((d, i) => `
 <button class="chip" data-q="${escapeHtml(d.q)}" type="button">
 <span class="rank">${String(i + 1).padStart(2, '0')}</span>
 <span class="q">${escapeHtml(d.q)}</span>
 <span class="count">${fmt(d.c)}</span>
 </button>
 `).join('');
 trendingList.querySelectorAll('.chip').forEach((c) => {
 c.addEventListener('click', () => pick(c.dataset.q));
 });
 } catch (e) { /* ignore */ }
}

async function fetchRecent() {
 try {
 const res = await fetch('/recent');
 const data = await res.json();
 const list = data.recent || [];
 if (list.length === 0) {
 recentWrap.hidden = true;
 return;
 }
 recentWrap.hidden = false;
 recentList.innerHTML = list.map((q) => `
 <button class="chip" data-q="${escapeHtml(q)}" type="button">
 <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#A8A293" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"></circle><polyline points="12 7 12 12 15.5 14"></polyline></svg>
 <span class="q">${escapeHtml(q)}</span>
 </button>
 `).join('');
 recentList.querySelectorAll('.chip').forEach((c) => {
 c.addEventListener('click', () => pick(c.dataset.q));
 });
 } catch (e) { /* ignore */ }
}

async function clearRecent() {
 await fetch('/recent/clear', { method: 'POST' });
 fetchRecent();
}

input.addEventListener('input', (e) => {
 state.query = e.target.value;
 state.activeIndex = -1;
 state.focused = true;
 renderDropdown();
 debouncedSuggest();
});

input.addEventListener('focus', () => { state.focused = true; renderDropdown(); });
input.addEventListener('blur', () => { state.focused = false; setTimeout(renderDropdown, 120); });

input.addEventListener('keydown', (e) => {
 const open = state.focused && state.query.trim().length > 0 && !state.loading;
 if (e.key === 'ArrowDown') {
 e.preventDefault();
 if (open && state.suggestions.length) {
 state.activeIndex = Math.min(state.activeIndex + 1, state.suggestions.length - 1);
 renderDropdown();
 }
 } else if (e.key === 'ArrowUp') {
 e.preventDefault();
 if (open && state.suggestions.length) {
 state.activeIndex = Math.max(state.activeIndex - 1, 0);
 renderDropdown();
 }
 } else if (e.key === 'Enter') {
 e.preventDefault();
 if (open && state.activeIndex >= 0 && state.suggestions[state.activeIndex]) {
 pick(state.suggestions[state.activeIndex].q);
 } else {
 submit(state.query);
 }
 } else if (e.key === 'Escape') {
 state.focused = false;
 state.activeIndex = -1;
 renderDropdown();
 }
});

submitBtn.addEventListener('click', () => submit(state.query));
modePop.addEventListener('click', () => setMode('popularity'));
modeRec.addEventListener('click', () => setMode('recency'));
clearRecentBtn.addEventListener('click', clearRecent);

fetchTrending();
fetchRecent();
