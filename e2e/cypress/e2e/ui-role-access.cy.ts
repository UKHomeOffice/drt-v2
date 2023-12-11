describe('UI role access', () => {

  describe('Navigation', () => {

    it("should display dashboard, terminal and feeds menu items for BorderForceStaff role", () => {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .get(".main-menu-content > :first-child")
        .should((nav) => {
          expect(nav).to.contain("Dashboard");
          expect(nav).to.contain("T1");
          expect(nav).to.contain("Feeds");
        });
    });

    it("should display dashboard and feeds menu items for PortOperatorStaff role", () => {
      cy
        .asAPortOperator()
        .navigateHome()
        .get(".main-menu-content > :first-child")
        .should((nav) => {
          expect(nav).to.contain("Dashboard");
          expect(nav).not.to.contain("T1");
          expect(nav).to.contain("Feeds");
        });
    });
  });

  describe('Dashboard', () => {

    it("should display terminals dashboard for BorderForceStaff role", () => {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .get(".terminal-summary-dashboard");
    });

    it("should display arrivals export page for PortOperatorStaff role", () => {
      cy
        .asAPortOperator()
        .navigateHome()
        .get(".terminal-export-dashboard");
    });

  });

  describe('MultiDayExport', () => {

    it("should not display the arrivals export for a port operator", () => {
      cy
        .asAPortOperator()
        .navigateHome()
        .get('[data-toggle="modal"]').should('not.exist')
        .get('#multi-day-export-modal-dialog').should('not.exist');
    });

  });
});
