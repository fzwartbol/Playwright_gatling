package com.loadtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo GWT-style Spring Boot application for load test pipeline validation.
 *
 * Auth: JWT (Bearer token) + XSRF token
 * Flow: Login → Form page 1 (name, email) → Form page 2 (address, phone) → GWT-RPC submit
 *
 * For load testing purposes:
 *  - JWT is a static Base64 string (no real signing library needed)
 *  - XSRF token is validated as non-empty only
 *  - GWT-RPC body is parsed by splitting on | (no gwt-servlet dependency)
 */
@SpringBootApplication
@RestController
public class GwtApp {

    public static void main(String[] args) {
        System.setProperty("server.port", "8090");
        SpringApplication.run(GwtApp.class, args);
    }

    // Static DEMO token issued by the WireMock __auth-login.json stub (priority=1).
    // Pre-registering it lets Playwright drive through WireMock in recording mode
    // without needing the login request to reach the real app.
    private static final String DEMO_TOKEN = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJsb2FkdGVzdCIsImlhdCI6MH0.DEMO";
    private static final String DEMO_XSRF  = "static-xsrf-token-for-load-test";

    // Downstream validation service — configured via env var in the loadtest overlay.
    // The overlay sets DOWNSTREAM_SERVICE_URL=http://wiremock.<ns>.svc.cluster.local:8080
    // so the app's outbound calls are intercepted by WireMock.
    private static final String DOWNSTREAM_URL =
        System.getenv().getOrDefault("DOWNSTREAM_SERVICE_URL", "");

    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .build();

    // ── In-memory stores ──────────────────────────────────────────────────
    // Map: token → {username, xsrfToken}
    private final Map<String, Map<String, String>> sessions = new ConcurrentHashMap<>(
        Map.of(DEMO_TOKEN, new HashMap<>(Map.of("username", "loadtest", "xsrfToken", DEMO_XSRF)))
    );
    // Map: token → page1 data
    private final Map<String, Map<String, String>> formData = new ConcurrentHashMap<>();

    private static final Map<String, String> USERS = Map.of(
        "user1", "pass1", "user2", "pass2", "user3", "pass3",
        "user4", "pass4", "user5", "pass5", "user6", "pass6",
        "user7", "pass7", "user8", "pass8", "user9", "pass9",
        "user10", "pass10"
    );

    // ── Helpers ───────────────────────────────────────────────────────────

