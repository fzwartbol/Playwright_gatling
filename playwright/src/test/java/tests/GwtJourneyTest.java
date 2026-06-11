package tests;

import org.junit.jupiter.api.Test;

/**
 * Two-page GWT form journey recorded through WireMock proxy.
 *
 * Traffic flow:
 *   Playwright (BASE_URL=http://localhost:8080)
 *       → WireMock (proxy+recording mode)
 *           → Real GWT app (http://localhost:8090)
 *
 * After recording stops, Gatling uses the same BASE_URL against WireMock
 * in replay mode — the real app is no longer needed.
 */
public class GwtJourneyTest extends BaseHarTest {

    @Test
    void twoPageFormJourney() {
        startRecording("gwt-form-journey");

        // ── 1. Login page ──────────────────────────────────────────────────
        page.navigate("/");
        page.fill("#username", "user1");
        page.fill("#password", "pass1");
        page.click("#login-btn");
        page.waitForSelector("#page1-form");

        // ── 2. Form page 1: personal details ──────────────────────────────
        page.fill("#name",  "John Load");
        page.fill("#email", "john@loadtest.com");
        page.click("#next-btn");
        page.waitForSelector("#page2-form");

        // ── 3. Form page 2: address details → GWT-RPC submit ──────────────
        page.fill("#address", "123 Test Street");
        page.fill("#phone",   "0612345678");
        page.click("#submit-btn");
        page.waitForSelector("#confirmation");
    }
}
