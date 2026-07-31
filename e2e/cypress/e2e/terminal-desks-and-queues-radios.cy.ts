describe('Terminal desks and queues radios', () => {

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

    cy.get('#show-recs').should('exist')
    cy.get('#show-deps').should('exist')
    cy.get('#display-table').should('exist')
    cy.get('#display-charts').should('exist')
    cy.get('#display-quaterly-interval').should('exist')
    cy.get('#display-hourly-interval').should('exist')

    cy.get('#show-recs').check({force: true})
    cy.get('#show-recs').should('be.checked')
    cy.location('hash', {timeout: 10000}).should('include', 'viewType=ideal')

    cy.get('#show-deps').check({force: true})
    cy.get('#show-deps').should('be.checked')
    cy.location('hash', {timeout: 10000}).should('include', 'viewType=deployments')

    cy.get('#display-charts').check({force: true})
    cy.get('#display-charts').should('be.checked')
    cy.location('hash', {timeout: 10000}).should('include', 'displayType=charts')
    cy.get('table.user-desk-recs').should('not.exist')
    cy.get('.chart-container').should('be.visible')

    cy.get('#display-table').check({force: true})
    cy.get('#display-table').should('be.checked')
    cy.location('hash', {timeout: 10000}).should('include', 'displayType=table')
    cy.get('table.user-desk-recs', {timeout: 10000}).should('be.visible')

    cy.get('#display-quaterly-interval').check({force: true})
    cy.get('#display-quaterly-interval').should('be.checked')
    cy.get('table.user-desk-recs tbody tr', {timeout: 10000}).should('have.length', 96)

    cy.get('#display-hourly-interval').check({force: true})
    cy.get('#display-hourly-interval').should('be.checked')
    cy.get('#display-quaterly-interval').should('not.be.checked')
    cy.get('table.user-desk-recs tbody tr', {timeout: 10000}).should('have.length', 24)
  })
})



