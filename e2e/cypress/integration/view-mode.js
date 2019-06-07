let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

describe('View Modes', function () {

  const todayAsScheduledDate = moment().format("YYYY-MM-DD");
  const tomorrowAsScheduledDate = moment().add(1, "day").format("YYYY-MM-DD");
  const timeString = "00:55:00";

  function timeOnDay(dayString, timeString) {

    return dayString + "T" + timeString + "Z";
  }

  const timeStringToday = timeOnDay(todayAsScheduledDate, timeString)
  const timeStringTomorrow = timeOnDay(tomorrowAsScheduledDate, timeString)


  beforeEach(() => cy.deleteData());

  describe('When switching between view modes in the app', function () {

    it("should poll for updates when looking at future days", function () {
      const endOfTheDayTomorrow = timeOnDay(tomorrowAsScheduledDate, "22:59:59")

      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .chooseArrivalsTab()
        .get('#tomorrow').click()
        .addFlightWithFlightCode("TS0123", timeStringTomorrow)
        .addFlightWithFlightCode("TS0234", timeOnDay(todayAsScheduledDate, "04:45"))
        .addFlightWithFlightCode("TS0235", timeOnDay(todayAsScheduledDate, "05:45"))
        .get("#arrivals").contains("TS0123")

    });


    it("should poll for updates when looking at the live view", function () {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .chooseArrivalsTab()
        .choose24Hours()
        .addFlightWithFlightCode("TS0123", timeStringToday)
        .get("#arrivals").contains("TS0123")
    });

    it("should successfully load data when a url for a future date is requested", function () {
      const endOfTheDayTomorrow = timeOnDay(tomorrowAsScheduledDate, "22:59:59")

      cy
        .addFlightWithFlightCode("TS0123", timeStringTomorrow)
        .asABorderForceOfficer()
        .visit('#terminal/T1/current/arrivals/?date=' + endOfTheDayTomorrow)
        .get("#arrivals").contains("TS0123")

    });

    it("should poll for updates when switching from historic to live view", function () {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .chooseArrivalsTab()
        .get('#yesterday').click()
        .get('#arrivals').contains("No flights to display")
        .get('#today').click()
        .choose24Hours()
        .addFlightWithFlightCode("TS0123", timeOnDay(todayAsScheduledDate, "01:30"))
        .get('#arrivals').contains("TS0123")
        .addFlightWithFlightCode("TS0234", timeOnDay(todayAsScheduledDate, "04:45"))
        .get('#arrivals').contains("td", "TS0234", { log: true, timeout: 60000});
    });
  });
});
