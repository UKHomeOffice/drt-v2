describe('Contact page', function () {

  it("Clicking on the contact link should display the contact details for in hours and out of hours", function () {
    cy
      .asABorderForceOfficer()
      .navigateHome()
      .get('.contact-us-link > a')
      .click({ force: true })
      .get('.contact-us')
      .contains("Contact the Dynamic Response Tool service team by email at support@test.com")
      .get('.contact-us')
      .contains("Contact our out of hours support team on 012345.");
  });

});
