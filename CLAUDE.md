# CLAUDE.md — Playwright → HAR → Gatling → Tekton Pipeline

## Project Purpose
Convert Playwright end-to-end tests into Gatling load simulations, orchestrated by Tekton pipelines.
Flow: Playwright (Node.js) records HAR files → `har-to-gatling` converts to Scala simulations → Gatling (Maven) runs load tests → Tekton pipeline glues it all together.

## Repository Layout
```
.
├── playwright/          # Playwright test suite (TypeScript)
│   ├── tests/           # .spec.ts test files
│   ├── playwright.config.ts
│   └── package.json
├── gatling/             # Gatling Maven project (Scala simulations)
│   ├── src/
│   │   └── test/
│   │       ├── scala/   # Generated + hand-edited simulations
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

### Playwright
```bash
cd playwright
npm ci
npx playwright install --with-deps
# Record HAR files (all tests)
npx playwright test --reporter=dot
# Record HAR for a single test file
npx playwright test tests/checkout.spec.ts
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
- One simulation class per user journey (e.g. `CheckoutSimulation`, `SearchSimulation`)
- Namespace: `simulations`
- Extend `io.gatling.core.scenario.Simulation`
- Use `constantUsersPerSec` for baseline, `rampUsersPerSec` for ramp scenarios
- Pause times: use `pause(1, 3)` (random 1–3 s) unless the HAR shows a specific think time
- Feeders: CSV files in `src/test/resources/feeders/`
- Checks: always assert HTTP status 200 (or expected code); add regex check on key response field when available

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
