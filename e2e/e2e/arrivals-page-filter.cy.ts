import moment from "moment-timezone";
moment.locale("en-gb");

import {todayAtUtc, todayAtUtcString} from './support/time-helpers'

describe('Arrivals page filter', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  it('Filters flights by any relevant time range intersecting the selected range', () => {

      const flightTime = todayAtUtc(16, 55)
      const scheduledHour = flightTime.tz("Europe/London").format("HH")
      let oneHourAfterScheduled = "" + (parseInt(scheduledHour) + 1);
      let twoHoursAfterScheduled = "" + (parseInt(scheduledHour) + 2);
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
      .get('.time-range > :nth-child(1)').select(scheduledHour)
      .get('.time-range > :nth-child(2)').select(oneHourAfterScheduled)
      .get('.arrivals__table__flight-code').contains("TS0123")
      .get('.time-range > :nth-child(1)').select(oneHourAfterScheduled)
      .get('.time-range > :nth-child(2)').select(twoHoursAfterScheduled)
      .get('.arrivals__table__flight-code').contains("TS0123")

  });
});
