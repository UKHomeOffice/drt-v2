"use strict";

exports.__esModule = true;
exports.shortcutsGridContext = shortcutsGridContext;
var _mixed = require("../helpers/mixed");
var _constants = require("./constants");
var _commands = require("./commands");
/**
 * The context that defines shortcut list available for selected cell or cells.
 *
 * @param {Handsontable} hot The Handsontable instance.
 */
function shortcutsGridContext(hot) {
  const context = hot.getShortcutManager().addContext('grid');
  const commandsPool = (0, _commands.createKeyboardShortcutCommandsPool)(hot);
  const config = {
    runOnlyIf: () => {
      const {
        navigableHeaders
      } = hot.getSettings();
      return (0, _mixed.isDefined)(hot.getSelected()) && (navigableHeaders || !navigableHeaders && hot.countRenderedRows() > 0 && hot.countRenderedCols() > 0);
    },
    group: _constants.GRID_GROUP
  };
  context.addShortcuts([{
    keys: [['F2']],
    callback: event => commandsPool.editorFastOpen(event)
  }, {
    keys: [['Enter'], ['Enter', 'Shift']],
    callback: (event, keys) => commandsPool.editorOpen(event, keys)
  }, {
    keys: [['Backspace'], ['Delete']],
    callback: () => commandsPool.emptySelectedCells()
  }], {
    group: _constants.EDITOR_EDIT_GROUP,
    runOnlyIf: () => (0, _mixed.isDefined)(hot.getSelected())
  });
  context.addShortcuts([{
    keys: [['Control/Meta', 'A']],
    callback: () => commandsPool.selectAllCells(),
    runOnlyIf: () => {
      var _hot$getSelectedRange;
      return !((_hot$getSelectedRange = hot.getSelectedRangeLast()) !== null && _hot$getSelectedRange !== void 0 && _hot$getSelectedRange.highlight.isHeader());
    }
  }, {
    keys: [['Control/Meta', 'A']],
    callback: () => {},
    runOnlyIf: () => {
      var _hot$getSelectedRange2;
      return (_hot$getSelectedRange2 = hot.getSelectedRangeLast()) === null || _hot$getSelectedRange2 === void 0 ? void 0 : _hot$getSelectedRange2.highlight.isHeader();
    },
    preventDefault: true
  }, {
    keys: [['Control/Meta', 'Shift', 'Space']],
    callback: () => commandsPool.selectAllCellsAndHeaders()
  }, {
    keys: [['Control/Meta', 'Enter']],
    callback: () => commandsPool.populateSelectedCellsData(),
    runOnlyIf: () => {
      var _hot$getSelectedRange3, _hot$getSelectedRange4;
      return !((_hot$getSelectedRange3 = hot.getSelectedRangeLast()) !== null && _hot$getSelectedRange3 !== void 0 && _hot$getSelectedRange3.highlight.isHeader()) && ((_hot$getSelectedRange4 = hot.getSelectedRangeLast()) === null || _hot$getSelectedRange4 === void 0 ? void 0 : _hot$getSelectedRange4.getCellsCount()) > 1;
    }
  }, {
    keys: [['Control', 'Space']],
    captureCtrl: true,
    callback: () => commandsPool.extendCellsSelectionToColumns()
  }, {
    keys: [['Shift', 'Space']],
    stopPropagation: true,
    callback: () => commandsPool.extendCellsSelectionToRows()
  }, {
    keys: [['ArrowUp']],
    callback: () => commandsPool.moveCellSelectionUp()
  }, {
    keys: [['ArrowUp', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.moveCellSelectionToMostTop()
  }, {
    keys: [['ArrowUp', 'Shift']],
    callback: () => commandsPool.extendCellsSelectionUp()
  }, {
    keys: [['ArrowUp', 'Shift', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.extendCellsSelectionToMostTop(),
    runOnlyIf: () => !(hot.selection.isSelectedByCorner() || hot.selection.isSelectedByColumnHeader())
  }, {
    keys: [['ArrowDown']],
    callback: () => commandsPool.moveCellSelectionDown()
  }, {
    keys: [['ArrowDown', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.moveCellSelectionToMostBottom()
  }, {
    keys: [['ArrowDown', 'Shift']],
    callback: () => commandsPool.extendCellsSelectionDown()
  }, {
    keys: [['ArrowDown', 'Shift', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.extendCellsSelectionToMostBottom(),
    runOnlyIf: () => !(hot.selection.isSelectedByCorner() || hot.selection.isSelectedByColumnHeader())
  }, {
    keys: [['ArrowLeft']],
    callback: () => commandsPool.moveCellSelectionLeft()
  }, {
    keys: [['ArrowLeft', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.moveCellSelectionToMostLeft()
  }, {
    keys: [['ArrowLeft', 'Shift']],
    callback: () => commandsPool.extendCellsSelectionLeft()
  }, {
    keys: [['ArrowLeft', 'Shift', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.extendCellsSelectionToMostLeft(),
    runOnlyIf: () => !(hot.selection.isSelectedByCorner() || hot.selection.isSelectedByRowHeader())
  }, {
    keys: [['ArrowRight']],
    callback: () => commandsPool.moveCellSelectionRight()
  }, {
    keys: [['ArrowRight', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.moveCellSelectionToMostRight()
  }, {
    keys: [['ArrowRight', 'Shift']],
    callback: () => commandsPool.extendCellsSelectionRight()
  }, {
    keys: [['ArrowRight', 'Shift', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.extendCellsSelectionToMostRight(),
    runOnlyIf: () => !(hot.selection.isSelectedByCorner() || hot.selection.isSelectedByRowHeader())
  }, {
    keys: [['Home']],
    captureCtrl: true,
    callback: () => commandsPool.moveCellSelectionToMostInlineStart(),
    runOnlyIf: () => hot.view.isMainTableNotFullyCoveredByOverlays()
  }, {
    keys: [['Home', 'Shift']],
    callback: () => commandsPool.extendCellsSelectionToMostInlineStart()
  }, {
    keys: [['Home', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.moveCellSelectionToMostTopInlineStart(),
    runOnlyIf: () => hot.view.isMainTableNotFullyCoveredByOverlays()
  }, {
    keys: [['End']],
    captureCtrl: true,
    callback: () => commandsPool.moveCellSelectionToMostInlineEnd(),
    runOnlyIf: () => hot.view.isMainTableNotFullyCoveredByOverlays()
  }, {
    keys: [['End', 'Shift']],
    callback: () => commandsPool.extendCellsSelectionToMostInlineEnd()
  }, {
    keys: [['End', 'Control/Meta']],
    captureCtrl: true,
    callback: () => commandsPool.moveCellSelectionToMostBottomInlineEnd(),
    runOnlyIf: () => hot.view.isMainTableNotFullyCoveredByOverlays()
  }, {
    keys: [['PageUp']],
    callback: () => commandsPool.moveCellSelectionUpByViewportHight()
  }, {
    keys: [['PageUp', 'Shift']],
    callback: () => commandsPool.extendCellsSelectionUpByViewportHeight()
  }, {
    keys: [['PageDown']],
    callback: () => commandsPool.moveCellSelectionDownByViewportHeight()
  }, {
    keys: [['PageDown', 'Shift']],
    callback: () => commandsPool.extendCellsSelectionDownByViewportHeight()
  }, {
    keys: [['Tab']],
    // The property value is controlled by focusCatcher module (https://github.com/handsontable/handsontable/blob/master/handsontable/src/core/focusCatcher/index.js)
    preventDefault: false,
    callback: event => commandsPool.moveCellSelectionInlineStart(event)
  }, {
    keys: [['Shift', 'Tab']],
    // The property value is controlled by focusCatcher module (https://github.com/handsontable/handsontable/blob/master/handsontable/src/core/focusCatcher/index.js)
    preventDefault: false,
    callback: event => commandsPool.moveCellSelectionInlineEnd(event)
  }, {
    keys: [['Control/Meta', 'Backspace']],
    callback: () => commandsPool.scrollToFocusedCell()
  }], config);
}