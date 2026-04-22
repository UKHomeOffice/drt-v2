import { defineConfig } from 'cypress'

export default defineConfig({
  defaultCommandTimeout: 12000,
  pageLoadTimeout: 10000,
  viewportWidth: 1440,
  viewportHeight: 900,
  video: false,
  e2e: {
    baseUrl: 'http://localhost:9000',
  }
})
