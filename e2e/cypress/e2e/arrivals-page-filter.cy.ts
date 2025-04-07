
import {todayAtUtc, todayAsLocalString} from '../support/time-helpers'
import moment from "moment-timezone";

describe('Arrivals page filter', () => {

  beforeEach(function () {
    cy.deleteData("");
  });

  it('Filters flights by any relevant time range intersecting the selected range', () => {
    const flightTime: moment.Moment = todayAtUtc(16, 55);
    const scheduledHour = flightTime.tz('Europe/London').format('HH');
    let oneHourFromScheduled = (parseInt(scheduledHour) + 1)+':00'

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
            cy.wait(5000);
            cy.get('div[role="combobox"]').eq(0).click({ force: true });
            cy.get('li[data-value="00:00"]').click({ force: true });
            cy.get('div[role="combobox"]').eq(1).click({ force: true });
            cy.get('li[data-value="01:00"]').click({ force: true });
            cy.get('#arrivals > div').contains('No flights to display');
            cy.get('div[role="combobox"]').eq(0).click({ force: true });
            cy.get(`li[data-value="${scheduledHour}:00"]`).click({ force: true });
            cy.get('.arrivals__table__flight-code').contains('TS0123');
            cy.get('div[role="combobox"]').eq(0).click({ force: true });
            cy.get(`li[data-value="${oneHourFromScheduled}"]`).click();
            cy.get('.arrivals__table__flight-code').contains('TS0123');
          });
      });
  });
});
