import {todayAtUtc, todayAtUtcString} from '../support/time-helpers'
import moment from "moment-timezone";
import {paxRagGreenSelector} from "../support/commands";
import {manifestForDateTime, passengerProfiles} from "../support/manifest-helpers";

describe('Arrivals page filter', () => {

  beforeEach(function () {
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

  it('should have 7 egates pax and 4 EEA queue pax when there are 10 UK Adults and 1 uk child on board a flight', () => {
    const ukAdults = ofPassengerProfile(passengerProfiles.euPassport, 10);
    const ukChildren = ofPassengerProfile(passengerProfiles.euChild, 1);
    const apiManifest = manifest(ukAdults.concat(ukChildren));

    const expectedNationalitySummary = [
      {
        "arrivalKey": {
          "origin": {"iata": "AMS"},
          "voyageNumber": {"$type": "uk.gov.homeoffice.drt.arrivals.VoyageNumber", "numeric": 123},
          "scheduled": scheduledTime.unix() * 1000
        },

        "ageRanges": [
          ["25-49", 10],
          ["0-11", 1]
        ],

        "nationalities": [
          [{"code": "ITA"}, 1],
          [{"code": "FRA"}, 10],
        ],

        "paxTypes": [
          ["EeaBelowEGateAge", 1],
          ["EeaMachineReadable", 10],
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
      .get(paxRagGreenSelector, {timeout: 5000})
      .wait(100)
      .get('.egate-queue-pax')

    cy
      .contains("Flag flights")
      .click()

    cy
      .contains("Nationality or ICAO code")
      .click({force: true})
      .type("ITA{downArrow}{enter}")

    cy
      .contains("ITA (1)")

    cy
      .contains("Italy (ITA)")
  })

});
