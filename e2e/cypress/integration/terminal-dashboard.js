describe('Viewing the terminal dashboard page', function () {

  it("should display a box for every queue in the terminal", () => {
    cy.asABorderForceOfficerWithRoles(["terminal-dashboard"])
    .visit("/#terminal/T1/dashboard/summary/")
    .get(".queue-name")
    .should('have.length', 3)

  })

});
