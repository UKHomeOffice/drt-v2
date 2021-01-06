import moment from "moment-timezone";
moment.locale("en-gb");

import { manifestForDateTime, passengerProfiles } from '../support/manifest-helpers'
import { todayAtUtc } from '../support/time-helpers'


describe('API splits', () => {

  beforeEach(() => {
    cy.deleteData();
  });

  const scheduledTime = todayAtUtc(14, 55);

  const manifest = (passengerList): object => manifestForDateTime(
    scheduledTime,
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

    const expectedNationalitySummary = [
      {
        "arrivalKey": {
          "origin": { "iata": "AMS" },
          "voyageNumber": { "$type": "drt.shared.VoyageNumber", "numeric": 123 },
          "scheduled": scheduledTime.unix() * 1000
        },

        "ageRanges": [
          [{ "bottom": 25, "top": [49] }, 10],
          [{ "bottom": 0, "top": [11] }, 1]
        ],

        "nationalities": [
          [{ "code": "GBR" }, 11]
        ],

        "paxTypes": [
          ["EeaMachineReadable", 10], ["EeaBelowEGateAge", 1]
        ]
      }
    ]

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
      .request({
        method: 'GET',
        url: "/manifest/" + scheduledTime.format("YYYY-MM-DD") + "/summary",
      }).then((resp) => {
        expect(resp.body).to.equal(JSON.stringify(expectedNationalitySummary), "Api splits incorrect for regular users")
      })
      .get("[aria-expanded=\"false\"]")
      .trigger("mouseenter")
      .get(".nationality-chart")
      .should("be.visible")
      .get(".passenger-type-chart")
      .should("be.visible")
      .get(".age-breakdown-chart")
      .should("be.visible")
      ;

  });

});

