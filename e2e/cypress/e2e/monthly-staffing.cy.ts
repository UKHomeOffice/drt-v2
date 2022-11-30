import moment from "moment-timezone";
moment.locale("en-gb");

describe('Monthly Staffing', () => {

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
        { "port_code": "test", "terminal": "T1", "staff": "1", "shift_start": firstMidnightOfThisMonth().toISOString() },
        { "port_code": "test", "terminal": "T1", "staff": "2", "shift_start": firstMidnightOfNextMonth().toISOString() }
      ]
    }
  }

  Cypress.Commands.add('addShiftForToday', () => {
    cy
      .asABorderForcePlanningOfficer()
      .request("POST", "/data/staff", {
        "shifts": [
          { "port_code": "test", "terminal": "T1", "staff": "1", "shift_start": midDayToday().toISOString() }
        ]
      });
  });

  Cypress.Commands.add('resetShifts', () => {
    cy
      .asABorderForcePlanningOfficer()
      .request("POST", "/data/staff", {
        "shifts": [
          { "port_code": "test", "terminal": "T1", "staff": "0", "shift_start": firstMidnightOfThisMonth().toISOString() },
          { "port_code": "test", "terminal": "T1", "staff": "0", "shift_start": firstMidnightOfNextMonth().toISOString() },
          { "port_code": "test", "terminal": "T1", "staff": "0", "shift_start": midDayToday().toISOString() }
        ]
      });
  });

  const thisMonthDateString = (): string => {
    return moment().toISOString().split("T")[0];
  }

  const nextMonthDateString = (): string => {
    return moment().add(1, 'M').toISOString().split("T")[0];
  }

  describe('No staff entered warning compontent', () => {
    // const cellToTest = ".htCore tbody :nth-child(1) :nth-child(2)";
    it("should display a warning when there are no staff entered for current period and hide it when there are.", () => {
      cy
        .asABorderForcePlanningOfficer()
        .visit('#terminal/T1/current/desksAndQueues/?timeRangeStart=0&timeRangeEnd=24')
        .get('.staff-alert')
        .contains("You have not entered any staff ")
        .addShiftForToday()
        .visit('#terminal/T1/current/desksAndQueues/?timeRangeStart=0&timeRangeEnd=24')
        .get('.staff-alert').should('not.exist')
        .resetShifts();
    });
  });

  describe('When adding staff using the monthly staff view', () => {
    const cellToTest = ".htCore tbody :nth-child(1) :nth-child(2)";
    it("If I enter staff for the current month those staff should still be visible if I change months and change back", () => {
      cy
        .asABorderForcePlanningOfficer()
        .saveShifts(shifts())
        .visit('#terminal/T1/staffing/15/')
        .get(cellToTest).contains("1")
        .visit('#terminal/T1/staffing/15/?date=' + nextMonthDateString())
        .get(cellToTest).contains("2")
        .visit('#terminal/T1/staffing/15/?date=' + thisMonthDateString())
        .get(cellToTest).contains("1")
        .resetShifts();
    });
  });
});
