describe('Staff movements', function () {
  before(function () {
    cy.request('DELETE', '/v2/test/live/test/data');
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

  function addMovementFor1HourAt(numStaff, hour) {
    cy.contains("Desks & Queues").click();

    for (let i = 0; i < numStaff; i++) {
      cy.get('#sticky-body > :nth-child(' + (hour + 1) + ')').contains("+").click();
      cy.contains("Save").click();
    }
  }

  function staffDeployedAtRow(row) {
    var selector = '#sticky-body > :nth-child(' + (row + 1) + ') > :nth-child(14)';
    return cy.get(selector);
  }

  function staffMovementsAtRow(row) {
    const selector = 'td.non-pcp:nth($index)';
    return cy.get(selector.replace('$index', row * 2 + 1));
  }

  function staffAvailableAtRow(row) {
    const selector = ':nth-child($index) > .staff-adjustments > :nth-child(1) > .deployed';
    return cy.get(selector.replace('$index', row + 1));
  }

  function staffOverTheDayAtSlot(slot) {
    const selector = 'tbody > :nth-child(2) > td';
    return cy.get(selector).eq(slot);
  }

  function checkStaffNumbersAre(numStaff) {
    cy.contains("Desks & Queues").click();

    [0, 1, 2, 3].map((row) => { staffMovementsAtRow(row).contains(numStaff) });
    staffMovementsAtRow(4).contains("0");

    [0, 1, 2, 3].map((row) => { staffDeployedAtRow(row).contains(numStaff) });
    staffDeployedAtRow(4).contains("0");

    [0, 1, 2, 3].map((row) => { staffAvailableAtRow(row).contains(numStaff) });
    staffAvailableAtRow(4).contains("0");

    cy.contains("Staff Movements").click();

    [0, 1, 2, 3].map((slot) => { staffOverTheDayAtSlot(slot).contains(numStaff)});
    staffOverTheDayAtSlot(4).contains("0");
  }

  function removeAllMovements() {
    cy.contains("Staff Movements").click();

    cy.get('.fa-remove').each(function (el) {
      el.click();
    });
  }

  describe('When adding staff movements on the desks and queues page', function () {
    it("Should update the available staff when 1 staff member is added for 1 hour", function () {
      cy.visit('/v2/test/live#terminal/T1/current/desksAndQueues//0/24');

      addMovementFor1HourAt(1, 0);
      checkStaffNumbersAre(1);

      removeAllMovements();
    });

    it("Should update the available staff when 1 staff member is added for 1 hour twice", function () {
      cy.visit('/v2/test/live#terminal/T1/current/desksAndQueues//0/24');
      addMovementFor1HourAt(1, 0);
      checkStaffNumbersAre(1);

      addMovementFor1HourAt(2, 0);
      checkStaffNumbersAre(3);

      removeAllMovements();
    });
  });

});
