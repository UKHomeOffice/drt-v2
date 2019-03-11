let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

describe('Multi day export', () => {
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

  const millis = moment(schString).unix() * 1000;

  function addFlight() {
    cy.setRoles(["test"]);
    cy.addFlight(estString, actString, estChoxString, actChoxString, schString);
  }


  before(() => {
    cy.setRoles(["test"]);
    cy.deleteData();
  });

  after(() => {
    cy.deleteData();
  });


  it('Allows you to download API splits using the API splits dialog', () => {
    addFlight()
    cy.visit('#terminal/T1/current/arrivals/?timeRangeStart=0&timeRangeEnd=24');

    cy.contains('Multi Day Export').click().end();
    cy.get('.modal-body').contains("Export Desks");
    cy.get('.modal-body').contains("Export Arrivals");

    cy.get('.modal-footer').contains("Close").click();
    cy.get('.modal-body').should('not.be.visible');
  });

  it('The multi day export dialog is still visible after 5 seconds', () => {
    addFlight()
    cy.visit('#terminal/T1/current/arrivals/?timeRangeStart=0&timeRangeEnd=24');

    cy.contains('Multi Day Export').click().end();

    cy.wait(5000);

    cy.get('.modal-footer').contains("Close").click();
    cy.get('.modal-body').should('not.be.visible');
  });


});
