import moment from "moment-timezone";
import {todayAtUtc, todayAtUtcString as todayAtString} from '../tests/support/time-helpers'

moment.locale("en-gb");

describe('Terminal dashboard', () => {

    beforeEach(function () {
        cy.deleteData();
    });

  let timeRangeSelector = ".tb-bar-wrapper";

  it("should display a box for every queue in the terminal", () => {
        const viewWindowHour = todayAtUtc(14, 0).tz("Europe/London").format("HH")

        cy
            .addFlight({
                "SchDT": todayAtString(14, 10),
                "ActChoxDT": todayAtString(14, 10),
                "ActPax": 51
            })
            .asABorderForceOfficer()
            .navigateHome()
            .visit("/#terminal/T1/dashboard/15/?start=" + todayAtString(14, 15))
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
        const viewWindowHourStart = todayAtUtc(14, 0).tz("Europe/London").format("HH")
        const viewWindowHourEnd = todayAtUtc(15, 0).tz("Europe/London").format("HH")
        cy
            .addFlight({
                "ICAO": "TS0123",
                "IATA": "TS0123",
                "SchDT": todayAtString(10, 30),
                "ActChoxDT": todayAtString(14, 15),
                "ActPax": 300
            })
            .addFlight({
                "ICAO": "TS0124",
                "IATA": "TS0124",
                "SchDT": todayAtString(14, 10),
                "ActChoxDT": todayAtString(14, 30),
                "ActPax": 51
            })
            .asABorderForceOfficer()
            .navigateHome()
            .visit("/#terminal/T1/dashboard/15/?start=" + todayAtString(14, 15))
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
