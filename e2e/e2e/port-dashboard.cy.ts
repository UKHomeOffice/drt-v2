import moment from "moment-timezone";
moment.locale("en-gb");
import { todayAtUtcString as todayAtString } from '../tests/support/time-helpers'
import { currentTimeString as currentTimeString } from '../tests/support/time-helpers'

describe('Port dashboard', () => {

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
      "ICAO": "TS0122",
      "IATA": "TS0122",
      "SchDT": todayAtString(10, 40),
      "ActChoxDT": currentTimeString(),
      "ActPax": 300
    })
      .asABorderForceOfficer()
      .navigateHome()
      .visit("/#portDashboard")
      .wait(250)
      .get('.flights-total')
      .contains("1 Flight")

  })

});
