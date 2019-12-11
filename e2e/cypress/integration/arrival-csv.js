let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

let todayAtUtcString = require('../support/functions').todayAtUtcString

describe('Arrivals CSV Export', () => {

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
    cy.deleteData();
  });

  const ukPassport = {
    "DocumentIssuingCountryCode": "GBR",
    "PersonType": "P",
    "DocumentLevel": "Primary",
    "Age": "30",
    "DisembarkationPortCode": "TST",
    "InTransitFlag": "N",
    "DisembarkationPortCountryCode": "TST",
    "NationalityCountryEEAFlag": "EEA",
    "PassengerIdentifier": "",
    "DocumentType": "Passport",
    "PoavKey": "1",
    "NationalityCountryCode": "GBR"
  };

  const visaNational = {
    "DocumentIssuingCountryCode": "ZWE",
    "PersonType": "P",
    "DocumentLevel": "Primary",
    "Age": "30",
    "DisembarkationPortCode": "TST",
    "InTransitFlag": "N",
    "DisembarkationPortCountryCode": "TST",
    "NationalityCountryEEAFlag": "",
    "PassengerIdentifier": "",
    "DocumentType": "P",
    "PoavKey": "2",
    "NationalityCountryCode": "ZWE"
  };
  const nonVisaNational = {
    "DocumentIssuingCountryCode": "MRU",
    "PersonType": "P",
    "DocumentLevel": "Primary",
    "Age": "30",
    "DisembarkationPortCode": "TST",
    "InTransitFlag": "N",
    "DisembarkationPortCountryCode": "TST",
    "NationalityCountryEEAFlag": "",
    "PassengerIdentifier": "",
    "DocumentType": "P",
    "PoavKey": "3",
    "NationalityCountryCode": "MRU"
  };
  const b5JNational = {
    "DocumentIssuingCountryCode": "AUS",
    "PersonType": "P",
    "DocumentLevel": "Primary",
    "Age": "30",
    "DisembarkationPortCode": "TST",
    "InTransitFlag": "N",
    "DisembarkationPortCountryCode": "TST",
    "NationalityCountryEEAFlag": "",
    "PassengerIdentifier": "",
    "DocumentType": "P",
    "PoavKey": "3",
    "NationalityCountryCode": "AUS"
  };

  function manifest(passengerList) {
    return {
      "EventCode": "DC",
      "DeparturePortCode": "AMS",
      "VoyageNumberTrailingLetter": "",
      "ArrivalPortCode": "TST",
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

  function manifestWithPaxSplits(euPax, visaNationals, nonVisaNationals, b5JNationals) {
    const pax = Array(euPax).fill(ukPassport)
      .concat(Array(visaNationals).fill(visaNational))
      .concat(Array(nonVisaNationals).fill(nonVisaNational))
      .concat(Array(b5JNationals).fill(b5JNational))

    return manifest(pax)
  }

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
  const actApiHeaders = "API Actual - B5JSSK to Desk,API Actual - B5JSSK to eGates,API Actual - EEA (Machine Readable),API Actual - Non EEA (Non Visa),API Actual - Non EEA (Visa),API Actual - eGates";

  const headersWithActApi = headersWithoutActApi + "," + actApiHeaders;

  const totalPax = "51";
  const eGatePax = "25";
  const eeaDesk = "9";
  const nonEEADesk = "17";
  const dataWithoutActApi = "TS0123,TS0123,AMS,46/44R,On Chox," +
    schDateString + "," + schTimeLocal + "," + estTimeLocal + "," + actTimeLocal + "," + estChoxTimeLocal + "," + actChoxTimeLocal + "," + pcpTimeLocal + "," +
    totalPax + "," + totalPax + "," +
    eGatePax + "," + eeaDesk + "," + nonEEADesk + ",," +
    ",,,," +
    "13,37,1,";
  const actApiData = "4.0,6.0,5.0,7.0,10.0,19.0";

  const dataWithActApi = dataWithoutActApi + "," + actApiData;

  const csvWithNoApiSplits = headersWithoutActApi + "\n" + dataWithoutActApi;
  const csvWithAPISplits = headersWithActApi + "\n" + dataWithActApi;

  it('Does not show API splits in the flights export for regular users', () => {
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
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .addManifest(manifestWithPaxSplits(24, 10, 7, 10))
      .get('.pax-api')
      .request({
        method: 'GET',
        url: '/export/arrivals/' + millis + '/T1?startHour=0&endHour=24',
      })
      .then((resp) => {
        expect(resp.body)
          .to
          .equal(csvWithNoApiSplits, "Api splits incorrect for regular users");
      });
  });

  it('Allows you to view API splits in the flights export for users with api:view permission', () => {
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
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .addManifest(manifestWithPaxSplits(24, 10, 7, 10))
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
});
