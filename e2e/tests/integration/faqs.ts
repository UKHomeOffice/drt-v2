describe('Faqs page', () => {

    it("Clicking on the faqs page , you should faq sections", () => {
    cy.server()
        cy
           .asABorderForceOfficer()
            .navigateFaqs()
            .get('.faqs-class')
            .then(() => {
                cy.contains("+ Desk and Queues")
                cy.contains("+ Arrivals")
                cy.contains("+ Port Configuration")
                cy.contains("+ Staff Movements")
                cy.contains("+ Monthly Staffing")
             });

    });

    it("Clicking on the Desk and Queues faqs section , you should see Q & A", () => {
        cy.server()
            cy
              .asABorderForceOfficer()
              .navigateFaqs()
              .get('a[href*="#faqs/DeskAndQueues"]')
              .click()
              .get('.faqs')
              .then(() => {
                cy.contains("How are staff allocated if I select the ‘Available staff deployment’ radio button")
              });
    });

    it("Clicking on the Arrival faqs section , you should see Q & A", () => {
        cy.server()
            cy
              .asABorderForceOfficer()
              .navigateFaqs()
              .get('a[href*="#faqs/arrivals"]')
              .click()
              .get('.faqs')
              .then(() => {
                cy.contains("What do the RAG colours mean for each flight?")
              });
    });



    it("Clicking on portConfiguration faqs section , you should see Q & A", () => {
        cy.server()
            cy
              .asABorderForceOfficer()
              .navigateFaqs()
              .get('a[href*="#faqs/portConfiguration"]')
              .click()
              .get('.faqs')
              .then(() => {
                 cy.contains("What are the processing times for each split")
              });

    });

    it("Clicking on staff-movements faqs section , you should see Q & A", () => {
        cy.server()
            cy
              .asABorderForceOfficer()
              .navigateFaqs()
              .get('a[href*="#faqs/staff-movements"]')
              .click()
              .get('.faqs')
              .then(() => {
                cy.contains("How do I can add fixed points/ Misc staff")
              });

    });

    it("Clicking on monthly-staffing faqs section , you should see Q & A", () => {
        cy.server()
            cy
              .asABorderForceOfficer()
              .navigateFaqs()
              .get('a[href*="#faqs/monthly-staffing"]')
              .click()
              .get('.faqs')
              .then(() => {
                cy.contains("How do I add my staff to the tool?")
              });
    });

})