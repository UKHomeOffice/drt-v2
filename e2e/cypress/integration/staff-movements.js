/// <reference types="Cypress" />

describe('Staff movements', function () {
  beforeEach(function () {
    cy.request('DELETE', '/v2/test/live/test/data');
    let schDT = new Date().toISOString().split("T")[0];
    cy.request('POST',
      '/v2/test/live/test/arrival',
      {
        "Operator": "Flybe",
        "Status": "On Chocks",
        "EstDT": schDT + "T00:55:00Z",
        "ActDT": schDT + "T00:55:00Z",
        "EstChoxDT": schDT + "T01:01:00Z",
        "ActChoxDT": schDT + "T01:05:00Z",
        "Gate": "46",
        "Stand": "44R",
        "MaxPax": 78,
        "ActPax": 51,
        "TranPax": 0,
        "RunwayID": "05L",
        "BaggageReclaimId": "05",
        "FlightID": 14710007,
        "AirportID": "MAN",
        "Terminal": "T1",
        "ICAO": "SA123",
        "IATA": "SA123",
        "Origin": "AMS",
        "SchDT": schDT + "T00:15:00Z"
      });
      waitAndHardReload();
  });

  function waitAndHardReload(waitTime = 500) {
      cy.wait(waitTime, {log: true});
      cy.reload(true, {log: true, timeout: 60000});
  }

  function addMovementFor1HourAt(numStaff, hour) {
    for (let i = 0; i < numStaff; i++) {
      waitAndHardReload();
      cy.get('.staff-adjustments > :nth-child(' + (hour + 1) + ') > :nth-child(3)').contains("+").then((el) => {
        el.click();
        cy.contains("Save").click();
      });
    }
  }

  function staffDeployedAtRow(row) {
    let selector = '#sticky-body > :nth-child(' + (row + 1) + ') > :nth-child(14)';
    return cy.get(selector);
  }

  function staffMovementsAtRow(row) {
    const selector = 'td.non-pcp:nth($index)';
    return cy.get(selector.replace('$index', row * 2 + 1));
  }

  function staffAvailableAtRow(row) {
    const selector = ':nth-child($index) > .staff-adjustments > :nth-child(1) > .deployed';
    return cy.get(selector.replace('$index', row + 1));
  }

  function staffOverTheDayAtSlot(slot) {
    const selector = '#available-staff tbody > :nth-child(2) > :nth-child(' + (slot + 1) + ')';
    return cy.get(selector);
  }

  function checkStaffNumbersOnDesksAndQueuesTabAre(numStaff) {
    waitAndHardReload(1000);
    [0, 1, 2, 3].map((row) => {
      staffMovementsAtRow(row).contains(numStaff);
      staffDeployedAtRow(row).contains(numStaff);
      staffAvailableAtRow(row).contains(numStaff);
    });

    staffMovementsAtRow(4).contains("0");
    staffDeployedAtRow(4).contains("0");
    staffAvailableAtRow(4).contains("0");
  }

  function checkStaffNumbersOnMovementsTabAre(numStaff) {
    cy.contains("Staff Movements").click().then(() => {
      [0, 1, 2, 3].map((slot) => { staffOverTheDayAtSlot(slot).contains(numStaff)});
      staffOverTheDayAtSlot(4).contains("0").end();
    }).end();
  }

  function removeXMovements(numToRemove) {
    cy.contains("Staff Movements").click().then(() => {
      for (let i = 1; i <= numToRemove; i++) {
        cy.get('.fa-remove').first().click().end();
        cy.get('.fa-remove').should('have.length', numToRemove - i).end();
      }
    }).end();
  }

  function navigateToMenuItem(itemName) {
    cy.get('.navbar-drt li').contains(itemName).click(5, 5, { force: true }).end();
  }

  function navigateToHome() {
    cy.visit('/v2/test/live').then(() => {
      cy.wait(500);
      cy.get('.navbar .container .navbar-drt').contains('DRT TEST').end();
    }).end();
  }

  function findAndClick(toFind) {
    cy.contains(toFind).click({ force: true }).end();
  }

  function choose24Hours() {
    cy.get('#current .date-selector .date-view-picker-container').contains('24 hours').click({force: true}).end();
    waitAndHardReload(1000);
  }

  describe('When adding staff movements on the desks and queues page', function () {
    it("Should update the available staff when 1 staff member is added for 1 hour", function () {
      navigateToHome();
      navigateToMenuItem('T1');
      choose24Hours();

      addMovementFor1HourAt(1, 0);
      checkStaffNumbersOnDesksAndQueuesTabAre(1);

      findAndClick('Staff Movements');
      checkStaffNumbersOnMovementsTabAre(1);
      removeXMovements(1);
    });

    it("Should update the available staff when 1 staff member is added for 1 hour twice", function () {
      navigateToHome();
      navigateToMenuItem('T1');
      choose24Hours();

      addMovementFor1HourAt(1, 0);
      checkStaffNumbersOnDesksAndQueuesTabAre(1);

      findAndClick('Staff Movements');
      checkStaffNumbersOnMovementsTabAre(1);

      findAndClick('Desks & Queues');
      addMovementFor1HourAt(1, 0);
      checkStaffNumbersOnDesksAndQueuesTabAre(2);

      findAndClick('Staff Movements');
      checkStaffNumbersOnMovementsTabAre(2);
      removeXMovements(2);
    });
  });
});
