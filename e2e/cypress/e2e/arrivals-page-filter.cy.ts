
import {todayAtUtc, todayAsLocalString} from '../support/time-helpers'
import moment from "moment-timezone";

describe('Arrivals page filter', () => {

    beforeEach(function () {
        cy.deleteData("");
    });

    it('Filters flights by any relevant time range intersecting the selected range', () => {
        const flightTime: moment.Moment = todayAtUtc(16, 55);
        const scheduledHour = flightTime.tz('Europe/London').format('HH');
        const oneHourFromScheduled = (parseInt(scheduledHour) + 1) + ':00'
        const twoHoursFromScheduled = (parseInt(scheduledHour) + 2) + ':00'

        cy.addFlight(
            {
                SchDT: todayAsLocalString(16, 55),
                EstDT: todayAsLocalString(16, 5),
                EstChoxDT: todayAsLocalString(16, 11),
                ActDT: todayAsLocalString(16, 7),
                ActChoxDT: todayAsLocalString(16, 45),
                ActPax: 300,
            },
            ''
        )
            .asABorderForceOfficer()
            .waitForFlightToAppear('TS0123')
            .get('.arrival-datetime-pax-search')
            .then(() => {
                cy.get('#from-date').should('be.disabled')
                    .get('#to-date').should('be.disabled')

                cy.contains('button', 'Custom')
                    .should('be.visible')
                    .click({ force: true })
                    .then(() => {
                        cy.wait(1000)
                            .get('#from-date').should('be.enabled').select('00:00')
                            .get('#to-date').select('02:00 (+2 hours)')
                            .get('#arrivals > div').contains('No flights to display')
                            .get('#from-date').select(`${scheduledHour}:00`)
                            .get('#to-date option:selected').should('have.text', `${oneHourFromScheduled} (+1 hours)`)
                            .get('.arrivals__table__flight-code').contains('TS0123')
                            .get('#from-date').select(oneHourFromScheduled)
                            .get('#to-date option:selected').should('have.text', `${twoHoursFromScheduled} (+1 hours)`)
                            .get('.arrivals__table__flight-code').contains('TS0123')
                    });
            });
    });
});
