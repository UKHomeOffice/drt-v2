import { todayAtUtcString as todayAtString } from './time-helpers'

Cypress.Commands.add('setRoles', (roles = []) => {
  cy.request("POST", '/test/mock-roles', { "roles": roles });
});

const portRole = ["test"]
const lhrPortRole = ["LHR"]
const bfRoles = [
  "border-force-staff",
  "forecast:view",
  "fixed-points:view",
  "arrivals-and-splits:view",
  "desks-and-queues:view",
  "staff-movements:edit",
  "staff-movements:export",
  "enhanced-api-view",
  "create-alerts",
  "test"
];
const bfReadOnlyRoles = [
  "border-force-staff",
  "forecast:view",
  "fixed-points:view",
  "arrivals-and-splits:view",
  "desks-and-queues:view",
  "test"
];
const bfPlanningRoles = ["staff:edit"];
const superUserRoles = ["create-alerts", "manage-users"];
const portOperatorRoles = ["port-operator-staff",
  "arrivals-and-splits:view",
  "api:view-port-arrivals"
]
const cedatRoles = ["cedat-staff",
  "arrivals-and-splits:view",
  "api:view-port-arrivals"
]
export const paxRagGreenSelector = '.pax-rag-green'
export const paxRagAmberSelector = '.pax-rag-amber'
export const paxRagRedSelector = '.pax-rag-red'

export const eGatesCellSelector = '.egate-queue-pax';
export const eeaCellSelector = '.eeadesk-queue-pax';
export const nonEeaCellSelector = '.noneeadesk-queue-pax';

Cypress.Commands.add('asABorderForceOfficer', () => {
  cy.request("POST", '/test/mock-roles', { "roles": portRole.concat(bfRoles) });
});

Cypress.Commands.add('asABorderForceReadOnlyOfficer', () => {
  cy.request("POST", '/test/mock-roles', { "roles": portRole.concat(bfReadOnlyRoles) });
});

Cypress.Commands.add('asATestPortUser', () => {
  cy.request("POST", '/test/mock-roles', { "roles": portRole });
});

Cypress.Commands.add('asAnLHRPortUser', () => {
  cy.request("POST", '/test/mock-roles', { "roles": lhrPortRole });
});

Cypress.Commands.add('asANonTestPortUser', () => {
  cy.request("POST", '/test/mock-roles', { "roles": [] });
});

Cypress.Commands.add('asABorderForcePlanningOfficer', () => {
  const roles = portRole.concat(bfRoles).concat(bfPlanningRoles);
  cy.request("POST", '/test/mock-roles', { "roles": roles });
});

Cypress.Commands.add('asADrtSuperUser', () => {
  cy.request("POST", '/test/mock-roles', { "roles": superUserRoles.concat(bfRoles).concat(portRole) });
});

Cypress.Commands.add('asAPortOperator', () => {
  cy.request("POST", '/test/mock-roles', { "roles": portOperatorRoles.concat(portRole) });
});

Cypress.Commands.add('asACedatStaffMember', () => {
  cy.request("POST", '/test/mock-roles', { "roles": portOperatorRoles.concat(portRole) });
});

Cypress.Commands.add('asABorderForceOfficerWithRoles', (roles = []) => {
  const withRoles = roles.concat(bfRoles).concat(portRole);
  cy.request("POST", '/test/mock-roles', { "roles": withRoles })
});

Cypress.Commands.add('addFlight', (params) => {
  const defaults = {
    "Operator": "TestAir",
    "Status": "On Chox",
    "EstDT": todayAtString(12, 0),
    "ActDT": todayAtString(12, 0),
    "EstChoxDT": todayAtString(12, 0),
    "ActChoxDT": todayAtString(12, 0),
    "Gate": "46",
    "Stand": "44R",
    "MaxPax": 78,
    "ActPax": 51,
    "TranPax": 0,
    "RunwayID": "05L",
    "FlightID": 100,
    "BaggageReclaimId": "05",
    "AirportID": "MAN",
    "Terminal": "T1",
    "ICAO": "TS123",
    "IATA": "TS123",
    "Origin": "AMS",
    "SchDT": todayAtString(12, 0)
  };

  const flightPayload = Object.assign({}, defaults, params);

  cy.request('POST', '/test/arrival', flightPayload);
});

Cypress.Commands.add('deleteData', () => {
  cy.request("DELETE", '/test/data')
});

Cypress.Commands.add('saveShifts', (shiftsJson) => {
  cy.request("POST", "/data/staff", shiftsJson)
});

Cypress.Commands.add('navigateHome', () => {
  cy.visit('/')
});

Cypress.Commands.add('navigateToMenuItem', (itemName) => {
  cy
    .get('.navbar-drt li')
    .contains(itemName)
    .click(5, 5, { force: true })
});

Cypress.Commands.add('selectCurrentTab', () => {
  cy.get('#currentTab').click({force: true})
})

Cypress.Commands.add('findAndClick', (toFind) => {
  cy.contains(toFind).click({ force: true })
});

Cypress.Commands.add('choose24Hours', () => {
  cy.get('.time-view-selector-container')
    .contains('24 hours')
    .click({force: true})
});

Cypress.Commands.add('chooseArrivalsTab', () => {
  cy.get("#arrivalsTab").click({force: true})
});

Cypress.Commands.add('addManifest', (manifest) => {
  cy.request('POST', '/test/manifest', manifest)
});

Cypress.Commands.add('waitForFlightToAppear', (flightCode) => {
  cy
    .navigateHome()
    .navigateToMenuItem('T1')
    .selectCurrentTab()
    .get("#currentTab").click({force: true})
    .get("#arrivalsTab").click({force: true})
    .choose24Hours()
    .get("#arrivals")
    .contains(flightCode);
})
