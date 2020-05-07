import moment from 'moment-timezone'
moment.locale("en-gb");
import { todayAtUtcString as todayAtString } from '../support/time-helpers'
import { currentTimeString as currentTimeString } from '../support/time-helpers'

describe('Viewing the port dashboard page', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  it("should not display cancelled flights with PCP time in the window", () => {
    cy
    .addFlight({
      "ICAO": "TS0124",
      "IATA": "TS0124",
      "SchDT": todayAtString(10, 35),
      "ActChoxDT": currentTimeString(),
      "ActPax": 0,
      "Status": "Cancelled"
    })
    .addFlight({
      "ICAO": "TS0123",
      "IATA": "TS0123",
      "SchDT": todayAtString(10, 30),
      "ActChoxDT": currentTimeString(),
      "ActPax": 300
    })
    .addFlight({
      "ICAO": "TS0122",
      "IATA": "TS0122",
      "SchDT": todayAtString(10, 40),
      "ActChoxDT": currentTimeString(),
      "ActPax": 300
    })
      .asABorderForceOfficer()
      .navigateHome()
      .visit("/#portDashboard")
      .get('.flights-total')
      .contains("2 Flights")

  })

});
