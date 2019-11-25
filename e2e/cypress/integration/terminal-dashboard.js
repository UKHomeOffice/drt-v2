let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

describe('Viewing the terminal dashboard page', function () {

  beforeEach(function () {
    cy.deleteData();
  });

  it("should display a box for every queue in the terminal", () => {

    const schDateString = moment().hours(14).minutes(10).seconds(0).toISOString();
    const viewingDateString = moment().hours(14).minutes(15).seconds(0).toISOString();
    cy
      .addFlightWithFlightCode("TS0100", schDateString)
      .asABorderForceOfficerWithRoles(["terminal-dashboard"])
      .navigateHome()
      .visit("/#terminal/T1/dashboard/summary/?start=" + viewingDateString)
      .get(".pax-bar")
      .contains("51 passengers")
      .get(".time-label")
      .contains("14:15 - 14:30")
      .get(".eeadesk")
      .contains("38 pax joining")
      .get(".eeadesk")
      .contains("6 min wait")
      .get(".eeadesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-up')
      .get(".next-bar")
      .click()
      .get(".time-label")
      .contains("14:30 - 14:45")
      .get(".eeadesk")
      .contains("10 min wait")
      .get(".eeadesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-up')
      .get(".prev-bar")
      .click()
      .get(".prev-bar")
      .click()
      .get(".time-label")
      .contains("14:00 - 14:15")
      .get(".eeadesk")
      .contains("0 min wait")
      .get(".eeadesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-right')
  })

});
