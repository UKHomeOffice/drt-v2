import {moment} from '../support/time-helpers'

describe('Staff movements', () => {

  beforeEach(function () {
    cy.deleteData()
      .addFlight({});
  });

  const midnightThisMorning = (): moment.Moment => {
    return moment().tz('Europe/London').startOf('day');
  }

  const shifts = (numberOfStaff): object => {
    return {
      "shifts": [
        {
          "port_code": "test",
          "terminal": "T1",
          "staff": String(numberOfStaff),
          "shift_start": midnightThisMorning().toISOString()
        },
        {
          "port_code": "test",
          "terminal": "T1",
          "staff": String(numberOfStaff),
          "shift_start": midnightThisMorning().add('minute', 15).toISOString()
        },
        {
          "port_code": "test",
          "terminal": "T1",
          "staff": String(numberOfStaff),
          "shift_start": midnightThisMorning().add('minute', 30).toISOString()
        },
        {
          "port_code": "test",
          "terminal": "T1",
          "staff": String(numberOfStaff),
          "shift_start": midnightThisMorning().add('minute', 45).toISOString()
        }
      ]
    };
  }

  describe('When adding staff movements on the desks and queues page', () => {
    it("Should update the available staff when 1 staff member is added for 1 hour, and record the correct reason and " +
      "it should appear in the export", () => {
        const movementsCSV = "Terminal,Reason,Time,Staff Change,Made by" + "\n" +
          "T1,Other start," + midnightThisMorning().format("YYYY-MM-DD") + " 00:00,1,\"Unknown\"" + "\n" +
          "T1,Other end," + midnightThisMorning().format("YYYY-MM-DD") + " 01:00,-1,\"Unknown\""
        cy
          .asABorderForcePlanningOfficer()
          .navigateHome()
          .navigateToMenuItem('T1')
          .selectCurrentTab()
          .choose24Hours()
          .openAdjustmentDialogueForHour('add', 0)
          .adjustMinutes(60)
          .adjustStaffBy(1)
          .checkStaffMovementsOnDesksAndQueuesTabAre(1)
          .checkStaffAvailableOnDesksAndQueuesTabAre(1)
          .findAndClick('Staff Movements')
          .checkStaffNumbersOnMovementsTabAre(1)
          .checkUserNameOnMovementsTab(1, "Unknown")
          .get('#export-day-staff-movements')
          .then((el) => {
            const href = el.prop('href')
            cy.request({
              method: 'GET',
              url: href,
            }).then((resp) => {
              expect(resp.body).to.equal(movementsCSV, "Staff movements Export CSV is wrong.");
            })
          })
          .removeXMovements(1);
      });

    it("Should update the available staff when 1 staff member is removed for 1 hour", () => {
      cy.asABorderForcePlanningOfficer()
        .request('/')
        .then((response) => {
          const $html = Cypress.$(response.body)
          const csrfToken: any = $html.filter('input:hidden[name="csrfToken"]').val()
          cy.saveShifts(shifts(2), csrfToken)
            .navigateHome()
            .navigateToMenuItem('T1')
            .selectCurrentTab()
            .choose24Hours()
            .openAdjustmentDialogueForHour('remove', 0)
            .adjustMinutes(60)
            .adjustStaffBy(1)
            .checkStaffMovementsOnDesksAndQueuesTabAre(-1)
            .checkStaffAvailableOnDesksAndQueuesTabAre(1)
            .findAndClick('Staff Movements')
            .checkStaffNumbersOnMovementsTabAre(1)
            .checkUserNameOnMovementsTab(1, "Unknown")
            .removeXMovements(1);
        })
    });

    it("Should update the available staff when 2 staff members are added for 1 hour, and record the reason", () => {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .selectCurrentTab()
        .choose24Hours()
        .openAdjustmentDialogueForHour('add', 0)
        .selectReason('Case working')
        .selectAdditionalReason('extra case work')
        .adjustMinutes(60)
        .adjustStaffBy(2)
        .checkStaffMovementsOnDesksAndQueuesTabAre(2)
        .checkStaffAvailableOnDesksAndQueuesTabAre(2)
        .findAndClick('Staff Movements')
        .checkStaffNumbersOnMovementsTabAre(2)
        .checkUserNameOnMovementsTab(1, "Unknown")
        .checkReasonOnMovementsTab('Case working: extra case work')
        .removeXMovements(1);
    });

    it("BorderForce user should be able to adjust the staff movement", () => {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .selectCurrentTab()
        .choose24Hours()
        .get('.staff-deployment-adjustment-container').should('exist')
    });

    it("BorderForceReadOnly should not be able to adjust the staff movement", () => {
      cy
        .asABorderForceReadOnlyOfficer()
        .navigateHome()
        .navigateToMenuItem('T1')
        .selectCurrentTab()
        .choose24Hours()
        .get('.staff-deployment-adjustment-container').should('not.exist')
    });
  });
});

