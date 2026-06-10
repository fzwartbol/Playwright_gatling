# Playwright → HAR → Gatling → Tekton

Convert Playwright end-to-end tests into Gatling load simulations, orchestrated by a Tekton pipeline.

## Quick Start

### 1. Record HAR files
```bash
cd playwright
npm ci
BASE_URL=https://myapp.example.com npx playwright test
```

### 2. Convert to Gatling simulation
```bash
node scripts/har-to-gatling.js \
  --har har/homepage-and-search-journey.har \
  --simulation HomepageAndSearchSimulation \
  --base-url https://myapp.example.com
```

### 3. Run load test
```bash
cd gatling
BASE_URL=https://myapp.example.com GATLING_USERS=20 GATLING_DURATION=120 mvn gatling:test
```

### 4. Run via Tekton
```bash
# One-time setup
kubectl apply -f tekton/pvc.yaml
kubectl apply -f tekton/tasks/
kubectl apply -f tekton/pipeline.yaml

# Edit base-url in pipelinerun.yaml, then:
kubectl create -f tekton/pipelinerun.yaml
tkn pipelinerun logs --last -f
```

## Pipeline topology
```
playwright-record-har → har-to-gatling → gatling-run → archive-report
```

See [CLAUDE.md](CLAUDE.md) for full documentation, URL filter rules, and common failure modes.
