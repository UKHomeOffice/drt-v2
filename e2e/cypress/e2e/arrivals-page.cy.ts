import {todayAtUtc, todayAsLocalString} from "../support/time-helpers"
import {eeaCellSelector, eGatesCellSelector, nonEeaCellSelector, paxRagGreenSelector} from "../support/commands";

describe('Arrivals page', () => {

    beforeEach(function () {
        cy.deleteData('nocheck');
    });

    const ukPassport = {
        "DocumentIssuingCountryCode": "GBR",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": "30",
        "DisembarkationPortCode": "TST",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "TST",
        "NationalityCountryEEAFlag": "EEA",
        "PassengerIdentifier": "",
        "DocumentType": "Passport",
        "PoavKey": "1",
        "NationalityCountryCode": "GBR"
    };

    const inTransitPassenger = {
        "DocumentIssuingCountryCode": "GBR",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": "30",
        "DisembarkationPortCode": "TST",
        "InTransitFlag": "Y",
        "DisembarkationPortCountryCode": "TST",
        "NationalityCountryEEAFlag": "EEA",
        "PassengerIdentifier": "",
        "DocumentType": "Passport",
        "PoavKey": "1",
        "NationalityCountryCode": "GBR"
    };

    const manifest = (passengerList): object => {
        const scheduledDateTime = todayAtUtc(0, 55);
        const schDateString = scheduledDateTime.format("YYYY-MM-DD");
        const schTimeString = scheduledDateTime.format('HH:mm:00');

        return {
            "EventCode": "DC",
            "DeparturePortCode": "AMS",
            "VoyageNumberTrailingLetter": "",
            "ArrivalPortCode": "TST",
            "DeparturePortCountryCode": "MAR",
            "VoyageNumber": "0123",
            "VoyageKey": "key",
            "ScheduledDateOfDeparture": schDateString,
            "ScheduledDateOfArrival": schDateString,
            "CarrierType": "AIR",
            "CarrierCode": "TS",
            "ScheduledTimeOfDeparture": "06:30:00",
            "ScheduledTimeOfArrival": schTimeString,
            "FileId": "fileID",
            "PassengerList": passengerList
        }
    }

    const totalPaxSelector = '.arrivals__table__flight__pcp-pax';

    it('Displays a flight after it has been ingested via the live feed', () => {
        cy.addFlight(
          {
              "SchDT": todayAsLocalString(0, 55),
              "EstDT": todayAsLocalString(1, 5),
              "EstChoxDT": todayAsLocalString(1, 11),
              "ActDT": todayAsLocalString(1, 7),
              "ActChoxDT": todayAsLocalString(1, 12)
          }
        )
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .get('.flight-origin-dfn .tooltip-trigger')
        .click()
        .get(".tippy-content")
        .contains("Schiphol, Amsterdam, Netherlands")

    });

    const ukPassengerNoDocType = {
        "DocumentIssuingCountryCode": "GBR",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": "30",
        "DisembarkationPortCode": "TST",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "TST",
        "NationalityCountryEEAFlag": "EEA",
        "PassengerIdentifier": "",
        "DocumentType": "",
        "PoavKey": "1",
        "NationalityCountryCode": "GBR"
    };
    const euPassengerWithPassportInsteadOfPDocType = {
        "DocumentIssuingCountryCode": "FRA",
        "PersonType": "P",
        "DocumentLevel": "Primary",
        "Age": "30",
        "DisembarkationPortCode": "TST",
        "InTransitFlag": "N",
        "DisembarkationPortCountryCode": "TST",
        "NationalityCountryEEAFlag": "EEA",
        "PassengerIdentifier": "",
        "DocumentType": "passport",
        "PoavKey": "1",
        "NationalityCountryCode": "GBR"
    };
    const passengerListBadDocTypes = [
        ukPassengerNoDocType,
        euPassengerWithPassportInsteadOfPDocType
    ];

    it('Handles manifests where the doctype is specified incorectly or left off', () => {

        cy.addFlight({
            "SchDT": todayAsLocalString(0, 55),
            "ActChoxDT": todayAsLocalString(0, 55),
            "ActPax": 2,
        })
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .then((csrfToken) => {
            cy.addManifest(manifest(passengerListBadDocTypes), csrfToken.toString())
            .get(paxRagGreenSelector)
            .get(eGatesCellSelector)
            .contains("2")
            .get(eeaCellSelector)
            .contains("0")
            .get(nonEeaCellSelector)
            .contains("0")
        })

    });

    it('Uses passenger numbers calculated from API data if no live pax number exists', () => {
        cy.addFlight({
            "ICAO": "TS0123",
            "IATA": "TS0123",
            "SchDT": todayAsLocalString(0, 55),
            "ActChoxDT": todayAsLocalString(0, 55),
            "ActPax": 0,
            "MaxPax": 0,
        })
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .then((csrfToken) => {
            cy.get(totalPaxSelector)
            .contains("n/a")
            .addManifest(manifest(
              [
                  ukPassport,
                  ukPassport
              ]
            ), csrfToken.toString())
            .get(eGatesCellSelector)
            .contains("2")
        })

    });

    const ukPassportWithIdentifier = (id): object => {
        return {
            "DocumentIssuingCountryCode": "GBR",
            "PersonType": "P",
            "DocumentLevel": "Primary",
            "Age": "30",
            "DisembarkationPortCode": "TST",
            "InTransitFlag": "N",
            "DisembarkationPortCountryCode": "TST",
            "NationalityCountryEEAFlag": "EEA",
            "PassengerIdentifier": id,
            "DocumentType": "Passport",
            "PoavKey": "1",
            "NationalityCountryCode": "GBR"
        };
    }

    it('only counts each passenger once if API data contains multiple entries for each passenger', () => {

        cy.addFlight({
            "SchDT": todayAsLocalString(0, 55),
            "ActPax": 0,
            "MaxPax": 0,
        })
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .then((csrfToken) => {
            cy.get(totalPaxSelector)
            .contains("n/a")
            .addManifest(manifest(
              [
                  ukPassportWithIdentifier("id1"),
                  ukPassportWithIdentifier("id1"),
                  ukPassportWithIdentifier("id2"),
                  ukPassportWithIdentifier("id2")
              ]
            ), csrfToken.toString())
        })
        .get(eGatesCellSelector)
        .contains("2")
    });

    it('does not add transit passengers to the total pax when using API pax', () => {
        cy.addFlight({
            "SchDT": todayAsLocalString(0, 55),
            "ActPax": 0,
            "MaxPax": 0,
        })
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .then((csrfToken) => {
            cy.get(totalPaxSelector)
            .contains("n/a")
            .addManifest(manifest(
              [
                  ukPassport,
                  ukPassport,
                  inTransitPassenger,
                  inTransitPassenger
              ]
            ), csrfToken.toString())
        })
        .get(eGatesCellSelector)
        .contains("2")
    });

    it('does have green bar (pax-api) when API pax count within 5% threshold of Live source splits passenger count', () => {
        cy.addFlight({
            "SchDT": todayAsLocalString(0, 55),
            "ActPax": 2,
            "MaxPax": 0,
        })
        .asABorderForceOfficer()
        .waitForFlightToAppear("TS0123")
        .then((csrfToken) => {
            cy.get(totalPaxSelector)
            .contains("2")
            .addManifest(manifest(
              [
                  ukPassport,
                  ukPassport,
              ]
            ), csrfToken.toString())
        })
        .get('.arrivals_table__splits__queue-pax')
        .contains("2")
    });
});
