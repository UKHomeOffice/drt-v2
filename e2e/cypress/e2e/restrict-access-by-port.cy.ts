describe('Restrict access by port', () => {

  Cypress.Commands.add('assertAccessRestricted', () => {
    cy.get('#access-restricted').should('exist')
      .get('#email-for-access').should('exist');
  });

  describe('Restrict access by port', () => {

    it("When I have the correct permission to view the port I see the app", () => {
      cy
        .asATestPortUser()
        .navigateHome().then(() => cy.contains('.navbar-drt', 'DRT TEST'));
    });
  });
});
