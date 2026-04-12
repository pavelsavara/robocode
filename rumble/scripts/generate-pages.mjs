#!/usr/bin/env node
// generate-pages.mjs — Generate static HTML pages from rankings JSON.
//
// Usage:
//   node generate-pages.mjs --rankings rankings.json --out site/

import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync, existsSync, copyFileSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const __dirname = dirname(fileURLToPath(import.meta.url));

const { values: args } = parseArgs({
    options: {
        rankings: { type: 'string', default: 'rankings.json' },
        results: { type: 'string', default: 'results' },
        out: { type: 'string', default: 'site' },
    },
});

const outDir = resolve(args.out);
mkdirSync(outDir, { recursive: true });

const rankings = JSON.parse(readFileSync(resolve(args.rankings), 'utf-8'));

// Flags — hardcoded paths relative to this script
const flagsSrc = join(__dirname, '..', 'flags');
let flagMap = {};
const flagsJsonPath = join(flagsSrc, 'flags.json');
if (existsSync(flagsJsonPath)) {
    flagMap = JSON.parse(readFileSync(flagsJsonPath, 'utf-8'));
}

// Copy flag images to output
const flagsOutDir = join(outDir, 'flags');
if (existsSync(flagsSrc)) {
    mkdirSync(flagsOutDir, { recursive: true });
    for (const f of readdirSync(flagsSrc)) {
        if (f.endsWith('.gif') || f.endsWith('.png')) {
            copyFileSync(join(flagsSrc, f), join(flagsOutDir, f));
        }
    }
}
const hasFlags = existsSync(flagsSrc);

function getBotFlag(botName) {
    const pkg = botName.split('.')[0];
    return flagMap[pkg] || 'NONE';
}

function flagImg(botName, relative) {
    if (!hasFlags) return '';
    const code = getBotFlag(botName);
    const prefix = relative || '';
    return `<img class="flag" src="${prefix}flags/${code}.gif" alt="${code}" title="${code}">`;
}

// Load robot catalog for metadata on detail pages
let robotCatalog = [];
const catalogPath = join(__dirname, '..', 'robots', 'index.json');
if (existsSync(catalogPath)) {
    robotCatalog = JSON.parse(readFileSync(catalogPath, 'utf-8'));
}
function getCatalogEntry(botName) {
    return robotCatalog.find(b => b.name === botName || b.fullName === botName);
}

// Load all results for head-to-head detail pages
let allResults = [];
try {
    const p = resolve(args.results);
    function walkResults(dir) {
        for (const entry of readdirSync(dir, { withFileTypes: true })) {
            const full = join(dir, entry.name);
            if (entry.isDirectory()) walkResults(full);
            else if (entry.name.endsWith('.json')) {
                const data = JSON.parse(readFileSync(full, 'utf-8'));
                if (Array.isArray(data)) allResults.push(...data);
                else allResults.push(data);
            }
        }
    }
    if (statSync(p).isDirectory()) {
        walkResults(p);
    } else {
        allResults = JSON.parse(readFileSync(p, 'utf-8'));
    }
} catch { /* no results detail available */ }
allResults = allResults.filter(r => !r.error);

function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function botSlug(name) {
    return name.replace(/[^a-zA-Z0-9._-]/g, '_');
}

// Robocode logo (hosted on GitHub pages or inline)
const LOGO_URL = 'https://robocode.sourceforge.io/images/robocode_logo_tanks.png';

