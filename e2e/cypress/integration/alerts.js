describe('Alerts system', function () {

  let today = new Date().toISOString().split("T")[0];
  let timeAtEndOfDay = "23:59:59";
  let timeAtStartOfDay = "00:00:00";


  beforeEach(function () {
    setRoles(["test"]);
    deleteAlerts();
  });

  afterEach(function() {
    deleteAlerts();
  });

  function setRoles(roles) {
    cy.request("POST", 'v2/test/live/test/mock-roles', {"roles": roles})
  }

  function deleteAlerts() {
    setRoles(["test"]);
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
    setRoles(["test"]);
    cy.visit('/v2/test/live').then(() => {
      cy.wait(1000);
      cy.get('.navbar-drt').contains('DRT TEST').end();
    }).end();

  }
  function shouldHaveAlerts(num) {
    setRoles(["test"]);
    cy.get('#has-alerts .alert').should('have.length', num);
  }

  describe('An alert exists in the app', function () {

    it("When an alert is not expired it should be displayed", function () {
      addAlert(timeAtEndOfDay);
      navigateToHome();
      shouldHaveAlerts(1)
    });

    // it("When there is an expired alert it should not be displayed", function () {
    //   deleteAlerts();
    //   addAlert(timeAtStartOfDay);
    //   navigateToHome();
    //   shouldHaveAlerts(0)
    // });

  });

  // describe('Two alerts exist in the app', function() {
  //   it("When there are two alerts that are not expired they should be displayed", function () {
  //     deleteAlerts();
  //     addAlert(timeAtEndOfDay, "1");
  //     addAlert(timeAtEndOfDay, "2");
  //
  //     navigateToHome();
  //     shouldHaveAlerts(2)
  //   });
  // })
});
