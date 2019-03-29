describe('Restrict access by port', function () {

  Cypress.Commands.add('assertAccessRestricted', () => {
    cy.get('#access-restricted').should('exist')
      .get('#email-for-access').should('exist');
  });

  describe('Restrict access by port', function () {

    it("When I have the correct permission to view the port I see the app", function () {
      cy
        .setRoles(["test"])
        .navigateHome().then(() => cy.contains('.navbar-drt', 'DRT TEST'));
    });

    it("When I do not have any permission to view any ports I see access restricted page", function () {
      cy
        .setRoles(["bogus"])
        .navigateHome().then(() => {
          cy.assertAccessRestricted()
            .get('#alternate-ports').should('not.exist');
        });
    });

    it("When I only have permission to view LHR I see access restricted page with a link to LHR only", function () {
      cy
        .setRoles(["LHR"])
        .navigateHome().then(() => {
          cy.assertAccessRestricted()
            .get('#alternate-ports').should('exist')
            .contains('#LHR-link', 'LHR')
            .get('#LGW-link').should('not.exist')
        });
    });

    it("When I have permission to view LHR and LGW I see access restricted page with a link to both ports", function () {
      cy
        .setRoles(["LHR", "LGW"])
        .navigateHome().then(() => {
          cy.assertAccessRestricted()
            .get('#alternate-ports').should('exist').then(() => {
              cy.contains('#LHR-link', 'LHR')
              cy.contains('#LGW-link', 'LGW');
            });
        });
    });
  });
});
