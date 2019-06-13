describe('Restrict access by port', function () {

  Cypress.Commands.add('assertAccessRestricted', () => {
    cy.get('#access-restricted').should('exist')
      .get('#email-for-access').should('exist');
  });

  describe('Restrict access by port', function () {

    it("When I have the correct permission to view the port I see the app", function () {
      cy
        .asATestPortUser()
        .navigateHome().then(() => cy.contains('.navbar-drt', 'DRT TEST'));
    });

    it("When I do not have any permission to view any ports I see access restricted page", function () {
      cy
        .asANonTestPortUser()
        .navigateHome()
        .assertAccessRestricted()
        .get('#alternate-ports')
        .should('not.exist');
    });

    it("The access restricted page should have contact details for the team on it.", function () {
      cy
        .asANonTestPortUser()
        .navigateHome()
        .contains("Contact the Dynamic Response Tool service team by email at support@test.com")
        .get('.access-restricted')
        .contains("Contact our out of hours support team on 012345.");
    });

    it("When I only have permission to view LHR I see access restricted page with a link to LHR only", function () {
      cy
        .asAnLHRPortUser()
        .navigateHome()
        .assertAccessRestricted()
        .get('#alternate-ports')
        .should('exist')
        .contains('#LHR-link', 'LHR')
        .get('#LGW-link')
        .should('not.exist');
    });

    it("When I have permission to view LHR and LGW I see access restricted page with a link to both ports", function () {
      cy
        .setRoles(["LHR", "LGW"])
        .navigateHome()
        .assertAccessRestricted()
        .get('#alternate-ports')
        .should('exist')
        .then(() => {
          cy.contains('#LHR-link', 'LHR')
          cy.contains('#LGW-link', 'LGW');
        });
    });
  });
});
