#!/usr/bin/env node
// scrape-wiki.mjs — Fetch RoboRumble participants from RoboWiki and download bot JARs.
//
// Usage:
//   node scrape-wiki.mjs --max-bots 30 --out-dir robots/ --participants participants.txt
//
// Reads the RoboWiki participants page, parses bot entries,
// downloads JARs, and writes a participants.txt file.

import { writeFileSync, mkdirSync, existsSync, createWriteStream } from 'node:fs';
import { resolve, join } from 'node:path';
import { parseArgs } from 'node:util';
import { get as httpsGet } from 'node:https';
import { get as httpGet } from 'node:http';
import { pipeline } from 'node:stream/promises';

const { values: args } = parseArgs({
    options: {
        url: {
            type: 'string',
            default: 'https://robowiki.net/wiki/RoboRumble/Participants?action=raw',
        },
        'max-bots': { type: 'string', default: '30' },
        'out-dir': { type: 'string', default: 'robots' },
        participants: { type: 'string', default: 'participants.txt' },
        'top-list': { type: 'string' }, // optional: path to a file with curated bot names
        'skip-download': { type: 'boolean', default: false },
    },
});

const maxBots = parseInt(args['max-bots'], 10);
const outDir = resolve(args['out-dir']);
const participantsFile = resolve(args.participants);

async function fetchText(url) {
    return new Promise((resolve, reject) => {
        const getter = url.startsWith('https') ? httpsGet : httpGet;
        getter(url, { headers: { 'User-Agent': 'RoboRumble-GH-Actions/1.0' } }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return fetchText(res.headers.location).then(resolve, reject);
            }
            if (res.statusCode !== 200) {
                return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
            }
            let data = '';
            res.on('data', chunk => { data += chunk; });
            res.on('end', () => resolve(data));
            res.on('error', reject);
        }).on('error', reject);
    });
}

async function downloadFile(url, dest) {
    return new Promise((resolve, reject) => {
        const getter = url.startsWith('https') ? httpsGet : httpGet;
        getter(url, { headers: { 'User-Agent': 'RoboRumble-GH-Actions/1.0' } }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return downloadFile(res.headers.location, dest).then(resolve, reject);
            }
            if (res.statusCode !== 200) {
                return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
            }
            const ws = createWriteStream(dest);
            pipeline(res, ws).then(resolve, reject);
        }).on('error', reject);
    });
}

// Parse wiki participants page
// The raw wiki page has bot entries inside a <pre> block.
// Format is one entry per line: "package.BotName version,URL"
// But some entries are split across two lines:
//   package.BotName
//   version,URL
function parseParticipants(text) {
    // Extract content between <pre> and </pre> tags
    const preMatch = text.match(/<pre>([\s\S]*?)<\/pre>/);
    const content = preMatch ? preMatch[1] : text;
    const rawLines = content.split('\n').map(l => l.trim()).filter(l => l.length > 0);
    const bots = [];
    let i = 0;

    while (i < rawLines.length) {
        const line = rawLines[i];

        // Skip non-entry lines
        if (line.startsWith('#') || line.startsWith('//')) { i++; continue; }

        // Check if this line contains ",http" — single-line entry
        const commaHttpIdx = line.search(/,https?:\/\//);
        if (commaHttpIdx >= 0) {
            const fullName = line.substring(0, commaHttpIdx).trim();
            const jarUrl = line.substring(commaHttpIdx + 1).trim();
            if (fullName && jarUrl.startsWith('http')) {
                addBot(bots, fullName, jarUrl);
            }
            i++;
            continue;
        }

        // Line ends with comma, URL on next line
        if (line.endsWith(',') && i + 1 < rawLines.length && rawLines[i + 1].match(/^https?:\/\//)) {
            const fullName = line.slice(0, -1).trim();
            const jarUrl = rawLines[i + 1].trim();
            if (fullName && jarUrl) addBot(bots, fullName, jarUrl);
            i += 2;
            continue;
        }

        // Two-line entry: name-only line, then "version,URL" on next line
        if (i + 1 < rawLines.length) {
            const nextLine = rawLines[i + 1];
            const nextCommaIdx = nextLine.search(/,https?:\/\//);
            if (nextCommaIdx >= 0) {
                const versionPart = nextLine.substring(0, nextCommaIdx).trim();
                const jarUrl = nextLine.substring(nextCommaIdx + 1).trim();
                const fullName = (line + ' ' + versionPart).trim();
                if (jarUrl.startsWith('http')) addBot(bots, fullName, jarUrl);
                i += 2;
                continue;
            }
            // name-only line, then bare URL on next line
            if (nextLine.match(/^https?:\/\//)) {
                addBot(bots, line.trim(), nextLine.trim());
                i += 2;
                continue;
            }
        }

        // Can't parse, skip
        i++;
    }
    return bots;
}

function addBot(bots, fullName, jarUrl) {
    const jarFile = fullName.replace(/ /g, '_') + '.jar';
    const nameMatch = fullName.match(/^(\S+)\s*(.*)$/);
    bots.push({
        name: nameMatch ? nameMatch[1] : fullName,
        version: nameMatch ? nameMatch[2] : '',
        fullName,
        jarFile,
        jarUrl,
    });
}

// Main
async function main() {
    console.log(`Fetching participants from ${args.url}...`);
    const text = await fetchText(args.url);
    const allBots = parseParticipants(text);
    console.log(`Found ${allBots.length} bots on wiki`);

    // Filter to curated list if provided
    let bots = allBots;
    if (args['top-list']) {
        const topNames = new Set(
            (await import('node:fs')).readFileSync(resolve(args['top-list']), 'utf-8')
                .split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'))
        );
        bots = allBots.filter(b => topNames.has(b.fullName) || topNames.has(b.name));
        console.log(`Filtered to ${bots.length} bots from curated list`);
    }

    // Limit
    bots = bots.slice(0, maxBots);
    console.log(`Processing ${bots.length} bots`);

    // Download JARs
    if (!args['skip-download']) {
        mkdirSync(outDir, { recursive: true });
        for (const bot of bots) {
            const dest = join(outDir, bot.jarFile);
            if (existsSync(dest)) {
                console.log(`  SKIP ${bot.jarFile} (exists)`);
                continue;
            }
            try {
                console.log(`  GET  ${bot.jarFile} from ${bot.jarUrl}`);
                await downloadFile(bot.jarUrl, dest);
            } catch (err) {
                console.error(`  FAIL ${bot.jarFile}: ${err.message}`);
            }
        }
    }

    // Write participants.txt (just the bot fully-qualified names, one per line)
    const participantsList = [
        '# RoboRumble Participants',
        `# Generated: ${new Date().toISOString()}`,
        `# Source: ${args.url}`,
        `# Count: ${bots.length}`,
        '',
        ...bots.map(b => b.fullName),
        '',
    ].join('\n');

    writeFileSync(participantsFile, participantsList);
    console.log(`Wrote ${bots.length} entries to ${participantsFile}`);
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
