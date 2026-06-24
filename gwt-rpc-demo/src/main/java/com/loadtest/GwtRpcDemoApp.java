package com.loadtest;

import com.loadtest.gwt.GwtXsrfServlet;
import com.loadtest.gwt.MyServiceImpl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Spring Boot host for real GWT-RPC servlets.
 *
 * Endpoints:
 *   POST /api/auth/login          — creates HttpSession (GWT XSRF needs it); returns JWT
 *   GET  /app/mymodule.nocache.js — bootstrap; simulation discovers policy strong name here
 *   POST /app/xsrf                — real XsrfTokenServiceServlet (gwt-servlet.jar)
 *   POST /app/open                — real XsrfProtectedServiceServlet (gwt-servlet.jar)
 *
 * Auth model:
 *   The real enterprise app uses JWT for auth and sessions are not used for
 *   user identity. However, GWT's XsrfTokenServiceServlet derives the XSRF
 *   token from the JSESSIONID session. So login here creates both: an HttpSession
 *   (required by real GWT XSRF internals) and a JWT (what the app actually uses
 *   for authorization). Gatling carries the JSESSIONID cookie automatically per
 *   VU, so XSRF validation works without the simulation doing anything special.
 *
 * Run:  mvn spring-boot:run   (port 8090)
 * Test: BASE_URL=http://localhost:8090 GWT_NOCACHE_JS_PATH=/app/mymodule.nocache.js \
 *       mvn -pl ../gatling gatling:test -Dgatling.simulationClass=simulations.GwtXsrfSimulation
 */
@SpringBootApplication
@RestController
public class GwtRpcDemoApp {

    private static final String DEMO_POLICY = "AABBCCDDEEFF00112233445566778899";

    private static final Map<String, String> USERS = Map.of(
        "user1",  "pass1",  "user2",  "pass2",  "user3",  "pass3",
        "user4",  "pass4",  "user5",  "pass5",  "user6",  "pass6",
        "user7",  "pass7",  "user8",  "pass8",  "user9",  "pass9",
        "user10", "pass10"
    );

    public static void main(String[] args) {
        System.setProperty("server.port", "8090");
        SpringApplication.run(GwtRpcDemoApp.class, args);
    }

    // ── Real GWT-RPC servlet registrations ───────────────────────────────────

    @Bean
    public ServletRegistrationBean<GwtXsrfServlet> xsrfServletBean() {
        return new ServletRegistrationBean<>(new GwtXsrfServlet(), "/app/xsrf");
    }

    @Bean
    public ServletRegistrationBean<MyServiceImpl> myServiceBean() {
        return new ServletRegistrationBean<>(new MyServiceImpl(), "/app/open");
    }

    // XsrfTokenServiceServlet reads this context-param in its init() to know
    // which cookie holds the session ID. Without it the servlet throws on first call.
    @Bean
    public ServletContextInitializer xsrfContextParam() {
        return ctx -> ctx.setInitParameter("gwt.xsrf.session_cookie_name", "JSESSIONID");
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> creds, HttpSession session) {
        String user = creds.getOrDefault("username", "");
        String pass = creds.getOrDefault("password", "");
        if (!USERS.containsKey(user) || !USERS.get(user).equals(pass)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        // Session is required so GWT's XsrfTokenServiceServlet can derive the XSRF
        // token from the JSESSIONID. The JWT is what the app exposes to clients.
        session.setAttribute("username", user);
        return ResponseEntity.ok(Map.of("token", buildDemoJwt(user)));
    }

    // Builds a structurally-valid JWT (header.payload.sig in base64url) so
    // Authorization: Bearer headers look realistic. The signature is not
    // cryptographically valid — the demo GWT endpoints don't verify it.
    private static String buildDemoJwt(String username) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header  = enc.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(
            ("{\"sub\":\"" + username + "\",\"iat\":1700000000}").getBytes(StandardCharsets.UTF_8));
        String sig     = enc.encodeToString("DEMO".getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + sig;
    }

    // ── .nocache.js bootstrap ─────────────────────────────────────────────────

    @GetMapping(value = "/app/mymodule.nocache.js", produces = "application/javascript")
    public String noCacheJs() {
        return "// GWT module bootstrap — policy strong name below\n"
             + "var strongName = '" + DEMO_POLICY + "';\n";
    }
}
