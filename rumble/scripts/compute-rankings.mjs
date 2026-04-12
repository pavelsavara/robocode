#!/usr/bin/env node
// compute-rankings.mjs — Compute APS, Survival%, Vote%, and ANPP from battle results.
//
// Usage:
//   node compute-rankings.mjs --results results/ --out rankings.json
//
// Reads all JSON files in results/ directory (or a single results.json file),
// computes aggregate rankings, outputs sorted rankings JSON.

import { readFileSync, writeFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { parseArgs } from 'node:util';

const { values: args } = parseArgs({
    options: {
        results: { type: 'string', default: 'results' },
        out: { type: 'string', default: 'rankings.json' },
    },
});

// Load all battle results
function loadResults(resultsPath) {
    const p = resolve(resultsPath);
    if (!existsSync(p)) {
        console.error(`Results path not found: ${p}`);
        process.exit(1);
    }

    const s = statSync(p);
    if (s.isFile()) {
        return JSON.parse(readFileSync(p, 'utf-8'));
    }

    // Directory: read all .json files recursively
    const all = [];
    function walk(dir) {
        for (const entry of readdirSync(dir, { withFileTypes: true })) {
            const full = join(dir, entry.name);
            if (entry.isDirectory()) {
                walk(full);
            } else if (entry.name.endsWith('.json')) {
                const data = JSON.parse(readFileSync(full, 'utf-8'));
                if (Array.isArray(data)) all.push(...data);
                else all.push(data);
            }
        }
    }
    walk(p);
    return all;
}

const allResults = loadResults(args.results).filter(r => !r.error);

// Build per-pairing aggregates
// Key: sorted "botA|botB"
const pairings = new Map();

for (const result of allResults) {
    const a = result.bot_a;
    const b = result.bot_b;
    const key = [a.name, b.name].sort().join('|');

    if (!pairings.has(key)) {
        pairings.set(key, { battles: [], bot_names: [a.name, b.name].sort() });
    }
    pairings.get(key).battles.push(result);
}

// Per-bot aggregates
const botStats = new Map();

function ensureBot(name) {
    if (!botStats.has(name)) {
        botStats.set(name, {
            name,
            pairings: new Map(), // opponent -> { aps, survival_pct, ... }
        });
    }
    return botStats.get(name);
}

// Process each pairing using LiteRumble's rolling average with decay.
//
// LiteRumble incrementally averages each new battle result into the pairing:
//   effective_battles = min(actual_battles, 10000 / num_bots)
//   weight = 1 / (effective_battles + 1)
//   pairing.APS = pairing.APS * (1 - weight) + new_APS * weight
//   opposite.APS = 100 - pairing.APS  (complementary)
//
// We replay all stored battles in chronological order to reproduce this.

const totalBots = new Set();
for (const result of allResults) {
    totalBots.add(result.bot_a.name);
    totalBots.add(result.bot_b.name);
}
const numBots = totalBots.size;
const maxPerPair = Math.max(1, Math.floor(10000 / numBots));

// Sort all results by timestamp to replay in order
allResults.sort((a, b) => (a.timestamp || '').localeCompare(b.timestamp || ''));

for (const result of allResults) {
    const a = result.bot_a;
    const b = result.bot_b;
    const rounds = result.rounds || 35;

    // Compute this battle's APS from bot A's perspective
    const totalScore = a.total_score + b.total_score;
    const battleApsA = totalScore > 0 ? (100 * a.total_score) / totalScore : 50;

    // Compute this battle's survival %
    const battleSurvA = (100 * a.firsts) / rounds;
    const battleSurvB = (100 * b.firsts) / rounds;

    const botA = ensureBot(a.name);
    const botB = ensureBot(b.name);

    // Get or create pairing state for A->B
    if (!botA.pairings.has(b.name)) {
        botA.pairings.set(b.name, { aps: 0, survival_pct: 0, battles: 0, bullet_damage: 0 });
    }
    if (!botB.pairings.has(a.name)) {
        botB.pairings.set(a.name, { aps: 0, survival_pct: 0, battles: 0, bullet_damage: 0 });
    }

    const pairAB = botA.pairings.get(b.name);
    const pairBA = botB.pairings.get(a.name);

    // Rolling average with decay cap (LiteRumble algorithm)
    const effectiveBattles = Math.min(pairAB.battles, maxPerPair);
    const weight = 1.0 / (effectiveBattles + 1.0);

    // Update APS — computed from A's perspective, B is complementary
    pairAB.aps = pairAB.aps * (1 - weight) + battleApsA * weight;
    pairBA.aps = 100 - pairAB.aps;

    // Update survival — each side independently averaged
    pairAB.survival_pct = pairAB.survival_pct * (1 - weight) + battleSurvA * weight;
    pairBA.survival_pct = pairBA.survival_pct * (1 - weight) + battleSurvB * weight;

    // Battle count (shared between both sides)
    pairAB.battles += 1;
    pairBA.battles = pairAB.battles;

    // Track latest bullet damage
    pairAB.bullet_damage = a.bullet_damage;
    pairBA.bullet_damage = b.bullet_damage;
}

// Set derived fields
for (const [, bot] of botStats) {
    for (const [, p] of bot.pairings) {
        p.won = p.aps > 50;
        p.total_score = 0; // not meaningful for rolling average
        p.opp_score = 0;
    }
}

// Compute overall stats
const rankings = [];

for (const [name, bot] of botStats) {
    const pairingValues = Array.from(bot.pairings.values());
    const numPairings = pairingValues.length;

    if (numPairings === 0) continue;

    const aps = pairingValues.reduce((sum, p) => sum + p.aps, 0) / numPairings;
    const survival = pairingValues.reduce((sum, p) => sum + p.survival_pct, 0) / numPairings;
    const vote = (100 * pairingValues.filter(p => p.won).length) / numPairings;
    const totalBattles = pairingValues.reduce((sum, p) => sum + p.battles, 0);
    // PL (pairings lead): +1 for each won pairing, -1 for each lost
    const pl = pairingValues.reduce((sum, p) => sum + (p.aps > 50 ? 1 : -1), 0);

    rankings.push({
        name,
        aps: Math.round(aps * 100) / 100,
        survival: Math.round(survival * 100) / 100,
        vote: Math.round(vote * 100) / 100,
        pwin: Math.round((50 * pl / numPairings + 50) * 100) / 100,
        pairings: numPairings,
        battles: totalBattles,
    });
}

// Compute ANPP (Average Normalised Percentage Pairs)
// For each pairing, normalise the APS relative to all other bots' APS against the same opponent
for (const entry of rankings) {
    const bot = botStats.get(entry.name);
    let anppSum = 0;
    let anppCount = 0;

    for (const [opponent, pairing] of bot.pairings) {
        // Find all other bots' APS against this opponent
        const otherAps = [];
        for (const [otherName, otherBot] of botStats) {
            if (otherName === entry.name) continue;
            const p = otherBot.pairings.get(opponent);
            if (p) otherAps.push(p.aps);
        }

        if (otherAps.length > 0) {
            const minAps = Math.min(...otherAps);
            const maxAps = Math.max(...otherAps);
            const range = maxAps - minAps;

            // Normalise: (myAPS - min) / (max - min) * 100, or 50 if range is 0
            const normalised = range > 0 ? ((pairing.aps - minAps) / range) * 100 : 50;
            anppSum += normalised;
            anppCount++;
        }
    }

    entry.anpp = anppCount > 0 ? Math.round((anppSum / anppCount) * 100) / 100 : 0;
}

// Sort by APS descending
rankings.sort((a, b) => b.aps - a.aps);

// Add rank numbers
rankings.forEach((r, i) => { r.rank = i + 1; });

const output = {
    generated: new Date().toISOString(),
    total_bots: rankings.length,
    total_pairings: pairings.size,
    total_battles: allResults.length,
    rankings,
};

writeFileSync(resolve(args.out), JSON.stringify(output, null, 2));
console.log(`Rankings: ${rankings.length} bots, ${pairings.size} pairings, ${allResults.length} battles`);
console.log(`Top 5:`);
rankings.slice(0, 5).forEach(r => {
    console.log(`  #${r.rank} ${r.name} — APS: ${r.aps}, Surv: ${r.survival}%, Vote: ${r.vote}%, ANPP: ${r.anpp}`);
});
