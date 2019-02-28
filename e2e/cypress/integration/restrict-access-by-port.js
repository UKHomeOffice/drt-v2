describe('Restrict access by port', function () {

  function navigateToHome() {
    cy.visit('').then(() => {
      cy.contains('.navbar-drt', 'DRT TEST').end();
    }).end();
  }

  function navigateToHomeAccessRestricted() {
    cy.visit('/').then(() => {
      cy.get('#access-restricted').should('exist').end();
      cy.get('#email-for-access').should('exist').end();
    }).end();
  }

  describe('Restrict access by port', function () {

    it("When I have the correct permission to view the port I see the app", function () {
      cy.setRoles(["test"]);
      navigateToHome();
    });

    it("When I do not have any permission to view any ports I see access restricted page", function () {
      cy.setRoles(["bogus"]);
      navigateToHomeAccessRestricted();
      cy.get('#alternate-ports').should('not.exist');
    });

    it("When I only have permission to view LHR I see access restricted page with a link to LHR only", function () {
      cy.setRoles(["LHR"]);
      navigateToHomeAccessRestricted();
      cy.get('#alternate-ports').should('exist');
      cy.contains('#LHR-link', 'LHR');
      cy.get('#LGW-link').should('not.exist');
    });

    it("When I have permission to view LHR and LGW I see access restricted page with a link to both ports", function () {
      cy.setRoles(["LHR", "LGW"]);
      navigateToHomeAccessRestricted();
      cy.get('#alternate-ports').should('exist');
      cy.contains('#LHR-link', 'LHR');
      cy.contains('#LGW-link', 'LGW');
    });

  });

});
