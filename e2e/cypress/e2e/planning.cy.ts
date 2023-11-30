describe('Planning page', () => {

  it("Clicking on the planning page should display a table", () => {
    cy
      .asABorderForceOfficer()
      .navigateHome()
      .get('.terminal-t1').click({force: true})
      .get('#planning-tab').click({force: true})
      .get('.tab-content').as('tabContent')
      .get('@tabContent').contains('Headline Figures')
      .get('@tabContent').contains('e-Gates')
      .get('@tabContent').contains('EEA')
      .get('@tabContent').contains('Non-EEA')
      .get('@tabContent').contains('Total Pax')
      .get('@tabContent').contains('Workloads')
      .get('@tabContent').contains('Total staff required at each hour of the day')
  })

})
