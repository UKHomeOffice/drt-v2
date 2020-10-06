import moment from "moment-timezone";

moment.locale("en-gb");

import {manifestForDateTime, passengerList} from '../support/manifest-helpers'
import {todayAtUtcString} from '../support/time-helpers'
import {todayAtUtc} from '../support/time-helpers';

describe('Arrivals page filter passenger flight', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  it('Filter flight by passenger flight only when LoadFactor is zero and valid service type', () => {
    cy
      .addFlight(
        {
          "Status" : "ACL Forecast",
          "SchDT": todayAtUtcString(0, 55),
          "EstDT": todayAtUtcString(1, 5),
          "EstChoxDT": todayAtUtcString(16, 11),
          "ActDT": todayAtUtcString(16, 7),
          "ActChoxDT": todayAtUtcString(16, 45),
          "ActPax": 300,
          "ServiceType" : "J",
          "LoadFactor" : 0
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .get("#toggle-non-passenger-flights-filter").click()
      .get('#arrivals > div').contains("No flights to display");

  });

  it('Not Filter flight by passenger flight when LoadFactor is not zero and valid service type', () => {
    cy
      .addFlight(
          {
            "Status" : "ACL Forecast",
            "SchDT": todayAtUtcString(0, 55),
            "EstDT": todayAtUtcString(1, 5),
            "EstChoxDT": todayAtUtcString(16, 11),
            "ActDT": todayAtUtcString(16, 7),
            "ActChoxDT": todayAtUtcString(16, 45),
            "ActPax": 300,
            "ServiceType" : "J",
            "LoadFactor" : 0.8
          }
        )
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .get("#toggle-non-passenger-flights-filter").click()
        .get(".arrivals__table__flight-code > div:nth(0)").contains("TS0123")
    });

  it('Not Filter flight by passenger flight for ACL Forecast when LoadFactor is not zero and invalid service type', () => {
    cy
    .addFlight(
      {
        "Status" : "ACL Forecast",
        "SchDT": todayAtUtcString(0, 55),
        "EstDT": todayAtUtcString(1, 5),
        "EstChoxDT": todayAtUtcString(16, 11),
        "ActDT": todayAtUtcString(16, 7),
        "ActChoxDT": todayAtUtcString(16, 45),
        "ActPax": 300,
        "LoadFactor" : 0.8
      }
    )
    .asABorderForceOfficer()
    .waitForFlightToAppear("TS0123")
    .get("#toggle-non-passenger-flights-filter").click()
    .get(".arrivals__table__flight-code > div:nth(0)").contains("TS0123")
    });

});

describe('Arrivals filter passenger flight CSV Export', () => {

  const scheduledDateTime = todayAtUtc(0, 55);
  const schDateString = scheduledDateTime.format("YYYY-MM-DD");
  const schTimeString = scheduledDateTime.format('HH:mm:00');
  const estTime = todayAtUtc(1, 5);
  const actTime = todayAtUtc(1, 7);
  const estChoxTime = todayAtUtc(1, 11);
  const actChoxTime = todayAtUtc(1, 12);

  const millis = scheduledDateTime.unix() * 1000;

  beforeEach(function () {
    cy.deleteData();
  });

  const manifest = (passengerList): object => manifestForDateTime(schDateString, schTimeString, passengerList)

  const schDateLocal = scheduledDateTime.tz("Europe/London").format("YYYY-MM-DD");
  const schTimeLocal = scheduledDateTime.tz("Europe/London").format("HH:mm");
  const estTimeLocal = estTime.tz("Europe/London").format("HH:mm");
  const actTimeLocal = actTime.tz("Europe/London").format("HH:mm");
  const estChoxTimeLocal = estChoxTime.tz("Europe/London").format("HH:mm");
  const actChoxTimeLocal = actChoxTime.tz("Europe/London").format("HH:mm");
  const pcpTimeLocal = actChoxTime.add(13, "minutes").tz("Europe/London").format("HH:mm");
  const headersWithoutActApi = "IATA,ICAO,Origin,Gate/Stand,Status," +
    "Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP," +
    "Total Pax,PCP Pax," +
    "API e-Gates,API EEA,API Non-EEA,API Fast Track," +
    "Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track," +
    "Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track";
  const actApiHeaders = "API Actual - B5JSSK to Desk,API Actual - B5JSSK to eGates" +
  ",API Actual - EEA (Machine Readable),API Actual - EEA (Non Machine Readable)," +
  "API Actual - Fast Track (Non Visa),API Actual - Fast Track (Visa),API Actual " +
  "- Non EEA (Non Visa),API Actual - Non EEA (Visa),API Actual - Transfer,API Actual - eGates";
  const headersWithActApi = headersWithoutActApi + "," + actApiHeaders;
  const totalPax = "51";
  const eGatePax = "25";
  const eeaDesk = "9";
  const nonEEADesk = "17";
  const dataWithoutActApi = "TS0123,TS0123,AMS,46/44R,On Chox," +
    schDateLocal + "," + schTimeLocal + "," + estTimeLocal + "," + actTimeLocal + "," + estChoxTimeLocal + "," + actChoxTimeLocal + "," + pcpTimeLocal + "," +
    totalPax + "," + totalPax + "," +
    eGatePax + "," + eeaDesk + "," + nonEEADesk + ",," +
    ",,,," +
    "13,37,1,";
  const actApiData = "4.0,6.0,5.0,0.0,0.0,0.0,7.0,10.0,0.0,19.0";

  const dataWithoutActACLForecastApi = "TS0123,TS0123,AMS,46/44R,ACL Forecast," +
    schDateLocal + "," + schTimeLocal + "," + estTimeLocal + "," + actTimeLocal + "," + estChoxTimeLocal + "," + actChoxTimeLocal + "," + pcpTimeLocal + "," +
    totalPax + "," + totalPax + "," +
    eGatePax + "," + eeaDesk + "," + nonEEADesk + ",," +
    ",,,," +
    "13,37,1,";

  const actApiACLForecastData = "4.0,6.0,5.0,0.0,0.0,0.0,7.0,10.0,0.0,19.0";

  const dataWithActApi = dataWithoutActApi + "," + actApiData;

  const dataWithActACLForecastApi = dataWithoutActACLForecastApi + "," + actApiACLForecastData;

  const csvWithNoApiSplits = headersWithoutActApi + "\n" + dataWithoutActApi + "\n";

  const csvWithAPISplits = headersWithActApi + "\n" + dataWithActApi + "\n";

  const csvWithAPISplitsNoData =  headersWithActApi + "\n" +  "\n" + "\n";

  const csvWithACLForecastAPISplitsData =  headersWithActApi + "\n" +  dataWithActACLForecastApi + "\n";

    it('should not include flights when the filter passenger toggle is selected and zero load factor with passenger service type', () => {
      cy
      .addFlight(
        {
          "Status" : "ACL Forecast",
          "SchDT": todayAtUtcString(0, 55),
          "EstDT": todayAtUtcString(1, 5),
          "EstChoxDT": todayAtUtcString(1, 11),
          "ActDT": todayAtUtcString(1, 7),
          "ActChoxDT": todayAtUtcString(1, 12),
          "ServiceType" : "J",
          "LoadFactor" : 0
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .addManifest(manifest(passengerList(24, 10, 7, 10)))
      .get('.pax-api')
      .asABorderForceOfficerWithRoles(["api:view"])
      .get("#toggle-non-passenger-flights-filter").click()
      .get('#export-day-arrivals')
      .then((el) => {
          const href = el.prop('href')
          cy.request({
            method: 'GET',
            url: href,
          }).then((resp) => {
            expect(resp.body).to.equal(csvWithAPISplitsNoData, "No data in export file for Zero load factor")
          })
        })
    });

    it('should include passenger flights when the filter passenger toggle is selected and non-zero load factor with passenger service type ', () => {
          cy
          .addFlight(
            {
              "Status" : "ACL Forecast",
              "SchDT": todayAtUtcString(0, 55),
              "EstDT": todayAtUtcString(1, 5),
              "EstChoxDT": todayAtUtcString(1, 11),
              "ActDT": todayAtUtcString(1, 7),
              "ActChoxDT": todayAtUtcString(1, 12),
              "ServiceType" : "J",
              "LoadFactor" : 1.0
            }
          )
          .asABorderForceOfficer()
          .waitForFlightToAppear("TS0123")
          .addManifest(manifest(passengerList(24, 10, 7, 10)))
          .get('.pax-api')
          .asABorderForceOfficerWithRoles(["api:view"])
          .get("#toggle-non-passenger-flights-filter").click()
          .get('#export-day-arrivals')
          .then((el) => {
              const href = el.prop('href')
              cy.request({
                method: 'GET',
                url: href,
              }).then((resp) => {
                expect(resp.body).to.equal(csvWithACLForecastAPISplitsData, "Data in export file for non zero load factor")
              })
            })
        });
});

