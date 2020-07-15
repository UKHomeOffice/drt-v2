import moment from "moment-timezone";
moment.locale("en-gb");

import { manifestForDateTime, passengerProfiles } from '../support/manifest-helpers'
import { todayAtUtc } from '../support/time-helpers'


describe('API splits', () => {

  beforeEach(() => {
    cy.deleteData();
  });

  const scheduledTime = todayAtUtc(0, 55);

  const scheduledDateString = scheduledTime.format("YYYY-MM-DD");
  const scheduledTimeString = scheduledTime.format("HH:mm:ss");

  const manifest = (passengerList): object => manifestForDateTime(
    scheduledDateString,
    scheduledTimeString,
    passengerList
  )

  const ofPassengerProfile = (passengerProfile, qty): object[] => {
    return Array(qty).fill(passengerProfile);
  }

  it('should have 8 egates pax and 2 EEA queue pax when there are 10 UK Adults on board a flight', () => {
    const apiManifest = manifest(ofPassengerProfile(passengerProfiles.ukPassport, 10));

    cy
      .addFlight(
        {
          "ActPax": 10,
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
          "ActPax": 11,
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
