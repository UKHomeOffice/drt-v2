import {moment} from '../support/time-helpers'


describe('Add and remove Shifts Staffing', () => {

  beforeEach(() => {
    cy.deleteData("");
  });

  const firstMidnightOfThisMonth = (): moment.Moment => {
    return moment().tz('Europe/London').startOf('month');
  }

  const firstMidnightOfNextMonth = (): moment.Moment => {
    return firstMidnightOfThisMonth().add(1, 'M');
  }

  const midDayToday = (): moment.Moment => {
    return moment().tz('Europe/London').startOf('day').hour(12).minute(0);
  }

  const shifts = (): object => {
    return {
      "shifts": [
        {
          "port_code": "test",
          "terminal": "T1",
          "staff": "1",
          "shift_start": firstMidnightOfThisMonth().toISOString()
        },
        {
          "port_code": "test",
          "terminal": "T1",
          "staff": "2",
          "shift_start": firstMidnightOfNextMonth().toISOString()
        }
      ]
    }
  }

  Cypress.Commands.add('addShiftForToday', (csrfParam) => {
    cy
      .request({
                 method: "POST",
                 url: "/test/replace-all-shifts",
                 body: {
                   "shifts": [
                     {
                       "port_code": "test",
                       "terminal": "T1",
                       "staff": "1",
                       "shift_start": midDayToday().toISOString()
                     }
                   ]
                 },
                 headers: {'Csrf-Token': csrfParam}
               });
  });

  Cypress.Commands.add('resetShifts', (csrfToken) => {
    cy.request({
                 method: "POST",
                 url: "/test/replace-all-shifts",
                 body: {
                   "shifts": [
                     {
                       "port_code": "test",
                       "terminal": "T1",
                       "staff": "0",
                       "shift_start": firstMidnightOfThisMonth().toISOString()
                     },
                     {
                       "port_code": "test",
                       "terminal": "T1",
                       "staff": "0",
                       "shift_start": firstMidnightOfNextMonth().toISOString()
                     },
                     {
                       "port_code": "test",
                       "terminal": "T1",
                       "staff": "0",
                       "shift_start": midDayToday().toISOString()
                     }
                   ]
                 },
                 headers: {'Csrf-Token': csrfToken}
               });
  });

  describe('When creating new shifts by clicking "Add shift"', () => {
    const cellToTest = ".htCore tbody :nth-child(1) :nth-child(2)";
    it("should display the assigned staff number in the table cell after adding a shift and remove staff number after removing shift", () => {
      Cypress.env('enableShiftPlanningChange', true);
      cy
      .asABorderForcePlanningOfficer()
      .request('/')
      .then((response) => {
        const $html = Cypress.$(response.body)
        const csrf: any = $html.filter('input:hidden[name="csrfToken"]').val()
        cy.saveShifts(shifts(), csrf).then(() => {
          const baseUrl = '#terminal/T1/shifts/60/';
          cy.visit(baseUrl)
            .clickCreateShiftPattern()
            .get('[data-cy="shift-name-input"]').type('Shift 1')
            .get('[data-cy="start-time-select"]').click()
            .get('[data-cy="select-start-time-option-12-00"]').click()
            .get('[data-cy="end-time-select"]').click()
            .get('[data-cy="select-end-time-option-18-00"]').click()
            .get('[data-cy="staff-number-input"]').type('5')
            .get('[data-cy="shift-continue-button"]').click()
            .get('[data-cy="shift-confirm-button"]').click()
            .clickAddShiftButton()
            .wait(500)
            .get('[data-cy="shift-name-input"]').type('Shift 2')
            .get('[data-cy="start-time-select"]').click()
            .get('[data-cy="select-start-time-option-16-00"]').click()
            .get('[data-cy="end-time-select"]').click()
            .get('[data-cy="select-end-time-option-20-00"]').click()
            .get('[data-cy="staff-number-input"]').type('5')
            .get('[data-cy="shift-continue-button"]').click()
            .get('[data-cy="shift-confirm-button"]').click()
            .wait(500)
            .get  (cellToTest, {timeout: 20000}).should('exist').contains("10")
            .get('[data-cy="shift-remove-0"]').click({ multiple: true })
            .get('[data-cy="shift-confirm-remove-button"]').click({ multiple: true })
            .wait(500)
            .get(cellToTest, { timeout: 1000 }).should('exist').should('not.contain', '10')
            .resetShifts(csrf);
        });
      });
    });
  });
});
