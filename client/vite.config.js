import { defineConfig } from "vite"
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs"

export default defineConfig({
  plugins: [scalaJSPlugin({
    cwd: '../',
    projectID: 'client',
    uriPrefix: 'scalajs',
  })],
  optimizeDeps: {
    include: [''],
  },
  build: {
    outDir: '../server/src/main',
    manifest: true,
    rollupOptions: {
      treeshake: 'recommended',
      onwarn(warning, warn) {
        if (warning.code === "MODULE_LEVEL_DIRECTIVE") return;
        warn(warning);
      },
    },
  },
  server: {
    proxy: {
      "/report-to": "http://localhost:9000",
      "/health-check": "http://localhost:9000",
      "/config": "http://localhost:9000",
      "/contact-details": "http://localhost:9000",
      "/ooh-status": "http://localhost:9000",
      "/feature-flags": "http://localhost:9000",
      "/airport-info": "http://localhost:9000",
      "/walk-times": "http://localhost:9000",
      "/alerts": "http://localhost:9000",
      "/version": "http://localhost:9000",
      "/crunch": "http://localhost:9000",
      "/control": "http://localhost:9000",
      "/crunch-snapshot": "http://localhost:9000",
      "/feed-statuses": "http://localhost:9000",
      "/feeds": "http://localhost:9000",
      "/arrival": "http://localhost:9000",
      "/manifest-summaries": "http://localhost:9000",
      "/red-list": "http://localhost:9000",
      "/egate-banks": "http://localhost:9000",
      "/sla-configs": "http://localhost:9000",
      "/logged-in": "http://localhost:9000",
      "/legacy-staff-assignments": "http://localhost:9000",
      "/fixed-points": "http://localhost:9000",
      "/shifts": "http://localhost:9000",
      "/staff-assignments": "http://localhost:9000",
      "/forecast-summary": "http://localhost:9000",
      "/export": "http://localhost:9000",
      "/desk-rec-simulation": "http://localhost:9000",
      "/forecast-accuracy": "http://localhost:9000",
      "/assets": "http://localhost:9000",
      "/data": "http://localhost:9000",
      "/api": "http://localhost:9000",
      "/debug": "http://localhost:9000",
      "/email": "http://localhost:9000",
      "/loggings": "http://localhost:9000",
      "/staff-movements": "http://localhost:9000",
      "/feature-guide-video": "http://localhost:9000",
      "/drop-ins": "http://localhost:9000",
      "/drop-in-registrations": "http://localhost:9000",
      "/user-feedback": "http://localhost:9000",
      "/user-preferences": "http://localhost:9000",
      "/ab-feature": "http://localhost:9000",
      "/feature-guides": "http://localhost:9000",
      "/is-new-feature-available-since-last-login": "http://localhost:9000",
      "/record-feature-guide-view": "http://localhost:9000",
      "/viewed-feature-guides": "http://localhost:9000",
    },
  },
})
