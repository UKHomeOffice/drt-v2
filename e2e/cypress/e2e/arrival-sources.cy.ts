import {todayAsLocalString} from '../support/time-helpers'


describe('Arrival sources', () => {

    beforeEach(function () {
        cy.deleteData('nocheck');
    });

    it('As an officer without the arrival-source role, i should not see any sources when clicking a flight code', () => {
        cy
        .addFlight(
          {
              "SchDT": todayAsLocalString(0, 55),
              "EstDT": todayAsLocalString(1, 5),
              "EstChoxDT": todayAsLocalString(1, 11),
              "ActDT": todayAsLocalString(1, 7),
              "ActChoxDT": todayAsLocalString(1, 12)
          }
        )
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .get('.arrivals__table__flight-code-value')
        .click({force: true})
        .get('.dashboard-arrivals-popup')
        .should('not.exist');
    });

    it('As an officer with the arrival-source role, clicking the flight code displays a popup displaying the sources', () => {
        cy.addFlight(
          {
              "SchDT": todayAsLocalString(0, 55),
              "EstDT": todayAsLocalString(1, 5),
              "EstChoxDT": todayAsLocalString(1, 11),
              "ActDT": todayAsLocalString(1, 7),
              "ActChoxDT": todayAsLocalString(1, 12)
          }
        )
        .asABorderForceOfficerWithRoles(["arrival-source"], 'nocheck')
        .waitForFlightToAppear("TS0123")
        .get('.arrivals__table__flight-code-value')
        .click({force: true})
        .get('.dashboard-arrivals-popup')
        .contains('Port live');
    });

});
