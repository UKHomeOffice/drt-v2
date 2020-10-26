import moment from "moment-timezone";
moment.locale("en-gb");

import { todayAtUtcString } from '../support/time-helpers'

describe('Arrivals page filter', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  it('Filters flights by any relevant time range intersecting the selected range', () => {
    cy
      .addFlight(
        {
          "SchDT": todayAtUtcString(16, 55),
          "EstDT": todayAtUtcString(16, 5),
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
      .get('.arrivals__table__flight-code').contains("TS0123")
      .get('.time-range > :nth-child(1)').select("17")
      .get('.time-range > :nth-child(2)').select("18")
      .get('.arrivals__table__flight-code').contains("TS0123")

  });
});
