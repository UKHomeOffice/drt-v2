let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

let todayAtUtcString = require('../support/functions').todayAtUtcString

describe('Arrivals page filter', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  it('Filters flights by PCP time range intersecting the selected range', () => {
    cy
      .addFlight(
        {
          "SchDT": todayAtUtcString(0, 55),
          "EstDT": todayAtUtcString(1, 5),
          "EstChoxDT": todayAtUtcString(16, 11),
          "ActDT": todayAtUtcString(16, 7),
          "ActChoxDT": todayAtUtcString(16, 45),
          "ActPax": 300
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .get('.time-range > :nth-child(1)').select("00")
      .get('.time-range > :nth-child(2)').select("01")
      .get('#arrivals > div').contains("No flights to display")
      .get('.time-range > :nth-child(1)').select("16")
      .get('.time-range > :nth-child(2)').select("17")
      .get('.danger > :nth-child(1)').contains("TS0123")
      .get('.time-range > :nth-child(1)').select("17")
      .get('.time-range > :nth-child(2)').select("18")
      .get('.danger > :nth-child(1)').contains("TS0123")

  });
});
