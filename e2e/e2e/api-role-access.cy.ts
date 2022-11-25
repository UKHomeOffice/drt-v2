
import { todayAtUtc } from '../support/time-helpers'


describe('API role access', () => {

  const today = todayAtUtc(0, 0);
  const millis = today.unix() * 1000;

  const testCases = [
    {
      roles: ["test"],
      endpoint: "/airport-config",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test", "arrivals-and-splits:view"],
      endpoint: "/airport-info",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/airport-info",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/alerts/" + millis,
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/version",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/feed-statuses",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/logged-in",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: [],
      endpoint: "/data/login",
      method: "POST",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/data/user",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/logging",
      method: "POST",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/data/staff",
      method: "POST",
      shouldBeGranted: false
    },
    {
      roles: ["test", "border-force-staff"],
      endpoint: "/staff-movements",
      method: "POST",
      shouldBeGranted: false
    },
    {
      roles: ["test", "border-force-staff"],
      endpoint: "/staff-movements/3b867548-b313-45df-a6b1-bb1f94c4d054",
      method: "DELETE",
      shouldBeGranted: false
    },
    {
      roles: ["test", "border-force-staff", "staff-movements:edit"],
      endpoint: "/staff-movements",
      method: "POST",
      shouldBeGranted: true
    },
    {
      roles: ["test", "border-force-staff", "staff-movements:edit"],
      endpoint: "/staff-movements/3b867548-b313-45df-a6b1-bb1f94c4d054",
      method: "DELETE",
      shouldBeGranted: true
    },
    {
      roles: ["test", "border-force-staff", "staff-movements:export"],
      endpoint: "export/staff-movements/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test", "border-force-staff"],
      endpoint: "export/staff-movements/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "port-feed-upload"],
      endpoint: "/data/feed/live/lhr",
      method: "POST",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/data/feed/live/lhr",
      method: "POST",
      shouldBeGranted: false
    },
    {
      roles: [],
      endpoint: "/logging",
      method: "POST",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/data/user/has-port-access",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: [],
      endpoint: "/data/user/has-port-access",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/alerts",
      method: "POST",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/alerts",
      method: "DELETE",
      shouldBeGranted: false
    },
    {
      roles: ["test", "fixed-points:view"],
      endpoint: "/fixed-points",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/fixed-points",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "fixed-points:edit"],
      endpoint: "/fixed-points",
      method: "POST",
      shouldBeGranted: true
    },
    {
      roles: ["test", "desks-and-queues:view"],
      endpoint: "/crunch",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/crunch",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "desks-and-queues:view"],
      endpoint: "/crunch-snapshot/1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/crunch-snapshot/1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "desks-and-queues:view"],
      endpoint: "/export/desk-recs/" + millis + "/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/desk-recs/" + millis + "/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "desks-and-queues:view"],
      endpoint: "/export/desk-deps/" + millis + "/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/desk-deps/" + millis + "/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "arrivals-and-splits:view"],
      endpoint: "/export/arrivals/2020-09-01/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/arrivals/2020-09-01/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "arrivals-and-splits:view"],
      endpoint: "/export/arrivals/" + millis + "/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/arrivals/" + millis + "/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/export/arrivals/2020-09-01/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "forecast:view"],
      endpoint: "/export/planning/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/planning/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "forecast:view"],
      endpoint: "/export/headlines/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/headlines/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/export/users",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/api/drt/shared/Api/getShowAlertModalDialog",
      method: "POST",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: [],
      endpoint: "/contact-details",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: [],
      endpoint: "/ooh-status",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/arrival/234/T1/100/origin",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "arrival-source"],
      endpoint: "/arrival/234/T1/100/origin",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/arrival/1000/234/T1/100/origin",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "arrival-source"],
      endpoint: "/arrival/1000/234/T1/100/origin",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test", "arrival-simulation-upload"],
      endpoint: "/export/desk-rec-simulation",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/desk-rec-simulation",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/manifest/" + today.format("YYYY-MM-DD") + "/summary",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "enhanced-api-view"],
      endpoint: "/manifest/" + today.format("YYYY-MM-DD") + "/summary",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/desk-rec-simulation",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/#faqs",
      method: "GET",
      shouldBeGranted: true
    }
  ]

  describe('Restrict access by role', () => {

    testCases.map(testCase => {
      it("Expects " + (testCase.shouldBeGranted ? "access ALLOWED" : "access DENIED") + " for a " + testCase.method + " request to " + testCase.endpoint + " with roles: [" + testCase.roles.join(", ") + "]", () => {

        cy.setRoles(testCase.roles)
          .request({
            method: testCase.method,
            url: testCase.endpoint,
            failOnStatusCode: false
          })
          .then(resp => {
            const accessGranted = resp.status != 401
            expect(accessGranted)
              .to
              .eq(testCase.shouldBeGranted, "Role: " + testCase.roles.join(", ") + " has incorrect permissions to " + testCase.method + " on " + testCase.endpoint)
          });
      });
    });
  });
});
