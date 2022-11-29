/// <reference types="cypress" />

declare namespace Cypress {
    interface Chainable {
        addFlight(flight: object): Chainable<Element>;
        addManifest(manifest: object): Chainable<Element>;
        addShiftForToday(): Chainable<Element>;
        adjustMinutes(minutes: number): Chainable<Element>;
        adjustStaffBy(amount: number): Chainable<Element>;
        asABorderForceOfficer(): Chainable<Element>;
        asABorderForceReadOnlyOfficer(): Chainable<Element>;
        asABorderForceOfficerWithRoles(roles: string[]): Chainable<Element>;
        asABorderForcePlanningOfficer(): Chainable<Element>;
        asADrtSuperUser(): Chainable<Element>;
        asANonTestPortUser(): Chainable<Element>;
        asAnLHRPortUser(): Chainable<Element>;
        asAPortOperator(): Chainable<Element>;
        asACedatStaffMember(): Chainable<Element>;
        asATestPortUser(): Chainable<Element>;
        // asATestSetupUser(): Chainable<Element>;
        assertAccessRestricted(): Chainable<Element>;
        checkStaffAvailableOnDesksAndQueuesTabAre(desk: number): Chainable<Element>;
        checkStaffMovementsOnDesksAndQueuesTabAre(desk: number): Chainable<Element>;
        checkStaffNumbersOnMovementsTabAre(desk: number): Chainable<Element>;
        checkUserNameOnMovementsTab(desk: number, state: string): Chainable<Element>;
        checkReasonOnMovementsTab(reason: string): Chainable<Element>;
        choose24Hours(): Chainable<Element>;
        chooseArrivalsTab(): Chainable<Element>;
        deleteAlerts(): Chainable<Element>;
        deleteData(): Chainable<Element>;
        // downloadCsv(type: string, year: number, month: number, day: number): Chainable<Response>;
        findAndClick(thing: string): Chainable<Element>;
        openAdjustmentDialogueForHour(direction: string, amount: number): Chainable<Element>;
        navigateHome(): Chainable<Element>;
        // navigateFaqs(): Chainable<Element>;
        navigateToMenuItem(id: string): Chainable<Element>;
        removeXMovements(count: number): Chainable<Element>;
        resetShifts(): Chainable<Element>;
        saveShifts(shifts: object): Chainable<Element>;
        selectAdditionalReason(reason: string): Chainable<Element>;
        selectCurrentTab(): Chainable<Element>;
        selectReason(reason: string): Chainable<Element>;
        setRoles(roles: string[]): Chainable<Element>;
        shouldHaveAlerts(alertCount: number): Chainable<Element>;
        staffAvailableAtRow(row: number, ...rows: number[]): Chainable<Element>;
        staffOverTheDayAtSlot(slot: number): Chainable<Element>;
        staffDeployedAtRow(row: number): Chainable<Element>;
        staffMovementsAtRow(row: number): Chainable<Element>;
        waitForFlightToAppear(flight: string): Chainable<Element>;
        // navigateToArrivalsTab(): Chainable<Element>;
    }
}