// CSS — styled after robo-code.blogspot.com (dark gray, green accents)
const css = `
:root {
  --bg: #333;
  --surface: #444;
  --surface2: #4a4a4a;
  --border: #555;
  --text: #e0e0e0;
  --text-muted: #aaa;
  --accent: #4a8f3f;
  --accent-light: #6abf5e;
  --link: #8cc63f;
  --link-hover: #afe06a;
  --header-bg: #222;
  --gold: #f5c518;
  --silver: #c0c0c0;
  --bronze: #cd7f32;
  --th-bg: #3a3a3a;
  --row-hover: rgba(74,143,63,0.12);
}
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); line-height: 1.6; }
.container { max-width: 1100px; margin: 0 auto; padding: 0 1rem; }
header { background: var(--header-bg); border-bottom: 3px solid var(--accent); padding: 0; margin-bottom: 1.5rem; }
.header-inner { display: flex; align-items: center; gap: 1rem; padding: 0.6rem 0; }
.header-logo img { height: 48px; width: auto; }
.header-text h1 { font-size: 1.5rem; letter-spacing: 0.02em; }
.header-text h1 a { color: var(--text); text-decoration: none; }
.header-text h1 span { color: var(--accent-light); }
.header-text .tagline { color: var(--accent-light); font-size: 0.75rem; font-style: italic; }
header .meta { color: var(--text-muted); font-size: 0.8rem; margin-left: auto; text-align: right; white-space: nowrap; }
.content-card { background: var(--surface); border-radius: 6px; padding: 1rem; margin-bottom: 1.5rem; border: 1px solid var(--border); }
h2 { font-size: 1.1rem; margin-bottom: 0.75rem; color: var(--text); border-bottom: 2px solid var(--accent); padding-bottom: 0.3rem; }
table { width: 100%; border-collapse: collapse; background: var(--surface); border-radius: 6px; overflow: hidden; }
th { background: var(--th-bg); color: var(--text); padding: 0.5rem 0.7rem; text-align: left; font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.04em; cursor: pointer; user-select: none; white-space: nowrap; border-bottom: 2px solid var(--accent); }
th:hover { background: #484848; }
td { padding: 0.4rem 0.7rem; border-bottom: 1px solid var(--border); font-size: 0.9rem; }
tr:hover td { background: var(--row-hover); }
.rank { font-weight: bold; text-align: center; width: 3rem; }
.rank-1 { color: var(--gold); }
.rank-2 { color: var(--silver); }
.rank-3 { color: var(--bronze); }
.num { text-align: right; font-variant-numeric: tabular-nums; }
a { color: var(--link); text-decoration: none; }
a:hover { color: var(--link-hover); text-decoration: underline; }
.bar { height: 6px; background: var(--th-bg); border-radius: 3px; position: relative; }
.bar-fill { height: 100%; background: var(--accent); border-radius: 3px; }
.flag { width: 18px; height: 12px; vertical-align: middle; margin-right: 4px; }
.bot-summary { display: flex; gap: 2rem; flex-wrap: wrap; margin-bottom: 1.5rem; }
.bot-stats { flex: 1; min-width: 250px; }
.bot-stats table { width: auto; }
.bot-stats td, .bot-stats th { padding: 0.3rem 0.8rem; }
.bot-meta { margin-bottom: 1rem; }
.bot-meta table { width: auto; }
.bot-meta td, .bot-meta th { padding: 0.25rem 0.6rem; font-size: 0.85rem; }
.bot-meta th { background: none; border-bottom: none; text-transform: none; font-size: 0.85rem; color: var(--text-muted); cursor: default; }
.score-chart { flex: 0 0 240px; }
.score-chart svg { border: 1px solid var(--border); border-radius: 4px; }
.compare-link { font-size: 0.8rem; }
.compare-header { display: flex; gap: 2rem; flex-wrap: wrap; margin-bottom: 1rem; }
.compare-bot { flex: 1; min-width: 200px; }
.compare-bot table { width: 100%; }
footer { text-align: center; color: var(--text-muted); font-size: 0.78rem; margin-top: 2rem; padding: 1rem 0; border-top: 1px solid var(--border); background: var(--header-bg); }
footer a { color: var(--link); }
`;

// Sortable table JS
const sortJs = `
document.querySelectorAll('th[data-sort]').forEach(th => {
  th.addEventListener('click', () => {
    const table = th.closest('table');
    const tbody = table.querySelector('tbody');
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const col = th.cellIndex;
    const type = th.dataset.sort;
    const asc = th.classList.toggle('sort-asc');
    table.querySelectorAll('th').forEach(h => { if (h !== th) h.classList.remove('sort-asc', 'sort-desc'); });
    if (!asc) th.classList.add('sort-desc');
    rows.sort((a, b) => {
      let va = a.cells[col]?.textContent.trim() ?? '';
      let vb = b.cells[col]?.textContent.trim() ?? '';
      if (type === 'num') { va = parseFloat(va) || 0; vb = parseFloat(vb) || 0; }
      const cmp = type === 'num' ? va - vb : va.localeCompare(vb);
      return asc ? cmp : -cmp;
    });
    rows.forEach(r => tbody.appendChild(r));
  });
});
`;

