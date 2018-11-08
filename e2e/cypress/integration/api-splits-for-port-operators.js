let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

describe('Advanced Passenger Information Splits exposed to Port Operators', function () {
  const now = moment();
  const schDateString = now.format("YYYY-MM-DD");
  const day = now.date();
  const month = now.month() + 1;
  const year = now.year();
  const header = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track";

  beforeEach(function () {
    cy.deleteData();
    cy.setRoles(["test"]);
  });

  function downloadCsv(day, month, year, terminalName) {
    return cy.request({url: '/v2/test/live/export/api/'+day+'/'+month+'/'+year+'/'+terminalName, failOnStatusCode: false})
  }

  function waitForArrivalToAppearInTheSystem() {
    cy.visit('/v2/test/live#terminal/T1/current/arrivals/?timeRangeStart=0&timeRangeEnd=24');
    cy.get("#arrivals").contains("TS0123");
  }

  it("Forbidden when user does not have the role `ApiViewPortCsv`", function () {
    downloadCsv(day, month, year, "T1").then((response) => {
      expect(response.status).to.eq(401)
    });
  });

  it("Bad Request when the date is invalid", function () {
    cy.setRoles(["test", "api:view-port-csv"]);
    downloadCsv(40, month, year, "T1").then((response) => {
      expect(response.status).to.eq(400)
    });
  });

  it("Bad Request when the terminal is invalid", function () {
    cy.setRoles(["test", "api:view-port-csv"]);
    downloadCsv(40, month, year, "InvalidTerminalName").then((response) => {
      expect(response.status).to.eq(400)
    });
  });

  it("Not Found when there is no arrivals on the date", function () {
    cy.setRoles(["test", "api:view-port-csv"]);
    downloadCsv(day, month, year, "T1").then((response) => {
      expect(response.status).to.eq(404)
    });
  });

  it("Ok when there are arrivals on the date and user has the correct role", function () {
    cy.addFlight(schDateString + "T00:55:00Z", schDateString + "T00:55:00Z", schDateString + "T01:01:00Z", schDateString + "T01:05:00Z", schDateString + "T00:15:00Z");
    cy.setRoles(["test", "api:view-port-csv"]);
    waitForArrivalToAppearInTheSystem();
    downloadCsv(day, month, year, "T1").then((response) => {
      expect(response.status).to.eq(200);
      expect(response.body).to.contain(header);
      expect(response.body).to.contain(schDateString + ',' + '00:15');
      expect(response.headers['content-disposition']).to.eq("attachment; filename='export-splits-TEST-T1-" + year + "-" + month + "-" + day + ".csv'")
    });
  })

});