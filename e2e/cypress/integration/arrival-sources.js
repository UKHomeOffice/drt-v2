let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

let todayAtUtcString = require('../support/functions').todayAtUtcString

describe('Arrivals page', () => {

  beforeEach(function () {
    cy.deleteData();
  });

//  const ukPassport = {
//    "DocumentIssuingCountryCode": "GBR",
//    "PersonType": "P",
//    "DocumentLevel": "Primary",
//    "Age": "30",
//    "DisembarkationPortCode": "TST",
//    "InTransitFlag": "N",
//    "DisembarkationPortCountryCode": "TST",
//    "NationalityCountryEEAFlag": "EEA",
//    "PassengerIdentifier": "",
//    "DocumentType": "Passport",
//    "PoavKey": "1",
//    "NationalityCountryCode": "GBR"
//  };
//
//  const inTransitPassenger = {
//    "DocumentIssuingCountryCode": "GBR",
//    "PersonType": "P",
//    "DocumentLevel": "Primary",
//    "Age": "30",
//    "DisembarkationPortCode": "TST",
//    "InTransitFlag": "Y",
//    "DisembarkationPortCountryCode": "TST",
//    "NationalityCountryEEAFlag": "EEA",
//    "PassengerIdentifier": "",
//    "DocumentType": "Passport",
//    "PoavKey": "1",
//    "NationalityCountryCode": "GBR"
//  };
//  const visaNational = {
//    "DocumentIssuingCountryCode": "ZWE",
//    "PersonType": "P",
//    "DocumentLevel": "Primary",
//    "Age": "30",
//    "DisembarkationPortCode": "TST",
//    "InTransitFlag": "N",
//    "DisembarkationPortCountryCode": "TST",
//    "NationalityCountryEEAFlag": "",
//    "PassengerIdentifier": "",
//    "DocumentType": "P",
//    "PoavKey": "2",
//    "NationalityCountryCode": "ZWE"
//  };
//  const nonVisaNational = {
//    "DocumentIssuingCountryCode": "MRU",
//    "PersonType": "P",
//    "DocumentLevel": "Primary",
//    "Age": "30",
//    "DisembarkationPortCode": "TST",
//    "InTransitFlag": "N",
//    "DisembarkationPortCountryCode": "TST",
//    "NationalityCountryEEAFlag": "",
//    "PassengerIdentifier": "",
//    "DocumentType": "P",
//    "PoavKey": "3",
//    "NationalityCountryCode": "MRU"
//  };
//  const b5JNational = {
//    "DocumentIssuingCountryCode": "AUS",
//    "PersonType": "P",
//    "DocumentLevel": "Primary",
//    "Age": "30",
//    "DisembarkationPortCode": "TST",
//    "InTransitFlag": "N",
//    "DisembarkationPortCountryCode": "TST",
//    "NationalityCountryEEAFlag": "",
//    "PassengerIdentifier": "",
//    "DocumentType": "P",
//    "PoavKey": "3",
//    "NationalityCountryCode": "AUS"
//  };
//
//  function manifest(passengerList) {
//    const schDateString = moment().format("YYYY-MM-DD");
//    const schTimeString = "00:55:00";
//
//    return {
//      "EventCode": "DC",
//      "DeparturePortCode": "AMS",
//      "VoyageNumberTrailingLetter": "",
//      "ArrivalPortCode": "TST",
//      "DeparturePortCountryCode": "MAR",
//      "VoyageNumber": "0123",
//      "VoyageKey": "key",
//      "ScheduledDateOfDeparture": schDateString,
//      "ScheduledDateOfArrival": schDateString,
//      "CarrierType": "AIR",
//      "CarrierCode": "TS",
//      "ScheduledTimeOfDeparture": "06:30:00",
//      "ScheduledTimeOfArrival": schTimeString,
//      "FileId": "fileID",
//      "PassengerList": passengerList
//    }
//  };

  it('Displays a flight after it has been ingested via the live feed', () => {
    cy
      .addFlight(
        {
          "SchDT": todayAtUtcString(0, 55),
          "EstDT": todayAtUtcString(1, 5),
          "EstChoxDT": todayAtUtcString(1, 11),
          "ActDT": todayAtUtcString(1, 7),
          "ActChoxDT": todayAtUtcString(1, 12)
        }
      )
      .asABorderForceOfficerWithRoles(["arrival-source"])
      .waitForFlightToAppear("TS0123")
      .get('.before-now > :nth-child(2) > span > span')
      .should('have.attr', 'title', 'Schiphol, Amsterdam, Netherlands')
      .get('.arrivals__table__flight-code')
      .click()
      .get('.dashboard-arrivals-popup')
      .contains('Port Live')
  });

});
