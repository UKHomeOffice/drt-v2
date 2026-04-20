import {todayAtPortLocal, todayAsUtcString} from '../support/time-helpers'


describe('Terminal dashboard', () => {

  beforeEach(function () {
    cy.deleteData('nocheck');
  });

  const timeRangeSelector = ".tb-bar-wrapper";
  const formatTimeRange = (start, slotSizeMinutes: number): string =>
    `${start.format('HH:mm')} - ${start.clone().add(slotSizeMinutes, 'minutes').format('HH:mm')}`

  it("should display a box for every queue in the terminal", () => {
    const slotStart = todayAtPortLocal(14, 15)
    const slotSizeMinutes = 15

    cy
      .addFlight({
        "SchDT": todayAsUtcString(14, 10),
        "EstDT": todayAsUtcString(14, 10),
        "ActDT": todayAsUtcString(14, 10),
        "EstChoxDT": todayAsUtcString(14, 10),
        "ActChoxDT": todayAsUtcString(14, 10),
        "ActPax": 51
      })
      .asABorderForceOfficer()
      .navigateHome()
      .visit("/#terminal/T1/dashboard/15/?start=" + slotStart.format('YYYY-MM-DDTHH:mm:ss'))
      .get(".pax-bar")
      .contains("51 passengers")
      .get(timeRangeSelector)
      .contains(formatTimeRange(slotStart, slotSizeMinutes))
      .get(".eeadesk")
      .contains("37 pax joining")
      .get(".eeadesk")
      .contains("6 minute wait")
      .get(".eeadesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-up')
      .get(".next-bar")
      .click({force: true})
      .get(timeRangeSelector)
      .contains(formatTimeRange(slotStart.clone().add(slotSizeMinutes, 'minutes'), slotSizeMinutes))
      .get(".eeadesk")
      .contains("18 minute wait")
      .get(".eeadesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-up')
      .get(".prev-bar")
      .click({force: true})
      .get(".prev-bar")
      .click({force: true})
      .get(timeRangeSelector)
      .contains(formatTimeRange(slotStart.clone().subtract(slotSizeMinutes, 'minutes'), slotSizeMinutes))
      .get(".eeadesk")
      .contains("0 minute wait")
      .get(".eeadesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-right')
  })

  it("should display flights with PCP time in the window", () => {
    const initialSlotStart = todayAtPortLocal(14, 15)
    cy
      .addFlight({
        "ICAO": "TS0123",
        "IATA": "TS0123",
        "SchDT": todayAsUtcString(10, 30),
        "EstDT": todayAsUtcString(10, 30),
        "ActDT": todayAsUtcString(10, 30),
        "EstChoxDT": todayAsUtcString(14, 15),
        "ActChoxDT": todayAsUtcString(14, 15),
        "ActPax": 300
      })
      .addFlight({
        "ICAO": "TS0124",
        "IATA": "TS0124",
        "SchDT": todayAsUtcString(14, 10),
        "EstDT": todayAsUtcString(14, 10),
        "ActDT": todayAsUtcString(14, 10),
        "EstChoxDT": todayAsUtcString(14, 35),
        "ActChoxDT": todayAsUtcString(14, 35),
        "ActPax": 51
      })
      .asABorderForceOfficer()
      .navigateHome()
      .visit("/#terminal/T1/dashboard/15/?start=" + initialSlotStart.format('YYYY-MM-DDTHH:mm:ss'))
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
      .contains(formatTimeRange(initialSlotStart.clone().add(15, 'minutes'), 30))
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
