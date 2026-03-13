import {defineConfig, loadEnv} from "vite"
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs"

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const shouldInjectHeaders = env.INJECT_TEST_HEADERS === "true";

  const testHeaders = {
        "X-Forwarded-Email": "test@example.com",
        "X-Forwarded-Groups":
          "role:forecast:view,role:national:view,role:fixed-points:view,role:staff:edit,role:TEST,role:EXT,role:terminal-dashboard,role:egate-banks:edit,role:BRS,role:desks-and-queues:view,role:manage-users,role:view-config,role:LBA,role:sla-configs:edit,role:EDI,role:STN,role:BFS,role:health-checks:edit,role:rcc:north,role:DSA,role:super-admin,role:EMA,role:staff:edit,role:BOH,role:uma_authorization,role:port-feed-upload,role:NWI,role:red-list-feature,role:api:view,role:MME,role:iei-dashboard:view,role:rcc:central,role:staff-movements:export,role:border-force-staff,role:NCL,role:default-roles-drt-prod,role:red-lists:edit,role:arrivals-and-splits:view,role:MAN,role:SEN,role:LGW,role:rcc:south,role:BHD,role:LCY,role:create-alerts,role:arrival-simulation-upload,role:LTN,role:LPL,role:INV,role:BHX,role:rcc:heathrow,role:enhanced-api-view,role:national:view,role:offline_access,role:LHR,role:forecast:view,role:CWL,role:ABZ,role:download-manager,role:debug,role:HUY,role:staff-movements:edit,role:PIK,role:arrival-source,role:fixed-points:view,role:NQY,role:SOU,role:GLA,role:account:manage-account,role:account:manage-account-links,role:account:view-profile,role:realm-management:view-realm,role:realm-management:view-identity-providers,role:realm-management:manage-identity-providers,role:realm-management:impersonation,role:realm-management:realm-admin,role:realm-management:create-client,role:realm-management:manage-users,role:realm-management:view-authorization,role:realm-management:query-clients,role:realm-management:query-users,role:realm-management:manage-events,role:realm-management:manage-realm,role:realm-management:view-events,role:realm-management:view-users,role:realm-management:view-clients,role:realm-management:manage-authorization,role:realm-management:manage-clients,role:realm-management:query-groups",
      }

  const mkProxy = () => ({
    target: "http://localhost:9000",
    changeOrigin: true,
    ws: true,
    headers: shouldInjectHeaders ? testHeaders : undefined,
  });

  const proxyPaths = [
    "/report-to",
    "/health-check",
    "/config",
    "/contact-details",
    "/ooh-status",
    "/feature-flags",
    "/airport-info",
    "/walk-times",
    "/alerts",
    "/version",
    "/crunch",
    "/control",
    "/crunch-snapshot",
    "/feed-statuses",
    "/feeds",
    "/arrival",
    "/manifest-summaries",
    "/red-list",
    "/egate-banks",
    "/sla-configs",
    "/logged-in",
    "/legacy-staff-assignments",
    "/fixed-points",
    "/shifts",
    "/staff-assignments",
    "/forecast-summary",
    "/export",
    "/desk-rec-simulation",
    "/forecast-accuracy",
    "/assets",
    "/data",
    "/api",
    "/debug",
    "/email",
    "/loggings",
    "/staff-movements",
    "/feature-guide-video",
    "/drop-ins",
    "/drop-in-registrations",
    "/user-feedback",
    "/user-preferences",
    "/ab-feature",
    "/feature-guides",
    "/is-new-feature-available-since-last-login",
    "/record-feature-guide-view",
    "/viewed-feature-guides",
  ];

  const proxyConfig = {};
  for (const path of proxyPaths) {
    proxyConfig[path] = mkProxy();
  }

  return {
    plugins: [
      scalaJSPlugin({
        cwd: "../",
        projectID: "client",
        uriPrefix: "scalajs",
      }),
    ],
    optimizeDeps: {
      include: [""],
    },
    build: {
      outDir: "../server/src/main",
      manifest: true,
      rollupOptions: {
        treeshake: "recommended",
        onwarn(warning, warn) {
          if (warning.code === "MODULE_LEVEL_DIRECTIVE") return;
          warn(warning);
        },
      },
    },
    server: {
      proxy: proxyConfig,
    },
  };
})
