
describe('Global error handler', function () {

  function parsePostData(string) {
    let postData = {};
    string.split("&").map((kv) => {
      let [key, value] = kv.split("=");
      postData[key] = value
    });
    return postData
  }

  function preventExceptionFromFailingTest() {
    cy.on('uncaught:exception', (str) => false);
  }

  function triggerError() {
    cy.window().then((win) => {
      win.dispatchEvent(new Event("error"))
    });
  }

  it("should log an error event to the server and popup a confirmation dialog to reload the page", function () {
    preventExceptionFromFailingTest();
    cy.visit('/');
    cy.server();
    let pageLoadCount = 1;
    cy.route("POST", "/logging", {}).as('postLog');
    cy.on('window:confirm', (str) => true);
    cy.on("window:before:load", () => {
      pageLoadCount++;
    });
    triggerError();
    cy.wait('@postLog').then(xhr => {
      let postFields = parsePostData(xhr.request.body);
      expect(postFields.logger).to.equal("error");
      expect(postFields.level).to.equal("ERROR");
      expect(pageLoadCount).to.be.above(1);
    });
  });

  it("should not reload the page if the user selects not to", function () {
    preventExceptionFromFailingTest();
    cy.visit('/');
    let pageLoadCount = 1;
    cy.on('window:confirm', (str) => false);

    cy.on("window:before:load", () => {
      pageLoadCount++;
    });

    triggerError();
    expect(pageLoadCount).to.equal(1);
  });
});
