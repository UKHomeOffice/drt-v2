import moment from "moment-timezone";
moment.locale("en-gb");

import { todayAtUtcString } from '../support/time-helpers'

describe('Arrivals page', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  it('As an officer without the arrival-source role, i should not see any sources when clicking a flight code', () => {
    cy
      .addFlight(
        {
          "SchDT": todayAtUtcString(0, 55),
          "EstDT": todayAtUtcString(1, 5),
          "EstChoxDT": todayAtUtcString(1, 11),
          "ActDT": todayAtUtcString(1, 7),
          "ActChoxDT": todayAtUtcString(1, 12)
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .get('.before-now > :nth-child(2) > span > span')
      .should('have.attr', 'title', 'Schiphol, Amsterdam, Netherlands')
      .get('.arrivals__table__flight-code')
      .click()
      .get('.dashboard-arrivals-popup')
      .should('not.exist');
  });

  it('As an officer with the arrival-source role, clicking the flight code displays a popup displaying the sources', () => {
    cy
      .addFlight(
        {
          "SchDT": todayAtUtcString(0, 55),
          "EstDT": todayAtUtcString(1, 5),
          "EstChoxDT": todayAtUtcString(1, 11),
          "ActDT": todayAtUtcString(1, 7),
          "ActChoxDT": todayAtUtcString(1, 12)
        }
      )
      .asABorderForceOfficerWithRoles(["arrival-source"])
      .waitForFlightToAppear("TS0123")
      .get('.before-now > :nth-child(2) > span > span')
      .should('have.attr', 'title', 'Schiphol, Amsterdam, Netherlands')
      .get('.arrivals__table__flight-code > span')
      .click()
      .get('.dashboard-arrivals-popup')
      .contains('Port live');
  });

});