    private String makeToken(String username) {
        // Static-format JWT (not cryptographically signed — demo only)
        String header  = b64("{'alg':'none'}");
        String payload = b64("{'sub':'" + username + "','iat':" + System.currentTimeMillis() + "}");
        return header + "." + payload + ".DEMO";
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes());
    }

    private Map<String, String> getSession(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return sessions.get(authHeader.substring(7));
    }

    private ResponseEntity<String> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Unauthorized\"}");
    }

    private ResponseEntity<String> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"XSRF token missing or invalid\"}");
    }

    private boolean validXsrf(String xsrfHeader, Map<String, String> session) {
        return xsrfHeader != null && !xsrfHeader.isBlank();
    }

    // ── Public: GET / ─────────────────────────────────────────────────────
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return """
            <!DOCTYPE html>
            <html>
            <head><title>GWT Demo App</title></head>
            <body>
              <h2>Login</h2>
              <div id="error" style="color:red;display:none"></div>
              <input id="username" type="text"     placeholder="Username" /><br>
              <input id="password" type="password" placeholder="Password" /><br>
              <button id="login-btn" onclick="doLogin()">Login</button>
              <script>
                var _jwt = null, _xsrf = null;
                function doLogin() {
                  var u = document.getElementById('username').value;
                  var p = document.getElementById('password').value;
                  fetch('/api/auth/login', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({username: u, password: p})
                  }).then(function(r) {
                    if (!r.ok) { document.getElementById('error').style.display='block';
                                 document.getElementById('error').textContent='Invalid credentials'; return; }
                    return r.json();
                  }).then(function(d) {
                    if (!d) return;
                    _jwt  = d.token;
                    _xsrf = d.xsrfToken;
                    window.location.href = '/form/page1?t=' + encodeURIComponent(_jwt)
                      + '&x=' + encodeURIComponent(_xsrf);
                  });
                }
              </script>
            </body>
            </html>
            """;
    }

    // ── Auth: POST /api/auth/login ────────────────────────────────────────
    @PostMapping(value = "/api/auth/login",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> creds) {
        String user = creds.getOrDefault("username", "");
        String pass = creds.getOrDefault("password", "");
        if (!USERS.containsKey(user) || !USERS.get(user).equals(pass)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        String token     = makeToken(user);
        String xsrfToken = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new HashMap<>(Map.of("username", user, "xsrfToken", xsrfToken)));
        return ResponseEntity.ok(Map.of("token", token, "xsrfToken", xsrfToken));
    }

    // ── Form page 1: GET /form/page1 ──────────────────────────────────────
    @GetMapping(value = "/form/page1", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> formPage1(
            @RequestParam(required = false) String t,
            @RequestParam(required = false) String x,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        // Accept JWT from either header or query param (Playwright uses header after first nav)
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : t;
        if (token == null || !sessions.containsKey(token)) return ResponseEntity.status(401).body("Unauthorized");
        Map<String, String> session = sessions.get(token);

        return ResponseEntity.ok("""
            <!DOCTYPE html>
            <html>
            <head><title>Form - Step 1 of 2</title>
              <meta name="xsrf-token" content="%s">
            </head>
            <body>
              <h2>Step 1: Personal details</h2>
              <div id="page1-form">
                <input id="name"  type="text" placeholder="Full name" /><br>
                <input id="email" type="email" placeholder="Email" /><br>
                <button id="next-btn" onclick="submitPage1()">Next →</button>
              </div>
              <script>
                var _jwt  = '%s';
                var _xsrf = '%s';
                function submitPage1() {
                  var name  = document.getElementById('name').value;
                  var email = document.getElementById('email').value;
                  fetch('/form/page2', {
                    method: 'POST',
                    headers: {
                      'Content-Type': 'application/json',
                      'Authorization': 'Bearer ' + _jwt,
                      'X-XSRF-Token': _xsrf
                    },
                    body: JSON.stringify({name: name, email: email})
                  }).then(function(r) { return r.text(); })
                    .then(function(html) { document.open(); document.write(html); document.close(); });
                }
              </script>
            </body>
            </html>
            """.formatted(session.get("xsrfToken"), token, session.get("xsrfToken")));
    }

    // ── Form page 2: POST /form/page2 ─────────────────────────────────────
    @PostMapping(value = "/form/page2",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> formPage2(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-XSRF-Token", required = false) String xsrf,
            @RequestBody Map<String, String> page1Data) {
        Map<String, String> session = getSession(auth);
        if (session == null) return ResponseEntity.status(401).body("Unauthorized");
        if (!validXsrf(xsrf, session)) return ResponseEntity.status(403).body("XSRF invalid");

        String token = auth.substring(7);
        formData.put(token, new HashMap<>(page1Data));
        // Evict stale entries from prior sessions to avoid unbounded growth
        if (formData.size() > 10_000) formData.clear();

        return ResponseEntity.ok("""
            <!DOCTYPE html>
            <html>
            <head><title>Form - Step 2 of 2</title>
              <meta name="xsrf-token" content="%s">
            </head>
            <body>
              <h2>Step 2: Address details</h2>
              <p>Name: %s | Email: %s</p>
              <div id="page2-form">
                <input id="address" type="text" placeholder="Street address" /><br>
                <input id="phone"   type="tel"  placeholder="Phone number" /><br>
                <button id="submit-btn" onclick="submitForm()">Submit ✓</button>
              </div>
              <script>
                var _jwt  = '%s';
                var _xsrf = '%s';
                function submitForm() {
                  var address = document.getElementById('address').value;
                  var phone   = document.getElementById('phone').value;
                  // GWT-RPC wire format: 7|0|<n>|<moduleBase>|<service>|<method>|<types>|<params>|
                  var moduleBase = window.location.origin + '/app/';
                  var rpcBody = '7|0|7|' + moduleBase
                    + '|com.loadtest.gwt.client.FormSubmitService'
                    + '|java.lang.String|submit'
                    + '|java.lang.String/2004016611|java.lang.String/2004016611|java.lang.String/2004016611'
                    + '|1|2|3|4|3|5|6|7|'
                    + encodeRpc(address) + '|'
                    + encodeRpc(phone)   + '|'
                    + encodeRpc('%s')    + '|';
                  fetch('/app/FormSubmitService', {
                    method: 'POST',
                    headers: {
                      'Content-Type': 'text/x-gwt-rpc; charset=utf-8',
                      'Authorization': 'Bearer ' + _jwt,
                      'X-XSRF-Token':  _xsrf,
                      'X-GWT-Module-Base': moduleBase,
                      'X-GWT-Permutation': 'STRONGNAME'
                    },
                    body: rpcBody
                  }).then(function(r) { return r.text(); })
                    .then(function(body) {
                      if (body.startsWith('//OK')) {
                        window.location.href = '/form/confirmation?t=' + encodeURIComponent(_jwt);
                      } else {
                        alert('Submit failed: ' + body);
                      }
                    });
                }
                function encodeRpc(s) { return s ? s.replace(/\\|/g, '\\\\|') : ''; }
              </script>
            </body>
            </html>
            """.formatted(
                session.get("xsrfToken"),
                page1Data.getOrDefault("name",  ""),
                page1Data.getOrDefault("email", ""),
                token,
                session.get("xsrfToken"),
                page1Data.getOrDefault("name",  "")
            ));
    }

    // ── GWT-RPC servlet: POST /app/FormSubmitService ──────────────────────
    @PostMapping(value = "/app/FormSubmitService",
                 consumes = "text/x-gwt-rpc",
                 produces = "text/plain")
    public ResponseEntity<String> gwtRpcSubmit(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-XSRF-Token", required = false) String xsrf,
            @RequestBody String rpcPayload) {
        Map<String, String> session = getSession(auth);
        if (session == null) return ResponseEntity.status(401).body("//EX[\"Unauthorized\",1]");
        if (!validXsrf(xsrf, session)) return ResponseEntity.status(403).body("//EX[\"XSRF invalid\",1]");

        // Parse GWT-RPC payload: fields are pipe-separated
        // Format: 7|0|7|<moduleBase>|<service>|<retType>|<method>|<types...>|1|2|3|4|3|<typeRefs>|<params>|
        String[] parts = rpcPayload.split("\\|");
        // Params start at index (7 + typeCount) where typeCount = int(parts[2]) - 4
        // For our fixed format they are at indices len-4, len-3, len-2 (address, phone, name)
        String token2 = auth.substring(7);
        formData.remove(token2); // evict page1 data — no longer needed
        String confirmationId = "CONF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Call downstream validation service if configured.
        // In the loadtest overlay DOWNSTREAM_SERVICE_URL points to WireMock, which
        // either proxies to the real validation service (recording) or replays stubs (load test).
        if (!DOWNSTREAM_URL.isEmpty()) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(DOWNSTREAM_URL + "/api/validate"))
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"submissionId\":\"" + confirmationId + "\"}"))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                System.out.println("Downstream /api/validate → HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (Exception e) {
                System.err.println("Downstream call failed: " + e.getMessage());
            }
        }

        // GWT-RPC success: //OK[<result>,1]
        return ResponseEntity.ok("//OK[\"" + confirmationId + "\",1]");
    }

    // ── Confirmation: GET /form/confirmation ──────────────────────────────
    @GetMapping(value = "/form/confirmation", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirmation(
            @RequestParam(required = false) String t,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : t;
        if (token == null || !sessions.containsKey(token)) return ResponseEntity.status(401).body("Unauthorized");

        return ResponseEntity.ok("""
            <!DOCTYPE html>
            <html>
            <head><title>Form Submitted</title></head>
            <body>
              <div id="confirmation">
                <h2>✓ Form submitted successfully!</h2>
                <p>Thank you. Your form has been received.</p>
                <a href="/">Submit another</a>
              </div>
            </body>
            </html>
            """);
    }
}
