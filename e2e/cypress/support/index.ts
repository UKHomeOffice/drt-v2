export {};

declare global {
  namespace Cypress {
    interface Chainable {
      addFlight(flight: object, csrfToken: string): Chainable<Element>;

      addManifest(manifest: object, csrfToken: string): Chainable<Element>;

      addShiftForToday(csrfParam: string): Chainable<Element>;

      adjustMinutes(minutes: number): Chainable<Element>;

      adjustStaffBy(amount: number): Chainable<Element>;

      asABorderForceOfficer(): Chainable<Element>;

      asABorderForceReadOnlyOfficer(): Chainable<Element>;

      asABorderForceOfficerWithRoles(roles: string[], csrfToken: String): Chainable<Element>;

      asABorderForcePlanningOfficer(): Chainable<Element>;

      asADrtSuperUser(): Chainable<Element>;

      asANonTestPortUser(): Chainable<Element>;

      asAnLHRPortUser(): Chainable<Element>;

      asAPortOperator(): Chainable<Element>;

      asATestPortUser(): Chainable<Element>;

      asATestSetupUser(): Chainable<Element>;

      assertAccessRestricted(): Chainable<Element>;

      checkStaffAvailableOnDesksAndQueuesTabAre(desk: number): Chainable<Element>;

      checkStaffMovementsOnDesksAndQueuesTabAre(desk: number): Chainable<Element>;

      checkStaffNumbersOnMovementsTabAre(desk: number): Chainable<Element>;

      checkUserNameOnMovementsTab(desk: number, state: string): Chainable<Element>;

      checkReasonOnMovementsTab(reason: string): Chainable<Element>;

      choose24Hours(): Chainable<Element>;

      clickShiftsGetStartedButton(): Chainable<Element>;

      toggleShiftView() : Chainable<Element>;

      chooseDesksAndQueuesTab(): Chainable<Element>;

      chooseArrivalsTab(): Chainable<Element>;

      deleteAlerts(): Chainable<Element>;

      deleteData(csrfToken: string): Chainable<Element>;

      downloadCsv(type: string, year: number, month: number, day: number): Chainable<Response<any>>;

      findAndClick(thing: string): Chainable<Element>;

      openAdjustmentDialogueForHour(direction: string, amount: number): Chainable<Element>;

      navigateHome(): Chainable<Element>;

      navigateFaqs(): Chainable<Element>;

      navigateToMenuItem(id: string): Chainable<Element>;

      removeXMovements(count: number): Chainable<Element>;

      resetShifts(csrfToken: string): Chainable<Element>;

      saveShifts(shifts: object, csrfToken: string): Chainable<Element>;

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

      navigateToArrivalsTab(): Chainable<Element>;
    }
  }
}
