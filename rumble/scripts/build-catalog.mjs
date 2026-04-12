#!/usr/bin/env node
// build-catalog.mjs — Build robots/index.json with metadata for each bot.
//
// Usage:
//   node build-catalog.mjs --top-list top30.txt --robots-dir robots --out robots/index.json

import { readFileSync, writeFileSync, existsSync, readdirSync, mkdirSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { get as httpsGet } from 'node:https';
import { get as httpGet } from 'node:http';

const __dirname = dirname(fileURLToPath(import.meta.url));

const { values: args } = parseArgs({
    options: {
        'top-list': { type: 'string', default: 'top30.txt' },
        'robots-dir': { type: 'string', default: 'robots' },
        'out': { type: 'string', default: 'robots/index.json' },
        'wiki-url': { type: 'string', default: 'https://robowiki.net/wiki/RoboRumble/Participants?action=raw' },
        'github-repo': { type: 'string', default: 'pavelsavara/robocode' },
        'github-branch': { type: 'string', default: 'data/robots' },
    },
});

// Flags
const flagsJsonPath = join(__dirname, '..', 'flags', 'flags.json');
const flagMap = existsSync(flagsJsonPath)
    ? JSON.parse(readFileSync(flagsJsonPath, 'utf-8'))
    : {};

// Country code → name mapping
const countryNames = {
    USA: 'United States', GBR: 'United Kingdom', CAN: 'Canada', AUS: 'Australia',
    DEU: 'Germany', FRA: 'France', NLD: 'Netherlands', BRA: 'Brazil', CHN: 'China',
    JPN: 'Japan', KOR: 'South Korea', PRT: 'Portugal', ESP: 'Spain', ITA: 'Italy',
    RUS: 'Russia', SWE: 'Sweden', NOR: 'Norway', FIN: 'Finland', DNK: 'Denmark',
    IRL: 'Ireland', SGP: 'Singapore', TUR: 'Turkey', POL: 'Poland', HUN: 'Hungary',
    CZE: 'Czech Republic', HRV: 'Croatia', ROU: 'Romania', BGR: 'Bulgaria',
    IND: 'India', ISR: 'Israel', ARG: 'Argentina', MEX: 'Mexico', CHL: 'Chile',
    COL: 'Colombia', PER: 'Peru', VEN: 'Venezuela', ZAF: 'South Africa',
    EGY: 'Egypt', NZL: 'New Zealand', BEL: 'Belgium', AUT: 'Austria', CHE: 'Switzerland',
    GRC: 'Greece', UKR: 'Ukraine', SRB: 'Serbia', LTU: 'Lithuania', EST: 'Estonia',
    LVA: 'Latvia', TWN: 'Taiwan', THA: 'Thailand', PHL: 'Philippines', IDN: 'Indonesia',
    MYS: 'Malaysia', VNM: 'Vietnam', SAU: 'Saudi Arabia', ARE: 'UAE',
    IRN: 'Iran', PAK: 'Pakistan', BGD: 'Bangladesh', LKA: 'Sri Lanka',
    NONE: 'Unknown',
};

function fetchText(url) {
    return new Promise((resolve, reject) => {
        const getter = url.startsWith('https') ? httpsGet : httpGet;
        getter(url, { headers: { 'User-Agent': 'RoboRumble-Catalog/1.0' } }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return fetchText(res.headers.location).then(resolve, reject);
            }
            if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode}`));
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve(data));
        }).on('error', reject);
    });
}

function parseParticipants(text) {
    const preMatch = text.match(/<pre>([\s\S]*?)<\/pre>/);
    const content = preMatch ? preMatch[1] : text;
    const rawLines = content.split('\n').map(l => l.trim()).filter(l => l.length > 0);
    const bots = [];
    let i = 0;
    while (i < rawLines.length) {
        const line = rawLines[i];
        if (line.startsWith('#') || line.startsWith('//')) { i++; continue; }
        const commaHttpIdx = line.search(/,https?:\/\//);
        if (commaHttpIdx >= 0) {
            addBot(bots, line.substring(0, commaHttpIdx).trim(), line.substring(commaHttpIdx + 1).trim());
            i++; continue;
        }
        if (line.endsWith(',') && i + 1 < rawLines.length && rawLines[i + 1].match(/^https?:\/\//)) {
            addBot(bots, line.slice(0, -1).trim(), rawLines[i + 1].trim());
            i += 2; continue;
        }
        if (i + 1 < rawLines.length) {
            const nextLine = rawLines[i + 1];
            const nextIdx = nextLine.search(/,https?:\/\//);
            if (nextIdx >= 0) {
                addBot(bots, (line + ' ' + nextLine.substring(0, nextIdx)).trim(), nextLine.substring(nextIdx + 1).trim());
                i += 2; continue;
            }
            if (nextLine.match(/^https?:\/\//)) {
                addBot(bots, line.trim(), nextLine.trim());
                i += 2; continue;
            }
        }
        i++;
    }
    return bots;
}

function addBot(bots, fullName, jarUrl) {
    const nameMatch = fullName.match(/^(\S+)\s*(.*)$/);
    bots.push({
        name: nameMatch ? nameMatch[1] : fullName,
        version: nameMatch ? nameMatch[2] : '',
        fullName,
        jarUrl,
    });
}

async function main() {
    // Load top list
    const topList = readFileSync(resolve(args['top-list']), 'utf-8')
        .split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'));

    console.log(`Loaded ${topList.length} bots from ${args['top-list']}`);

    // Fetch wiki participants
    console.log(`Fetching wiki participants from ${args['wiki-url']}...`);
    const wikiText = await fetchText(args['wiki-url']);
    const wikiParticipants = parseParticipants(wikiText);
    console.log(`Found ${wikiParticipants.length} bots on wiki`);

    // Build lookup by fullName and by name
    const wikiByFull = new Map();
    const wikiByName = new Map();
    for (const p of wikiParticipants) {
        wikiByFull.set(p.fullName, p);
        wikiByName.set(p.name, p);
    }

    const robotsDir = resolve(args['robots-dir']);
    const existingJars = existsSync(robotsDir)
        ? new Set(readdirSync(robotsDir).filter(f => f.endsWith('.jar')))
        : new Set();

    const catalog = [];

    for (const fullName of topList) {
        const nameMatch = fullName.match(/^(\S+)\s*(.*)$/);
        const name = nameMatch ? nameMatch[1] : fullName;
        const version = nameMatch ? nameMatch[2] : '';
        const pkg = name.split('.')[0];

        // Find wiki entry
        const wiki = wikiByFull.get(fullName) || wikiByName.get(name);

        // Derive author from package name
        const author = pkg;

        // Flag/country
        const countryCode = flagMap[pkg] || 'NONE';
        const country = countryNames[countryCode] || countryCode;

        // JAR URL
        const jarUrl = wiki ? wiki.jarUrl : '';
        const jarFile = fullName.replace(/ /g, '_') + '.jar';

        // Check if JAR exists locally
        const hasLocalJar = existingJars.has(jarFile);

        // GitHub raw URL
        const githubRawUrl = hasLocalJar
            ? `https://raw.githubusercontent.com/${args['github-repo']}/${args['github-branch']}/${jarFile}`
            : '';

        // Wiki page URL (standard RoboWiki convention)
        const botPageName = name.split('.').pop(); // e.g., "DrussGT" from "jk.mega.DrussGT"
        const wikiUrl = `https://robowiki.net/wiki/${botPageName}`;

        const entry = {
            name,
            fullName,
            version,
            author,
            country,
            countryCode,
            wikiUrl,
            jarUrl,
            jarFile,
            githubRawUrl,
        };

        catalog.push(entry);
        console.log(`  ${name} → ${countryCode} | jar:${hasLocalJar ? 'YES' : 'NO'} | wiki:${wikiUrl}`);
    }

    // Write catalog
    const outPath = resolve(args.out);
    mkdirSync(dirname(outPath), { recursive: true });
    writeFileSync(outPath, JSON.stringify(catalog, null, 2));
    console.log(`\nWrote ${catalog.length} entries to ${outPath}`);
}

main().catch(err => { console.error(err); process.exit(1); });
