import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: 'dot',
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:3000',
    // Record HAR for every test — saved to ../har/<test-name>.har
    recordHar: {
      outputPath: '../har',
      // Omit response bodies to keep HAR files small; Gatling only needs request shape
      omitContent: true,
      // Filter to app domain only (third-party scripts still appear but are filtered at conversion)
      urlFilter: process.env.HAR_URL_FILTER || '**',
    },
    trace: 'off',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
