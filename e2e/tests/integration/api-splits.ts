import moment from "moment-timezone";
import {manifestForDateTime, passengerProfiles, ukAdultWithId} from '../support/manifest-helpers'
import {todayAtUtc} from '../support/time-helpers'
import {paxRagGreenSelector} from "../support/commands";

moment.locale("en-gb");


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

  it('should have 8 egates pax and 2 EEA queue pax when there are 10 EU Adults on board a flight', () => {
    const apiManifest = manifest(ofPassengerProfile(passengerProfiles.euPassport, 10));
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
      .get('.egate-queue-pax')
      .contains("8")
      .get('.eeadesk-queue-pax')
      .contains("2");
  });

  it('should ignore the API splits if they are more than 5% different in passenger numbers to the live feed and flight charts option not exist', () => {
    const apiManifest = manifest(ofPassengerProfile(passengerProfiles.euPassport, 12));

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
      .get('.notApiData', {timeout: 5000})
      .contains("10")
      .get(".arrivals__table__flight-code__info > .tooltip-trigger")
      .should('not.exist')
    ;
  });


  it('should count multiple entries with the same PassengerIdentifier as one passenger', () => {
    const apiManifest = manifest(
      ofPassengerProfile(ukAdultWithId("1"), 3).concat(
        ofPassengerProfile(ukAdultWithId("2"), 3)
      )
    );

    const summaryWith2Pax = [
      {
        "arrivalKey": {
          "origin": {"iata": "AMS"},
          "voyageNumber": {"$type": "uk.gov.homeoffice.drt.arrivals.VoyageNumber", "numeric": 123},
          "scheduled": scheduledTime.unix() * 1000
        },
        "ageRanges": [["25-49", 2]],
        "nationalities": [[{"code": "GBR"}, 2]],
        "paxTypes": [["GBRNational", 2]]
      }]

    cy
      .addFlight(
        {
          "ActPax": 2,
          "SchDT": scheduledTime.format()
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .addManifest(apiManifest)
      .get(paxRagGreenSelector)
      .request({
        method: 'GET',
        url: "/manifest/" + scheduledTime.format("YYYY-MM-DD") + "/summary",
      }).then((resp) => {
      expect(resp.body).to.equal(JSON.stringify(summaryWith2Pax), "Api splits incorrect for regular users")
    })
    ;

  });

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
          [{"code": "FRA"}, 10], [{"code": "ITA"}, 1]
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
      .get(paxRagGreenSelector, {timeout: 5000})
      .get('.egate-queue-pax')
      .contains("8")
      .get('.eeadesk-queue-pax')
      .contains("3")
      .request({
        method: 'GET',
        url: "/manifest/" + scheduledTime.format("YYYY-MM-DD") + "/summary",
      })
      .then((resp) => {
        expect(resp.body).to.equal(JSON.stringify(expectedNationalitySummary), "Api splits incorrect for regular users")
      })
      .get(".arrivals__table__flight__chart-box-wrapper .tooltip-trigger")
      .click({force: true})
      .get(".nationality-chart")
      .should("be.visible")
      .get(".passenger-type-chart")
      .should("be.visible")
      .get(".age-breakdown-chart")
      .should("be.visible")
    ;

  });

});

