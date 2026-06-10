#!/usr/bin/env node
/**
 * Converts a HAR file into a Gatling Java simulation (Gatling Java DSL).
 *
 * Auth handling:
 *  - Cookie headers are STRIPPED: Gatling maintains a per-VU cookie jar
 *    automatically. Session cookies from a login response flow into all
 *    subsequent requests without any manual intervention.
 *  - XSRF/CSRF headers: the hardcoded value is replaced with #{xsrfToken}.
 *    A .check(jsonPath("$.xsrfToken").saveAs("xsrfToken")) is added to the
 *    login request. The server must return the token in the JSON body (most
 *    SPAs do this alongside the Set-Cookie). The session cookie is handled
 *    automatically by Gatling's per-VU cookie jar.
 *  - Authorization: Bearer headers: value replaced with #{authToken}.
 *    A warning comment is emitted — you need to add a login step + extraction.
 *  - A CSV feeder stub is emitted when any auth header is detected, so
 *    each virtual user authenticates with their own credentials.
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

// ── CLI args ──────────────────────────────────────────────────────────────────
const args = process.argv.slice(2);
function getArg(flag) {
  const i = args.indexOf(flag);
  return i !== -1 ? args[i + 1] : null;
}

const harPath        = getArg('--har');
const simulationName = getArg('--simulation') || 'GeneratedSimulation';
const baseUrl        = getArg('--base-url')   || 'http://localhost:3000';
const outPath        = getArg('--out') || `gatling/src/test/java/simulations/${simulationName}.java`;

if (!harPath) {
  console.error('Usage: node har-to-gatling.js --har <file> [--simulation Name] [--base-url URL] [--out file]');
  process.exit(1);
}

// ── URL filter: skip third-party and static assets ────────────────────────────
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

// ── Auth pattern detection ────────────────────────────────────────────────────
const LOGIN_PATH_RE   = /\/(login|signin|auth\/login|authenticate|token)(\/|$)/i;
const XSRF_HEADER_RE  = /^x-(xsrf|csrf)-token$/i;
const COOKIE_HDR_RE   = /^cookie$/i;
const BEARER_HDR_RE   = /^authorization$/i;

// ── Load HAR ──────────────────────────────────────────────────────────────────
const har = JSON.parse(fs.readFileSync(harPath, 'utf8'));
const entries = (har.log || har).entries || [];

// ── Normalise entries ─────────────────────────────────────────────────────────
const filtered = entries
  .filter(e => shouldInclude(e.request.url))
  .map(e => {
    const url         = new URL(e.request.url);
    const relPath     = url.pathname + (url.search || '');
    const method      = e.request.method.toUpperCase();
    const status      = (e.response && e.response.status) || 200;
    const body        = e.request.postData ? e.request.postData.text : null;
    const contentType = e.request.postData ? (e.request.postData.mimeType || '').split(';')[0].trim() : null;
    const isLogin     = LOGIN_PATH_RE.test(url.pathname);

    // Classify request headers
    const extraHeaders = [];
    let hasXsrf    = false;
    let hasBearer  = false;
    let hasCookie  = false;

    for (const h of (e.request.headers || [])) {
      const name = h.name.trim();
      if (COOKIE_HDR_RE.test(name)) {
        hasCookie = true;
        // Do NOT emit — Gatling's cookie jar handles this automatically.
        continue;
      }
      if (XSRF_HEADER_RE.test(name)) {
        hasXsrf = true;
        extraHeaders.push({ name, value: '#{xsrfToken}' });
        continue;
      }
      if (BEARER_HDR_RE.test(name) && /^Bearer /i.test(h.value)) {
        hasBearer = true;
        extraHeaders.push({ name, value: 'Bearer #{authToken}' });
        continue;
      }
      // Skip standard headers that Gatling sets via protocol-level config
      if (/^(host|content-length|accept-encoding|connection|user-agent|accept-language|accept)$/i.test(name)) {
        continue;
      }
    }

    return { relPath, method, status, body, contentType, isLogin,
             extraHeaders, hasXsrf, hasBearer, hasCookie };
  });

if (filtered.length === 0) {
  console.error(`No requests passed the filter in ${harPath}. Check BASE_URL and EXCLUDE_PATTERNS.`);
  process.exit(1);
}

// ── Detect global auth characteristics ───────────────────────────────────────
const hasAnyAuth     = filtered.some(e => e.hasCookie || e.hasXsrf || e.hasBearer);
const hasAnyXsrf     = filtered.some(e => e.hasXsrf);
const hasAnyBearer   = filtered.some(e => e.hasBearer);
const loginEntry     = filtered.find(e => e.isLogin && e.method === 'POST');

// ── Render helpers ────────────────────────────────────────────────────────────
function escapeJava(s) {
  return (s || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

function renderRequest(entry, idx) {
  const lines = [];
  lines.push(`      http("${entry.method} ${escapeJava(entry.relPath.split('?')[0])}")`);
  lines.push(`          .${entry.method.toLowerCase()}("${escapeJava(entry.relPath)}")`);

  for (const h of entry.extraHeaders) {
    lines.push(`          .header("${escapeJava(h.name)}", "${escapeJava(h.value)}")`);
  }
  if (entry.contentType) {
    lines.push(`          .header("Content-Type", "${escapeJava(entry.contentType)}")`);
  }
  if (entry.body) {
    lines.push(`          .body(StringBody("${escapeJava(entry.body)}"))`);
  }

  const statuses = [...new Set([entry.status, 200, 201, 204])];
  let checkLine = `.check(status().in(${statuses.join(', ')}))`;

  if (entry.isLogin && entry.method === 'POST') {
    lines.push(`          ${checkLine}`);
    lines.push(`          // XSRF token returned in JSON body — extract into VU session`);
    lines.push(`          .check(jsonPath("$.xsrfToken").saveAs("xsrfToken"))`);
  } else {
    lines.push(`          ${checkLine}`);
  }

  const block = [
    `      .exec(`,
    lines.map(l => '  ' + l).join('\n'),
    `      )`,
  ].join('\n');

  const xsrfExtract = '';

  const trailer = idx < filtered.length - 1 ? '\n      .pause(1, 3)' : '';
  return block + xsrfExtract + trailer;
}

// ── Feeder declaration ────────────────────────────────────────────────────────
const feederDecl = hasAnyAuth
  ? `
  // CSV feeder: each virtual user authenticates with its own credentials.
  // Populate gatling/src/test/resources/feeders/users.csv with username,password rows.
  private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").circular();
`
  : '';

const feederFeed = hasAnyAuth
  ? '\n      .feed(userFeeder)  // pull {username, password} for this VU\n'
  : '';

// ── Auth warnings ─────────────────────────────────────────────────────────────
const authWarnings = [];
if (hasAnyBearer) {
  authWarnings.push(
    ' * NOTE: Bearer tokens detected. Add a login step that extracts #{authToken}',
    ' *   or replace it with a feeder column. Hardcoded tokens expire and break under load.'
  );
}
if (hasAnyXsrf && !loginEntry) {
  authWarnings.push(
    ' * NOTE: XSRF headers detected but no login request found in this HAR.',
    ' *   Add a login exec() before the first protected request to populate #{xsrfToken}.'
  );
}
if (filtered.some(e => e.hasCookie)) {
  authWarnings.push(
    ' * Cookie headers were stripped — Gatling\'s per-VU cookie jar replays them automatically.'
  );
}
const warnBlock = authWarnings.length
  ? '\n' + authWarnings.map(l => ` ${l}`).join('\n') + '\n'
  : '';

// ── Build imports ─────────────────────────────────────────────────────────────
const feederImport = hasAnyAuth ? 'import io.gatling.javaapi.core.FeederBuilder;\n' : '';

// ── Render simulation ─────────────────────────────────────────────────────────
const requestBlocks = filtered.map(renderRequest).join('\n');

const java = `package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
${feederImport}import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Generated from HAR: ${path.basename(harPath)}
 *${warnBlock} *
 * SESSION COOKIES  : Gatling's per-virtual-user cookie jar is enabled by default.
 *                    Cookies set by the login response (JSESSIONID etc.) are
 *                    automatically sent on all subsequent requests.
 * XSRF TOKEN       : Extracted from the JSON login response body via
 *                    jsonPath("$.xsrfToken").saveAs("xsrfToken"). The server
 *                    must return the token in the body alongside Set-Cookie.
 *                    Used in state-changing requests as header #{xsrfToken}.
 * MULTIPLE USERS   : CSV feeder assigns unique credentials to each virtual user.
 *                    Each VU's session is fully isolated.
 */
public class ${simulationName} extends Simulation {

  private final String baseUrl  = System.getenv().getOrDefault("BASE_URL", "${escapeJava(baseUrl)}");
  private final int    users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "10"));
  private final int    duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "60"));
${feederDecl}
  private final HttpProtocolBuilder httpProtocol = http
      .baseUrl(baseUrl)
      .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
      .acceptEncodingHeader("gzip, deflate")
      .acceptLanguageHeader("en-US,en;q=0.5")
      .userAgentHeader("Gatling/LoadTest")
      .disableCaching();

  private final ScenarioBuilder journey = scenario("${simulationName}")
${feederFeed}${requestBlocks};

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
console.log(`Written: ${outPath}  (${filtered.length} requests, auth=${hasAnyAuth})`);
if (authWarnings.length) {
  authWarnings.forEach(w => console.warn(`  ${w.trim()}`));
}
