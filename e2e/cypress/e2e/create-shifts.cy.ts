import {moment} from '../support/time-helpers'


describe('Create Shifts for default Staffing', () => {

  beforeEach(() => {
    cy.deleteData("");
  });

  const shifts = (): object => {
    return {
      "shifts": []
    }
  }
  describe('When creating new shifts by clicking "Get Start"', () => {
    it("should display shifts details when toggle shift view", () => {
      Cypress.env('enableShiftPlanningChange', true);
      cy
      .asABorderForcePlanningOfficer()
      .request('/')
      .then((response) => {
        const $html = Cypress.$(response.body)
        const csrf: any = $html.filter('input:hidden[name="csrfToken"]').val()
        cy.saveShifts(shifts(), csrf).then(() => {
          const baseUrl = '#terminal/T1/shifts/60/';
          cy.visit(baseUrl)
            .toggleShiftView()
            .clickShiftsGetStartedButton()
            .get('[data-cy="shift-name-input"]').type('Shift 1')
            .get('[data-cy="start-time-select"]').click()
            .get('[data-cy="select-start-time-option-00-00"]').click()
            .get('[data-cy="end-time-select"]').click()
            .get('[data-cy="select-end-time-option-18-00"]').click()
            .get('[data-cy="staff-number-input"]').type('8')
            .get('[data-cy="shift-continue-button"]').click()
            .get('[data-cy="shift-confirm-button"]').click()
            .wait(1000)
            cy.get('[data-cy="shift-name-0"]').contains('Shift 1').should('exist');
            cy.get('[data-cy="shift-time-0"]').contains('Time covered: 00:00 to 18:00').should('exist');
            cy.get('[data-cy="shift-staff-number-0"]').contains('Default staff: 8').should('exist');
          });
      });
    });
  });
});
