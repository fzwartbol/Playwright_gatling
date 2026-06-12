#!/usr/bin/env node
/**
 * har-to-simulation.js
 *
 * Generates a Gatling Java DSL simulation from a Playwright HAR recording.
 * The simulation replays the recorded journey against WireMock at scale.
 * WireMock matches on URL+method and returns recorded responses — auth headers
 * are sent as-is and are never validated, so all VUs share the same session.
 *
 * Usage:
 *   node har-to-simulation.js <input.har> <ClassName> [outputDir]
 *
 * Output:
 *   <outputDir>/<ClassName>.java
 */

'use strict';

const fs   = require('fs');
const path = require('path');

const harFile   = process.argv[2];
const className = process.argv[3] || 'GeneratedSimulation';
const outDir    = process.argv[4] || '.';

if (!harFile) {
  console.error('Usage: har-to-simulation.js <input.har> [ClassName] [outputDir]');
  process.exit(1);
}

// ── Filters ─────────────────────────────────────────────────────────────────

const SKIP_EXT  = /\.(css|js|mjs|map|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)(\?.*)?$/i;
const SKIP_PATH = /^\/(favicon|robots\.txt|sitemap)/i;

// Headers worth sending in replay — skip browser internals (Accept-Language etc.)
const KEEP_HEADERS = new Set([
  'content-type',
  'authorization',
  'x-xsrf-token',
  'x-gwt-module-base',
  'x-gwt-permutation',
  'x-requested-with',
  'accept',
]);

function shouldInclude(entry) {
  try { new URL(entry.request.url); } catch { return false; }
  const url    = new URL(entry.request.url);
  const status = entry.response.status;
  if (status === 0 || status >= 400) return false;  // failed or client errors
  if (SKIP_EXT.test(url.pathname))   return false;
  if (SKIP_PATH.test(url.pathname))  return false;
  return true;
}

// ── Java string escaping ─────────────────────────────────────────────────────

function esc(s) {
  return String(s)
    .replace(/\\/g, '\\\\')
    .replace(/"/g,  '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t');
}

// ── Build one exec block ─────────────────────────────────────────────────────

function buildExec(entry, stepNum) {
  const req    = entry.request;
  const resp   = entry.response;
  const url    = new URL(req.url);
  const relUrl = url.pathname + (url.search || '');
  const label  = `${req.method} ${url.pathname}`;
  const method = req.method.toLowerCase();

  const lines = [];
  lines.push(`        // ── Step ${stepNum} ─────────────────────────────────────────────────`);
  lines.push(`        .exec(`);
  lines.push(`            http("${esc(label)}")`);
  lines.push(`                .${method}("${esc(relUrl)}")`);

  // Relevant request headers (skip Content-Type — added with body below)
  for (const h of req.headers) {
    const lo = h.name.toLowerCase();
    if (lo === 'content-type') continue;
    if (!KEEP_HEADERS.has(lo))  continue;
    lines.push(`                .header("${esc(h.name)}", "${esc(h.value)}")`);
  }

  // Request body
  if (req.postData && req.postData.text && req.postData.text.trim()) {
    const ct = req.headers.find(h => h.name.toLowerCase() === 'content-type');
    if (ct) {
      // Strip charset suffix so WireMock matching is lenient
      const ctVal = ct.value.split(';')[0].trim();
      lines.push(`                .header("Content-Type", "${esc(ctVal)}")`);
    }
    lines.push(`                .body(StringBody("${esc(req.postData.text)}"))`);
  }

  lines.push(`                .check(status().is(${resp.status}))`);
  lines.push(`        )`);

  return lines.join('\n');
}

// ── Build pause between steps (from HAR timing) ──────────────────────────────

function buildPause(entry, nextEntry) {
  if (!nextEntry) return null;
  const endMs   = new Date(entry.startedDateTime).getTime() + entry.time;
  const nextMs  = new Date(nextEntry.startedDateTime).getTime();
  const gapSecs = Math.max(0, Math.round((nextMs - endMs) / 1000));
  if (gapSecs < 1) return null;
  return `        .pause(${Math.min(gapSecs, 5)})`;  // cap think-time at 5 s
}

// ── Main ─────────────────────────────────────────────────────────────────────

const har      = JSON.parse(fs.readFileSync(harFile, 'utf8'));
const filtered = har.log.entries.filter(shouldInclude);

if (filtered.length === 0) {
  console.error('No requests survived filtering. Check that the HAR is non-empty.');
  process.exit(1);
}

const steps = [];
filtered.forEach((entry, i) => {
  steps.push(buildExec(entry, i + 1));
  const pause = buildPause(entry, filtered[i + 1]);
  if (pause) steps.push(pause);
});

const now = new Date().toISOString();

const java = `package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

// Auto-generated from ${path.basename(harFile)} on ${now}
// Replays ${filtered.length} recorded requests against WireMock.
// WireMock matches on URL+method and returns recorded responses —
// no real application or auth validation needed during load test.
public class ${className} extends Simulation {

    private final String baseUrl  = System.getenv().getOrDefault("BASE_URL",         "http://localhost:8080");
    private final int    users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "10"));
    private final int    duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "60"));

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .disableCaching();

    private final ScenarioBuilder journey = scenario("${className}")

${steps.join('\n\n')};

    {
        setUp(
            journey.injectOpen(
                rampUsersPerSec(1).to(users).during(Duration.ofSeconds(duration))
            )
        ).protocols(httpProtocol)
         .assertions(
             global().responseTime().max().lt(5000),
             global().successfulRequests().percent().gte(95.0)
         );
    }
}
`;

fs.mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, `${className}.java`);
fs.writeFileSync(outFile, java);
console.log(`Generated ${outFile} (${filtered.length} steps from ${har.log.entries.length} HAR entries)`);
