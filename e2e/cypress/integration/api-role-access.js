describe('Restrict access to endpoint by role', function () {

  let moment = require('moment-timezone');
  require('moment/locale/en-gb');
  moment.locale("en-gb");
  const todayDateString = moment().format("YYYY-MM-DD");
  const todayString = todayDateString + "T00:00:00Z";
  const millis = moment(todayString).unix() * 1000;

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
      endpoint: "/export/desks/"+millis+"/"+millis+"/T1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/desks/"+millis+"/"+millis+"/T1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test", "arrivals-and-splits:view"],
      endpoint: "/export/arrivals/" + millis + "/T1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test", "api:view-port-arrivals"],
      endpoint: "/export/api/T1/2019/5/1",
      method: "GET",
      shouldBeGranted: true
    },
    {
      roles: ["test"],
      endpoint: "/export/api/T1/2019/5/1",
      method: "GET",
      shouldBeGranted: false
    },
    {
      roles: ["test"],
      endpoint: "/export/arrivals/" + millis + "/T1",
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
      endpoint: "/export/arrivals/" + millis + "/T1",
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
      roles: [],
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
    }
  ]

  describe('Restrict access by role', function () {

    testCases.map(testCase => {
      it("Expects " + (testCase.shouldBeGranted ? "access ALLOWED" : "access DENIED") + " for a "+ testCase.method + " request to " + testCase.endpoint + " with roles: [" + testCase.roles.join(", ") + "]", function () {

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
