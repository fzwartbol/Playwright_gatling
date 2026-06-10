#!/usr/bin/env node
/**
 * Converts a HAR file into a Gatling Scala simulation.
 *
 * Usage:
 *   node scripts/har-to-gatling.js --har har/checkout.har \
 *       --simulation CheckoutSimulation \
 *       --base-url https://myapp.example.com \
 *       --out gatling/src/test/scala/simulations/CheckoutSimulation.scala
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
const outPath        = getArg('--out')         || `gatling/src/test/scala/simulations/${simulationName}.scala`;

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
    return { relPath, method, status, body, name: `${method} ${url.pathname}` };
  });

if (filtered.length === 0) {
  console.error(`No requests passed the filter in ${harPath}. Check BASE_URL and EXCLUDE_PATTERNS.`);
  process.exit(1);
}

// --- Render Scala ---
function escapeScala(s) {
  return (s || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

function renderRequest(entry, idx) {
  const lines = [];
  lines.push(`    .exec(`);
  lines.push(`      http("${escapeScala(entry.name)}")`);
  lines.push(`        .${entry.method.toLowerCase()}("${escapeScala(entry.relPath)}")`);
  if (entry.body) {
    lines.push(`        .body(StringBody("${escapeScala(entry.body)}"))`);
  }
  lines.push(`        .check(status.in(${entry.status}, 200, 201, 204))`);
  lines.push(`    )`);
  if (idx < filtered.length - 1) {
    lines.push(`    .pause(1, 3)`);
  }
  return lines.join('\n');
}

const requestBlocks = filtered.map(renderRequest).join('\n');

const scala = `package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

// Generated from HAR: ${path.basename(harPath)}
class ${simulationName} extends Simulation {

  val baseUrl: String = sys.env.getOrElse("BASE_URL", "${baseUrl}")
  val users: Int      = sys.env.getOrElse("GATLING_USERS", "10").toInt
  val duration: Int   = sys.env.getOrElse("GATLING_DURATION", "60").toInt

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .userAgentHeader("Gatling/LoadTest")

  val journey = scenario("${simulationName}")
${requestBlocks}

  setUp(
    journey.inject(
      rampUsersPerSec(1).to(users).during(duration.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.max.lt(5000),
      global.successfulRequests.percent.gte(95)
    )
}
`;

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, scala, 'utf8');
console.log(`Written: ${outPath}  (${filtered.length} requests)`);
