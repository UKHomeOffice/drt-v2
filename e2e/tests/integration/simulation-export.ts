import moment from "moment"
import papa from "papaparse"
import {manifestForDateTime, passengerList} from '../support/manifest-helpers'
import {todayAtUtc, todayAtUtcString} from '../support/time-helpers'

moment.locale("en-gb");

describe('Simulation export', () => {

    const eeaDeskPaxCsvIndex = 2;
    const eeaDeskWaitCsvIndex = 3;
    const eeaDeskRecCsvIndex = 4;
    const eGatePaxCsvIndex = 7;
    const nonEeaDeskPaxCsvIndex = 12;
    const scheduledDateTime = todayAtUtc(0, 55);

    beforeEach(function () {
        cy.deleteData();
    });

    const manifest = (pl): object => manifestForDateTime(scheduledDateTime, pl)

    function sumColumn(csvData, index: number): number {
        return csvData.slice(2)
            .reduce((acc: number, row): number => {
                const eeaPax = parseInt(row[index]);
                if (eeaPax > 0)
                    return acc + eeaPax;
                else
                    return acc;
            }, 0);
    }

    function maxColumn(csvData, index: number): number {
        return csvData.slice(2)
            .reduce((current: number, row): number => {
                const cellValue = parseInt(row[index]);
                if (cellValue > current)
                    return cellValue;
                else
                    return current;
            }, 0);
    }

    it('Allows you to view API splits in the flights export for users with api:view permission', () => {
        cy
            .addFlight(
                {
                    "ActChoxDT": scheduledDateTime,
                    "SchDT": todayAtUtcString(0, 55),
                    "ActPax": 30
                }
            )
            .asABorderForceOfficerWithRoles(["arrival-simulation-upload"])
            .waitForFlightToAppear("TS0123")
            .addManifest(manifest(passengerList(10, 10, 10, 0)))
            .get('.pax-api')
            .get('#simulationDayTab')
            .click()
            .get("#adjust_processing_times")
            .click()
            .get("#EeaMachineReadable_EeaDesk")
            .type("{selectall}60")
            .blur()
            .get('#export-simulation')
            .then((el) => {
                const href = el.prop('href')
                cy.request({
                    method: 'GET',
                    url: href,
                }).then((resp) => {
                    const csvData = papa.parse(resp.body, {"header": false}).data;

                    const eeaDeskPxCount = sumColumn(csvData, eeaDeskPaxCsvIndex)
                    const nonEeaDeskPaxCount = sumColumn(csvData, nonEeaDeskPaxCsvIndex)
                    const eGatePaxCount = sumColumn(csvData, eGatePaxCsvIndex)

                    expect(eeaDeskPxCount).to.equal(2, "expected 2 passengers in EEA with weighting of 1")
                    expect(nonEeaDeskPaxCount).to.equal(20, "expected 20 passengers in Non EEA with weighting of 1")
                    expect(eGatePaxCount).to.equal(8, "expected 8 passengers in EGate with weighting of 1")

                    const eeaMaxWait = maxColumn(csvData, eeaDeskWaitCsvIndex)
                    expect(eeaMaxWait).to.equal(1, "expected 1 minute EEA wait time with 60 seconds proc time, 1 desk and 2 pax")

                })
            })

            .get("#adjust_passenger_numbers")
            .click()
            .get("#passenger-weighting")
            .type("{selectall}2")
            .get("#EeaMachineReadable_EeaDesk")
            .type("{selectall}300")
            .get("#adjust_queue_slas")
            .click()
            .get('#EeaDesk_sla')
            .type("{selectall}3")
            .blur()
            .get('#export-simulation')
            .then((el) => {
                const href = el.prop('href')
                cy.request({
                    method: 'GET',
                    url: href,
                }).then((resp) => {
                    const csvData = papa.parse(resp.body, {"header": false}).data;

                    const eeaDeskPxCount = sumColumn(csvData, eeaDeskPaxCsvIndex)
                    const nonEeaDeskPaxCount = sumColumn(csvData, nonEeaDeskPaxCsvIndex)
                    const eGatePaxCount = sumColumn(csvData, eGatePaxCsvIndex)

                    expect(eeaDeskPxCount).to.equal(4, "expected 2 passengers in EEA with weighting of 2")
                    expect(nonEeaDeskPaxCount).to.equal(40, "expected 20 passengers in Non EEA with weighting of 2")
                    expect(eGatePaxCount).to.equal(16, "expected 8 passengers in EGate with weighting of 2")

                    const eeaMaxDesks = maxColumn(csvData, eeaDeskRecCsvIndex)
                    expect(eeaMaxDesks).to.equal(4, "expected 4 open EEA Desks with 4 pax, 300 second proc times and 3 minute SLA")
                })
            })
    });
});


