import {moment} from '../support/time-helpers'


describe('Monthly Staffing', () => {

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

  describe('No staff entered warning component', () => {
    describe('When adding staff using the monthly staff view', () => {
      const cellToTest = ".htCore tbody :nth-child(1) :nth-child(2)";
      it("If I enter staff for the current month those staff should still be visible if I change months and change back", () => {
        cy
          .asABorderForcePlanningOfficer()
          .request('/')
          .then((response) => {
            const $html = Cypress.$(response.body)
            const csrf: any = $html.filter('input:hidden[name="csrfToken"]').val()
            cy.saveShifts(shifts(), csrf).then(() => {
              const baseUrl = '#terminal/T1/shifts/viewShifts/';
              cy.visit(baseUrl)
                .get(cellToTest, { timeout: 2000 }).should('exist').contains("1")
                .get('.staffing-controls-navigation').should($navigation => {
                  const buttons = $navigation.find('button')
                  expect(buttons).to.have.length(2)
                  const view = $navigation[0].ownerDocument.defaultView
                  buttons.each((_, button) => {
                    expect(button.classList.contains('drt-govuk-button--secondary-home-office')).to.be.true
                    expect(view.getComputedStyle(button).marginBottom).to.equal('0px')
                    expect(button.getBoundingClientRect().height).to.be.closeTo(40, 1)
                  })
                  expect(buttons[0].getBoundingClientRect().top).to.be.closeTo(buttons[1].getBoundingClientRect().top, 1)
                })
                .visit(baseUrl + '?date=' + nextMonthDateString())
                .get(cellToTest, { timeout: 2000 }).should('exist').contains("2")
                .visit(baseUrl + '?date=' + thisMonthDateString())
                .get(cellToTest, { timeout: 2000 }).should('exist').contains("1")
                .resetShifts(csrf);
            });
          });
      });
    });
  });
});
