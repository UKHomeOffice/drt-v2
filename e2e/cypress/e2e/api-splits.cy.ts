import {adultWithCountryCode, manifestForDateTime, passengerProfiles, ukAdultWithId} from '../support/manifest-helpers'
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

  const nationalityCodes = [
    'AFG', 'ALB', 'DZA', 'AND', 'AGO', 'ARG', 'ARM', 'AUS', 'AUT', 'AZE', 'BHS', 'BHR', 'BGD',
    'BRB', 'BLR', 'BEL', 'BLZ', 'BEN', 'BTN', 'BOL', 'BIH', 'BWA', 'BRA', 'BRN', 'BGR', 'BFA',
    'BDI', 'CPV', 'KHM', 'CMR', 'CAN'
  ]

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
      scheduledTime,
      ofPassengerProfile(ukAdultWithId("1"), 3).concat(
        ofPassengerProfile(ukAdultWithId("2"), 3)
      )
    )

    const summaryWith2Pax = [
      {
        "arrivalKey": {
          "origin": {"iata": "AMS"},
          "voyageNumber": {"$type": "uk.gov.homeoffice.drt.arrivals.VoyageNumber", "numeric": 123},
          "scheduled": scheduledTime.unix() * 1000
        },
        "ageRanges": ageRangesForEligibilityDate(scheduledTime, 0, 2),
        "nationalities": [[{"code": "GBR"}, 2]],
        "paxTypes": [["GBRNational", 2]]
      }]

    csrfTokenForArrivalsDate(scheduledTime)
      .then((csrfToken) => {
        cy.addFlight(
          {
            "ActPax": 2,
            "ICAO": "TS0123",
            "IATA": "TS0123",
            "EstDT": scheduledTime.format(),
            "ActDT": scheduledTime.format(),
            "EstChoxDT": scheduledTime.format(),
            "ActChoxDT": scheduledTime.format(),
            "SchDT": scheduledTime.format()
          },
          csrfToken.toString()
        )
        cy.addManifest(apiManifest, csrfToken.toString())
      })
      .waitForFlightToAppear("TS0123")
      .get(paxRagGreenSelector)
      .request({
        method: 'GET',
        url: "/manifest-summaries/" + scheduledTime.format("YYYY-MM-DD") + "/summary",
      }).then((resp) => {
      expect(resp.body).to.equal(JSON.stringify(summaryWith2Pax), "Api splits incorrect for regular users")
    })
    ;

  });

  it('should scale a large nationality chart to fit the desktop two-row tooltip layout', () => {
    const apiManifest = manifest(nationalityCodes.map(adultWithCountryCode));

    cy
      .viewport(1440, 900)
      .addFlight({"ActPax": nationalityCodes.length, "SchDT": scheduledTime.format()})
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .then((csrfToken) => cy.addManifest(apiManifest, csrfToken.toString()))
      .get(".arrivals__table__flight__chart-box-wrapper .tooltip-trigger")
      .click()
      .get(".arrivals__table__flight__chart-box")
      .should("have.class", "arrivals__table__flight__chart-box--two-rows")
      .get(".arrivals__table__flight__chart-wrapper")
      .should("have.class", "arrivals__table__flight__chart-wrapper--two-rows")
      .get(".arrivals__table__flight__chart-box__chart")
      .should("have.length", 3)
      .each(($chart, index) => {
        const chartClassByIndex = [
          "arrivals__table__flight__chart-box__chart--pax",
          "arrivals__table__flight__chart-box__chart--age",
          "arrivals__table__flight__chart-box__chart--nat",
        ];

        cy.wrap($chart)
          .should("have.class", chartClassByIndex[index])
          .and("have.css", "height", "258px")
      })
      .get(".arrivals__table__flight__chart-box__chart--nat")
      .should("have.class", "arrivals__table__flight__chart-box__chart--nat-scaled")
      .get(".arrivals__table__flight__chart-box__chart--pax, .arrivals__table__flight__chart-box__chart--age")
      .each(($chart) => cy.wrap($chart).should(($chart) => expect($chart.width()).to.be.within(180, 320)))
      .get(".arrivals__table__flight__chart-nat-scroller--enabled")
      .should(($scroller) => {
        const tippyContent = $scroller[0].closest(".tippy-content");
        expect(tippyContent).not.to.be.null;
        expect($scroller[0].getBoundingClientRect().right)
          .to.be.at.most(tippyContent!.getBoundingClientRect().right + 1);
        expect($scroller[0].clientWidth).to.be.greaterThan(655);
        expect($scroller[0].scrollWidth).to.be.at.most($scroller[0].clientWidth);
      });
  });

  it('should keep an 11-nationality chart compact without desktop downscaling', () => {
    const apiManifest = manifest(nationalityCodes.slice(0, 11).map(adultWithCountryCode));

    cy
      .viewport(1440, 900)
      .addFlight({"ActPax": 11, "SchDT": scheduledTime.format()})
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .then((csrfToken) => cy.addManifest(apiManifest, csrfToken.toString()))
      .get(".arrivals__table__flight__chart-box-wrapper .tooltip-trigger")
      .click()
      .get(".arrivals__table__flight__chart-box")
      .should("have.class", "arrivals__table__flight__chart-box--two-rows--compact")
      .should(($box) => expect($box.width()).to.be.lessThan(700))
      .get(".arrivals__table__flight__chart-wrapper")
      .should("have.class", "arrivals__table__flight__chart-wrapper--two-rows--compact")
      .get(".arrivals__table__flight__chart-box__chart--pax, .arrivals__table__flight__chart-box__chart--age")
      .each(($chart) => cy.wrap($chart).should(($chart) => expect($chart.width()).to.be.within(180, 320)))
      .get(".arrivals__table__flight__chart-nat-scroller--enabled")
      .should(($scroller) => expect($scroller[0].scrollWidth).to.be.at.most($scroller[0].clientWidth));
  });

  it('should stack a large nationality chart at the 720px breakpoint', () => {
    const apiManifest = manifest(nationalityCodes.map(adultWithCountryCode));

    cy
      .viewport(1440, 900)
      .addFlight({"ActPax": nationalityCodes.length, "SchDT": scheduledTime.format()})
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .then((csrfToken) => cy.addManifest(apiManifest, csrfToken.toString()))
      .viewport(720, 900)
      .get(".arrivals__table__flight__chart-box-wrapper .tooltip-trigger")
      .click()
      .get(".arrivals__table__flight__chart-box--two-rows")
       .should(($box) => {
         expect($box[0].clientWidth).to.be.closeTo(662, 1);
         expect(getComputedStyle($box[0]).overflowY).to.equal("visible");
         expect($box[0].scrollHeight).to.be.at.most($box[0].clientHeight + 1);
         expect($box[0].scrollWidth).to.be.at.most($box[0].clientWidth + 1);
       })
      .get(".arrivals__table__flight__chart-box__chart")
      .should("have.length", 3)
       .each(($chart) => cy.wrap($chart).should("have.css", "height", "350px"))
      .then(($charts) => {
        const bounds = [...$charts].map((chart) => chart.getBoundingClientRect());
        expect(bounds.every(({left}) => left === bounds[0].left)).to.equal(true);
        expect(bounds[0].top).to.be.lessThan(bounds[1].top);
        expect(bounds[1].top).to.be.lessThan(bounds[2].top);
      })
       .get(".arrivals__table__flight__chart-box__chart--pax, .arrivals__table__flight__chart-box__chart--age")
       .each(($chart) => cy.wrap($chart).should(($chart) => expect($chart.width()).to.be.within(180, 320)))
      .get(".arrivals__table__flight__chart-nat-scroller--enabled")
      .should(($scroller) => {
        expect($scroller[0].scrollWidth).to.be.greaterThan($scroller[0].clientWidth);
      });
  });

  it('should stack a compact nationality chart within the viewport at the 720px breakpoint', () => {
    const apiManifest = manifest(nationalityCodes.slice(0, 11).map(adultWithCountryCode));

    cy
      .addFlight({"ActPax": 11, "SchDT": scheduledTime.format()})
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .then((csrfToken) => cy.addManifest(apiManifest, csrfToken.toString()))
      .viewport(720, 900)
      .get(".arrivals__table__flight__chart-box-wrapper .tooltip-trigger")
      .click()
      .get(".arrivals__table__flight__chart-box--two-rows--compact")
       .should(($box) => {
         expect($box.attr("style")).to.contain("--flight-chart-stacked-content-width: 330px");
         expect($box[0].clientWidth).to.be.closeTo(330, 1);
         expect($box[0].clientWidth).to.be.lessThan(720 * 0.92);
         expect(getComputedStyle($box[0]).overflowY).to.equal("visible");
         expect($box[0].scrollHeight).to.be.at.most($box[0].clientHeight + 1);
         expect($box[0].scrollWidth).to.be.at.most($box[0].clientWidth + 1);
       })
      .get(".arrivals__table__flight__chart-box__chart")
      .should("have.length", 3)
       .each(($chart) => cy.wrap($chart).should("have.css", "height", "350px"))
      .then(($charts) => {
        const bounds = [...$charts].map((chart) => chart.getBoundingClientRect());
        expect(bounds.every(({left}) => left === bounds[0].left)).to.equal(true);
        expect(bounds[0].top).to.be.lessThan(bounds[1].top);
        expect(bounds[1].top).to.be.lessThan(bounds[2].top);
       })
       .get(".arrivals__table__flight__chart-box__chart--pax, .arrivals__table__flight__chart-box__chart--age")
       .each(($chart) => cy.wrap($chart).should(($chart) => expect($chart.width()).to.be.within(180, 320)));
  });

  it('should preserve normal chart widths when a single-row chart stacks on a narrow screen', () => {
    const apiManifest = manifest(nationalityCodes.slice(0, 10).map(adultWithCountryCode));

    cy
      .addFlight({"ActPax": 10, "SchDT": scheduledTime.format()})
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .then((csrfToken) => cy.addManifest(apiManifest, csrfToken.toString()))
      .viewport(500, 900)
      .get(".arrivals__table__flight__chart-box-wrapper .tooltip-trigger")
      .click()
      .get(".arrivals__table__flight__chart-box--single-row")
      .should(($box) => {
        expect($box[0].style.getPropertyValue("--flight-chart-stacked-content-width")).to.equal("310px");
        expect($box[0].clientWidth).to.be.closeTo(310, 1);
        expect($box[0].clientWidth).to.be.lessThan(500 * 0.92);
      })
      .get(".arrivals__table__flight__chart-box__chart")
      .should("have.length", 3)
      .each(($chart) => cy.wrap($chart).should("have.css", "height", "350px"))
      .then(($charts) => {
        const bounds = $charts.toArray().map((chart) => chart.getBoundingClientRect());
        expect(bounds.every(({left}) => left === bounds[0].left)).to.equal(true);
        expect(bounds[0].top).to.be.lessThan(bounds[1].top);
        expect(bounds[1].top).to.be.lessThan(bounds[2].top);
        expect(bounds.map(({width}) => Math.round(width))).to.deep.equal([240, 310, 300]);
      })
      .get(".arrivals__table__flight__chart-nat-scroller")
      .should(($scroller) => expect($scroller[0].scrollWidth).to.be.at.most($scroller[0].clientWidth));
  });

  it('should have 8 egates pax and 3 EEA queue pax when there are 10 UK Adults and 1 uk child on board a flight after the eligibility date change', () => {
    const ukAdults = ofPassengerProfile(passengerProfiles.euPassport, 10);
    const ukChildren = ofPassengerProfile(passengerProfiles.euChild, 1);
    const apiManifest = manifestForDateTime(
        scheduledTime,
      ukAdults.concat(ukChildren)
    )

    const expectedNationalitySummary = [
      {
        "arrivalKey": {
          "origin": {"iata": "AMS"},
          "voyageNumber": {"$type": "uk.gov.homeoffice.drt.arrivals.VoyageNumber", "numeric": 123},
          "scheduled": scheduledTime.unix() * 1000
        },

        "ageRanges": ageRangesForEligibilityDate(scheduledTime, 1, 10),

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

    csrfTokenForArrivalsDate(scheduledTime)
      .then((csrfToken) => {
        cy.addFlight(
          {
            "ActPax": 11,
            "ICAO": "TS0123",
            "IATA": "TS0123",
            "EstDT": scheduledTime.format(),
            "ActDT": scheduledTime.format(),
            "EstChoxDT": scheduledTime.format(),
            "ActChoxDT": scheduledTime.format(),
            "SchDT": scheduledTime.format()
          },
          csrfToken.toString()
        )
        cy.addManifest(apiManifest, csrfToken.toString())
      })
      .waitForFlightToAppear("TS0123")
      .get(paxRagGreenSelector, {timeout: 5000})
      .get('.egate-queue-pax')
      .contains("8")
      .get('.eeadesk-queue-pax')
      .contains("3")
      .request({
        method: 'GET',
        url: "/manifest-summaries/" + scheduledTime.format("YYYY-MM-DD") + "/summary",
      })
      .then((resp) => {
        expect(resp.body).to.equal(JSON.stringify(expectedNationalitySummary), "Api splits incorrect for regular users")
      })
       .viewport(1024, 900)
      .get(".arrivals__table__flight__chart-box-wrapper .tooltip-trigger")
      .click()
      .get(".arrivals__table__flight__chart-box")
      .should("be.visible")
      .and("have.class", "arrivals__table__flight__chart-box--single-row")
      .get(".arrivals__table__flight__chart-wrapper")
      .should("be.visible")
      .and("have.class", "arrivals__table__flight__chart-wrapper--single-row")
      .get(".arrivals__table__flight__chart-nat-scroller")
      .should("not.have.class", "arrivals__table__flight__chart-nat-scroller--enabled")
      .get(".arrivals__table__flight__chart-box__chart")
      .should("have.length", 3)
      .each(($chart, index) => {
        cy.wrap($chart)
          .should("be.visible")
          .and("have.css", "height", "350px")
          .should(($chart) => expect($chart.width()).to.be.within(180, 320))

        const chartClassByIndex = [
          "arrivals__table__flight__chart-box__chart--pax",
          "arrivals__table__flight__chart-box__chart--age",
          "arrivals__table__flight__chart-box__chart--nat",
        ];

        cy.wrap($chart).should("have.class", chartClassByIndex[index]);
      })
       .get(".arrivals__table__flight__chart-box__chart")
       .should(($charts) => {
         const bounds = [...$charts].map((chart) => chart.getBoundingClientRect());
         const tippyContent = $charts[0].closest(".tippy-content");

         expect(bounds.every(({top}) => top === bounds[0].top)).to.equal(true);
         expect(bounds[0].right).to.be.lessThan(bounds[1].left);
         expect(bounds[1].right).to.be.lessThan(bounds[2].left);
         expect(tippyContent).not.to.be.null;
         expect(bounds[2].right).to.be.at.most(tippyContent!.getBoundingClientRect().right + 1);
       })
      .get(".arrivals__table__flight__chart-box__chart--nat")
      .should("have.attr", "style")
       .and("contain", "width: 100%");

     cy.viewport(720, 900)
       .get(".arrivals__table__flight__chart-box__chart")
       .should("have.length", 3)
       .each(($chart) => cy.wrap($chart).should("have.css", "height", "350px"))
       .then(($charts) => {
         const bounds = [...$charts].map((chart) => chart.getBoundingClientRect());
         expect(bounds.every(({left}) => left === bounds[0].left)).to.equal(true);
         expect(bounds[0].top).to.be.lessThan(bounds[1].top);
         expect(bounds[1].top).to.be.lessThan(bounds[2].top);
       });

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
