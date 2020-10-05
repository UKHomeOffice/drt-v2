import moment from "moment-timezone";
moment.locale("en-gb");

import { todayAtUtcString } from '../support/time-helpers'

describe('Arrivals page filter', () => {

  beforeEach(function () {
    cy.deleteData();
  });

  it('Filters flights by PCP time range intersecting the selected range', () => {
    cy
      .addFlight(
        {
          "SchDT": todayAtUtcString(0, 55),
          "EstDT": todayAtUtcString(1, 5),
          "EstChoxDT": todayAtUtcString(16, 11),
          "ActDT": todayAtUtcString(16, 7),
          "ActChoxDT": todayAtUtcString(16, 45),
          "ActPax": 300
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .get('.time-range > :nth-child(1)').select("00")
      .get('.time-range > :nth-child(2)').select("01")
      .get('#arrivals > div').contains("No flights to display")
      .get('.time-range > :nth-child(1)').select("16")
      .get('.time-range > :nth-child(2)').select("17")
      .get('.danger > :nth-child(1)').contains("TS0123")
      .get('.time-range > :nth-child(1)').select("17")
      .get('.time-range > :nth-child(2)').select("18")
      .get('.danger > :nth-child(1)').contains("TS0123")

  });

 //valid service types for filter ("J", "S", "Q", "G", "B", "R", "C", "L")

  it('Filter flight by passenger flight only for ACL Forecast when LoadFactor is zero and valid service type', () => {
    cy
      .addTestFlight(
        {
          "Status" : "ACL Forecast",
          "SchDT": todayAtUtcString(0, 55),
          "EstDT": todayAtUtcString(1, 5),
          "EstChoxDT": todayAtUtcString(16, 11),
          "ActDT": todayAtUtcString(16, 7),
          "ActChoxDT": todayAtUtcString(16, 45),
          "ActPax": 300,
          "ServiceType" : "J",
          "LoadFactor" : 0
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .get("#toggle-arrival").click()
      .get('#arrivals > div').contains("No flights to display");

  });

  it('Not Filter flight by passenger flight for ACL Forecast when LoadFactor is not zero and valid service type', () => {
    cy
      .addTestFlight(
          {
            "Status" : "ACL Forecast",
            "SchDT": todayAtUtcString(0, 55),
            "EstDT": todayAtUtcString(1, 5),
            "EstChoxDT": todayAtUtcString(16, 11),
            "ActDT": todayAtUtcString(16, 7),
            "ActChoxDT": todayAtUtcString(16, 45),
            "ActPax": 300,
            "ServiceType" : "J",
            "LoadFactor" : 0.8
          }
        )
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .get("#toggle-arrival").click()
        .get(".arrivals__table__flight-code > div:nth(0)").contains("TS0123")
    });

  it('Not filter flight by passenger flight when Non ACL Forecast', () => {
    cy
      .addTestFlight(
        {
          "Status" : "Non ACL Forecast",
          "SchDT": todayAtUtcString(0, 55),
          "EstDT": todayAtUtcString(1, 5),
          "EstChoxDT": todayAtUtcString(16, 11),
          "ActDT": todayAtUtcString(16, 7),
          "ActChoxDT": todayAtUtcString(16, 45),
          "ActPax": 300,
          "ServiceType" : "J",
          "LoadFactor" : 0
        }
      )
      .asABorderForceOfficer()
      .waitForFlightToAppear("TS0123")
      .get("#toggle-arrival").click()
      .get(".arrivals__table__flight-code > div:nth(0)").contains("TS0123")

     });


  it('Not Filter flight by passenger flight for ACL Forecast when LoadFactor is not zero and invalid service type', () => {
    cy
    .addTestFlight(
      {
        "Status" : "ACL Forecast",
        "SchDT": todayAtUtcString(0, 55),
        "EstDT": todayAtUtcString(1, 5),
        "EstChoxDT": todayAtUtcString(16, 11),
        "ActDT": todayAtUtcString(16, 7),
        "ActChoxDT": todayAtUtcString(16, 45),
        "ActPax": 300,
        "LoadFactor" : 0.8
      }
    )
    .asABorderForceOfficer()
    .waitForFlightToAppear("TS0123")
    .get("#toggle-arrival").click()
    .get(".arrivals__table__flight-code > div:nth(0)").contains("TS0123")
    });

});
