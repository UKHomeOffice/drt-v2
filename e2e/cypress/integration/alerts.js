describe('Alerts system', function () {

  let today = new Date().toISOString().split("T")[0];
  let timeAtEndOfDay = "23:59:59";
  let timeAtStartOfDay = "00:00:00";


  beforeEach(function () {
    cy.deleteData()
      .deleteAlerts()
      .setRoles(["test"])
  });

  Cypress.Commands.add('addAlert', (time, number="") => {
    cy
      .request('POST', '/alerts', {
        "title": "This is an alert"+number,
        "alertClass": "notice",
        "message": "This is the message of the alert",
        "expires": today + " " + time
      })
      .its("body").should('include', "This is an alert");
  });

  Cypress.Commands.add('deleteAlerts', () => cy.request('DELETE', '/alerts'))

  Cypress.Commands.add('shouldHaveAlerts', (num) => cy.get('#has-alerts .alert').should('have.length', num))

  describe('An alert exists in the app', function () {

    it("Should be possible to add an alert, view it and the delete it.", function () {
      cy
        .addAlert(timeAtEndOfDay)
        .navigateHome()
        .shouldHaveAlerts(1)
        .deleteAlerts()
        .shouldHaveAlerts(0);
    });

  });
});
