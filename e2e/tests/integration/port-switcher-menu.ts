
describe('Port switcher menu', () => {

  it("should not be visible when a user has access to one port only", () => {
    cy
      .asABorderForceOfficer()
      .navigateHome()
      .get(".main-menu")
      .find('li')
      .should('have.length', 3)
  });

  it("should be visible as a single link if the user has access to 2 ports", () => {
    cy
      .asABorderForceOfficerWithRoles(["LHR"])
      .navigateHome()
      .get(".main-menu")
      .find('li')
      .should('have.length', 4)
      .get(":nth-child(4) > a")
      .contains("LHR")
  });

  it("should be a drop down menu for users with access to more than 2 ports, which includes the current port", () => {
    cy
      .asABorderForceOfficerWithRoles(["LHR", "STN", "LGW"])
      .navigateHome()
      .get('.dropdown > a')
      .click({ force: true })

    cy.get(".dropdown-menu")
      .should('have.class', 'show')
      .get(".dropdown-menu")
      .find('li')
      .should("have.length", 4)
      .contains("LGW")
      .get('.dropdown')
      .click( { force: true } )
      .get(".dropdown-menu")
      .should('not.have.class', 'show')

  });

});

