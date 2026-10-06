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

  const pressArrowRight = () => {
    cy.then(() => Cypress.automation('remote:debugger:protocol', {
      command: 'Input.dispatchKeyEvent',
      params: {type: 'keyDown', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39},
    }))
    cy.then(() => Cypress.automation('remote:debugger:protocol', {
      command: 'Input.dispatchKeyEvent',
      params: {type: 'keyUp', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39},
    }))
  }

  const tabToRadio = (selector: string, remainingTabs = 8) => {
    cy.focused().then($previous => {
      cy.press(Cypress.Keyboard.Keys.TAB)
      cy.focused().then($next => {
        // Tooltips between groups are legitimate tab stops; each step must still move forward.
        expect($previous[0].compareDocumentPosition($next[0]) & Node.DOCUMENT_POSITION_FOLLOWING)
          .not.to.equal(0)
        if (!$next.is(selector)) {
          expect(remainingTabs, 'Tab reaches the next radio group').to.be.greaterThan(1)
          tabToRadio(selector, remainingTabs - 1)
        }
      })
    })
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

  it('does not return focus or scroll to a staffing radio after clicking lower in the table', () => {
    openDesksAndQueues()
    cy.get(radioSelectors.displayTypeTable).first().check()
    cy.get(radioSelectors.displayIntervalQuarterly).first().check()
    cy.get(radioSelectors.deskTypeRecommended).first().check()
    cy.get(radioSelectors.deskTypeDeployments).first().check()
    cy.location('hash').should('include', 'viewType=deployments')

    cy.get('table.user-desk-recs tbody tr').should('have.length', 96)
    cy.get('table.user-desk-recs tbody tr')
      .eq(72).find('td').first().as('lowerCell').scrollIntoView({offset: {top: -300, left: 0}})

    let scrollBeforeClick = 0
    cy.window().then(win => {
      scrollBeforeClick = win.scrollY
      expect(scrollBeforeClick).to.be.greaterThan(100)
    })
    cy.get('@lowerCell').click({scrollBehavior: false})

    // Exercise the router's restore hook deterministically, without waiting for a background poll.
    cy.window().then(win => {
      win.location.hash = win.location.hash.replace('viewType=deployments', 'viewType=ideal')
    })
    cy.get(radioSelectors.deskTypeRecommended).first().should('be.checked')
    cy.window().should(win => {
      expect(Math.abs(win.scrollY - scrollBeforeClick)).to.be.lessThan(5)
      expect(win.document.activeElement?.matches('input[name="deskType"]')).to.equal(false)
    })
  })

  it('keeps keyboard focus on selected radios and tabs to the next group', function () {
    if (!Cypress.isBrowser({family: 'chromium'})) this.skip()

    openDesksAndQueues()
    cy.get(radioSelectors.displayTypeTable).first().check()
    cy.get(radioSelectors.displayIntervalQuarterly).first().check()
    // Start with pointer focus, then switch to keyboard without another focusin event.
    cy.get(radioSelectors.deskTypeRecommended).first().check()
    cy.get(radioSelectors.deskTypeRecommended).first().focus()
    pressArrowRight()
    cy.location('hash').should('include', 'viewType=deployments')
    cy.get(radioSelectors.deskTypeDeployments).first().should('be.checked').and('be.focused')

    tabToRadio(radioSelectors.displayTypeTable)
    cy.get(radioSelectors.displayTypeTable).first().should('be.focused')
    pressArrowRight()
    cy.location('hash').should('include', 'displayType=charts')
    cy.get(radioSelectors.displayTypeCharts).first().should('be.checked').and('be.focused')

    tabToRadio(radioSelectors.displayIntervalQuarterly)
    cy.get(radioSelectors.displayIntervalQuarterly).first().should('be.focused')
  })
})
