describe('Terminal desks and queues radios', () => {

  const radioSelectors = {
    deskTypeRecommended: '#show-recs, input[type="radio"][name="deskType"][value="recommended"]',
    deskTypeDeployments: '#show-deps, input[type="radio"][name="deskType"][value="deployments"]',
    displayTypeTable: '#display-table, input[type="radio"][name="displayType"][value="table"]',
    displayTypeCharts: '#display-charts, input[type="radio"][name="displayType"][value="charts"]',
    displayIntervalQuarterly: '#display-quaterly-interval, input[type="radio"][name="displayInterval"][value="quarterly"]',
    displayIntervalHourly: '#display-hourly-interval, input[type="radio"][name="displayInterval"][value="hourly"]',
  }

  beforeEach(() => {
    cy.deleteData('')
      .addFlight({}, '')
  })

  const openDesksAndQueues = () => {
    cy.asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
      .chooseDesksAndQueuesTab()
      .choose24Hours()
      .get('#desksAndQueues', {timeout: 20000}).should('be.visible')
      .contains('Desks and queues')
  }

  it('should render and switch the desks and queues radio controls', () => {
    openDesksAndQueues()

    cy.contains('.view-controls-label', 'Staffing').should('be.visible')
    cy.contains('.view-controls-label', 'View').should('be.visible')
    cy.contains('.view-controls-label', 'Time interval').should('be.visible')

    cy.get(radioSelectors.deskTypeRecommended).should('exist')
    cy.get(radioSelectors.deskTypeDeployments).should('exist')
    cy.get(radioSelectors.displayTypeTable).should('exist')
    cy.get(radioSelectors.displayTypeCharts).should('exist')
    cy.get(radioSelectors.displayIntervalQuarterly).should('exist')
    cy.get(radioSelectors.displayIntervalHourly).should('exist')

    cy.get(radioSelectors.deskTypeRecommended).first().check({force: true})
    cy.get(radioSelectors.deskTypeRecommended).first().should('be.checked')
    cy.location('hash', {timeout: 10000}).should('include', 'viewType=ideal')

    cy.get(radioSelectors.deskTypeDeployments).first().check({force: true})
    cy.get(radioSelectors.deskTypeDeployments).first().should('be.checked')
    cy.location('hash', {timeout: 10000}).should('include', 'viewType=deployments')

    cy.get(radioSelectors.displayTypeCharts).first().check({force: true})
    cy.get(radioSelectors.displayTypeCharts).first().should('be.checked')
    cy.location('hash', {timeout: 10000}).should('include', 'displayType=charts')
    cy.get('table.user-desk-recs').should('not.exist')
    cy.get('.chart-container').should('be.visible')

    cy.get(radioSelectors.displayTypeTable).first().check({force: true})
    cy.get(radioSelectors.displayTypeTable).first().should('be.checked')
    cy.location('hash', {timeout: 10000}).should('include', 'displayType=table')
    cy.get('table.user-desk-recs', {timeout: 10000}).should('be.visible')

    cy.get(radioSelectors.displayIntervalQuarterly).first().check({force: true})
    cy.get(radioSelectors.displayIntervalQuarterly).first().should('be.checked')
    cy.get('table.user-desk-recs tbody tr', {timeout: 10000}).should('have.length', 96)

    cy.get(radioSelectors.displayIntervalHourly).first().check({force: true})
    cy.get(radioSelectors.displayIntervalHourly).first().should('be.checked')
    cy.get(radioSelectors.displayIntervalQuarterly).first().should('not.be.checked')
    cy.get('table.user-desk-recs tbody tr', {timeout: 10000}).should('have.length', 24)
  })
})




