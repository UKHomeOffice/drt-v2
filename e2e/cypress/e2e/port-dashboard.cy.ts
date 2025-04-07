import { todayAsLocalString } from '../support/time-helpers'
import { currentTimeString as currentTimeString } from '../support/time-helpers'

describe('Port dashboard', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  it("should not display cancelled flights with PCP time in the window", () => {
    cy
    .addFlight({
      "ICAO": "TS0124",
      "IATA": "TS0124",
      "SchDT": todayAsLocalString(10, 35),
      "ActChoxDT": currentTimeString(),
      "ActPax": 0,
      "Status": "Cancelled"
    })
    .addFlight({
      "ICAO": "TS0122",
      "IATA": "TS0122",
      "SchDT": todayAsLocalString(10, 40),
      "ActChoxDT": currentTimeString(),
      "ActPax": 300
    })
      .asABorderForceOfficer()
      .navigateHome()
      .visit("/#portDashboard")
      .get('.flights-total')
      .contains("1 Flight")

  })

});
