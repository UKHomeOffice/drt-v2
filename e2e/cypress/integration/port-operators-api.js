let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

let todayAt = require('../support/functions').todayAtUtc
let todayAtString = require('../support/functions').todayAtUtcString

Cypress.Commands.add('downloadCsv', (terminalName, year, month, day) => {
  cy.request({url: '/export/api/'+terminalName+'/'+year+'/'+month+'/'+day, failOnStatusCode: false})
});

describe('Advanced Passenger Information Splits exposed to Port Operators', function () {
  const now = moment.utc();

  const day = now.date();
  const month = now.month() + 1;
  const year = now.year();

  const header = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track";

  beforeEach(function () {
    cy.deleteData();
  });

  it("Bad Request when the date is invalid", function () {
    cy
      .asAPortOperator()
      .downloadCsv("T1", year, month, 40).then((response) => {
        expect(response.status).to.eq(400);
      });
  });

  it("Bad Request when the terminal is invalid", function () {
    cy
      .setRoles(["test", "api:view-port-arrivals"])
      .downloadCsv("InvalidTerminalName", year, month, day).then((response) => {
        expect(response.status).to.eq(400);
      });
  });

  it("Ok when there are arrivals on the date and user has the correct role", function () {
    const localTimeScheduledDate = todayAt(0, 52).tz("Europe/London")
    cy
      .asATestSetupUser()
      .addFlight({
        "SchDT": todayAtString(0, 52)
      })
      .waitForFlightToAppear("TS0123")
      .asAPortOperator()
      .downloadCsv("T1", year, month, day).then((response) => {
        expect(response.status).to.eq(200);
        expect(response.body).to.contain(header);
        expect(response.body).to.contain(localTimeScheduledDate.format("YYYY-MM-DD") + ',' + localTimeScheduledDate.format("HH:mm"));
        expect(response.headers['content-disposition']).to.eq("attachment; filename=export-splits-TEST-T1-" + year + "-" + month + "-" + day + ".csv")
      });
  })
});
