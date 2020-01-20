
describe('Port switcher menu', function () {

  it("should not be visible when a user has access to one port only", function () {
    cy
      .asABorderForceOfficer()
      .navigateHome()
      .get(".main-menu")
      .find('li')
      .should('have.length', 3)
  });

  it("should be visible as a single link if the user has access to 2 ports", function () {
    cy
      .asABorderForceOfficerWithRoles(["LHR"])
      .navigateHome()
      .get(".main-menu")
      .find('li')
      .should('have.length', 4)
      .get(":nth-child(4) > a")
      .contains("LHR")
  });

  it("should be a drop down menu for users with access to more than 2 ports", function () {
    cy
      .asABorderForceOfficerWithRoles(["LHR","STN", "LGW"])
      .navigateHome()
      .get('.dropdown > a')
      .click()
      .get(".dropdown-menu")
      .should('have.class', 'show')
      .get(".dropdown-menu")
      .find('li')
      .should("have.length", 3)
      .contains("LGW")
      .get('.dropdown')
      .click()
      .get(".dropdown-menu")
      .should('not.have.class', 'show')

  });

});

