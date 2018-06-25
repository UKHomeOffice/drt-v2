describe('Staff movements', function () {
  before(function () {
    var schDT = new Date().toISOString().split("T")[0];
    cy.request('POST',
      '/v2/test/live/test/arrival',
      {
        "Operator": "Flybe",
        "Status": "On Chocks",
        "EstDT": schDT + "T00:55:00Z",
        "ActDT": schDT + "T00:55:00Z",
        "EstChoxDT": schDT + "T01:01:00Z",
        "ActChoxDT": schDT + "T01:05:00Z",
        "Gate": "46",
        "Stand": "44R",
        "MaxPax": 78,
        "ActPax": 51,
        "TranPax": 0,
        "RunwayID": "05L",
        "BaggageReclaimId": "05",
        "FlightID": 14710007,
        "AirportID": "MAN",
        "Terminal": "T1",
        "ICAO": "SA123",
        "IATA": "SA123",
        "Origin": "AMS",
        "SchDT": schDT + "T00:15:00Z"
      });
  });
  function addMovementFor1Hour() {
    cy.get('#sticky-body > :nth-child(1)').contains("+").click();
    cy.contains("Save").click();
  }

  describe('When adding staff movements on the desks and queues page', function () {
    it("Should update the available staff when 1 staff member is added for 1 hour", function () {
      cy.visit('/v2/test/live#terminal/T1/current/desksAndQueues//0/24');
      addMovementFor1Hour()
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
