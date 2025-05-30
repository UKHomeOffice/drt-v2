import {todayAsLocalString, todayAsUtcString} from '../support/time-helpers'


describe('Terminal dashboard', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  let timeRangeSelector = ".tb-bar-wrapper";

  it("should display a box for every queue in the terminal", () => {
    const viewWindowHour = '14'

    cy
      .addFlight({
        "SchDT": todayAsUtcString(14, 10),
        "ActChoxDT": todayAsUtcString(14, 10),
        "ActPax": 51
      })
      .asABorderForceOfficer()
      .navigateHome()
      .visit("/#terminal/T1/dashboard/15/?start=" + todayAsLocalString(14, 15))
      .get(".pax-bar")
      .contains("51 passengers")
      .get(timeRangeSelector)
      .contains(viewWindowHour + ":15 - " + viewWindowHour + ":30")
      .get(".eeadesk")
      .contains("37 pax joining")
      .get(".eeadesk")
      .contains("6 minute wait")
      .get(".eeadesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-up')
      .get(".next-bar")
      .click({force: true})
      .get(timeRangeSelector)
      .contains(viewWindowHour + ":30 - " + viewWindowHour + ":45")
      .get(".eeadesk")
      .contains("18 minute wait")
      .get(".eeadesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-up')
      .get(".prev-bar")
      .click({force: true})
      .get(".prev-bar")
      .click({force: true})
      .get(timeRangeSelector)
      .contains(viewWindowHour + ":00 - " + viewWindowHour + ":15")
      .get(".eeadesk")
      .contains("0 minute wait")
      .get(".eeadesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-right')
  })

  it("should display flights with PCP time in the window", () => {
    const viewWindowHourStart = '14'
    const viewWindowHourEnd = '15'
    cy
      .addFlight({
        "ICAO": "TS0123",
        "IATA": "TS0123",
        "SchDT": todayAsUtcString(10, 30),
        "ActChoxDT": todayAsUtcString(14, 15),
        "ActPax": 300
      })
      .addFlight({
        "ICAO": "TS0124",
        "IATA": "TS0124",
        "SchDT": todayAsUtcString(14, 10),
        "ActChoxDT": todayAsUtcString(14, 35),
        "ActPax": 51
      })
      .asABorderForceOfficer()
      .navigateHome()
      .visit("/#terminal/T1/dashboard/15/?start=" + todayAsLocalString(14, 15))
      .get("a.terminal-dashboard-side__sidebar_widget").click({force: true})
      .get(".dashboard-arrivals-popup tbody tr").contains("TS0123")
      .get(".dashboard-arrivals-popup tbody tr").should('have.length', 1)
      .get(".popover-overlay")
      .click({force: true})
      .get(".next-bar")
      .click({force: true})
      .get("a.terminal-dashboard-side__sidebar_widget").click({force: true})
      .get(".dashboard-arrivals-popup tbody tr").should('have.length', 2)
      .get(".dashboard-arrivals-popup tbody tr").contains("TS0123")
      .get(":nth-child(2) > .arrivals__table__flight-code").contains("TS0124")
      .get(".popover-overlay")
      .click({force: true})
      .get(".prev-bar")
      .get("select")
      .select('30')
      .url().should('include', 'dashboard/30')
      .get(timeRangeSelector)
      .contains(viewWindowHourStart + ":30 - " + viewWindowHourEnd + ":00")
      .get('select')
      .should('have.value', '30')
      .get(".pax-bar")
      .contains("311 passengers")
      .get("a.terminal-dashboard-side__sidebar_widget").click({force: true})
      .get(".dashboard-arrivals-popup tbody tr").contains("TS0123")
      .get(".dashboard-arrivals-popup tbody tr").contains("TS0124")
      .get(".dashboard-arrivals-popup tbody tr").should('have.length', 2)

  })

});
