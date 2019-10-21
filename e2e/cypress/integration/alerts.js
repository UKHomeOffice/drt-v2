
describe('Alerts system', function () {

  Cypress.Commands.add('deleteAlerts', () => cy.request('DELETE', '/alerts'))
  Cypress.Commands.add('shouldHaveAlerts', (num) => cy.get('#alerts .has-alerts .alert').should('have.length', num))

  describe('An alert exists in the app', function () {

    it("Should be possible to add an alert, view it and the delete it.", function () {
      cy
        .deleteData()
        .asADrtSuperUser()
        .navigateHome()
        .get('.alerts-link > a')
        .click({force: true})
        .get('#alert-title').type("This is an alert")
        .get('#alert-message').type("This is the message of the alert")
        .get(':nth-child(11) > .btn').click()
        .shouldHaveAlerts(1)
        .deleteAlerts()
        .shouldHaveAlerts(0);
    });
  });
});
