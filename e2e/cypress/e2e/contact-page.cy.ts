describe('Contact page', () => {

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
      .get('.contact')
      .contains("Contact: support@test.com")

  });

});