// Generate index page
function generateIndex() {
    const rows = rankings.rankings.map(r => {
        const rankClass = r.rank <= 3 ? ` rank-${r.rank}` : '';
        const flag = flagImg(r.name, '');
        return `<tr>
      <td class="rank${rankClass}">${r.rank}</td>
      <td>${flag}</td>
      <td><a href="bots/${botSlug(r.name)}.html">${escapeHtml(r.name)}</a></td>
      <td class="num">${r.aps.toFixed(2)}</td>
      <td class="num"><div class="bar" style="width:80px"><div class="bar-fill" style="width:${r.aps}%"></div></div></td>
      <td class="num">${(r.pwin ?? 0).toFixed(2)}</td>
      <td class="num">${r.anpp.toFixed(2)}</td>
      <td class="num">${r.vote.toFixed(1)}</td>
      <td class="num">${r.survival.toFixed(1)}</td>
      <td class="num">${r.pairings}</td>
      <td class="num">${r.battles ?? 0}</td>
    </tr>`;
    }).join('\n');

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Robocode Rumble Rankings</title>
  <style>${css}</style>
</head>
<body>
<header><div class="container">
  <div class="header-inner">
    <div class="header-logo"><img src="${LOGO_URL}" alt="Robocode"></div>
    <div class="header-text">
      <h1>Robocode <span>Rumble</span></h1>
      <div class="tagline">Build the best - destroy the rest!</div>
    </div>
    <div class="meta">
      ${rankings.total_bots} bots &middot;
      ${rankings.total_battles} battles &middot;
      ${rankings.total_pairings} pairings<br>
      ${rankings.generated}
    </div>
  </div>
</div></header>
<div class="container">
<div class="content-card">
<table>
  <thead><tr>
    <th data-sort="num">#</th>
    <th>Flag</th>
    <th data-sort="str">Robot</th>
    <th data-sort="num">APS</th>
    <th></th>
    <th data-sort="num">PWIN</th>
    <th data-sort="num">ANPP</th>
    <th data-sort="num">Vote%</th>
    <th data-sort="num">Surv%</th>
    <th data-sort="num">Pairs</th>
    <th data-sort="num">Battles</th>
  </tr></thead>
  <tbody>
${rows}
  </tbody>
</table>
</div></div>
<footer><div class="container">
  <a href="https://github.com/pavelsavara/robocode">pavelsavara/robocode</a> &middot; Powered by GitHub Actions
</div></footer>
<script>${sortJs}</script>
</body>
</html>`;
}

// Build a lookup of bot overall APS for score distribution chart
const botApsLookup = new Map();
for (const r of rankings.rankings) {
    botApsLookup.set(r.name, r);
}

// Generate score distribution SVG for a bot
function generateScoreDistSvg(bot, opponents) {
    const size = 230;
    const pad = 0;
    // Plot opponent's overall APS (X) vs this bot's pairing APS/survival (Y)
    const points = [];
    for (const o of opponents) {
        const oppRanking = botApsLookup.get(o.name);
        const oppAps = oppRanking ? oppRanking.aps : 50;
        points.push({ x: oppAps, yAps: o.aps, ySurv: o.survival_pct ?? 50 });
    }

    let circles = '';
    for (const p of points) {
        const cx = pad + (p.x / 100) * size;
        // APS — red dots
        const cyAps = pad + size - (p.yAps / 100) * size;
        circles += `<circle cx="${cx.toFixed(1)}" cy="${cyAps.toFixed(1)}" r="2.5" fill="rgba(204,37,41,0.8)"/>`;
        // Survival — green dots
        const cySurv = pad + size - (p.ySurv / 100) * size;
        circles += `<circle cx="${cx.toFixed(1)}" cy="${cySurv.toFixed(1)}" r="2.5" fill="rgba(62,150,81,0.8)"/>`;
    }

    // Midline at 50%
    const mid = pad + size * 0.5;
    const gridLines = `<line x1="${pad}" y1="${mid}" x2="${pad + size}" y2="${mid}" stroke="#555" stroke-width="0.5" stroke-dasharray="2,2"/>`;

    return `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" xmlns="http://www.w3.org/2000/svg">
<rect width="${size}" height="${size}" fill="#3a3a3a"/>
${gridLines}
${circles}
</svg>`;
}

// Generate per-bot detail page
function generateBotPage(bot) {
    const botResults = allResults.filter(r =>
        r.bot_a.name === bot.name || r.bot_b.name === bot.name
    );

    // Aggregate results per opponent
    const oppMap = new Map();
    for (const r of botResults) {
        const isA = r.bot_a.name === bot.name;
        const me = isA ? r.bot_a : r.bot_b;
        const opp = isA ? r.bot_b : r.bot_a;
        const rounds = r.rounds || 35;
        if (!oppMap.has(opp.name)) {
            oppMap.set(opp.name, { my_score: 0, opp_score: 0, firsts: 0, rounds: 0, battles: 0 });
        }
        const agg = oppMap.get(opp.name);
        agg.my_score += me.total_score;
        agg.opp_score += opp.total_score;
        agg.firsts += me.firsts;
        agg.rounds += rounds;
        agg.battles++;
    }

    const opponents = [];
    for (const [name, agg] of oppMap) {
        const total = agg.my_score + agg.opp_score;
        const aps = total > 0 ? (100 * agg.my_score / total) : 50;
        const survPct = agg.rounds > 0 ? (100 * agg.firsts / agg.rounds) : 0;
        opponents.push({
            name,
            aps: Math.round(aps * 100) / 100,
            survival_pct: Math.round(survPct * 100) / 100,
            my_score: agg.my_score,
            opp_score: agg.opp_score,
            battles: agg.battles,
            won: aps > 50,
        });
    }
    opponents.sort((a, b) => b.aps - a.aps);

    const flag = flagImg(bot.name, '../');
    const svgChart = generateScoreDistSvg(bot, opponents);

    // Build metadata section from catalog
    const catalog = getCatalogEntry(bot.name);
    let metaHtml = '';
    if (catalog) {
        const rows = [];
        if (catalog.author) rows.push(`<tr><th>Author</th><td>${escapeHtml(catalog.author)}</td></tr>`);
        if (catalog.country) rows.push(`<tr><th>Country</th><td>${flagImg(bot.name, '../')} ${escapeHtml(catalog.country)}</td></tr>`);
        if (catalog.version) rows.push(`<tr><th>Version</th><td>${escapeHtml(catalog.version)}</td></tr>`);
        if (catalog.wikiUrl) rows.push(`<tr><th>Wiki</th><td><a href="${escapeHtml(catalog.wikiUrl)}" target="_blank">RoboWiki page</a></td></tr>`);
        if (catalog.jarUrl) rows.push(`<tr><th>JAR</th><td><a href="${escapeHtml(catalog.jarUrl)}" target="_blank">Download</a></td></tr>`);
        if (catalog.githubRawUrl) rows.push(`<tr><th>Mirror</th><td><a href="${escapeHtml(catalog.githubRawUrl)}" target="_blank">GitHub mirror</a></td></tr>`);
        if (rows.length > 0) {
            metaHtml = `<div class="bot-meta"><table>${rows.join('\n')}</table></div>`;
        }
    }

    const rows = opponents.map((o, i) => {
        const oFlag = flagImg(o.name, '../');
        const compareSlug = `${botSlug(bot.name)}_vs_${botSlug(o.name)}`;
        return `<tr>
    <td>${i + 1}</td>
    <td>${oFlag}</td>
    <td><a href="${botSlug(o.name)}.html">${escapeHtml(o.name)}</a></td>
    <td class="compare-link"><a href="../compare/${compareSlug}.html">compare</a></td>
    <td class="num">${o.aps.toFixed(2)}</td>
    <td class="num">${(o.survival_pct).toFixed(1)}</td>
    <td class="num">${o.my_score}</td>
    <td class="num">${o.opp_score}</td>
    <td class="num">${o.battles}</td>
    <td>${o.won ? '&#10003;' : ''}</td>
  </tr>`;
    }).join('\n');

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(bot.name)} — Robocode Rumble</title>
  <style>${css}</style>
</head>
<body>
<header><div class="container">
  <div class="header-inner">
    <div class="header-logo"><img src="${LOGO_URL}" alt="Robocode"></div>
    <div class="header-text">
      <h1><a href="../index.html">Rumble</a> &rsaquo; ${flag}${escapeHtml(bot.name)}</h1>
      <div class="tagline">Build the best - destroy the rest!</div>
    </div>
  </div>
</div></header>
<div class="container">
${metaHtml}
<div class="content-card">
<div class="bot-summary">
  <div class="bot-stats">
    <table>
      <tr><th>Rank</th><td>#${bot.rank}</td></tr>
      <tr><th>APS</th><td>${bot.aps.toFixed(2)}</td></tr>
      <tr><th>PWIN</th><td>${(bot.pwin ?? 0).toFixed(2)}</td></tr>
      <tr><th>ANPP</th><td>${bot.anpp.toFixed(2)}</td></tr>
      <tr><th>Vote</th><td>${bot.vote.toFixed(1)}%</td></tr>
      <tr><th>Survival</th><td>${bot.survival.toFixed(1)}%</td></tr>
      <tr><th>Pairings</th><td>${bot.pairings}</td></tr>
      <tr><th>Battles</th><td>${bot.battles ?? 0}</td></tr>
    </table>
  </div>
  <div class="score-chart">
    <div style="font-size:0.8rem;color:var(--text-muted);margin-bottom:4px;">
      Score Distribution &mdash;
      <span style="color:rgb(204,37,41);">&#9679; APS</span>
      <span style="color:rgb(62,150,81);">&#9679; Survival</span>
    </div>
    ${svgChart}
    <div style="font-size:0.75rem;color:var(--text-muted);margin-top:2px;">
      Opponent APS (X) vs. Pairing Score (Y)
    </div>
  </div>
</div>
<h2>Head-to-Head Results</h2>
<table>
  <thead><tr>
    <th data-sort="num">#</th>
    <th>Flag</th>
    <th data-sort="str">Opponent</th>
    <th></th>
    <th data-sort="num">APS</th>
    <th data-sort="num">Surv%</th>
    <th data-sort="num">My Score</th>
    <th data-sort="num">Opp Score</th>
    <th data-sort="num">Battles</th>
    <th data-sort="str">Won</th>
  </tr></thead>
  <tbody>
${rows}
  </tbody>
</table>
</div></div>
<footer><div class="container">
  <a href="https://github.com/pavelsavara/robocode">pavelsavara/robocode</a> &middot; Powered by GitHub Actions
</div></footer>
<script>${sortJs}</script>
</body>
</html>`;
}

