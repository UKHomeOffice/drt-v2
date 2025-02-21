var assert      = require('assert');
var executioner = require('../');
var testsToRun  = require('./tests.json');
var totalTests  = testsToRun.length;

// execute tests one by one
runTest(testsToRun, function()
{
  assert.notEqual(testsToRun.length, totalTests);
  console.log('Done with tests (', testsToRun.length, ').');
});

/**
 * Runs provided tests one by one
 *
 * @param   {array} tests - list of tests
 * @param   {function} callback - invoked after all tests are done
 * @returns {void}
 */
function runTest(tests, callback)
{
  var test    = tests.shift()
    , backup  = {}
    , asserts = 0
    , runner
    , job
    ;

  if (!test)
  {
    callback();
    return;
  }

  console.log('Running "' + test.description + '" ...');

  // augment "global" state
  if (test.executioner)
  {
    Object.keys(test.executioner).forEach(function(p)
    {
      backup[p]      = executioner[p];
      executioner[p] = test.executioner[p];
    });
  }

  // workaround for JSON `undefined` problem
  Object.keys(test.params || {}).forEach(function(p)
  {
    test.params[p] = test.params[p] == 'undefined' ? undefined : test.params[p];
  });

  // terminate ongoing job
  if ('terminate' in test)
  {
    setTimeout(function()
    {
      executioner.terminate(job);
    }, test.terminate);
  }

  // allow tests without running main executioner
  if (!test.command)
  {
    // proceed to the next one
    process.nextTick(function()
    {
      console.log('Finished "' + test.description + '" test with ' + asserts + ' assert(s).\n');
      runTest(tests, callback);
    });
    return;
  }

  // assemble executioner function
  runner = executioner.bind(this, test.command, test.params || {});

  // options is optional element
  if ('options' in test)
  {
    runner = runner.bind(this, test.options);
  }

  job = runner(function(err, result)
  {
    // not expecting errors, check for it
    if (!test.expected.error)
    {
      assert.ifError(err);
      asserts++;
    }
    else
    {
      Object.keys(test.expected.error).forEach(function(p)
      {
        assert.deepEqual(err[p], test.expected.error[p]);
        asserts++;
      });
    }

    // check result
    assert.deepEqual(result, test.expected.result);
    asserts++;

    // restore augmented properties
    Object.keys(backup).forEach(function(p)
    {
      executioner[p] = backup[p];
    });

    console.log('Finished "' + test.description + '" test with ' + asserts + ' assert(s).\n');

    // proceed to the next one
    runTest(tests, callback);
  });

}
