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
  return cy.request("POST", '/test/mock-roles', { "roles": roles});
});

const portRole = ["test"]
const lhrPortRole = ["LHR"]
const bfRoles = ["border-force-staff", "forecast:view", "fixed-points:view", "arrivals-and-splits:view", "desks-and-queues:view"];
const bfPlanningRoles = ["staff:edit"];
const superUserRoles = ["create-alerts", "manage-users"];
const portOperatorRoles = ["port-operator-staff", "arrivals-and-splits:view", "api:view-port-arrivals"]

Cypress.Commands.add('asABorderForceOfficer', () => {
  return cy.request("POST", '/test/mock-roles', { "roles": portRole.concat(bfRoles)});
});

Cypress.Commands.add('asATestPortUser', () => {
  return cy.request("POST", '/test/mock-roles', { "roles": portRole});
});

Cypress.Commands.add('asAnLHRPortUser', () => {
  return cy.request("POST", '/test/mock-roles', { "roles": lhrPortRole});
});

Cypress.Commands.add('asANonTestPortUser', () => {
  return cy.request("POST", '/test/mock-roles', { "roles":[]});
});

Cypress.Commands.add('asABorderForcePlanningOfficer', () => {
  const roles = portRole.concat(bfRoles).concat(bfPlanningRoles);
  return cy.request("POST", '/test/mock-roles', { "roles": roles});
});

Cypress.Commands.add('asADrtSuperUser', () => {
  return cy.request("POST", '/test/mock-roles', { "roles": superUserRoles.concat(bfRoles).concat(portRole)});
});

Cypress.Commands.add('asATestSetupUser', () => {
  return cy.request("POST", '/test/mock-roles', { "roles": superUserRoles.concat(bfRoles).concat(portRole)});
});

Cypress.Commands.add('asAPortOperator', () => {
  return cy.request("POST", '/test/mock-roles', { "roles": portOperatorRoles.concat(portRole)});
});

Cypress.Commands.add('asABorderForceOfficerWithRoles', (roles = []) => {
  const withRoles = roles.concat(bfRoles).concat(portRole);
  return cy.request("POST", '/test/mock-roles', { "roles": withRoles})
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
    "FlightID" : 100,
    "BaggageReclaimId": "05",
    "AirportID": "MAN",
    "Terminal": "T1",
    "ICAO": "TS123",
    "IATA": "TS123",
    "Origin": "AMS",
    "SchDT": schString
  };

  cy.request('POST', '/test/arrival', flightPayload);
});

Cypress.Commands.add('addFlightWithFlightCode', (flightCode, estString, actString, estChoxString, actChoxString, schString) => {

  let act = actString || estString;
  let estChox = estChoxString || estString;
  let actChox = actChoxString || estString;
  let sch = schString || estString;

  const flightPayload = {
    "Operator": "TestAir",
    "Status": "On Chocks",
    "EstDT": estString,
    "ActDT": act,
    "EstChoxDT": estChox,
    "ActChoxDT": actChox,
    "Gate": "46",
    "Stand": "44R",
    "MaxPax": 78,
    "ActPax": 51,
    "TranPax": 0,
    "RunwayID": "05L",
    "FlightID" : 100,
    "BaggageReclaimId": "05",
    "AirportID": "MAN",
    "Terminal": "T1",
    "ICAO": flightCode,
    "IATA": flightCode,
    "Origin": "AMS",
    "SchDT": sch
  };

  cy.request('POST', '/test/arrival', flightPayload);
});

Cypress.Commands.add('deleteData', () => cy.request("DELETE", '/test/data'));

Cypress.Commands.add('saveShifts', (shiftsJson) => cy.request("POST", "/data/staff", shiftsJson));

Cypress.Commands.add('navigateHome', () => cy.visit('/'));

Cypress.Commands.add('navigateToMenuItem', (itemName) => cy
  .get('.navbar-drt li')
  .contains(itemName)
  .click(5, 5, { force: true })
);

Cypress.Commands.add('findAndClick', (toFind) => cy.contains(toFind).click({ force: true }));

Cypress.Commands.add('choose24Hours', () => cy.get('#current .date-selector .date-view-picker-container').contains('24 hours').click());

Cypress.Commands.add('chooseArrivalsTab', () => cy.get("#arrivalsTab").click());
