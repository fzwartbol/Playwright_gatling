# CLAUDE.md — Playwright → HAR → Gatling → Tekton Pipeline

## Project Purpose
Convert Playwright end-to-end tests into Gatling load simulations, orchestrated by Tekton pipelines.
Flow: Playwright (Node.js) records HAR files → `har-to-gatling` converts to Scala simulations → Gatling (Maven) runs load tests → Tekton pipeline glues it all together.

## Repository Layout
```
.
├── playwright/          # Playwright test suite (Java bindings + JUnit 5)
│   ├── src/test/java/tests/   # *Test.java journeys (extend BaseHarTest)
│   └── pom.xml
├── gatling/             # Gatling Maven project (Java DSL simulations)
│   ├── src/
│   │   └── test/
│   │       ├── java/    # Generated + hand-edited simulations (simulations/*.java)
│   │       └── resources/
│   │           └── gatling.conf
│   └── pom.xml
├── har/                 # HAR output directory (gitignored)
├── tekton/              # Tekton Tasks and Pipeline YAML
│   ├── tasks/
│   └── pipeline.yaml
└── CLAUDE.md
```

## Key Commands

### Playwright (Java)
```bash
cd playwright
# One-time: install browser binaries used by the Playwright Java bindings
mvn -q compile exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --with-deps"
# Record HAR files (all tests) — HAR written to ../har/<name>.har
BASE_URL=https://myapp.example.com mvn test
# Record HAR for a single test class or method
BASE_URL=https://myapp.example.com mvn test -Dtest=ExampleTest#checkout
```

### HAR Inspection
```bash
# Inspect a HAR file (pretty-print, filter by URL)
node -e "const h=require('./har/checkout.har'); h.log.entries.filter(e=>e.request.url.includes('api')).forEach(e=>console.log(e.request.method, e.request.url))"
```

### Gatling (Maven)
```bash
cd gatling
# Run all simulations
mvn gatling:test
# Run a specific simulation
mvn gatling:test -Dgatling.simulationClass=simulations.CheckoutSimulation
# Clean and run
mvn clean gatling:test
# Skip tests (compile only)
mvn compile -DskipTests
```

### Tekton
```bash
# Apply all resources
kubectl apply -f tekton/
# Watch a pipeline run
tkn pipelinerun logs --last -f
# List recent runs
tkn pipelinerun list
```

## URL Filter Rules
When generating Gatling simulations from HAR files, **exclude** these patterns:
- `*.google-analytics.com/*`
- `*.googletagmanager.com/*`
- `*.doubleclick.net/*`
- `*.facebook.net/*`
- `*.hotjar.com/*`
- Static assets: `*.css`, `*.js`, `*.png`, `*.jpg`, `*.woff2`, `*.ico`
- Health checks: `/health`, `/ping`, `/metrics`

Only include requests to the **application under test** domain(s).

## Gatling Simulation Conventions
- **Language: Java DSL** (Gatling 3.7+ Java API). Source lives in `gatling/src/test/java/simulations/`.
- One simulation class per user journey (e.g. `CheckoutSimulation`, `SearchSimulation`)
- Package: `simulations`
- Extend `io.gatling.javaapi.core.Simulation`; static-import `CoreDsl.*` and `HttpDsl.*`
- `setUp(...)` goes in an instance initializer block `{ ... }`; injection profile uses `injectOpen(...)`
- Use `constantUsersPerSec` for baseline, `rampUsersPerSec` for ramp scenarios
- Pause times: use `pause(1, 3)` (random 1–3 s) unless the HAR shows a specific think time
- Feeders: CSV files in `src/test/resources/feeders/`
- Checks: always assert HTTP status with `status().in(...)` / `status().is(...)`; add a regex check on a key response field when available

## Tekton Pipeline Topology
```
playwright-task → har-to-gatling-task → gatling-task → report-task
```
- `playwright-task`: runs `npx playwright test`, saves HAR to workspace
- `har-to-gatling-task`: runs conversion script, writes `.scala` to workspace
- `gatling-task`: runs `mvn gatling:test`, writes report path to result
- `report-task`: archives HTML report; emits Tekton result `report-url`

## Workspace / PVC
All tasks share a single `PersistentVolumeClaim` named `playwright-gatling-ws`.

## Environment Variables (referenced in Tasks)
| Variable | Description |
|---|---|
| `BASE_URL` | Application under test base URL |
| `GATLING_USERS` | Target concurrent users (default `10`) |
| `GATLING_DURATION` | Test duration in seconds (default `60`) |
| `NAMESPACE` | Kubernetes namespace for Tekton runs |

## Maven Coordinates
```xml
<groupId>com.loadtest</groupId>
<artifactId>gatling-simulations</artifactId>
<version>1.0.0-SNAPSHOT</version>
```
Gatling version: `3.11.x` (check pom.xml for exact pin).

## Common Failure Modes
- **HAR is empty**: Playwright ran but HAR plugin wasn't configured — check `playwright.config.ts` for `recordHar`.
- **Simulation compiles but gets 0 requests**: URL filter excluded everything — broaden filter or check `BASE_URL`.
- **Gatling OOM**: increase Maven heap with `MAVEN_OPTS=-Xmx2g`.
- **Tekton workspace not bound**: PVC must exist before `PipelineRun`; create with `kubectl apply -f tekton/pvc.yaml`.
