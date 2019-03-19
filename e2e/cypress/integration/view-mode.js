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

  beforeEach(() => {
    cy.setRoles(["test"]);
    cy.deleteData();
  });

  describe('When switching between view modes in the app', function () {

    it("should poll for updates when looking at the live view", function () {
      cy.visit('#terminal/T1/current/arrivals/?timeRangeStart=0&timeRangeEnd=24');
      cy.addFlight(timeStringToday)
      cy.get("#arrivals").contains("TS0123")
    });

    it("should poll for updates when looking at future days", function () {
      const endOfTheDayTomorrow = timeOnDay(tomorrowAsScheduledDate, "23:59:59")

      cy.visit('#terminal/T1/current/arrivals/?date=' + endOfTheDayTomorrow);
      cy.addFlight(timeStringTomorrow)
      cy.get("#arrivals").contains("TS0123")

    });

    it("should poll for updates when switching from historic to live view", function () {
      cy.visit('#terminal/T1/current/arrivals/?timeRangeStart=0&timeRangeEnd=24');
      cy.get('#yesterday').click();
      cy.get('#terminal-data').contains("Nothing to show for this time period");
      cy.wait(2000);
      cy.get('#tomorrow').click();

      cy.addFlight(timeStringTomorrow).then(() => {
        cy.get("#arrivals").contains("TS0123").then(() => {
          cy.addFlightWithFlightCode("TS0234", timeStringTomorrow);
          cy.get("#arrivals").contains("TS0234");
        });
      });
    });
  });
});
