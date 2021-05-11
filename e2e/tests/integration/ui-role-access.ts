describe('Restrict access by Role', () => {

  describe('Navigation', () => {

    it("should display dashboard, terminal and feeds menu items for BorderForceStaff role", () => {
      cy
        .asABorderForceOfficer()
        .navigateHome()
        .get(".nav")
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
        .get(".nav")
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
    it("should display arrivals export page for  PortOperatorStaff role", () => {
      cy
        .asAPortOperator()
        .navigateHome()
        .get(".terminal-export-dashboard");
    });

  });

  describe('MultiDayExport', () => {

    it("should display the arrivals export only if a user does not have permission to view crunch data", () => {
      cy
        .asAPortOperator()
        .navigateHome()
        .get('[data-toggle="modal"]')
        .get('#multi-day-export-modal-dialog')
        .should((modal) => {
          expect(modal).to.contain("Arrivals");
          expect(modal).not.to.contain("Deployments");
          expect(modal).not.to.contain("Recommendations");
        });

    });

  });
});
