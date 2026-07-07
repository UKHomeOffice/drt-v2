import {manifestForDateTime, passengerProfiles, ukAdultWithId} from '../support/manifest-helpers'
import {moment, todayAtUtc} from '../support/time-helpers'
import {paxRagGreenSelector} from "../support/commands";


describe('API splits', () => {

  beforeEach(() => {
    cy.deleteData('nocheck');
  });

  const scheduledTime = todayAtUtc(14, 55);
  const scheduledTimeBeforeEligibilityChange = moment.utc("2026-07-07T14:55:00Z");
  const egateAgeEligibilityDateChange = moment.utc("2026-07-08T09:00:00Z");
  const scheduledTimeAfterEligibilityChange = egateAgeEligibilityDateChange.clone().add(1, 'hour');

  const manifest = (passengerList): object => manifestForDateTime(
    scheduledTime,
    passengerList
  )

  const ofPassengerProfile = (passengerProfile, qty): object[] => {
    return Array(qty).fill(passengerProfile);
  }

  const ageRangesForEligibilityDate = (scheduled, childCount: number, adultCount: number) => {
    const beforeChange = scheduled.isBefore(egateAgeEligibilityDateChange)

    return [
      [beforeChange ? "0 to 9" : "0 to 7", childCount],
      [beforeChange ? "10 to 17" : "8 to 17", 0],
      ["18 to 24", 0],
      ["25 to 49", adultCount],
      ["50 to 65", 0],
      ["66 and over", 0],
    ]
  }

  const csrfTokenForArrivalsDate = (scheduled) =>
    cy
      .asABorderForceOfficer()
      .visit('#terminal/T1/current/arrivals/?date=' + scheduled.format("YYYY-MM-DD"))
      .choose24Hours()
      .get('input:hidden[name="csrfToken"]').should('exist').invoke('val')

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
      .then((csrfToken) => {
        cy.addManifest(apiManifest, csrfToken.toString())
      })
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
      .then((csrfToken) => {
        cy.addManifest(apiManifest, csrfToken.toString())
      })
      .get('.notApiData', {timeout: 5000})
      .contains("10")
      .get(".arrivals__table__flight-code__info > .tooltip-trigger")
      .should('not.exist')
    ;
  });

  it('should count multiple entries with the same PassengerIdentifier as one passenger', () => {
    const apiManifest = manifestForDateTime(
      scheduledTimeBeforeEligibilityChange,
      ofPassengerProfile(ukAdultWithId("1"), 3).concat(
        ofPassengerProfile(ukAdultWithId("2"), 3)
      )
    )

    const summaryWith2Pax = [
      {
        "arrivalKey": {
          "origin": {"iata": "AMS"},
          "voyageNumber": {"$type": "uk.gov.homeoffice.drt.arrivals.VoyageNumber", "numeric": 123},
          "scheduled": scheduledTimeBeforeEligibilityChange.unix() * 1000
        },
        "ageRanges": ageRangesForEligibilityDate(scheduledTimeBeforeEligibilityChange, 0, 2),
        "nationalities": [[{"code": "GBR"}, 2]],
        "paxTypes": [["GBRNational", 2]]
      }]

    csrfTokenForArrivalsDate(scheduledTimeBeforeEligibilityChange)
      .then((csrfToken) => {
        cy.addFlight(
          {
            "ActPax": 2,
            "SchDT": scheduledTimeBeforeEligibilityChange.format()
          },
          csrfToken.toString()
        )
        cy.addManifest(apiManifest, csrfToken.toString())
      })
      .get("#arrivals")
      .contains("TS0123")
      .get(paxRagGreenSelector)
      .request({
        method: 'GET',
        url: "/manifest-summaries/" + scheduledTimeBeforeEligibilityChange.format("YYYY-MM-DD") + "/summary",
      }).then((resp) => {
      expect(resp.body).to.equal(JSON.stringify(summaryWith2Pax), "Api splits incorrect for regular users")
    })
    ;

  });

  it('should have 8 egates pax and 3 EEA queue pax when there are 10 UK Adults and 1 uk child on board a flight after the eligibility date change', () => {
    const ukAdults = ofPassengerProfile(passengerProfiles.euPassport, 10);
    const ukChildren = ofPassengerProfile(passengerProfiles.euChild, 1);
    const apiManifest = manifestForDateTime(
      scheduledTimeAfterEligibilityChange,
      ukAdults.concat(ukChildren)
    )

    const expectedNationalitySummary = [
      {
        "arrivalKey": {
          "origin": {"iata": "AMS"},
          "voyageNumber": {"$type": "uk.gov.homeoffice.drt.arrivals.VoyageNumber", "numeric": 123},
          "scheduled": scheduledTimeAfterEligibilityChange.unix() * 1000
        },

        "ageRanges": ageRangesForEligibilityDate(scheduledTimeAfterEligibilityChange, 1, 10),

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

    csrfTokenForArrivalsDate(scheduledTimeAfterEligibilityChange)
      .then((csrfToken) => {
        cy.addFlight(
          {
            "ActPax": 11,
            "EstDT": scheduledTimeAfterEligibilityChange.format(),
            "ActDT": scheduledTimeAfterEligibilityChange.format(),
            "EstChoxDT": scheduledTimeAfterEligibilityChange.format(),
            "ActChoxDT": scheduledTimeAfterEligibilityChange.format(),
            "SchDT": scheduledTimeAfterEligibilityChange.format()
          },
          csrfToken.toString()
        )
        cy.addManifest(apiManifest, csrfToken.toString())
      })
      .then(() => {
        cy.visit('#terminal/T1/current/arrivals/?date=' + scheduledTimeAfterEligibilityChange.format("YYYY-MM-DD"))
        cy.choose24Hours()
      })
      .get("#arrivals", {timeout: 30000})
      .contains("TS0123", {timeout: 30000})
      .get(paxRagGreenSelector, {timeout: 5000})
      .wait(100)
      .get('.egate-queue-pax')
      .contains("8")
      .get('.eeadesk-queue-pax')
      .contains("3")
      .request({
        method: 'GET',
        url: "/manifest-summaries/" + scheduledTimeAfterEligibilityChange.format("YYYY-MM-DD") + "/summary",
      })
      .then((resp) => {
        expect(resp.body).to.equal(JSON.stringify(expectedNationalitySummary), "Api splits incorrect for regular users")
      })
      .get(".arrivals__table__flight__chart-box-wrapper .tooltip-trigger")
      .click()
      .get(".arrivals__table__flight__chart-box__chart")
      .should("be.visible")
    ;

  });

  it('should classify an 8-year-old EEA child differently before and after the eligibility date change', () => {
    const euBorderlineChild = {
      ...passengerProfiles.euChild,
      Age: '8',
    };
    const ukAdults = ofPassengerProfile(passengerProfiles.euPassport, 10);
    const beforeManifest = manifestForDateTime(
      scheduledTimeBeforeEligibilityChange,
      ukAdults.concat([euBorderlineChild])
    );
    const afterManifest = manifestForDateTime(
      scheduledTimeAfterEligibilityChange,
      ukAdults.concat([euBorderlineChild])
    );

    csrfTokenForArrivalsDate(scheduledTimeBeforeEligibilityChange)
      .then((csrfToken) => {
        cy.addFlight(
          {
            "ActPax": 11,
            "EstDT": scheduledTimeBeforeEligibilityChange.format(),
            "ActDT": scheduledTimeBeforeEligibilityChange.format(),
            "EstChoxDT": scheduledTimeBeforeEligibilityChange.format(),
            "ActChoxDT": scheduledTimeBeforeEligibilityChange.format(),
            "SchDT": scheduledTimeBeforeEligibilityChange.format()
          },
          csrfToken.toString()
        )
        cy.addManifest(beforeManifest, csrfToken.toString())
      })
      .request({
        method: 'GET',
        url: "/manifest-summaries/" + scheduledTimeBeforeEligibilityChange.format("YYYY-MM-DD") + "/summary",
      })
      .then((resp) => {
        const [summary] = JSON.parse(resp.body);
        expect(summary.paxTypes).to.deep.equal([
          ["EeaBelowEGateAge", 1],
          ["EeaMachineReadable", 10],
        ]);
      })
      .then(() => {
        cy.visit('#terminal/T1/current/arrivals/?date=' + scheduledTimeAfterEligibilityChange.format("YYYY-MM-DD"))
        cy.choose24Hours()
      })
      .get('input:hidden[name="csrfToken"]').should('exist').invoke('val')
      .then((csrfToken) => {
        cy.addFlight(
          {
            "ActPax": 11,
            "EstDT": scheduledTimeAfterEligibilityChange.format(),
            "ActDT": scheduledTimeAfterEligibilityChange.format(),
            "EstChoxDT": scheduledTimeAfterEligibilityChange.format(),
            "ActChoxDT": scheduledTimeAfterEligibilityChange.format(),
            "SchDT": scheduledTimeAfterEligibilityChange.format()
          },
          csrfToken.toString()
        )
        cy.addManifest(afterManifest, csrfToken.toString())
      })
      .request({
        method: 'GET',
        url: "/manifest-summaries/" + scheduledTimeAfterEligibilityChange.format("YYYY-MM-DD") + "/summary",
      })
      .then((resp) => {
        const [summary] = JSON.parse(resp.body);
        expect(summary.paxTypes).to.deep.equal([
          ["EeaMachineReadable", 11],
        ]);
      })
    ;
  });

});
