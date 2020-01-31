let moment = require('moment-timezone');
let todayAtUtcString = require('../support/functions').todayAtUtcString

require('moment/locale/en-gb');
moment.locale("en-gb");

describe('Multi day export', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  it('Allows you to download API splits using the API splits dialog', () => {
    cy
      .addFlight()
      .asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
      .selectCurrentTab()
      .choose24Hours()
      .get("#arrivalsTab").click().then(() => {
        cy.contains('Multi Day Export').click().then(() => {
          cy.get('.modal-body').contains("Export Desks")
            .get('.modal-body').contains("Export Arrivals")
            .get('.modal-footer').contains("Close").click()
            .get('.modal-body').should('not.be.visible');
        })
      });
  });

  it('The multi day export dialog is still visible after 5 seconds', () => {
    cy
      .addFlight()
      .asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
      .selectCurrentTab()
      .choose24Hours()
      .then(() => {
        cy.contains('Multi Day Export').click().then(() => {
          cy.wait(5000)
            .get('.modal-footer').contains("Close").click()
            .get('.modal-body').should('not.be.visible');
        });
      });
  });

  it('Exporting desks & queues results in a file with desk recommendations', () => {
    cy
      .addFlight({
        "SchDT": todayAtUtcString(0, 55),
        "ActChoxDT": todayAtUtcString(1, 2),
        "ActPax": 51
      })
      .asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
      .selectCurrentTab()
      .choose24Hours()
      .then(() => {
        cy
          .get("#desksAndQueues")
          .contains("38").then(() => {
            cy.contains('Multi Day Export').click().then(() => {
              cy
                .get('.modal-body')
                .contains("Export Desks")
                .should('have.attr', 'href')
                .then((href) => {
                  cy.request(href).then((response) => {
                    expect(response.body).to.contain(",38,10,1,,,13,0,1,,,1,0,1,,,0,0,0,3")
                  });
                });
            });
          });
      });
  });
});
