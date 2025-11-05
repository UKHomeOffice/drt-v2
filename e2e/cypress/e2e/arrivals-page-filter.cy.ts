
import {todayAtUtc, todayAsLocalString} from '../support/time-helpers'
import moment from "moment-timezone";

describe('Arrivals page filter', () => {

  beforeEach(function () {
    cy.deleteData("");
  });

  it('Filters flights by any relevant time range intersecting the selected range', () => {
    const flightTime: moment.Moment = todayAtUtc(16, 55);
    const scheduledHour = flightTime.tz('Europe/London').format('HH');
    const oneHourFromScheduled = (parseInt(scheduledHour) + 1)+':00'

    const todayYYYYMMDD = flightTime.format('YYYY-MM-DD');

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
        cy.contains('button', 'Custom')
          .should('be.visible')
          .click({ force: true })
          .then(() => {
            cy.wait(1000)
            .get('div[role="combobox"]').eq(0).click()
            .get(`[data-cy="select-start-time-option-${todayYYYYMMDD}_00:00"]`).click()
            .get('div[role="combobox"]').eq(1).click()
            .get(`[data-cy="select-end-time-option-${todayYYYYMMDD}_02:00"]`).click()
            .get('#arrivals > div').contains('No flights to display')
            .get('div[role="combobox"]').eq(0).click()
            .get(`li[data-cy="select-start-time-option-${todayYYYYMMDD}_${scheduledHour}:00"]`).click()
            .get('.arrivals__table__flight-code').contains('TS0123')
            .get('div[role="combobox"]').eq(0).click()
            .get(`li[data-cy="select-start-time-option-${todayYYYYMMDD}_${oneHourFromScheduled}"]`).click()
            .get('.arrivals__table__flight-code').contains('TS0123')
          });
      });
  });
});
