let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

describe('Monthly Staffing', function () {

  beforeEach(function () {
    cy.deleteData()
      .setRoles(["test"]);
  });

  function firstMidnightOfThisMonth() {
    return moment().tz('Europe/London').startOf('month');
  }

  function firstMidnightOfNextMonth() {
    return firstMidnightOfThisMonth().add(1, 'M');
  }

  Cypress.Commands.add('saveShifts', ()=> {
    cy
      .request("POST", "/data/staff", {
        "shifts": [
          {"port_code": "test", "terminal": "T1", "staff": "1", "shift_start": firstMidnightOfThisMonth().toISOString()},
          {"port_code": "test", "terminal": "T1", "staff": "2", "shift_start": firstMidnightOfNextMonth().toISOString()}
        ]
      });
  });

  Cypress.Commands.add('resetShifts', () => {
    cy
      .request("POST", "/data/staff", {
        "shifts": [
          {"port_code": "test", "terminal": "T1", "staff": "0", "shift_start": firstMidnightOfThisMonth().toISOString()},
          {"port_code": "test", "terminal": "T1", "staff": "0", "shift_start": firstMidnightOfNextMonth().toISOString()}
        ]
      });
  });

  function thisMonthDateString() {
    return moment().toISOString().split("T")[0];
  }

  function nextMonthDateString() {
    return moment().add(1, 'M').toISOString().split("T")[0];
  }

  describe('When adding staff using the monthly staff view', function () {

    let cellToTest = ".htCore tbody :nth-child(1) :nth-child(2)";
    it("If I enter staff for the current month those staff should still be visible if I change months and change back", function () {
      cy
        .saveShifts()
        .setRoles(["staff:edit", "test"])
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
