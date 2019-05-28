let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

Cypress.Commands.add('downloadCsv', (terminalName, year, month, day) => {
  cy.request({url: '/export/api/'+terminalName+'/'+year+'/'+month+'/'+day, failOnStatusCode: false})
});

Cypress.Commands.add('waitForArrivalToAppearInTheSystem', () => {
  cy
    .visit('#terminal/T1/current/arrivals/?timeRangeStart=0&timeRangeEnd=24')
    .get("#arrivals")
    .contains("TS0123", { timeout: 10000 });
});

describe('Advanced Passenger Information Splits exposed to Port Operators', function () {
  const now = moment.utc();
  const scheduledDate = now.hour(0).minute(55)
  const localTimeScheduledDate = scheduledDate.tz("Europe/London")

  const day = now.date();
  const month = now.month() + 1;
  const year = now.year();
  const header = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track";

  beforeEach(function () {
    cy
      .deleteData();
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
   cy.log(scheduledDate.format("YYYY-MM-DDTHH:mmZ"));
    cy
      .asATestSetupUser()
      .addFlightWithFlightCode("TS0123", scheduledDate.format("YYYY-MM-DDTHH:mmZ"))
      .waitForArrivalToAppearInTheSystem()
      .asAPortOperator()
      .downloadCsv("T1", year, month, day).then((response) => {
        expect(response.status).to.eq(200);
        expect(response.body).to.contain(header);
        expect(response.body).to.contain(localTimeScheduledDate.format("YYYY-MM-DD") + ',' + localTimeScheduledDate.format("HH:mm"));
        expect(response.headers['content-disposition']).to.eq("attachment; filename=export-splits-TEST-T1-" + year + "-" + month + "-" + day + ".csv")
      });
  })

});
