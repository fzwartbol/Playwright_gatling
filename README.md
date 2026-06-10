# Playwright → HAR → Gatling → Tekton

Convert Playwright (Java) end-to-end tests into Gatling Java DSL load simulations, orchestrated by a Tekton pipeline.

**Stack:** Playwright Java bindings · JUnit 5 · Gatling 3.11 Java DSL · Maven · Tekton

## Quick Start

### 1. Install Playwright browsers (once per machine)
```bash
cd playwright
mvn -q compile exec:java -e \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install --with-deps"
```

### 2. Record HAR files
```bash
BASE_URL=https://myapp.example.com mvn test
# HAR files written to ../har/<test-name>.har
```

### 3. Convert to Gatling Java simulation
```bash
node scripts/har-to-gatling.js \
  --har har/checkout.har \
  --simulation CheckoutSimulation \
  --base-url https://myapp.example.com
# Written to gatling/src/test/java/simulations/CheckoutSimulation.java
```

### 4. Run the load test
```bash
cd gatling
BASE_URL=https://myapp.example.com GATLING_USERS=20 GATLING_DURATION=120 \
  mvn gatling:test -Dgatling.simulationClass=simulations.CheckoutSimulation
```

### 5. Run the full pipeline via Tekton
```bash
# One-time setup
kubectl apply -f tekton/pvc.yaml
kubectl apply -f tekton/tasks/
kubectl apply -f tekton/pipeline.yaml

# Edit base-url in tekton/pipelinerun.yaml, then:
kubectl create -f tekton/pipelinerun.yaml
tkn pipelinerun logs --last -f
```

## Writing a new journey

Add a test class in `playwright/src/test/java/tests/` extending `BaseHarTest`:

```java
public class CheckoutTest extends BaseHarTest {

    @Test
    void checkout() {
        startRecording("checkout");      // writes ../har/checkout.har
        page.navigate("/cart");
        page.locator("#checkout-btn").click();
        page.waitForLoadState();
    }
}
```

Run `mvn test -Dtest=CheckoutTest`, then convert the resulting HAR.

## Pipeline topology
```
playwright-record-har → har-to-gatling → gatling-run → archive-report
```

See [CLAUDE.md](CLAUDE.md) for full documentation, URL filter rules, and common failure modes.