// Generate comparison page for two bots
function generateComparePage(botAEntry, botBEntry) {
    const nameA = botAEntry.name;
    const nameB = botBEntry.name;
    const flagA = flagImg(nameA, '../');
    const flagB = flagImg(nameB, '../');

    // Aggregate all results per opponent for each bot
    const aAgg = new Map(); // opponent -> { my_score, opp_score, firsts, rounds }
    const bAgg = new Map();

    for (const r of allResults) {
        const isAa = r.bot_a.name === nameA;
        const isAb = r.bot_b.name === nameA;
        const iBa = r.bot_a.name === nameB;
        const iBb = r.bot_b.name === nameB;
        const rounds = r.rounds || 35;

        if (isAa || isAb) {
            const me = isAa ? r.bot_a : r.bot_b;
            const opp = isAa ? r.bot_b : r.bot_a;
            if (!aAgg.has(opp.name)) aAgg.set(opp.name, { my_score: 0, opp_score: 0, firsts: 0, rounds: 0 });
            const a = aAgg.get(opp.name);
            a.my_score += me.total_score; a.opp_score += opp.total_score;
            a.firsts += me.firsts; a.rounds += rounds;
        }
        if (iBa || iBb) {
            const me = iBa ? r.bot_a : r.bot_b;
            const opp = iBa ? r.bot_b : r.bot_a;
            if (!bAgg.has(opp.name)) bAgg.set(opp.name, { my_score: 0, opp_score: 0, firsts: 0, rounds: 0 });
            const b = bAgg.get(opp.name);
            b.my_score += me.total_score; b.opp_score += opp.total_score;
            b.firsts += me.firsts; b.rounds += rounds;
        }
    }

    // Convert aggregated data to APS/survival
    const aResults = new Map();
    for (const [name, a] of aAgg) {
        const total = a.my_score + a.opp_score;
        aResults.set(name, {
            aps: total > 0 ? Math.round((100 * a.my_score / total) * 100) / 100 : 50,
            survival: a.rounds > 0 ? Math.round((100 * a.firsts / a.rounds) * 100) / 100 : 0,
        });
    }
    const bResults = new Map();
    for (const [name, b] of bAgg) {
        const total = b.my_score + b.opp_score;
        bResults.set(name, {
            aps: total > 0 ? Math.round((100 * b.my_score / total) * 100) / 100 : 50,
            survival: b.rounds > 0 ? Math.round((100 * b.firsts / b.rounds) * 100) / 100 : 0,
        });
    }

    // Find common opponents
    const commonOpponents = [];
    for (const [opp, aData] of aResults) {
        if (opp === nameB || opp === nameA) continue;
        const bData = bResults.get(opp);
        if (bData) {
            const oppRanking = botApsLookup.get(opp);
            commonOpponents.push({
                name: opp,
                aAps: aData.aps,
                aSurv: aData.survival,
                bAps: bData.aps,
                bSurv: bData.survival,
                diffAps: Math.round((aData.aps - bData.aps) * 100) / 100,
                diffSurv: Math.round((aData.survival - bData.survival) * 100) / 100,
                oppAps: oppRanking ? oppRanking.aps : 50,
            });
        }
    }
    commonOpponents.sort((a, b) => a.name.localeCompare(b.name));

    // Summary stats
    const commonApsA = commonOpponents.length > 0 ? commonOpponents.reduce((s, o) => s + o.aAps, 0) / commonOpponents.length : 0;
    const commonApsB = commonOpponents.length > 0 ? commonOpponents.reduce((s, o) => s + o.bAps, 0) / commonOpponents.length : 0;
    const commonSurvA = commonOpponents.length > 0 ? commonOpponents.reduce((s, o) => s + o.aSurv, 0) / commonOpponents.length : 0;
    const commonSurvB = commonOpponents.length > 0 ? commonOpponents.reduce((s, o) => s + o.bSurv, 0) / commonOpponents.length : 0;

    // Build diff distribution SVG
    const diffSvg = generateDiffDistSvg(commonOpponents);

    const rows = commonOpponents.map((o, i) => {
        const oFlag = flagImg(o.name, '../');
        const diffClass = o.diffAps > 0 ? 'style="color:#62c462"' : o.diffAps < 0 ? 'style="color:#e94560"' : '';
        return `<tr>
    <td>${i + 1}</td>
    <td>${oFlag}</td>
    <td><a href="../bots/${botSlug(o.name)}.html">${escapeHtml(o.name)}</a></td>
    <td class="num">${o.aAps.toFixed(2)}</td>
    <td class="num">${o.aSurv.toFixed(1)}</td>
    <td class="num">${o.bAps.toFixed(2)}</td>
    <td class="num">${o.bSurv.toFixed(1)}</td>
    <td class="num" ${diffClass}>${o.diffAps > 0 ? '+' : ''}${o.diffAps.toFixed(2)}</td>
    <td class="num">${o.oppAps.toFixed(2)}</td>
  </tr>`;
    }).join('\n');

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(nameA)} vs ${escapeHtml(nameB)} — Robocode Rumble</title>
  <style>${css}</style>
