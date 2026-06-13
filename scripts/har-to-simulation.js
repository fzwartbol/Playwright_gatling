#!/usr/bin/env node
/**
 * har-to-simulation.js  <input.har>  <ClassName>  [outputDir]
 *
 * Generates a Gatling Java DSL simulation from a Playwright HAR recording.
 * The simulation targets the REAL APPLICATION under load; WireMock stubs
 * the app's downstream service calls (it does not sit in front of the app).
 *
 * Smart features:
 *  - Detects login requests (JSON body with username+password) and replaces
 *    credentials with #{username}/#{password} from a CSV feeder so every
 *    virtual user logs in with unique credentials.
 *  - Detects auth tokens in login JSON responses (token, xsrfToken, etc.)
 *    and adds .check(jsonPath(...).saveAs(...)) to capture them per VU.
 *  - Replaces all occurrences of the recorded token values in subsequent
 *    request URLs, headers, and bodies with #{varName} session references
 *    so every VU uses its own freshly-issued token.
 *  - Replaces the recording base URL in header values with Gatling's
 *    baseUrl field so the simulation is portable across environments.
 */

'use strict';

const fs   = require('fs');
const path = require('path');

const harFile   = process.argv[2];
const className = process.argv[3] || 'RecordedSimulation';
const outDir    = process.argv[4] || '.';

if (!harFile) {
  console.error('Usage: har-to-simulation.js <input.har> [ClassName] [outputDir]');
  process.exit(1);
}

// ── Parse HAR ────────────────────────────────────────────────────────────────

const har = JSON.parse(fs.readFileSync(harFile, 'utf8'));

// ── Filters ──────────────────────────────────────────────────────────────────

const SKIP_EXT  = /\.(css|js|mjs|map|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)(\?.*)?$/i;
const SKIP_PATH = /^\/(favicon|robots\.txt|sitemap)/i;

const entries = har.log.entries.filter(e => {
  let url;
  try { url = new URL(e.request.url); } catch { return false; }
  if (e.response.status === 0 || e.response.status >= 500) return false;
  if (SKIP_EXT.test(url.pathname))  return false;
  if (SKIP_PATH.test(url.pathname)) return false;
  return true;
});

if (entries.length === 0) {
  console.error('No requests survived filtering.');
  process.exit(1);
}

// Recording base URL — used to make generated headers portable (replaced with baseUrl)
const recordingOrigin = new URL(entries[0].request.url).origin;

// ── Auth token detection ──────────────────────────────────────────────────────

const TOKEN_KEY = /^(token|accessToken|access_token|jwt|jwtToken|xsrfToken|xsrf_token|csrfToken|csrf_token|refreshToken|id_token|idToken|sessionToken|authToken|auth_token)$/i;

function parseJson(text) {
  try { return JSON.parse(text); } catch { return null; }
}

function isJsonContentType(headers) {
  const ct = (headers || []).find(h => h.name.toLowerCase() === 'content-type');
  return ct && ct.value.includes('application/json');
}

// Map: literal token value (from HAR) → session variable name (Gatling EL)
const tokenValues = new Map(); // string → string

for (const entry of entries) {
  if (!isJsonContentType(entry.response.headers)) continue;
  const body = parseJson(entry.response.content && entry.response.content.text);
  if (!body || typeof body !== 'object' || Array.isArray(body)) continue;

  for (const [key, val] of Object.entries(body)) {
    if (!TOKEN_KEY.test(key)) continue;
    if (typeof val !== 'string' || val.length < 8) continue;
    if (!tokenValues.has(val)) {
      tokenValues.set(val, key);   // e.g. "eyJ..." → "token"
    }
  }
}

// ── Login body detection ──────────────────────────────────────────────────────

function credentialFields(postData) {
  if (!postData || !postData.text) return null;
  const body = parseJson(postData.text);
  if (!body) return null;
  const userKey = ['username','user','login','email'].find(k => k in body);
  const passKey = ['password','pass','passwd','secret'].find(k => k in body);
  if (userKey && passKey) return { userKey, passKey };
  return null;
}

const hasLogin = entries.some(e => credentialFields(e.request.postData) !== null);

// ── Text replacement helpers ──────────────────────────────────────────────────

function replaceAll(str, find, replace) {
  return str.split(find).join(replace);
}

// Replace all known token values with #{varName}
function substituteTokens(text) {
  let out = text;
  for (const [val, varName] of tokenValues.entries()) {
    out = replaceAll(out, val, `#{${varName}}`);
  }
  return out;
}

// Replace the recording base URL in header/body strings so the simulation
// is portable: e.g. "http://localhost:8090/app/" → baseUrl + "/app/"
// Returns [prefix, suffix] so the caller can decide how to emit Java.
function substituteBaseUrl(text) {
  return replaceAll(text, recordingOrigin, '__BASEURL__');
}

// ── Java string escaping ──────────────────────────────────────────────────────

