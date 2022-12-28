describe('Global error handler', () => {

  interface ErrorResponse {
    logger?: string;
    level?: string;
  }

  const parsePostData = (string): ErrorResponse => {
    const postData: ErrorResponse = {};
    string.split("&").map((kv) => {
      const [key, value] = kv.split("=");
      postData[key] = value
    });
    return postData
  }


  const preventExceptionFromFailingTest = (): void => {
    cy.on('uncaught:exception', () => false);
  }

  const triggerError = (): void => {
    cy.window().then((win) => {
      win.dispatchEvent(new Event("error"))
    });
  }

  it("should log an error event to the server and popup a confirmation dialog to reload the page", () => {
    preventExceptionFromFailingTest();
    let pageLoadCount = 1;
    cy
      .asABorderForceOfficer()
      .visit('/')
      .intercept('POST', '/logging', {}).as('postLog');
    cy.on('window:confirm', () => true);
    cy.on("window:before:load", () => {
      pageLoadCount++;
    });
    triggerError();
    cy.wait('@postLog').then(xhr => {
      const postFields = parsePostData(xhr.request.body);
      expect(postFields.logger).to.equal("error");
      expect(postFields.level).to.equal("ERROR");
      expect(pageLoadCount).to.be.above(1);
    });
  });

  it("should not reload the page if the user selects not to", () => {
    preventExceptionFromFailingTest();
    let pageLoadCount = 1;
    cy
      .asABorderForceOfficer()
      .visit('/');

    cy.on('window:confirm', () => false);
    cy.on("window:before:load", () => {
      pageLoadCount++;
    });

    triggerError();
    expect(pageLoadCount).to.equal(1);
  });
});
