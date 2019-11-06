let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

describe('Viewing the terminal dashboard page', function () {

  beforeEach(function () {
    cy.deleteData();
  });

  it("should display a box for every queue in the terminal", () => {

    const schDateString = moment().hours(14).minutes(10).seconds(0).toISOString();
    cy
      .addFlightWithFlightCode("TS0100", schDateString)
      .asABorderForceOfficerWithRoles(["terminal-dashboard"])
      .navigateHome()
      .visit("/#terminal/T1/dashboard/summary/?start=2019-11-05T14:15:00.000Z")
      .get(".pax-bar")
      .contains("51 passengers")
      .get(".time-label")
      .contains("14:15 - 14:30")
      .get(".eeaDesk")
      .contains("38 pax joining")
      .get(".eeaDesk")
      .contains("6 min wait")
      .get(".eeaDesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-up')
      .get(".next-bar > a")
      .click()
      .get(".time-label")
      .contains("14:30 - 14:45")
      .get(".eeaDesk")
      .contains("10 min wait")
      .get(".eeaDesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-up')
      .get(".next-bar > a")
      .get(".prev-bar > a")
      .click()
      .get(".prev-bar > a")
      .click()
      .get(".time-label")
      .contains("14:00 - 14:15")
      .get(".eeaDesk")
      .contains("0 min wait")
      .get(".eeaDesk > :nth-child(4) > .fa")
      .should('have.class', 'fa-arrow-right')
  })

});
