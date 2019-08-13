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

    it("During business hours I should see only the DRT Team contact address but Out of Hours I should see the Support phone number", function () {
      cy.server()
      cy
        .route({ method: 'GET', url: 'ooh-status', status: 200, response: { "localTime": "2019-08-12 16:58", "isOoh": true }, delay: 100, })
        .as('getSupportOOH')
        .asANonTestPortUser()
        .navigateHome()
        .wait('@getSupportOOH')
        .assertAccessRestricted()
        .get(".access-restricted")
        .contains("For urgent issues contact our out of hours support team on")
        .get('#alternate-ports')
        .should('not.exist')
        .route({ method: 'GET', url: 'ooh-status', status: 200, response: { "localTime": "2019-08-12 16:58", "isOoh": false }, delay: 100, })
        .as('getSupportInHours')
        .navigateHome()
        .wait('@getSupportInHours')
        .get(".access-restricted")
        .contains("Contact the Dynamic Response Tool service team by email at")
        ;
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
