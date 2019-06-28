let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

describe('Multi day export', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  const schDateString = moment().format("YYYY-MM-DD");

  const schTimeString = "00:55:00";
  const estTimeString = "01:05:00";
  const actTimeString = "01:07:00";
  const estChoxTimeString = "01:11:00";
  const actChoxTimeString = "01:12:00";

  const schString = schDateString + "T" + schTimeString + "Z";
  const estString = schDateString + "T" + estTimeString + "Z";
  const actString = schDateString + "T" + actTimeString + "Z";
  const estChoxString = schDateString + "T" + estChoxTimeString + "Z";
  const actChoxString = schDateString + "T" + actChoxTimeString + "Z";

  it('Allows you to download API splits using the API splits dialog', () => {
    cy
      .addFlight(estString, actString, estChoxString, actChoxString, schString)
      .asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
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
      .addFlight(estString, actString, estChoxString, actChoxString, schString)
      .asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
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
      .addFlight(estString, actString, estChoxString, actChoxString, schString)
      .asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
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
                    expect(response.body).to.contain(",38,4,1,,,13,5,0,,,1,0,1,,,0,0,0,2")
                  });
                });
            });
          });
      });
  });
});
