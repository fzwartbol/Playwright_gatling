package tests;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.HarContentPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Paths;

/**
 * Base class for HAR-recording Playwright tests (Java bindings).
 *
 * Each test calls {@link #startRecording(String)} to open a browser context
 * that writes a HAR file to ../har/<name>.har. Closing the context (in
 * {@link #stopRecording()}) flushes the HAR to disk. Those HAR files are then
 * converted into Gatling Java simulations by scripts/har-to-gatling.js.
 */
public abstract class BaseHarTest {

  protected static final String BASE_URL =
      System.getenv().getOrDefault("BASE_URL", "http://localhost:3000");

  static Playwright playwright;
  static Browser browser;

  protected BrowserContext context;
  protected Page page;

  @BeforeAll
  static void launchBrowser() {
    playwright = Playwright.create();
    browser = playwright.chromium().launch(
        new BrowserType.LaunchOptions().setHeadless(true));
  }

  @AfterAll
  static void closeBrowser() {
    if (browser != null) browser.close();
    if (playwright != null) playwright.close();
  }

  /** Opens a context that records a HAR file named <harName>.har in ../har/. */
  protected void startRecording(String harName) {
    context = browser.newContext(new Browser.NewContextOptions()
        .setBaseURL(BASE_URL)
        .setRecordHarPath(Paths.get("../har/" + harName + ".har"))
        // EMBED captures full response payloads inline in the HAR so WireMock can replay them
        .setRecordHarContent(HarContentPolicy.EMBED));
    page = context.newPage();
  }

  @AfterEach
  void stopRecording() {
    // Closing the context flushes the recorded HAR to disk
    if (context != null) context.close();
  }
}
