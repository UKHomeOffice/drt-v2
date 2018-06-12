describe('Arrivals page', function () {
  const schDT = new Date().toISOString().split("T")[0];

  before(function () {
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
    cy.visit('/v2/test/live#terminal/T1/current/arrivals//0/24');
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
        "DocumentIssuingCountryCode": "ZIM",
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
        "NationalityCountryCode": "GBR"
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
        "NationalityCountryCode": "GBR"
      }
    ],
    "ScheduledTimeOfArrival": "00:15:00",
    "FileId": "fileID"
  };

  it('Displays a flight after it has been ingested via the live feed', function () {
    cy.get("#arrivals").contains("TS0123")
  });

  it('Allows you to view API splits in the flights export', function () {
    cy.request('POST',
      '/v2/test/live/test/manifest',
      manifest);
  });


});
