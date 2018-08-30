describe('Alerts system', function () {

  let today = new Date().toISOString().split("T")[0];
  let timeAtEndOfDay = "23:59:59";
  let timeAtStartOfDay = "00:00:00";


  beforeEach(function () {
    deleteAlerts();
    setRoles(["api:view"]);
  });

  afterEach(function() {
    deleteAlerts();
  });

  function setRoles(roles) {
    cy.request("POST", 'v2/test/live/test/mock-roles', {"roles": roles})
  }

  function deleteAlerts() {
    cy.request('DELETE', '/v2/test/live/data/alert');

  }
  function addAlert(time, number="") {
    cy.request('POST',
      '/v2/test/live/data/alert',
      {
        "title": "This is an alert"+number,
        "message": "This is the message of the alert",
        "expires": today + " " + time
      }).its("body").should('include', "This is an alert");

  }
  function navigateToHome() {
    cy.visit('/v2/test/live').then(() => {
      cy.wait(500);
      cy.get('.navbar .container .navbar-drt').contains('DRT TEST').end();
    }).end();

  }
  function shouldHaveAlerts(num) {
    cy.get('#has-alerts .text').should('have.length', num);

  }
  function closeAlerts() {
    cy.get("#close-alert").click();

  }

  describe('An alert exists in the app', function () {

    it("When an alert is not expired it should be displayed", function () {
      addAlert(timeAtEndOfDay);
      navigateToHome();
      shouldHaveAlerts(1)
    });

    it("When there is an expired alert it should not be displayed", function () {
      addAlert(timeAtStartOfDay);
      navigateToHome();
      shouldHaveAlerts(0)
    });

    it("When the user closes the alerts no alerts are displayed", function () {
      addAlert(timeAtEndOfDay);
      navigateToHome();
      shouldHaveAlerts(1);
      closeAlerts();
      shouldHaveAlerts(0);
    });

  });

  describe('Two alerts exist in the app', function() {
    it("When there are two alerts that are not expired they should be displayed", function () {
      addAlert(timeAtEndOfDay, "1");
      addAlert(timeAtEndOfDay, "2");

      navigateToHome();
      shouldHaveAlerts(2)
    });
  })
});
