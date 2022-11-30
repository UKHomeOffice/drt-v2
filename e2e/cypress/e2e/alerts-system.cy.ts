import {todayAtUtc} from "./support/time-helpers";
hello
describe('Alerts system', () => {

  const today = todayAtUtc(0, 0);
  const createdMillis = today.unix() * 1000;
  const expiresMillis = today.add(3, "day").unix() * 1000;

  Cypress.Commands.add('deleteAlerts', () => {
    cy.request('DELETE', '/alerts')
  })
  Cypress.Commands.add('shouldHaveAlerts', (num) => {
    cy.get('#alerts .has-alerts .alert.alert-class-notice').should('have.length', num)
  })

  describe('The alert endpoint', () => {

    it("Should be possible to add an alert via the API, view it and the delete it.", () => {
      cy
        .asADrtSuperUser()
        .deleteData()
        .deleteAlerts()
        .request({
            method: "POST",
            url: "/alerts",
            body: {
              "title": "This is an alert",
              "message": "This is the message of the alert",
              "alertClass": "notice",
              "expires": expiresMillis,
              "createdAt": createdMillis
            },
            headers: {
              "content-type": "text/plain;charset=UTF-8"
            }
          }
        )
        .navigateHome()
        .shouldHaveAlerts(1)
        .deleteAlerts()
        .shouldHaveAlerts(0);
    });
  });
});
