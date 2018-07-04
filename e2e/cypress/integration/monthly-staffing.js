describe('Monthly staffing', function () {
  before(function () {
    cy.request('DELETE', '/v2/test/live/test/data');
    var schDT = new Date().toISOString().split("T")[0];
  });
  function addMovementFor1Hour() {
    cy.get('#sticky-body > :nth-child(1)').contains("+").click();
    cy.contains("Save").click();
  }

  describe('When adding staff movements on the desks and queues page', function () {
    it("Should update the available staff when 1 staff member is added for 1 hour", function () {
      cy.visit('/v2/test/live#terminal/T1/current/desksAndQueues//0/24');
      addMovementFor1Hour();
      var staffDeployedSelector = '#sticky-body > :nth-child(1) > :nth-child(14)';
      cy.get(staffDeployedSelector).contains("1");
      cy.contains("Staff Movements").click();
      cy.get('tbody > :nth-child(2) > td').eq(0).contains("1");
      cy.get('tbody > :nth-child(2) > td').eq(1).contains("1");
      cy.get('tbody > :nth-child(2) > td').eq(2).contains("1");
      cy.get('tbody > :nth-child(2) > td').eq(3).contains("1");
      cy.get('tbody > :nth-child(2) > td').eq(4).contains("0");
      cy.get('.fa-remove').click()
    });
    it("Should update the available staff when 1 staff member is added for 1 hour twice", function () {
      cy.visit('/v2/test/live#terminal/T1/current/desksAndQueues//0/24');
      addMovementFor1Hour();
      addMovementFor1Hour();
      var staffDeployedSelector = '#sticky-body > :nth-child(1) > :nth-child(14)';
      cy.get(staffDeployedSelector).contains("1");
      cy.contains("Staff Movements").click();
      cy.get('tbody > :nth-child(2) > td').eq(0).contains("2");
      cy.get('tbody > :nth-child(2) > td').eq(1).contains("2");
      cy.get('tbody > :nth-child(2) > td').eq(2).contains("2");
      cy.get('tbody > :nth-child(2) > td').eq(3).contains("2");
      cy.get('tbody > :nth-child(2) > td').eq(4).contains("0");
      cy.get('.fa-remove').each(function (el) {
        el.click()
      })

    });
  });

});
