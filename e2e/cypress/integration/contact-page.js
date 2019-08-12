describe('Contact page', function () {

  it("Clicking on the contact page out of hours should display the OOH support message", function () {
    cy.server()
    cy
      .route({ method: 'GET', url: 'ooh-status', status: 200, response: { "localTime": "2019-08-12 16:58", "isOoh": true }, delay: 100, })
      .as('getSupportOOH')
      .asABorderForceOfficer()
      .navigateHome()
      .wait('@getSupportOOH')
      .get('.contact-us-link > a')
      .click({ force: true })
      .get('.contact-us')
      .contains("For anything else, please email us at support@test.com and we'll respond on the next business day.")
      .get('.contact-us')
      .contains("For urgent issues contact our out of hours support team on 012345.");
  });

  it("Clicking on the contact page during office hours should display the office hours support message", function () {
    cy.server()
    cy
      .route({ method: 'GET', url: 'ooh-status', status: 200, response: { "localTime": "2019-08-12 16:58", "isOoh": false }, delay: 100, })
      .as('getSupportInHours')
      .asABorderForceOfficer()
      .navigateHome()
      .wait('@getSupportInHours')
      .get('.contact-us-link > a')
      .click({ force: true })
      .get('.contact-us')
      .contains("Contact the Dynamic Response Tool service team by email at support@test.com.")
  });

});
