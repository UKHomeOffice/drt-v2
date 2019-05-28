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
      responseCode: 200
    },
    {
      roles: ["test", "arrivals-and-splits:view"],
      endpoint: "/airport-info",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/airport-info",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test"],
      endpoint: "/alerts/" + millis,
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/version",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/feed-statuses",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/logged-in",
      method: "GET",
      responseCode: 200
    },
    {
      roles: [],
      endpoint: "/data/login",
      method: "POST",
      responseCode: 400
    },
    {
      roles: ["test"],
      endpoint: "/data/user",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/logging",
      method: "POST",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/data/staff",
      method: "POST",
      responseCode: 401
    },
    {
      roles: ["test", "port-operator-staff"],
      endpoint: "/data/feed/live/lhr",
      method: "POST",
      responseCode: 400
    },
    {
      roles: ["test"],
      endpoint: "/data/feed/live/lhr",
      method: "POST",
      responseCode: 401
    },
    {
      roles: [],
      endpoint: "/logging",
      method: "POST",
      responseCode: 401
    },
    {
      roles: ["test"],
      endpoint: "/data/user/has-port-access",
      method: "GET",
      responseCode: 200
    },
    {
      roles: [],
      endpoint: "/data/user/has-port-access",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test"],
      endpoint: "/alerts",
      method: "POST",
      responseCode: 401
    },
    {
      roles: ["test"],
      endpoint: "/alerts",
      method: "DELETE",
      responseCode: 401
    },
    {
      roles: ["test", "fixed-points:view"],
      endpoint: "/fixed-points",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/fixed-points",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test", "fixed-points:edit"],
      endpoint: "/fixed-points",
      method: "POST",
      responseCode: 400
    },
    {
      roles: ["test", "desks-and-queues:view"],
      endpoint: "/crunch-state",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/crunch-state",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test", "desks-and-queues:view"],
      endpoint: "/export/desks/"+millis+"/"+millis+"/T1",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/export/desks/"+millis+"/"+millis+"/T1",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test", "api:view-port-arrivals"],
      endpoint: "/export/arrivals/" + millis + "/T1",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test", "api:view-port-arrivals"],
      endpoint: "/export/api/T1/2019/5/1",
      method: "GET",
      responseCode: 404
    },
    {
      roles: ["test"],
      endpoint: "/export/api/T1/2019/5/1",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test"],
      endpoint: "/export/arrivals/" + millis + "/T1",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test", "arrivals-and-splits:view"],
      endpoint: "/export/arrivals/" + millis + "/" + millis + "/T1",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/export/arrivals/" + millis + "/" + millis + "/T1",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test"],
      endpoint: "/export/arrivals/" + millis + "/T1",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test", "forecast:view"],
      endpoint: "/export/planning/" + millis + "/T1",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/export/planning/" + millis + "/T1",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test", "forecast:view"],
      endpoint: "/export/headlines/" + millis + "/T1",
      method: "GET",
      responseCode: 200
    },
    {
      roles: ["test"],
      endpoint: "/export/headlines/" + millis + "/T1",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test"],
      endpoint: "/export/users",
      method: "GET",
      responseCode: 401
    },
    {
      roles: ["test"],
      endpoint: "/api/drt/shared/Api/getShowAlertModalDialog",
      method: "POST",
      responseCode: 401
    },
    {
      roles: [],
      endpoint: "/",
      method: "GET",
      responseCode: 200
    }
  ]

  describe('Restrict access by role', function () {

    testCases.map(testCase => {
      it("Expects " + testCase.responseCode + " for a "+ testCase.method + " request to " + testCase.endpoint + " with roles: [" + testCase.roles.join(", ") + "]", function () {

        cy.setRoles(testCase.roles)
          .request({
            method: testCase.method,
            url: testCase.endpoint,
            failOnStatusCode: false
          })
          .then(resp => expect(resp.status)
            .to
            .eq(testCase.responseCode, "wrong status code")
          );
      });
    });
  });
});