</head>
<body>
<header><div class="container">
  <div class="header-inner">
    <div class="header-logo"><img src="${LOGO_URL}" alt="Robocode"></div>
    <div class="header-text">
      <h1><a href="../index.html">Rumble</a> &rsaquo; Compare</h1>
      <div class="tagline">Build the best - destroy the rest!</div>
    </div>
  </div>
</div></header>
<div class="container">
<div class="content-card">
<div class="compare-header">
  <div class="compare-bot">
    <h2>${flagA}<a href="../bots/${botSlug(nameA)}.html">${escapeHtml(nameA)}</a></h2>
    <table>
      <tr><th>Rank</th><td>#${botAEntry.rank}</td></tr>
      <tr><th>APS</th><td>${botAEntry.aps.toFixed(2)}</td></tr>
      <tr><th>Common APS</th><td>${commonApsA.toFixed(2)}</td></tr>
      <tr><th>Common Surv</th><td>${commonSurvA.toFixed(1)}%</td></tr>
    </table>
  </div>
  <div class="compare-bot">
    <h2>${flagB}<a href="../bots/${botSlug(nameB)}.html">${escapeHtml(nameB)}</a></h2>
    <table>
      <tr><th>Rank</th><td>#${botBEntry.rank}</td></tr>
      <tr><th>APS</th><td>${botBEntry.aps.toFixed(2)}</td></tr>
      <tr><th>Common APS</th><td>${commonApsB.toFixed(2)}</td></tr>
      <tr><th>Common Surv</th><td>${commonSurvB.toFixed(1)}%</td></tr>
    </table>
  </div>
  <div class="score-chart">
    <div style="font-size:0.8rem;color:var(--text-muted);margin-bottom:4px;">
      Diff Distribution &mdash; APS(A) - APS(B)
    </div>
    ${diffSvg}
  </div>
