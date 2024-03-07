import {todayAtUtcString, inDaysAtTimeUtcString} from '../support/time-helpers'

describe('View Modes', () => {

  beforeEach(() => cy.deleteData());

  describe('When switching between view modes in the app', () => {

    it("should poll for updates when looking at future days", () => {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .selectCurrentTab()
        .chooseArrivalsTab()
        .get('#tomorrow').click({force: true})
        .reload()
        .get('input:hidden[name="csrfToken"]').should('exist').invoke('val').then((csrfToken) => {
          cy.addFlight({
            "ICAO": "TS0123",
            "IATA": "TS0123",
            "SchDT": inDaysAtTimeUtcString(1, 0, 55),
            "ActChoxDT": inDaysAtTimeUtcString(1, 0, 55)
          }, csrfToken.toString())
            .addFlight({
              "ICAO": "TS0234",
              "IATA": "TS0234",
              "SchDT": todayAtUtcString(4, 45),
              "ActChoxDT": todayAtUtcString(4, 45)
            }, csrfToken.toString())
            .addFlight({
              "ICAO": "TS0235",
              "IATA": "TS0235",
              "SchDT": todayAtUtcString(5, 45),
              "ActChoxDT": todayAtUtcString(5, 45)
            }, csrfToken.toString())
            .get("#arrivals")
            .contains("TS0123")
      })

    });

    it("should poll for updates when looking at the live view", () => {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .selectCurrentTab()
        .chooseArrivalsTab()
        .choose24Hours()
        .get('input:hidden[name="csrfToken"]').should('exist').invoke('val').then((csrfToken) => {
          cy.addFlight({
            "ICAO": "TS0123",
            "IATA": "TS0123",
            "SchDT": todayAtUtcString(0, 55),
            "ActChoxDT": todayAtUtcString(0, 55)
          }, csrfToken.toString())
        })
        .get("#arrivals")
        .contains("TS0123")
    });

    it("should successfully load data when a url for a future date is requested", () => {
      cy.asABorderForceOfficer()
        .visit('#terminal/T1/current/arrivals/?date=' + inDaysAtTimeUtcString(1, 22, 59))
        .choose24Hours()
        .get('input:hidden[name="csrfToken"]').should('exist').invoke('val').then((csrfToken) => {
          cy.addFlight({
            "ICAO": "TS0123",
            "IATA": "TS0123",
            "SchDT": inDaysAtTimeUtcString(1, 0, 55),
            "ActChoxDT": inDaysAtTimeUtcString(1, 0, 55)
          }, csrfToken.toString())
        })
        .get("#arrivals")
        .contains("TS0123")
    });

    it("should poll for updates when switching from historic to live view", () => {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .selectCurrentTab()
        .chooseArrivalsTab()
        .get('#yesterday').click({force: true})
        .wait(100)
        .get('#arrivals').contains("No flights found")
        .get('#today').click({force: true})
        .wait(100)
        .choose24Hours()
        .get('input:hidden[name="csrfToken"]').should('exist').invoke('val').then((csrfToken) => {
          cy.addFlight({
            "ICAO": "TS0123",
            "IATA": "TS0123",
            "SchDT": todayAtUtcString(1, 30),
            "ActChoxDT": todayAtUtcString(1, 30)
          }, csrfToken.toString())
            .get('#arrivals')
            .contains("TS0123")
            .addFlight({
              "ICAO": "TS0234",
              "IATA": "TS0234",
              "SchDT": todayAtUtcString(4, 45),
              "ActChoxDT": todayAtUtcString(4, 45)
            }, csrfToken.toString())
      })

        .get('#arrivals')
        .contains("td", "TS0234", {log: true, timeout: 60000});
    });
  });
});
