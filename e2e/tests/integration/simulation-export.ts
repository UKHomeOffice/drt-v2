import moment from 'moment'

moment.locale("en-gb");

// import {manifestForDateTime, passengerList} from '../support/manifest-helpers'
import {todayAtUtcString} from '../support/time-helpers'
// import {todayAtUtc} from '../support/time-helpers';

describe('Simulation export', () => {

  it('Allows you to view API splits in the flights export for users with api:view permission', () => {
    cy
      .addFlight(
        {
          "SchDT": todayAtUtcString(0, 55),
          "ActPax": 100
        }
      )
      .asABorderForceOfficerWithRoles(["arrival-simulation-upload"])
      .waitForFlightToAppear("TS0123")
      .get('#simulationDayTab')
      .click()
      // .then((el) => {
      //   const href = el.prop('href')
      //   cy.request({
      //     method: 'GET',
      //     url: href,
      //   }).then((resp) => {
      //     expect(resp.body).to.equal("", "Api splits incorrect for users with API reporting role")
      //   })
      // })
  });
});

