package tests;

import org.junit.jupiter.api.Test;

/**
 * Example of a journey that requires login before interacting with the app.
 *
 * Playwright records the full HTTP traffic including the login POST, the
 * Set-Cookie response headers (JSESSIONID + XSRF-TOKEN), and all subsequent
 * authenticated requests. The resulting HAR is then converted by
 * scripts/har-to-gatling.js into a Java simulation where:
 *
 *   - Cookie headers are stripped   → Gatling's per-VU cookie jar handles them
 *   - XSRF-TOKEN is extracted once  → stored in the VU session as #{xsrfToken}
 *   - Credentials come from a feeder → each VU logs in with unique credentials
 */
public class AuthenticatedTest extends BaseHarTest {

  @Test
  void authenticatedCheckoutJourney() {
    startRecording("authenticated-checkout");

    // ── Login ────────────────────────────────────────────────────────────────
    // Playwright records the POST body and the Set-Cookie response.
    // In the converted simulation this becomes the login exec() with
    // .check(cookieValue("XSRF-TOKEN").saveAs("xsrfToken")).
    page.navigate("/");
    page.locator("#username").fill("user1");
    page.locator("#password").fill("pass1");
    page.locator("button[type=submit]").click();
    page.waitForURL("**/dashboard");

    // ── Authenticated pages ──────────────────────────────────────────────────
    // Every fetch here carries the session cookie set during login.
    // The XSRF token is sent as a header by the front-end JS on state-changing
    // requests — Playwright captures the header so the converter can emit it.
    page.navigate("/profile");
    page.waitForLoadState();

    page.waitForResponse("**/api/cart", () -> page.locator(".add-to-cart").first().click());
  }
}