</div>

<h2>Common Opponents (${commonOpponents.length})</h2>
<table>
  <thead><tr>
    <th data-sort="num">#</th>
    <th>Flag</th>
    <th data-sort="str">Opponent</th>
    <th data-sort="num">APS (A)</th>
    <th data-sort="num">Surv (A)</th>
    <th data-sort="num">APS (B)</th>
    <th data-sort="num">Surv (B)</th>
    <th data-sort="num">Diff APS</th>
    <th data-sort="num">Opp APS</th>
  </tr></thead>
  <tbody>
${rows}
  </tbody>
</table>
</div></div>
<footer><div class="container">
  <a href="https://github.com/pavelsavara/robocode">pavelsavara/robocode</a> &middot; Powered by GitHub Actions
</div></footer>
<script>${sortJs}</script>
</body>
</html>`;
}

// Generate a diff distribution SVG for comparison page
function generateDiffDistSvg(commonOpponents) {
    const size = 230;
    let circles = '';
    for (const o of commonOpponents) {
        // X = opponent APS, Y = diff (centered at 50% = 0 diff)
        const cx = (o.oppAps / 100) * size;
        // Map diff (-100..+100) to Y (size..0)
        const diffNorm = Math.max(-100, Math.min(100, o.diffAps));
        const cy = size - ((diffNorm + 100) / 200) * size;
        const color = diffNorm > 0 ? 'rgba(62,150,81,0.8)' : diffNorm < 0 ? 'rgba(204,37,41,0.8)' : 'rgba(128,128,128,0.8)';
        circles += `<circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="2.5" fill="${color}"/>`;
    }
    const mid = size * 0.5;
    const gridLine = `<line x1="0" y1="${mid}" x2="${size}" y2="${mid}" stroke="#555" stroke-width="0.5" stroke-dasharray="2,2"/>`;
    return `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" xmlns="http://www.w3.org/2000/svg">
