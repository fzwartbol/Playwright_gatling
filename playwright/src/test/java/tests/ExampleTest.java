package tests;

import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Example user journeys. Each test produces one HAR file used to generate a
 * Gatling simulation. Replace the steps with your real application flows.
 */
public class ExampleTest extends BaseHarTest {

  @Test
  void homepageAndSearch() {
    startRecording("homepage-and-search");

    page.navigate("/");
    // Every HTTP request triggered below is captured in the HAR.

    // Example search interaction — guarded so it works even on a bare page.
    var search = page.locator("input[type=search], input[name=q]").first();
    if (search.count() > 0 && search.isVisible()) {
      search.fill("test product");
      search.press("Enter");
      page.waitForLoadState();
    }
  }

  @Test
  void checkout() {
    startRecording("checkout");

    page.navigate("/");
    // Add your checkout steps here — each request is recorded.
    page.waitForLoadState();
  }
}
