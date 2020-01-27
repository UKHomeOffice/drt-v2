let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

const todayAtUtcString = require('../support/functions').todayAtUtcString
const inDaysAtTimeUtcString = require('../support/functions').inDaysAtTimeUtcString

describe('View Modes', function () {

  beforeEach(() => cy.deleteData());

  describe('When switching between view modes in the app', function () {

    it("should poll for updates when looking at future days", function () {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .selectCurrentTab()
        .chooseArrivalsTab()
        .get('#tomorrow').click()
        .reload()
        .addFlight({
          "ICAO": "TS0123",
          "IATA": "TS0123",
          "SchDT": inDaysAtTimeUtcString(1, 0, 55),
          "ActChoxDT": inDaysAtTimeUtcString(1, 0, 55)
        })
        .addFlight({
          "ICAO": "TS0234",
          "IATA": "TS0234",
          "SchDT": todayAtUtcString(4, 45),
          "ActChoxDT": todayAtUtcString(4, 45)
        })
        .addFlight({
          "ICAO": "TS0235",
          "IATA": "TS0235",
          "SchDT": todayAtUtcString(5, 45),
          "ActChoxDT": todayAtUtcString(5, 45)
        })
        .get("#arrivals")
        .contains("TS0123")
    });

    it("should poll for updates when looking at the live view", function () {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .selectCurrentTab()
        .chooseArrivalsTab()
        .choose24Hours()
        .addFlight({
          "ICAO": "TS0123",
          "IATA": "TS0123",
          "SchDT": todayAtUtcString(0, 55),
          "ActChoxDT": todayAtUtcString(0, 55)
        })
        .get("#arrivals")
        .contains("TS0123")
    });

    it("should successfully load data when a url for a future date is requested", function () {
      cy
        .addFlight({
          "ICAO": "TS0123",
          "IATA": "TS0123",
          "SchDT": inDaysAtTimeUtcString(1, 0, 55),
          "ActChoxDT": inDaysAtTimeUtcString(1, 0, 55)
        })
        .asABorderForceOfficer()
        .visit('#terminal/T1/current/arrivals/?date=' + inDaysAtTimeUtcString(1, 22, 59))
        .get("#arrivals")
        .contains("TS0123")

    });

    it("should poll for updates when switching from historic to live view", function () {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .selectCurrentTab()
        .chooseArrivalsTab()
        .get('#yesterday').click()
        .get('#arrivals').contains("No flights to display")
        .get('#today').click()
        .choose24Hours()
        .addFlight({
          "ICAO": "TS0123",
          "IATA": "TS0123",
          "SchDT": todayAtUtcString(1, 30),
          "ActChoxDT": todayAtUtcString(1, 30)
        })
        .get('#arrivals')
        .contains("TS0123")
        .addFlight({
          "ICAO": "TS0234",
          "IATA": "TS0234",
          "SchDT": todayAtUtcString(4, 45),
          "ActChoxDT": todayAtUtcString(4, 45)
        })
        .get('#arrivals')
        .contains("td", "TS0234", { log: true, timeout: 60000 });
    });
  });
});
