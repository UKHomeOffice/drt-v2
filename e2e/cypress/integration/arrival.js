describe('Arrivals page', () => {

  const currentDateTime = new Date();
  const schDT = currentDateTime.toISOString().split("T")[0];
  const currentMillis = currentDateTime.getTime();

  function addFlight() {
    cy.request('POST',
      '/v2/test/live/test/arrival',
      {
        "Operator": "TestAir",
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
        "ICAO": "TS123",
        "IATA": "TS123",
        "Origin": "AMS",
        "SchDT": schDT + "T00:15:00Z"
      });
  }

  function loadManifestFixture() {
    cy.request('POST', '/v2/test/live/test/manifest', manifest);
    cy.request('GET', '/v2/test/live/export/arrivals/' + currentMillis + '/T1?startHour=0&endHour=24')
    cy.get('.pax-api');
  }

  function setRoles(roles) {
    cy.request("POST", 'v2/test/live/test/mock-roles', { "roles": roles})
  }

  before(() => {
    cy.request('DELETE', '/v2/test/live/test/data');
  });

  after(() => {
    cy.request('DELETE', '/v2/test/live/test/data');
  });

  const manifest = {
    "EventCode": "DC",
    "DeparturePortCode": "AMS",
    "VoyageNumberTrailingLetter": "",
    "ArrivalPortCode": "STN",
    "DeparturePortCountryCode": "MAR",
    "VoyageNumber": "0123",
    "VoyageKey": "key",
    "ScheduledDateOfDeparture": schDT,
    "ScheduledDateOfArrival": schDT,
    "CarrierType": "AIR",
    "CarrierCode": "TS",
    "ScheduledTimeOfDeparture": "06:30:00",
    "PassengerList": [
      {
        "DocumentIssuingCountryCode": "GBR",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": "30",
        "DisembarkationPortCode": "",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "",
        "NationalityCountryEEAFlag": "EEA",
        "PassengerIdentifier": "",
        "DocumentType": "P",
        "PoavKey": "1",
        "NationalityCountryCode": "GBR"
      },
      {
        "DocumentIssuingCountryCode": "ZWE",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": "30",
        "DisembarkationPortCode": "",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "",
        "NationalityCountryEEAFlag": "",
        "PassengerIdentifier": "",
        "DocumentType": "P",
        "PoavKey": "2",
        "NationalityCountryCode": "ZWE"
      },
      {
        "DocumentIssuingCountryCode": "AUS",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": "30",
        "DisembarkationPortCode": "",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "",
        "NationalityCountryEEAFlag": "",
        "PassengerIdentifier": "",
        "DocumentType": "P",
        "PoavKey": "3",
        "NationalityCountryCode": "AUS"
      }
    ],
    "ScheduledTimeOfArrival": "00:15:00",
    "FileId": "fileID"
  };

  const csvWithNoApiSplits = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est " +
    "Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical " +
    "EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal " +
    "Average Non-EEA,Terminal Average Fast Track" + "\n" +
    "TS0123,TS0123,AMS,46/44R,On Chocks," + schDT + ",01:15,01:55,01:55,02:01,02:05,02:18,51,51,17,0,34,,,,,,13,37,1,";

  const csvWithhAPISplits = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival," +
    "Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates," +
    "Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal " +
    "Average Non-EEA,Terminal Average Fast Track,API Actual - EEA (Machine Readable),API Actual - Non EEA (Non Visa)," +
    "API Actual - Non EEA (Visa),API Actual - eGates" + "\n" +
    "TS0123,TS0123,AMS,46/44R,On Chocks," + schDT + ",01:15,01:55,01:55,02:01,02:05,02:18,51,51,17,0,34,,,,,,13,37,1,,0.0,1.0,1.0,1.0";

  it('Displays a flight after it has been ingested via the live feed', () => {
    addFlight();
    cy.visit('/v2/test/live#terminal/T1/current/arrivals//0/24');
    cy.get("#arrivals").contains("TS0123")
  });

  it('Does not show API splits in the flights export for regular users', () => {
    loadManifestFixture();
    cy.request('POST', '/v2/test/live/test/manifest', manifest);
    cy.request('GET', '/v2/test/live/export/arrivals/' + currentMillis + '/T1?startHour=0&endHour=24').then((resp) => {
      expect(resp.body).to.equal(csvWithNoApiSplits)
    });
  });

  it('Allows you to view API splits in the flights export for users with api:view permission', () => {
    loadManifestFixture();
    setRoles(["api:view"]);
    cy.request({
      method: 'GET',
      url: '/v2/test/live/export/arrivals/' + currentMillis + '/T1?startHour=0&endHour=24',
    }).then((resp) => {
      expect(resp.body).to.equal(csvWithhAPISplits)
    })
  });
});
