describe('Monthly Staffing', function () {
  const today = new Date();

  function setRoles(roles) {
    cy.request("POST", 'v2/test/live/test/mock-roles', {"roles": roles})
  }

  function firstMidnightOfThisMonth() {
    let year = today.getFullYear();
    let month = today.getMonth();
    return new Date(year, month, 1, 0, 0);
  }

  function firstMidnightOfNextMonth() {
    let year = today.getFullYear();
    let month = (today.getMonth()+1)%12;
    return new Date(year, month, 1, 0, 0);
  }

  function saveShifts() {
    cy.request("POST", "/v2/test/live/data/staff", {
      "shifts": [
        {"port_code": "test", "terminal": "T1", "staff": "1", "shift_start": firstMidnightOfThisMonth().toISOString()},
        {"port_code": "test", "terminal": "T1", "staff": "2", "shift_start": firstMidnightOfNextMonth().toISOString()}
      ]
    });
  }
  function resetShifts() {
    cy.request("POST", "/v2/test/live/data/staff", {
      "shifts": [
        {"port_code": "test", "terminal": "T1", "staff": "0", "shift_start": firstMidnightOfThisMonth().toISOString()},
        {"port_code": "test", "terminal": "T1", "staff": "0", "shift_start": firstMidnightOfNextMonth().toISOString()}
      ]
    });
  }

  function thisMonthDateString() {
    return today.toISOString().split("T")[0];
  }

  function nextMonthDateString() {
    let year = today.getFullYear();
    let month = (today.getMonth()+1)%12 + 1;
    return new Date(year, month, 1, 0, 0).toISOString().split("T")[0];
  }

  describe('When adding staff using the monthly staff view', function () {

    let cellToTest = ".htCore tbody :nth-child(1) :nth-child(2)";
    it("If I enter staff for the current month those staff should still be visible if I change months and change back", function () {
      saveShifts();

      setRoles(["staff:edit"]);

      cy.visit('/v2/test/live#terminal/T1/staffing/15///');
      cy.get(cellToTest).contains("1");


      cy.visit('/v2/test/live#terminal/T1/staffing/15/' + nextMonthDateString() +'//');
      cy.get(cellToTest).contains("2");
      cy.visit('/v2/test/live#terminal/T1/staffing/15/' + thisMonthDateString() +'//');
      cy.get(cellToTest).contains("1");

      resetShifts();
    });
  });
});
