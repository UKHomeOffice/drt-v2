let moment = require('moment-timezone');
require('moment/locale/en-gb');
moment.locale("en-gb");

let todayAtUtc = require('../support/time-helpers').todayAtUtc
let manifestForDateTime = require('../support/manifest-helpers').manifestForDateTime;
let passengerProfiles = require('../support/manifest-helpers').passengerProfiles;
let passengerList = require('../support/manifest-helpers').passengerList;


describe('API splits', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  const scheduledTime = todayAtUtc(0, 55);

  const scheduledDateString = scheduledTime.format("YYYY-MM-DD");
  const scheduledTimeString = scheduledTime.format("HH:mm:ss");

  const manifest = (passengerList) => manifestForDateTime(
    scheduledDateString,
    scheduledTimeString,
    passengerList
  )

  function ofPassengerProfile(passengerProfile, qty) {
    return Array(qty).fill(passengerProfile);
  }

  it('should have 8 egates pax and 2 EEA queue pax when there are 10 UK Adults on board a flight', () => {
    const apiManifest = manifest(ofPassengerProfile(passengerProfiles.ukPassport, 10));

    cy
      .addFlight(
        {
          "SchDT": scheduledTime.format()
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .addManifest(apiManifest)
      .get('.egate-queue-pax > span')
      .contains("8")
      .get('.eeadesk-queue-pax > span')
      .contains("2")
      ;

  });

  it('should have 7 egates pax and 4 EEA queue pax when there are 10 UK Adults and 1 uk child on board a flight', () => {
    const ukAdults = ofPassengerProfile(passengerProfiles.ukPassport, 10);
    const ukChildren = ofPassengerProfile(passengerProfiles.ukChild, 1);
    const apiManifest = manifest(ukAdults.concat(ukChildren));

    cy
      .addFlight(
        {
          "SchDT": scheduledTime.format()
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .addManifest(apiManifest)
      .get('.pax-api')
      .get('.egate-queue-pax > span')
      .contains("7")
      .get('.eeadesk-queue-pax > span')
      .contains("4")
      ;

  });
});
