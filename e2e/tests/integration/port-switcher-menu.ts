
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

  it("should be a drop down menu for users with access to more than 2 ports in the same region, including the current port", () => {
    cy
      .asABorderForceOfficerWithRoles(["LTN", "STN", "LCY"])
      .navigateHome()
      .get('.dropdown > a')
      .click({ force: true })

    cy.get(".dropdown-menu")
      .should('have.class', 'show')
      .get(".dropdown-menu")
      .contains("LTN")
      .get(".dropdown-menu")
      .contains("STN")
      .get(".dropdown-menu")
      .contains("LCY")
      .get('.dropdown')
      .click( { force: true } )
      .get(".dropdown-menu")
      .should('not.have.class', 'show')
  });

  it("should be a drop down menu for users with access to more than 1 region ports, showing each region and its ports", () => {
    cy
      .asABorderForceOfficerWithRoles(["LHR", "STN", "LGW"])
      .navigateHome()
      .get('.dropdown > a')
      .click({ force: true })

    cy.get(".dropdown-menu")
      .should('have.class', 'show')
      .get(".dropdown-menu")
      .contains("LHR")
      .get(".dropdown-menu")
      .contains("STN")
      .get(".dropdown-menu")
      .contains("LGW")
      .get(".dropdown-menu")
      .contains("Heathrow")
      .get(".dropdown-menu")
      .contains("Central")
      .get(".dropdown-menu")
      .contains("South")
      .get('.dropdown')
      .click( { force: true } )
      .get(".dropdown-menu")
      .should('not.have.class', 'show')
  });

});

