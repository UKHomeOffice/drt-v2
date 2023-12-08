describe('Contact page', () => {

  it("Clicking on the contact page out of hours should display the OOH support message", () => {
    cy
      .intercept('GET', 'ooh-status', {
        statusCode: 200,
          body: {
            localTime: "2019-08-12 16:58",
            isOoh: true
          }
      })
      .as('getSupportOOH')
      .asABorderForceOfficer()
      .navigateHome()
      .wait('@getSupportOOH')
      .get('.contact-us-link > a')
      .click({ force: true })
      .get('.contact-us')
      .contains("Contact number (outside of office hours) :")
      .get('.contact-us')
      .contains("012345");
  });

  it("Clicking on the contact page during office hours should display the office hours support message", () => {
    cy
      .intercept('GET', 'ooh-status', {
        statusCode: 200,
          body: {
            localTime: "2019-08-12 16:58",
            isOoh: false
          }
      })
      .as('getSupportInHours')
      .asABorderForceOfficer()
      .navigateHome()
      .wait('@getSupportInHours')
      .get('.contact-us-link > a')
      .click({ force: true })
      .get('.contact-us')
      .contains("Email :")
      .contains("support@test.com")

  });

});
