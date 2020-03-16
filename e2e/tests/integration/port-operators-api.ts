import moment from 'moment-timezone'

moment.locale("en-gb");

import {todayAtUtc, todayAtUtcString} from '../support/time-helpers'

Cypress.Commands.add('downloadCsv', (terminalName, year, month, day) => {
    cy.request({url: '/export/api/' + terminalName + '/' + year + '/' + month + '/' + day, failOnStatusCode: false})
});

describe('Advanced Passenger Information Splits exposed to Port Operators', () => {
    const now = moment.utc();

    const day = now.date();
    const month = now.month() + 1;
    const year = now.year();

    Number.prototype["pad"] = function (size) {
        let s = String(this);
        while (s.length < (size || 2)) {
            s = "0" + s;
        }
        return s;
    };

    const header = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax,API e-Gates,API EEA,API Non-EEA,API Fast Track,Historical e-Gates,Historical EEA,Historical Non-EEA,Historical Fast Track,Terminal Average e-Gates,Terminal Average EEA,Terminal Average Non-EEA,Terminal Average Fast Track";

    beforeEach(function () {
        cy.deleteData();
    });

    it("Bad Request when the date is invalid", () => {
        cy
            .asAPortOperator()
            .downloadCsv("T1", year, month, 40)
            .then((response) => {
                expect(response.status).to.eq(400);
            });
    });

    it("Bad Request when the terminal is invalid", () => {
        cy
            .setRoles(["test", "api:view-port-arrivals"])
            .downloadCsv("InvalidTerminalName", year, month, day)
            .then((response) => {
                expect(response.status).to.eq(400);
            });
    });

    it("Ok when there are arrivals on the date and user has the correct role", () => {
        const localTimeScheduledDate = todayAtUtc(0, 52).tz("Europe/London")
        cy
            .asATestSetupUser()
            .addFlight({
                "SchDT": todayAtUtcString(0, 52)
            })
            .waitForFlightToAppear("TS0123")
            .asAPortOperator()
            .downloadCsv("T1", year, month, day)
            .then((response) => {
                expect(response.status).to.eq(200);
                expect(response.body).to.contain(header);
                expect(response.body).to.contain(localTimeScheduledDate.format("YYYY-MM-DD") + ',' + localTimeScheduledDate.format("HH:mm"));
                expect(response.headers['content-disposition']).to.eq("attachment; filename=TEST-T1-flights-" + year + "-" + month.pad(2) + "-" + day.pad(2) + ".csv")
            });
    })
});
