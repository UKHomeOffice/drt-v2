import {todayAtUtc, todayAtUtcString} from "../support/time-helpers";
import {manifestForDateTime, passengerList} from "../support/manifest-helpers";
import {paxRagGreenSelector} from "../support/commands";


describe("CEDAT arrival exports", () => {

  const scheduledDateTime = todayAtUtc(0, 55);

  const cedatExportHeaders = "IATA,ICAO,Origin,Gate/Stand,Status," +
    "Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP," +
    "Total Pax,PCP Pax," +
    "API e-Gates,API EEA,API Non-EEA,API Fast Track," +
    "Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track," +
    "Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track," +
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
    "API Actual - Transit to Transfer"

  const schDateLocal = scheduledDateTime.tz("Europe/London").format("YYYY-MM-DD");
  const schTimeLocal = scheduledDateTime.tz("Europe/London").format("HH:mm");

  const estTime = todayAtUtc(1, 5);
  const actTime = todayAtUtc(1, 7);
  const estChoxTime = todayAtUtc(1, 11);
  const actChoxTime = todayAtUtc(1, 12);

  const totalPax = "51";
  const eeaDesk = "8";
  const nonEEADesk = "17";

  const estTimeLocal = estTime.tz("Europe/London").format("HH:mm");
  const actTimeLocal = actTime.tz("Europe/London").format("HH:mm");
  const estChoxTimeLocal = estChoxTime.tz("Europe/London").format("HH:mm");
  const actChoxTimeLocal = actChoxTime.tz("Europe/London").format("HH:mm");
  const pcpTimeLocal = actChoxTime.add(13, "minutes").tz("Europe/London").format("HH:mm");

  const csvRow = (apiEgatePax: string, eGateApiActual: string, terminalAverageEGates: string = "13") => "TS0123,TS0123,AMS,46/44R,On Chocks," +
    schDateLocal + "," + schTimeLocal + "," + estTimeLocal + "," + actTimeLocal + "," + estChoxTimeLocal + "," + actChoxTimeLocal + "," + pcpTimeLocal + "," +
    totalPax + "," + totalPax + "," +
    apiEgatePax + "," + eeaDesk + "," + nonEEADesk + ",," +
    ",,,," +
    terminalAverageEGates + ",37,1,," + eGateApiActual + ",5.0,0.0,0.0,0.0,0.0,0.0,7.0,3.0,0.0,10.0,7.0,0.0,0.0,0.0\n";

  const manifest = (passengerList): object => manifestForDateTime(scheduledDateTime, passengerList)
  it('Exports CEDAT data in the format they expect', () => {
    const cedatCsv = cedatExportHeaders + "\n" +
      csvRow("26", "19.0", "13");
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
      .get(paxRagGreenSelector)
      .asABorderForceOfficerWithRoles(["cedat-staff"])
      .get('#export-day-arrivals')
      .then((el) => {
        const href = el.prop('href')
        cy.request({
          method: 'GET',
          url: href,
        }).then((resp) => {
          expect(resp.body).to.equal(cedatCsv, "CSV export for CEDAT doesn't match their expectations")
        })
      })
  });

  it('Uses the port feed passenger numbers not the API passenger numbers in the export', () => {
    const cedatCsv = cedatExportHeaders + "\n" +
      csvRow("25", "18.0", "12");
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
      .get(paxRagGreenSelector)
      .asABorderForceOfficerWithRoles(["cedat-staff"])
      .get('#export-day-arrivals')
      .then((el) => {
        const href = el.prop('href')
        cy.request({
          method: 'GET',
          url: href,
        }).then((resp) => {
          expect(resp.body).to.equal(cedatCsv, "CSV export for CEDAT doesn't match their expectations")
        })
      })
  });

})
