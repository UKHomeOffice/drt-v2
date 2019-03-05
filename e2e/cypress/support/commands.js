// ***********************************************
// This example commands.js shows you how to
// create various custom commands and overwrite
// existing commands.
//
// For more comprehensive examples of custom
// commands please read more here:
// https://on.cypress.io/custom-commands
// ***********************************************
//
//
// -- This is a parent command --
// Cypress.Commands.add("login", (email, password) => { ... })
//
//
// -- This is a child command --
// Cypress.Commands.add("drag", { prevSubject: 'element'}, (subject, options) => { ... })
//
//
// -- This is a dual command --
// Cypress.Commands.add("dismiss", { prevSubject: 'optional'}, (subject, options) => { ... })
//
//
// -- This is will overwrite an existing command --
// Cypress.Commands.overwrite("visit", (originalFn, url, options) => { ... })
Cypress.Commands.add('setRoles', (roles = []) => {
  cy.request("POST", '/test/mock-roles', { "roles": roles});
});

Cypress.Commands.add('addFlight', (estString, actString, estChoxString, actChoxString, schString) => {
  const flightPayload = {
    "Operator": "TestAir",
    "Status": "On Chocks",
    "EstDT": estString,
    "ActDT": actString,
    "EstChoxDT": estChoxString,
    "ActChoxDT": actChoxString,
    "Gate": "46",
    "Stand": "44R",
    "MaxPax": 78,
    "ActPax": 51,
    "TranPax": 0,
    "RunwayID": "05L",
    "BaggageReclaimId": "05",
    "FlightID": 14710007,
    "AirportID": "MAN",
    "Terminal": "T1",
    "ICAO": "TS123",
    "IATA": "TS123",
    "Origin": "AMS",
    "SchDT": schString
  };

  cy.request('POST', '/test/arrival', flightPayload);
});

Cypress.Commands.add('deleteData', () => {
  cy.request("DELETE", '/test/data');
});