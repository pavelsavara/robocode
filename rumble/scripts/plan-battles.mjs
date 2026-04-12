#!/usr/bin/env node
// plan-battles.mjs — Generate all 1v1 pairings from a participants list and split into chunks.
//
// Usage:
//   node plan-battles.mjs --participants participants.txt --chunks 20 --out battle-plan.json
//
// Output: JSON with { total_battles, chunks: [ [{bot_a, bot_b}, ...], ... ] }

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { parseArgs } from 'node:util';

const { values: args } = parseArgs({
    options: {
        participants: { type: 'string', default: 'participants.txt' },
        chunks: { type: 'string', default: '20' },
        out: { type: 'string', default: 'battle-plan.json' },
    },
});

// Parse participants file
// Format: one bot name per line (fully qualified, e.g. "sample.Fire")
// Lines starting with # are comments, empty lines ignored
const raw = readFileSync(resolve(args.participants), 'utf-8');
const bots = raw
    .split('\n')
    .map(l => l.trim())
    .filter(l => l && !l.startsWith('#'));

// Generate all N*(N-1)/2 unique pairings
const pairs = [];
for (let i = 0; i < bots.length; i++) {
    for (let j = i + 1; j < bots.length; j++) {
        pairs.push({ bot_a: bots[i], bot_b: bots[j] });
    }
}

// Shuffle for even distribution across chunks
for (let i = pairs.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [pairs[i], pairs[j]] = [pairs[j], pairs[i]];
}

// Split into chunks
const numChunks = Math.min(parseInt(args.chunks, 10), pairs.length);
const chunks = Array.from({ length: numChunks }, () => []);
for (let i = 0; i < pairs.length; i++) {
    chunks[i % numChunks].push(pairs[i]);
}

const plan = {
    generated: new Date().toISOString(),
    total_bots: bots.length,
    total_battles: pairs.length,
    num_chunks: numChunks,
    bots,
    chunks,
};

writeFileSync(resolve(args.out), JSON.stringify(plan, null, 2));
console.log(`Planned ${pairs.length} battles for ${bots.length} bots in ${numChunks} chunks`);
console.log(`Max battles per chunk: ${Math.max(...chunks.map(c => c.length))}`);
