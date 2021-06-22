import moment from "moment-timezone";
import {manifestForDateTime, passengerList} from '../support/manifest-helpers'
import {todayAtUtc, todayAtUtcString} from '../support/time-helpers'

moment.locale("en-gb");

describe('Arrival API with exports', () => {

    const scheduledDateTime = todayAtUtc(0, 55);

    const estTime = todayAtUtc(1, 5);
    const actTime = todayAtUtc(1, 7);
    const estChoxTime = todayAtUtc(1, 11);
    const actChoxTime = todayAtUtc(1, 12);

    beforeEach(function () {
        cy.deleteData();
    });

    const manifest = (passengerList): object => manifestForDateTime(scheduledDateTime, passengerList)

    const schDateLocal = scheduledDateTime.tz("Europe/London").format("YYYY-MM-DD");
    const schTimeLocal = scheduledDateTime.tz("Europe/London").format("HH:mm");
    const estTimeLocal = estTime.tz("Europe/London").format("HH:mm");
    const actTimeLocal = actTime.tz("Europe/London").format("HH:mm");
    const estChoxTimeLocal = estChoxTime.tz("Europe/London").format("HH:mm");
    const actChoxTimeLocal = actChoxTime.tz("Europe/London").format("HH:mm");
    const pcpTimeLocal = actChoxTime.add(13, "minutes").tz("Europe/London").format("HH:mm");
    const headersWithoutActApi = "IATA,ICAO,Origin,Gate/Stand,Status," +
        "Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP," +
        "Total Pax,PCP Pax,Invalid API," +
        "API e-Gates,API EEA,API Non-EEA,API Fast Track," +
        "Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track," +
        "Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track";

    const actApiHeaders = "API Actual - B5J+ National to EEA,API Actual - B5J+ National to e-Gates," +
      "API Actual - B5J+ Child to EEA,API Actual - EEA Machine Readable to EEA," +
      "API Actual - EEA Machine Readable to e-Gates,API Actual - EEA Non-Machine Readable to EEA," +
      "API Actual - EEA Child to EEA,API Actual - Non-Visa National to Fast Track," +
      "API Actual - Visa National to Fast Track,API Actual - Non-Visa National to Non-EEA," +
      "API Actual - Visa National to Non-EEA,API Actual - Transit to Tx"

    const headersWithActApi = headersWithoutActApi + "," + actApiHeaders;

    const eGatePax = "25";
    const eeaDesk = "9";
    const nonEEADesk = "17";
    const invalidApi = "";

    const csvRow = (totalPax: string, apiEGates: string, terminalAverageEgates: string = "13") =>
        "TS0123,TS0123,AMS,46/44R,On Chocks," + schDateLocal + "," + schTimeLocal + "," + estTimeLocal + "," + actTimeLocal + "," + estChoxTimeLocal + "," + actChoxTimeLocal + "," + pcpTimeLocal + "," +
        totalPax + "," + totalPax + "," + invalidApi + "," +
        apiEGates + "," + eeaDesk + "," + nonEEADesk + ",," +
        ",,,," +
        terminalAverageEgates + ",37,1,";


    it('Does not show API splits in the flights export for regular users', () => {
        const dataWithoutActApi = csvRow("51", eGatePax);

        const csvWithNoApiSplits = headersWithoutActApi + "\n" + dataWithoutActApi + "\n";

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
            .addManifest(manifest(passengerList(24, 10, 7, 10)))
            .get('.pax-api')
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
        const dataWithoutActApi = csvRow("51", eGatePax);
        const actApiData = "4.0,6.0,0.0,5.0,19.0,0.0,0.0,0.0,0.0,7.0,10.0,0.0";
        const dataWithActApi = dataWithoutActApi + "," + actApiData;

        const csvWithAPISplits = headersWithActApi + "\n" + dataWithActApi + "\n";
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
            .addManifest(manifest(passengerList(24, 10, 7, 10)))
            .get('.pax-api')
            .asABorderForceOfficerWithRoles(["api:view"])
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

    it('uses API splits for passenger numbers if they are within 5% of the port feed', () => {
        const dataWithoutActApi = csvRow("50", "24", "12");
        const actApiData = "4.0,6.0,0.0,5.0,18.0,0.0,0.0,0.0,0.0,7.0,10.0,0.0";
        const dataWithActApi = dataWithoutActApi + "," + actApiData;

        const csvWithAPISplits = headersWithActApi + "\n" + dataWithActApi + "\n";

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
            .addManifest(manifest(passengerList(23, 10, 7, 10)))
            .get('.pax-api')
            .asABorderForceOfficerWithRoles(["api:view"])
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
