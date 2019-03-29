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
      .setRoles(["test"])
      .addFlight(estString, actString, estChoxString, actChoxString, schString);
  });

  const manifest = {
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
    ]
  };

  const schTimeLocal = moment(schString).tz("Europe/London").format("HH:mm")
  const estTimeLocal = moment(estString).tz("Europe/London").format("HH:mm")
  const actTimeLocal = moment(actString).tz("Europe/London").format("HH:mm")
  const estChoxTimeLocal = moment(estChoxString).tz("Europe/London").format("HH:mm")
  const actChoxTimeLocal = moment(actChoxString).tz("Europe/London").format("HH:mm")
  const pcpTimeLocal = moment(actChoxString).add(13, "minutes").tz("Europe/London").format("HH:mm")

  const csvWithNoApiSplits = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est " +
    "Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical " +
    "EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal " +
    "Average Non-EEA,Terminal Average Fast Track" + "\n" +
    "TS0123,TS0123,AMS,46/44R,On Chocks," + schDateString + "," + schTimeLocal + "," + estTimeLocal + "," + actTimeLocal + "," + estChoxTimeLocal + "," + actChoxTimeLocal + "," + pcpTimeLocal + ",51,51,17,0,34,,,,,,13,37,1,";

  const csvWithAPISplits = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival," +
    "Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates," +
    "Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal " +
    "Average Non-EEA,Terminal Average Fast Track,API Actual - EEA (Machine Readable),API Actual - Non EEA (Non Visa)," +
    "API Actual - Non EEA (Visa),API Actual - eGates" + "\n" +
    "TS0123,TS0123,AMS,46/44R,On Chocks," + schDateString + "," + schTimeLocal + "," + estTimeLocal + "," + actTimeLocal + "," + estChoxTimeLocal + "," + actChoxTimeLocal + "," + pcpTimeLocal + ",51,51,17,0,34,,,,,,13,37,1,,0.0,1.0,1.0,1.0";

  it('Displays a flight after it has been ingested via the live feed', () => {
    cy
      .waitForFlightToAppear("TS0123")
  });

  it('Does not show API splits in the flights export for regular users', () => {
    cy
      .waitForFlightToAppear("TS0123")
      .addManifest(manifest)
      .get('.pax-api')
      .request('GET', '/export/arrivals/' + millis + '/T1?startHour=0&endHour=24').then((resp) => {
        expect(resp.body).to.equal(csvWithNoApiSplits);
      });
  });

  it('Allows you to view API splits in the flights export for users with api:view permission', () => {
    cy
      .setRoles(["test", "api:view"])
      .waitForFlightToAppear("TS0123")
      .addManifest(manifest)
      .get('.pax-api')
      .request({
        method: 'GET',
        url: '/export/arrivals/' + millis + '/T1?startHour=0&endHour=24',
      })
      .then((resp) => {
        expect(resp.body).to.equal(csvWithAPISplits)
      })
  });
});

Cypress.Commands.add('addManifest', (manifest) => cy.request('POST', '/test/manifest', manifest));

Cypress.Commands.add('waitForFlightToAppear', (flightCode) => {
  cy
    .navigateHome()
    .navigateToMenuItem('T1')
    .choose24Hours()
    .get("#arrivalsTab").click()
    .get("#arrivals")
    .contains(flightCode);
})

