describe('Restrict access by Role', function () {

    describe('Navigation', function () {

      it("should display dashboard, terminal and feeds menu items for BorderForceStaff role", function () {
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

      it("should display dashboard and feeds menu items for PortOperatorStaff role", function () {
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

    describe('Dashboard', function () {

      it("should display terminals dashboard for BorderForceStaff role", function () {
        cy
          .asABorderForceOfficer()
          .navigateHome()
          .get(".terminal-summary-dashboard");
      });
      it("should display arrivals export page for  PortOperatorStaff role", function () {
        cy
          .asAPortOperator()
          .navigateHome()
          .get(".terminal-export-dashboard");
      });

    });

    describe('MultiDayExport', function () {

      it("should display the arrivals export only if a user does not have permission to view crunch data", function () {
        cy
          .asAPortOperator()
          .navigateHome()
          .contains("Multi Day Export")
          .click()
          .get(".modal-dialog")
          .should((modal) => {
            expect(modal).to.contain("Export Arrivals");
            expect(modal).not.to.contain("Export Desks");
          });
          ;
      });

    });
  });
