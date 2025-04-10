import {moment} from '../support/time-helpers'


describe('Monthly Shifts Staffing', () => {

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

  const thisMonthDateString = (): string => {
    return moment().toISOString().split("T")[0];
  }

  const nextMonthDateString = (): string => {
    return moment().add(1, 'M').toISOString().split("T")[0];
  }

  describe('When creating new shifts by clicking "Get Start"', () => {
    const cellToTest = ".htCore tbody :nth-child(1) :nth-child(2)";
    it("should display the assigned staff name in the table cell after creating a shift", () => {
      Cypress.env('enableShiftPlanningChange', true);
      cy
        .asABorderForcePlanningOfficer()
        .request('/')
        .then((response) => {
          const $html = Cypress.$(response.body)
          const csrf: any = $html.filter('input:hidden[name="csrfToken"]').val()
          cy.saveShifts(shifts(), csrf).then(() => {
            const baseUrl = '#terminal/T1/shifts/15/';
            cy.visit(baseUrl)
              .clickShiftsGetStartedButton()
              .get('[data-cy="shift-name-input"]').type('Shift 1')
              .get('[data-cy="start-time-select"]').click()
              .get('[data-cy="select-start-time-option-00-00"]').click()
              .get('[data-cy="end-time-select"]').click()
              .get('[data-cy="select-end-time-option-18-00"]').click()
              .get('[data-cy="staff-number-input"]').type('8')
              .get('[data-cy="shift-continue-button"]').click()
              .get('[data-cy="shift-confirm-button"]').click()
              .wait(1000)
              .get(cellToTest, {timeout: 20000}).should('exist').contains("1")
              .visit(baseUrl + '?date=' + nextMonthDateString())
              .get(cellToTest, {timeout: 20000}).should('exist').contains("2")
              .visit(baseUrl + '?date=' + thisMonthDateString())
              .get(cellToTest, {timeout: 20000}).should('exist').contains("1")
              .resetShifts(csrf);
          });
        });
    });
  });
});
