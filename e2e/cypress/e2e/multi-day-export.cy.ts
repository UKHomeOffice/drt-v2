import {todayAsLocalString} from '../support/time-helpers'


describe('Multi day export', () => {

  beforeEach(function () {
    cy.deleteData("");
  });

  it('Allows you to download API splits using the API splits dialog', () => {
    cy
      .addFlight({},"")
      .asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
      .selectCurrentTab()
      .chooseDesksAndQueuesTab()
      .choose24Hours()
      .get("#arrivalsTab").click({force: true}).then(() => {
      cy.wait(5000)
      cy.contains('button', 'Advanced Downloads', {timeout: 20000}).should('be.visible').click({force: true}).then(() => {
        cy.contains('Multi Day Export').click({force: true}).then(() => {
          cy.get('#multi-day-export-modal-body').contains("Recommendations")
            .get('#multi-day-export-modal-body').contains("Deployments")
            .get('#multi-day-export-modal-body').contains("Arrivals")
            .get('#multi-day-export-modal-footer').contains("Close").click({force: true})
            .get('#multi-day-export-modal-body').should('not.be.visible');
        });
      });
    });
  });

  it('The multi day export dialog is still visible after 5 seconds', () => {
    cy
      .addFlight({},"")
      .asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
      .selectCurrentTab()
      .choose24Hours()
      .then(() => {
        cy.wait(5000)
        cy.contains('button', 'Advanced Downloads', {timeout: 20000}).should('be.visible').click({force: true}).then(() => {
          cy.contains('Multi Day Export').click({force: true}).then(() => {
            cy.get('#multi-day-export-modal-footer').contains("Close").click({force: true})
              .get('#multi-day-export-modal-body').should('not.be.visible');
          });
        });
      });
  });

  it('Exporting desks & queues results in a file with desk recommendations', () => {
    cy
      .addFlight({
                   "SchDT": todayAsLocalString(0, 55),
                   "ActChoxDT": todayAsLocalString(4, 2),
                   "ActPax": 51
                 },"")
      .asABorderForceOfficer()
      .navigateHome()
      .navigateToMenuItem('T1')
      .selectCurrentTab()
      .chooseDesksAndQueuesTab()
      .choose24Hours()
      .then(() => {
        cy
          .get("#desksAndQueues")
          .contains("37")
          .then(() => {
            cy.wait(5000)
            cy.contains('button', 'Advanced Downloads', {timeout: 20000}).should('be.visible').click({force: true}).then(() => {
              cy.contains('Multi Day Export').click({force: true}).then(() => {
                  cy
                    .get('#multi-day-export-modal-body')
                    .contains("Recommendations")
                    .should('have.attr', 'href')
                    .then((href) => {
                      if (typeof href === 'string') {
                        cy
                          .request(href)
                          .then((response) => {
                            expect(response.body).to.contain(",37,13,1,,,13,0,1,,,1,1,1,,,0,0,0,3")
                          });
                      }
                    });
                });
            });
          });
      });
  });
});
