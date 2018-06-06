describe('Arrivals page',
  function () {
    it('Displays a flight after it has been ingested via the live feed',
      function () {

        var schDT = new Date().toISOString().split("T")[0];
        cy.request('POST',
          '/v2/test/live/test/arrival',
          {
            "Operator": "Flybe",
            "Status": "On Chocks",
            "EstDT": schDT + "T10:55:00Z",
            "ActDT": schDT + "T10:55:00Z",
            "EstChoxDT": schDT + "T11:01:00Z",
            "ActChoxDT": schDT + "T11:05:00Z",
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
            "SchDT": schDT + "T09:55:00Z"
          });
        cy.visit('http://localhost:9000/v2/test/live#terminal/T1/current/arrivals//0/24');
        cy.get("#arrivals").contains("SA0123")
      });
  });
