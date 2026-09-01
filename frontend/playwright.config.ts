import { defineConfig } from '@playwright/test'

// The browser-e2e job and the local drill both point at a full Compose stack.
// CI exports AICOSTOPS_E2E_BASE_URL; local runs default to localhost:8080.
const baseURL = process.env.AICOSTOPS_E2E_BASE_URL ?? 'http://localhost:8080'

export default defineConfig({
  testDir: './e2e',
  // The V1 E2E specs share one Compose stack and one organization's books, so
  // the runner must be strictly serial: no parallel workers, no file parallelism.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['junit', { outputFile: 'playwright-results.xml' }],
  ],
  outputDir: 'test-results',
  use: {
    baseURL,
    browserName: 'chromium',
    actionTimeout: 20_000,
    // Retention policy only: the specs are stateful so retries stay at 0, but a
    // future retried run must still keep a trace for diagnosis.
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
})