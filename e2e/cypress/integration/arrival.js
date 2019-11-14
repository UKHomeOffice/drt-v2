let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

describe('Arrivals page', () => {

  const schDateString = moment().format("YYYY-MM-DD");

  const schTimeString = "00:55:00";
  const estTimeString = "01:05:00";
  const actTimeString = "01:07:00";
  const estChoxTimeString = "01:11:00";
  const actChoxTimeString = "01:12:00";

  const schString = schDateString + "T" + schTimeString + "Z";
  const estString = schDateString + "T" + estTimeString + "Z";
  const actString = schDateString + "T" + actTimeString + "Z";
  const estChoxString = schDateString + "T" + estChoxTimeString + "Z";
  const actChoxString = schDateString + "T" + actChoxTimeString + "Z";

  const millis = moment(schString).unix() * 1000;

  beforeEach(function () {
    var schDT = new Date().toISOString().split("T")[0];
    cy.deleteData()
      .addFlight(estString, actString, estChoxString, actChoxString, schString);
  });

  const passengerListMixed = [
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
      "DocumentType": "Passport",
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
  ];

  function manifest(passengerList) {
    return {
      "EventCode": "DC",
      "DeparturePortCode": "AMS",
      "VoyageNumberTrailingLetter": "",
      "ArrivalPortCode": "STN",
      "DeparturePortCountryCode": "MAR",
      "VoyageNumber": "0123",
      "VoyageKey": "key",
      "ScheduledDateOfDeparture": schDateString,
      "ScheduledDateOfArrival": schDateString,
      "CarrierType": "AIR",
      "CarrierCode": "TS",
      "ScheduledTimeOfDeparture": "06:30:00",
      "ScheduledTimeOfArrival": schTimeString,
      "FileId": "fileID",
      "PassengerList": passengerList
    }
  };

  const schTimeLocal = moment(schString).tz("Europe/London").format("HH:mm")
  const estTimeLocal = moment(estString).tz("Europe/London").format("HH:mm")
  const actTimeLocal = moment(actString).tz("Europe/London").format("HH:mm")
  const estChoxTimeLocal = moment(estChoxString).tz("Europe/London").format("HH:mm")
  const actChoxTimeLocal = moment(actChoxString).tz("Europe/London").format("HH:mm")
  const pcpTimeLocal = moment(actChoxString).add(13, "minutes").tz("Europe/London").format("HH:mm")

  const headersWithoutActApi = "IATA,ICAO,Origin,Gate/Stand,Status," +
    "Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP," +
    "Total Pax,PCP Pax," +
    "API e-Gates,API EEA,API Non-EEA,API Fast Track," +
    "Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track," +
    "Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track";
  const actApiHeaders = "API Actual - B5JSSK to Desk,API Actual - B5JSSK to eGates,API Actual - EEA (Machine Readable),API Actual - Non EEA (Visa),API Actual - eGates";

  const headersWithActApi = headersWithoutActApi + "," + actApiHeaders;

  const dataWithoutActApi = "TS0123,TS0123,AMS,46/44R,On Chocks," +
    schDateString + "," + schTimeLocal + "," + estTimeLocal + "," + actTimeLocal + "," + estChoxTimeLocal + "," + actChoxTimeLocal + "," + pcpTimeLocal + "," +
    "51,51," +
    "24,10,17,," +
    ",,,," +
    "13,37,1,";
  const actApiData = "0.0,1.0,0.0,1.0,1.0";

  const dataWithActApi = dataWithoutActApi + "," + actApiData;

  const csvWithNoApiSplits = headersWithoutActApi + "\n" + dataWithoutActApi;
  const csvWithAPISplits = headersWithActApi + "\n" + dataWithActApi;

  it('Displays a flight after it has been ingested via the live feed', () => {
    cy
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .get('.before-now > :nth-child(2) > span > span')
      .should('have.attr', 'title', 'Schiphol, Amsterdam, Netherlands')
  });

  it('Does not show API splits in the flights export for regular users', () => {
    cy
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .addManifest(manifest(passengerListMixed))
      .get('.pax-api')
      .request({
        method: 'GET',
        url: '/export/arrivals/' + millis + '/T1?startHour=0&endHour=24',
      })
      .then((resp) => {
        expect(resp.body).to.equal(csvWithNoApiSplits, "Api splits incorrect for regular users");
      });
  });

  it('Allows you to view API splits in the flights export for users with api:view permission', () => {
    cy
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .addManifest(manifest(passengerListMixed))
      .get('.pax-api')
      .asABorderForceOfficerWithRoles(["api:view"])
      .request({
        method: 'GET',
        url: '/export/arrivals/' + millis + '/T1?startHour=0&endHour=24',
      })
      .then((resp) => {
        expect(resp.body).to.equal(csvWithAPISplits, "Api splits incorrect for users with API reporting role")
      })
  });

  const passengerListBadDocTypes = [
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
      "DocumentType": "",
      "PoavKey": "1",
      "NationalityCountryCode": "GBR"
    },
    {
      "DocumentIssuingCountryCode": "FRA",
      "PersonType": "P",
      "DocumentLevel": "Primary",
      "Age": "30",
      "DisembarkationPortCode": "",
      "InTransitFlag": "N",
      "DisembarkationPortCountryCode": "",
      "NationalityCountryEEAFlag": "EEA",
      "PassengerIdentifier": "",
      "DocumentType": "passport",
      "PoavKey": "1",
      "NationalityCountryCode": "GBR"
    }
  ];


  it('handles manifests where the doctype is specified incorectly or left off', () => {
    const eGatesCellSelector = ':nth-child(12) > span';
    const eeaCellSelector = ':nth-child(13) > span';
    const nonEeaCellSelector = ':nth-child(14) > span';
    cy
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .addManifest(manifest(passengerListBadDocTypes))
      .get('.pax-api')
      .get(eGatesCellSelector)
      .contains("41")
      .get(eeaCellSelector)
      .contains("10")
      .get(nonEeaCellSelector)
      .contains("0")
  });
});