Cypress.Commands.add('selectReason', (reason) => {
  cy
    .get('.staff-adjustment--select-reason')
    .select(reason)
    .should('have.value', reason)
});

Cypress.Commands.add('selectAdditionalReason', (reason) => {
  cy
    .get('.staff-adjustment--additional-info')
    .type(reason)
});

Cypress.Commands.add('staffMovementsAtRow', (row) => {
  const selector = `td.non-pcp:nth(${row * 2 + 1})`
  cy.get(selector);
});

Cypress.Commands.add('staffAvailableAtRow', (row) => {
  const selector = `:nth-child(${row + 1}) > .staff-adjustments > :nth-child(1) > .deployed`;
  cy.get(selector);
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

Cypress.Commands.add('checkStaffNumbersOnMovementsTabAre', (numStaff) => {
  cy.contains("Staff Movements").click({force: true}).then(() => {
    [0, 1, 2, 3].map((slot) => {
      cy.staffOverTheDayAtSlot(slot).contains(numStaff)
    });
    cy.staffOverTheDayAtSlot(4).contains("0");
  });
});

Cypress.Commands.add('removeXMovements', (numToRemove) => {
  cy.contains("Staff Movements").click({force: true}).then(() => {
    for (let i = 1; i <= numToRemove; i++) {
      cy.get('.fa-remove').first().click({force: true}).end();
      cy.get('.fa-remove').should('have.length', numToRemove - i).end();
    }
  });
});

Cypress.Commands.add('checkUserNameOnMovementsTab', (numMovements, userName) => {
  cy.get('.movement-display')
    .should('have.length', numMovements)
    .each(($element) => {
      cy.wrap($element).contains(userName);
      cy.wrap($element);
    });
});

Cypress.Commands.add('checkReasonOnMovementsTab', (reason) => {
  cy
    .get('.staff-movements-list')
    .contains(reason);
});

Cypress.Commands.add('openAdjustmentDialogueForHour', (addOrRemove, hour) => {
  const buttonLabel = addOrRemove == "add" ? "+" : "-";
  const buttonNth = addOrRemove == "add" ? 3 : 1;

  cy
    .get('.staff-adjustments > :nth-child(' + (hour + 1) + ') > :nth-child(' + buttonNth + ')')
    .contains(buttonLabel)
    .click({force: true});
});

Cypress.Commands.add('adjustStaffBy', (numStaff) => {
  const adjustmentSelector = numStaff > 0 ? '.staff-adjustment--adjustment-button__increase' : '.staff-adjustment--adjustment-button__decrease';

  for (let i = 1; i < numStaff; i++) {
    cy.get(adjustmentSelector).click({force: true});
  }

  cy
    .get('.btn-primary.staff-adjustment--save-cancel')
    .click({force: true});
});

Cypress.Commands.add('adjustMinutes', (minutes) => {
  cy
    .get('.staff-adjustment--select-time-length')
    .select('' + minutes)
    .should('have.value', '' + minutes);
});