function esc(s) {
  return String(s)
    .replace(/\\/g, '\\\\')
    .replace(/"/g,  '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
    // Null bytes and other control characters that would produce uncompilable Java
    .replace(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, m =>
      `\\u${m.charCodeAt(0).toString(16).padStart(4, '0')}`
    );
}

// Emit a Java string literal that may contain __BASEURL__ (replaced with baseUrl field)
function javaStr(s) {
  if (!s.includes('__BASEURL__')) {
    return `"${esc(s)}"`;
  }
  const parts = s.split('__BASEURL__');
  return parts.map(p => `"${esc(p)}"`).join(' + baseUrl + ');
}

// ── Headers to forward ───────────────────────────────────────────────────────

const KEEP_HEADERS = new Set([
  'authorization', 'x-xsrf-token', 'x-gwt-module-base',
  'x-gwt-permutation', 'x-requested-with', 'accept',
]);

// ── Build one exec block ──────────────────────────────────────────────────────

function buildExec(entry, stepNum) {
  const req    = entry.request;
  const resp   = entry.response;
  const url    = new URL(req.url);
  const method = req.method.toLowerCase();
  const label  = `${req.method} ${url.pathname}`;

  // Apply token substitution then base-URL substitution to the relative URL
  const relUrl = substituteBaseUrl(substituteTokens(url.pathname + (url.search || '')));

  const lines = [];
  lines.push(`        // ── Step ${stepNum}: ${label}`);
  lines.push(`        .exec(`);
  lines.push(`            http("${esc(label)}")`);
  lines.push(`                .${method}(${javaStr(relUrl)})`);

  // Request headers (skip Content-Type — emitted with body)
  for (const h of req.headers) {
    const lo = h.name.toLowerCase();
    if (lo === 'content-type') continue;
    if (!KEEP_HEADERS.has(lo))  continue;
    const val = substituteBaseUrl(substituteTokens(h.value));
    lines.push(`                .header("${esc(h.name)}", ${javaStr(val)})`);
  }

  // Request body
  if (req.postData && req.postData.text && req.postData.text.trim()) {
    const ct = req.headers.find(h => h.name.toLowerCase() === 'content-type');
    if (ct) {
      lines.push(`                .header("Content-Type", "${esc(ct.value.split(';')[0].trim())}")`);
    }

    let bodyText = req.postData.text;

    // Login parameterisation: replace literal credentials with feeder vars
    const creds = credentialFields(req.postData);
    if (creds) {
      const body = parseJson(bodyText);
      body[creds.userKey] = '#{username}';
      body[creds.passKey] = '#{password}';
      bodyText = JSON.stringify(body);
    }

    bodyText = substituteBaseUrl(substituteTokens(bodyText));
    lines.push(`                .body(StringBody(${javaStr(bodyText)}))`);
  }

  // Status check
  lines.push(`                .check(status().is(${resp.status}))`);

  // Token extraction from login response
  if (isJsonContentType(resp.headers)) {
    const body = parseJson(resp.content && resp.content.text);
    if (body && typeof body === 'object' && !Array.isArray(body)) {
      for (const [key, val] of Object.entries(body)) {
        if (!TOKEN_KEY.test(key)) continue;
        if (typeof val !== 'string' || val.length < 8) continue;
        lines.push(`                .check(jsonPath("$.${key}").saveAs("${key}"))`);
      }
    }
  }

  lines.push(`        )`);
  return lines.join('\n');
}

// ── Pauses from HAR timing ────────────────────────────────────────────────────

function buildPause(a, b) {
  if (!b) return null;
  const gapMs = new Date(b.startedDateTime).getTime()
              - new Date(a.startedDateTime).getTime()
              - a.time;
  const secs = Math.min(Math.max(0, Math.round(gapMs / 1000)), 5);
  return secs >= 1 ? `        .pause(${secs})` : null;
}

// ── Assemble steps ────────────────────────────────────────────────────────────

const steps = [];
entries.forEach((e, i) => {
  steps.push(buildExec(e, i + 1));
  const p = buildPause(e, entries[i + 1]);
  if (p) steps.push(p);
});

// ── Render Java class ─────────────────────────────────────────────────────────

const feederLine = hasLogin
  ? `\n    private final FeederBuilder<String> userFeeder = csv("feeders/users.csv").random();\n`
  : '';
const feedStep = hasLogin
  ? `\n        .feed(userFeeder)\n`
  : '\n';

const tokenSummary = tokenValues.size
  ? `  Auth tokens detected and extracted per VU: ${[...tokenValues.values()].join(', ')}\n *`
  : '';
const loginSummary = hasLogin
  ? `  Login parameterised with #{username}/#{password} from feeders/users.csv\n *`
  : '';

const java = `package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Auto-generated from ${path.basename(harFile)} — DO NOT EDIT (regenerated each pipeline run)
 *
 * Traffic flow:   Gatling → real app → WireMock (downstream stubs)
 * ${entries.length} steps recorded from Playwright journey.
 * ${tokenSummary}${loginSummary} */
public class ${className} extends Simulation {

    private final String baseUrl  = System.getenv().getOrDefault("BASE_URL",         "http://localhost:8090");
    private final int    users    = Integer.parseInt(System.getenv().getOrDefault("GATLING_USERS",    "10"));
    private final int    duration = Integer.parseInt(System.getenv().getOrDefault("GATLING_DURATION", "60"));

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .disableCaching();
${feederLine}
    private final ScenarioBuilder journey = scenario("${className}")
${feedStep}
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

console.log(`Generated: ${outFile}`);
console.log(`  ${entries.length} steps`);
if (tokenValues.size) console.log(`  Auth tokens: ${[...tokenValues.values()].join(', ')} → per-VU via .saveAs()`);
if (hasLogin)         console.log(`  Login → #{username}/#{password} from feeders/users.csv`);
