import {moment} from '../support/time-helpers'

describe('Update Monthly Staffing', () => {

  beforeEach(() => {
    cy.deleteData();
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
  const cellToTest = ".htCore tbody :nth-child(1) :nth-child(2)";

  describe('Edit Staff Flow', () => {
    it('should edit staff successfully using UpdateStaffForTimeRangeForm', () => {
      cy
        .asABorderForcePlanningOfficer()
        .request('/')
        .then((response) => {
          const $html = Cypress.$(response.body)
          const csrf: any = $html.filter('input:hidden[name="csrfToken"]').val()
          cy.saveShifts(shifts(), csrf).then(() => {
            cy.visit('#terminal/T1/staffing/15/?date=2024-11-01')
            cy.get('[data-cy=edit-staff-button]').first().click().then(() => {
              cy.get('[data-testid="start-date-picker"]').type('{selectall}{backspace}').then(() => {
              cy.get('[data-testid="start-date-picker"]').type('01 November 2024')});
              cy.get('[data-testid="end-date-picker"]').type('{selectall}{backspace}').then(() => {
              cy.get('[data-testid="end-date-picker"]').type('02 November 2024')});
              cy.get('[data-testid="start-time-select"]').click().then(() => {
              cy.get('li[data-value="00:00"]').click()});
              cy.get('[data-testid="end-time-select"]').click().then(() => {
              cy.get('li[data-value="05:00"]').click()});
              cy.get('[data-testid="staff-number-input"]').clear().type('5');
              cy.get('[data-testid="save-staff-button"]').click().then(() => {
              cy.get(cellToTest).contains("5")});
              cy.resetShifts(csrf);
            });
          });
        });
    });
  });
});