<rect width="${size}" height="${size}" fill="#3a3a3a"/>
${gridLine}
${circles}
</svg>`;
}

// Write pages
writeFileSync(join(outDir, 'index.html'), generateIndex());

// Bot detail pages
const botsDir = join(outDir, 'bots');
mkdirSync(botsDir, { recursive: true });
for (const bot of rankings.rankings) {
    writeFileSync(join(botsDir, `${botSlug(bot.name)}.html`), generateBotPage(bot));
}

// Compare pages — generate for all pairs that have head-to-head data
const compareDir = join(outDir, 'compare');
mkdirSync(compareDir, { recursive: true });
const generatedCompares = new Set();
for (const bot of rankings.rankings) {
    const botResults = allResults.filter(r =>
        r.bot_a.name === bot.name || r.bot_b.name === bot.name
    );
    for (const r of botResults) {
        const oppName = r.bot_a.name === bot.name ? r.bot_b.name : r.bot_a.name;
        const key = [bot.name, oppName].sort().join('|');
        if (generatedCompares.has(key)) continue;
        generatedCompares.add(key);

        const oppEntry = rankings.rankings.find(b => b.name === oppName);
        if (!oppEntry) continue;

        const slug = `${botSlug(bot.name)}_vs_${botSlug(oppName)}`;
        const slugReverse = `${botSlug(oppName)}_vs_${botSlug(bot.name)}`;
        const html = generateComparePage(bot, oppEntry);
        writeFileSync(join(compareDir, `${slug}.html`), html);
        // Also write reverse direction so links work from either side
        if (slug !== slugReverse) {
            writeFileSync(join(compareDir, `${slugReverse}.html`), generateComparePage(oppEntry, bot));
        }
    }
}

// Write rankings JSON for programmatic access
writeFileSync(join(outDir, 'rankings.json'), JSON.stringify(rankings, null, 2));

const numCompare = generatedCompares.size;
console.log(`Generated ${rankings.rankings.length + 1} pages + ${numCompare * 2} compare pages in ${outDir}`);
