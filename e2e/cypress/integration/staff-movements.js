let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

describe('Staff movements', function () {
  var userName = "Unknown";

  beforeEach(function () {
    var schDT = new Date().toISOString().split("T")[0];
    cy.deleteData()
      .addFlight();
  });

  function midnightThisMorning() {
    return moment().tz('Europe/London').startOf('day');
  }

  function shifts(numberOfStaff) {
    return {
      "shifts": [
        { "port_code": "test", "terminal": "T1", "staff": String(numberOfStaff), "shift_start": midnightThisMorning().toISOString() },
        { "port_code": "test", "terminal": "T1", "staff": String(numberOfStaff), "shift_start": midnightThisMorning().add('minute', 15).toISOString() },
        { "port_code": "test", "terminal": "T1", "staff": String(numberOfStaff), "shift_start": midnightThisMorning().add('minute', 30).toISOString() },
        { "port_code": "test", "terminal": "T1", "staff": String(numberOfStaff), "shift_start": midnightThisMorning().add('minute', 45).toISOString() }
      ]
    };
  }

  describe('When adding staff movements on the desks and queues page', function () {
    it("Should update the available staff when 1 staff member is added for 1 hour", function () {
      cy
        .asABorderForcePlanningOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .choose24Hours()
        .addMovementFor1HourAt(1, 0)
        .checkStaffMovementsOnDesksAndQueuesTabAre(1)
        .checkStaffDeployedOnDesksAndQueuesTabAre(1)
        .checkStaffAvailableOnDesksAndQueuesTabAre(1)
        .findAndClick('Staff Movements')
        .checkStaffNumbersOnMovementsTabAre(1)
        .checkUserNameOnMovementsTab(1, "Unknown")
        .removeXMovements(1);
    });

    it("Should update the available staff when 1 staff member is removed for 1 hour", function () {
      cy
        .asABorderForcePlanningOfficer()
        .saveShifts(shifts(2))
        .navigateHome()
        .navigateToMenuItem('T1')
        .choose24Hours()
        .removeMovementFor1HourAt(1, 0)
        .checkStaffMovementsOnDesksAndQueuesTabAre(-1)
        .checkStaffDeployedOnDesksAndQueuesTabAre(1)
        .checkStaffAvailableOnDesksAndQueuesTabAre(1)
        .findAndClick('Staff Movements')
        .checkStaffNumbersOnMovementsTabAre(1)
        .checkUserNameOnMovementsTab(1, "Unknown")
        .removeXMovements(1);
    });

    it("Should update the available staff when 1 staff member is added for 1 hour twice", function () {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .choose24Hours()
        .addMovementFor1HourAt(1, 0)
        .checkStaffMovementsOnDesksAndQueuesTabAre(1)
        .checkStaffDeployedOnDesksAndQueuesTabAre(1)
        .checkStaffAvailableOnDesksAndQueuesTabAre(1)
        .findAndClick('Staff Movements')
        .checkStaffNumbersOnMovementsTabAre(1)
        .checkUserNameOnMovementsTab(1, "Unknown")
        .findAndClick('Desks & Queues')
        .addMovementFor1HourAt(1, 0)
        .checkStaffMovementsOnDesksAndQueuesTabAre(2)
        .checkStaffDeployedOnDesksAndQueuesTabAre(2)
        .checkStaffAvailableOnDesksAndQueuesTabAre(2)
        .findAndClick('Staff Movements')
        .checkStaffNumbersOnMovementsTabAre(2)
        .checkUserNameOnMovementsTab(2, "Unknown")
        .removeXMovements(2);
    });
  });
});

Cypress.Commands.add('addMovementFor1HourAt', (numStaff, hour) => cy.addOrRemoveMovementFor1HourAt("add", numStaff, hour));

Cypress.Commands.add('removeMovementFor1HourAt', (numStaff, hour) => cy.addOrRemoveMovementFor1HourAt("remove", numStaff, hour));

Cypress.Commands.add('staffDeployedAtRow', (row) => {
  const selector = '#sticky-body > :nth-child(' + (row + 1) + ') > :nth-child(14)';
  cy.get(selector);
});

Cypress.Commands.add('staffMovementsAtRow', (row) => {
  const selector = 'td.non-pcp:nth($index)';
  cy.get(selector.replace('$index', row * 2 + 1));
});

Cypress.Commands.add('staffAvailableAtRow', (row) => {
  const selector = ':nth-child($index) > .staff-adjustments > :nth-child(1) > .deployed';
  cy.get(selector.replace('$index', row + 1));
});

Cypress.Commands.add('staffOverTheDayAtSlot', (slot) => {
  const selector = '#available-staff tbody > :nth-child(2) > :nth-child(' + (slot + 1) + ')';
  cy.get(selector);
});

Cypress.Commands.add('checkStaffAvailableOnDesksAndQueuesTabAre', (numStaff) => {
  [0, 1, 2, 3].map((row) => {
    cy.staffAvailableAtRow(row).contains(numStaff);
  });
  cy.staffAvailableAtRow(4, 0).contains(0);
});

Cypress.Commands.add('checkStaffMovementsOnDesksAndQueuesTabAre', (numStaff) => {
  [0, 1, 2, 3].map((row) => {
    cy.staffMovementsAtRow(row).contains(numStaff);
  });
  cy.staffMovementsAtRow(4).contains(0);
});

Cypress.Commands.add('checkStaffDeployedOnDesksAndQueuesTabAre', (numStaff) => {
  [0, 1, 2, 3].map((row) => {
    cy.staffDeployedAtRow(row).contains(numStaff);
  });
  cy.staffDeployedAtRow(4).contains(0);
});

Cypress.Commands.add('checkStaffNumbersOnMovementsTabAre', (numStaff) => {
  cy.contains("Staff Movements").click().then(() => {
    [0, 1, 2, 3].map((slot) => { cy.staffOverTheDayAtSlot(slot).contains(numStaff) });
    cy.staffOverTheDayAtSlot(4).contains("0");
  });
});

Cypress.Commands.add('removeXMovements', (numToRemove) => {
  cy.contains("Staff Movements").click().then(() => {
    for (let i = 1; i <= numToRemove; i++) {
      cy.get('.fa-remove').first().click().end();
      cy.get('.fa-remove').should('have.length', numToRemove - i).end();
    }
  });
});

Cypress.Commands.add('checkUserNameOnMovementsTab', (numMovements, userName) => {
  cy.get('.movement-display')
    .should('have.length', numMovements)
    .each(($element, index, $lis) => {
      cy.wrap($element).contains(userName);
      cy.wrap($element);
    });
});

Cypress.Commands.add('addOrRemoveMovementFor1HourAt', (addOrRemove, numStaff, hour) => {
  const buttonLabel = addOrRemove == "add" ? "+" : "-";
  const buttonNth = addOrRemove == "add" ? 3 : 1;

  cy.get('#sticky-body tr:nth-child(1) td').should('have.length', 15).then(() => {
    for (let i = 0; i < numStaff; i++) {
      cy.get('.staff-adjustments > :nth-child(' + (hour + 1) + ') > :nth-child(' + buttonNth + ')').contains(buttonLabel).then((el) => {
        el.click();
        cy
          .get('#staff-adjustment-dialogue')
          .get('.staff-adjustment--select-time-length')
          .select('60')
          .get('.btn-primary.staff-adjustment--save-cancel')
          .then((saveButton) => {
            saveButton.click();
          })
      });
    }
  });
});
