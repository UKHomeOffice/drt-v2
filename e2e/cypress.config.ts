import { defineConfig } from 'cypress'

export default defineConfig({
  defaultCommandTimeout: 12000,
  pageLoadTimeout: 10000,
  viewportWidth: 1440,
  viewportHeight: 900,
  video: false,
  e2e: {
    // We've imported your old cypress plugins here.
    // You may want to clean this up later by importing these.
    setupNodeEvents(on, config) {
    },
    baseUrl: 'http://localhost:9000',
  },
})
