import {manifestForDateTime, passengerList} from '../support/manifest-helpers'
import {todayAtUtc, todayAtUtcString} from '../support/time-helpers'
import {Moment} from "moment/moment";
import {paxRagGreenSelector} from "../support/commands";

describe('Arrival API with exports', () => {

    const scheduledDateTime = todayAtUtc(0, 55);

    const estTime = todayAtUtc(1, 5);
    const actTime = todayAtUtc(1, 7);
    const estChoxTime = todayAtUtc(1, 11);
    const actChoxTime = todayAtUtc(1, 12);

    beforeEach(function () {
        cy.deleteData('nocheck');
    });

    function dateTimeString(mDate: Moment) {
        return mDate.tz("Europe/London").format("YYYY-MM-DD HH:mm")
    }

    const manifest = (passengerList): object => manifestForDateTime(scheduledDateTime, passengerList)
    const schDateTimeLocal = dateTimeString(scheduledDateTime)
    const estDateTimeLocal = dateTimeString(estTime)
    const actDateTimeLocal = dateTimeString(actTime)
    const estChoxDateTimeLocal = dateTimeString(estChoxTime)
    const actChoxDateTimeLocal = dateTimeString(actChoxTime)
    const pcpDateTimeLocal = dateTimeString(actChoxTime.add(13, "minutes"))
    const headersWithoutActApi = "IATA,ICAO,Origin,Gate/Stand,Status," +
      "Scheduled,Predicted Arrival,Est Arrival,Act Arrival,Est Chox,Act Chox,Minutes off scheduled,Est PCP," +
      "Total Pax,PCP Pax,Invalid API," +
      "API e-Gates,API EEA,API Non-EEA,API Fast Track," +
      "Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track," +
      "Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track";

    const actApiHeaders =
      "API Actual - EEA Machine Readable to e-Gates," +
      "API Actual - EEA Machine Readable to EEA," +
      "API Actual - EEA Non-Machine Readable to EEA," +
      "API Actual - EEA Child to EEA," +
      "API Actual - GBR National to e-Gates," +
      "API Actual - GBR National to EEA," +
      "API Actual - GBR National Child to EEA," +
      "API Actual - B5J+ National to e-Gates," +
      "API Actual - B5J+ National to EEA," +
      "API Actual - B5J+ Child to EEA," +
      "API Actual - Visa National to Non-EEA," +
      "API Actual - Non-Visa National to Non-EEA," +
      "API Actual - Visa National to Fast Track," +
      "API Actual - Non-Visa National to Fast Track," +
      "Nationalities," +
      "Ages"

    const headersWithActApi = headersWithoutActApi + "," + actApiHeaders;

    const eGatePax = "26";
    const eeaDesk = "8";
    const nonEEADesk = "17";
    const invalidApi = "";

    const csvRow = (diffFromScheduled: string, totalPax: string, apiEGates: string, terminalAverageEgates: string = "13") =>
      `TS0123,TS0123,AMS,46/44R,On Chocks,${schDateTimeLocal},,${estDateTimeLocal},${actDateTimeLocal},${estChoxDateTimeLocal},${actChoxDateTimeLocal},${diffFromScheduled},${pcpDateTimeLocal},` +
      `${totalPax},${totalPax},${invalidApi},` +
      `${apiEGates},${eeaDesk},${nonEEADesk},,` +
      ",,,," +
      `${terminalAverageEgates},37,1,`;


    it('Does not show API splits in the flights export for regular users', () => {
        const dataWithoutActApi = csvRow("12", "51", eGatePax);

        const csvWithNoApiSplits = headersWithoutActApi + "\n" + dataWithoutActApi + "\n";

        cy
        .addFlight(
          {
              "SchDT": todayAtUtcString(0, 55),
              "EstDT": todayAtUtcString(1, 5),
              "ActDT": todayAtUtcString(1, 7),
              "EstChoxDT": todayAtUtcString(1, 11),
              "ActChoxDT": todayAtUtcString(1, 12)
          }
        )
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .then((csrfToken) => {
            cy.addManifest(manifest(passengerList(24, 10, 7, 10)), csrfToken.toString())
        })
        .get(paxRagGreenSelector)
        .get('#export-day-arrivals')
        .then((el) => {
            const href = el.prop('href')
            cy.request({
                method: 'GET',
                url: href,
            }).then((resp) => {
                expect(resp.body).to.equal(csvWithNoApiSplits, "Api splits incorrect for regular users")
            })
        })
    });

    it('Allows you to view API splits in the flights export for users with api:view permission', () => {
        const dataWithoutActApi = csvRow("12", "51", eGatePax);
        const actApiData = "19.0,5.0,0.0,0.0,0.0,0.0,0.0,7.0,3.0,0.0,10.0,7.0,0.0,0.0,\"FRA:24,AUS:10,ZWE:10,MRU:7\",\"25-49:51\"";
        const dataWithActApi = dataWithoutActApi + "," + actApiData;

        const csvWithAPISplits = headersWithActApi + "\n" + dataWithActApi + "\n";
        cy
        .addFlight(
          {
              "SchDT": todayAtUtcString(0, 55),
              "EstDT": todayAtUtcString(1, 5),
              "ActDT": todayAtUtcString(1, 7),
              "EstChoxDT": todayAtUtcString(1, 11),
              "ActChoxDT": todayAtUtcString(1, 12)
          }
        )
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .then((csrfToken) => {
            cy.addManifest(manifest(passengerList(24, 10, 7, 10)), csrfToken.toString())
            .get(paxRagGreenSelector)
            .asABorderForceOfficerWithRoles(["api:view"], csrfToken.toString())
        })
        .get('#export-day-arrivals')
        .then((el) => {
            const href = el.prop('href')
            cy.request({
                method: 'GET',
                url: href,
            }).then((resp) => {
                expect(resp.body).to.equal(csvWithAPISplits, "Api splits incorrect for users with API reporting role")
            })
        })
    });

    it('uses live port feed for passenger numbers even if API is within 5% of the port feed', () => {
        const dataWithoutActApi = csvRow("12", "51", "26", "13");
        const actApiData = "18.0,5.0,0.0,0.0,0.0,0.0,0.0,7.0,3.0,0.0,10.0,7.0,0.0,0.0,\"FRA:23,AUS:10,ZWE:10,MRU:7\",\"25-49:50\"";
        const dataWithActApi = dataWithoutActApi + "," + actApiData;

        const csvWithAPISplits = headersWithActApi + "\n" + dataWithActApi + "\n";

        cy
        .addFlight(
          {
              "SchDT": todayAtUtcString(0, 55),
              "EstDT": todayAtUtcString(1, 5),
              "ActDT": todayAtUtcString(1, 7),
              "EstChoxDT": todayAtUtcString(1, 11),
              "ActChoxDT": todayAtUtcString(1, 12)
          }
        )
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .then((csrfToken) => {
            cy.addManifest(manifest(passengerList(23, 10, 7, 10)), csrfToken.toString())
            .get(paxRagGreenSelector)
            .asABorderForceOfficerWithRoles(["api:view"], csrfToken.toString())
        })
        .get('#export-day-arrivals')
        .then((el) => {
            const href = el.prop('href')
            cy.request({
                method: 'GET',
                url: href,
            }).then((resp) => {
                expect(resp.body).to.equal(csvWithAPISplits, "Api splits incorrect for users with API reporting role")
            })
        })
    });

});
