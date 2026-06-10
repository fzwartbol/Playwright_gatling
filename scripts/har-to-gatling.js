#!/usr/bin/env node
/**
 * Converts a HAR file into a Gatling Java simulation (Gatling Java DSL).
 *
 * Usage:
 *   node scripts/har-to-gatling.js --har har/checkout.har \
 *       --simulation CheckoutSimulation \
 *       --base-url https://myapp.example.com \
 *       --out gatling/src/test/java/simulations/CheckoutSimulation.java
 */
'use strict';

const fs   = require('fs');
const path = require('path');

// --- CLI args (simple, no dependency needed) ---
const args = process.argv.slice(2);
function getArg(flag) {
  const i = args.indexOf(flag);
  return i !== -1 ? args[i + 1] : null;
}

const harPath        = getArg('--har');
const simulationName = getArg('--simulation') || 'GeneratedSimulation';
const baseUrl        = getArg('--base-url')   || 'http://localhost:3000';
const outPath        = getArg('--out')         || `gatling/src/test/java/simulations/${simulationName}.java`;

if (!harPath) {
  console.error('Usage: node har-to-gatling.js --har <file> [--simulation Name] [--base-url URL] [--out file]');
  process.exit(1);
}

// --- URL filter: skip third-party and static assets ---
const EXCLUDE_PATTERNS = [
  /google-analytics\.com/,
  /googletagmanager\.com/,
  /doubleclick\.net/,
  /facebook\.net/,
  /hotjar\.com/,
  /\.(css|js|png|jpg|jpeg|gif|svg|ico|woff|woff2|ttf|eot|map)(\?|$)/i,
  /\/(health|ping|metrics)(\/|$)/,
];

function shouldInclude(url) {
  return !EXCLUDE_PATTERNS.some(p => p.test(url));
}

// --- Load HAR ---
const har = JSON.parse(fs.readFileSync(harPath, 'utf8'));
const entries = (har.log || har).entries || [];

// Filter and normalise entries
const filtered = entries
  .filter(e => shouldInclude(e.request.url))
  .map(e => {
    const url   = new URL(e.request.url);
    const relPath = url.pathname + (url.search || '');
    const method  = e.request.method.toUpperCase();
    const status  = (e.response && e.response.status) || 200;
    const body    = e.request.postData ? e.request.postData.text : null;
    const contentType = e.request.postData ? e.request.postData.mimeType : null;
    return { relPath, method, status, body, contentType, name: `${method} ${url.pathname}` };
  });

if (filtered.length === 0) {
  console.error(`No requests passed the filter in ${harPath}. Check BASE_URL and EXCLUDE_PATTERNS.`);
  process.exit(1);
}

// --- Render Java ---
function escapeJava(s) {
  return (s || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

function renderRequest(entry, idx) {
  const lines = [];
  lines.push(`    .exec(`);
  lines.push(`      http("${escapeJava(entry.name)}")`);
  lines.push(`        .${entry.method.toLowerCase()}("${escapeJava(entry.relPath)}")`);
  if (entry.contentType) {
    // Strip any charset suffix for a clean header value
    const ct = entry.contentType.split(';')[0].trim();
    lines.push(`        .header("Content-Type", "${escapeJava(ct)}")`);
  }
  if (entry.body) {
    lines.push(`        .body(StringBody("${escapeJava(entry.body)}"))`);
  }
  // De-duplicate expected status codes (observed code + common success codes)
  const statuses = [...new Set([entry.status, 200, 201, 204])];
  lines.push(`        .check(status().in(${statuses.join(', ')}))`);
  lines.push(`    )`);
  if (idx < filtered.length - 1) {
    lines.push(`    .pause(1, 3)`);
  }
  return lines.join('\n');
}

const requestBlocks = filtered.map(renderRequest).join('\n');

const java = `package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

// Generated from HAR: ${path.basename(harPath)}
public class ${simulationName} extends Simulation {

  private final String baseUrl = System.getenv().getOrDefault("BASE_URL", "${escapeJava(baseUrl)}");
  private final int users = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS", "10"));
  private final int duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "60"));

  private final HttpProtocolBuilder httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .userAgentHeader("Gatling/LoadTest");

  private final ScenarioBuilder journey = scenario("${simulationName}")
${requestBlocks};

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

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, java, 'utf8');
console.log(`Written: ${outPath}  (${filtered.length} requests)`);
