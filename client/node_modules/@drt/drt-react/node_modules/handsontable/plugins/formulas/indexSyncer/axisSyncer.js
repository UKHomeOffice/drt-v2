"use strict";

exports.__esModule = true;
require("core-js/modules/es.error.cause.js");
require("core-js/modules/es.array.push.js");
require("core-js/modules/esnext.iterator.constructor.js");
require("core-js/modules/esnext.iterator.for-each.js");
require("core-js/modules/esnext.iterator.map.js");
var _string = require("../../../helpers/string");
var _moves = require("../../../helpers/moves");
function _classPrivateFieldInitSpec(e, t, a) { _checkPrivateRedeclaration(e, t), t.set(e, a); }
function _checkPrivateRedeclaration(e, t) { if (t.has(e)) throw new TypeError("Cannot initialize the same private elements twice on an object"); }
function _classPrivateFieldGet(s, a) { return s.get(_assertClassBrand(s, a)); }
function _classPrivateFieldSet(s, a, r) { return s.set(_assertClassBrand(s, a), r), r; }
function _assertClassBrand(e, t, n) { if ("function" == typeof e ? e === t : e.has(t)) return arguments.length < 3 ? t : n; throw new TypeError("Private element is not present on this object"); }
/**
 * @private
 * @class IndexSyncer
 * @description
 *
 * Indexes synchronizer responsible for providing logic for particular axis. It respects an idea to represent trimmed
 * elements in HF's engine to perform formulas calculations on them. It also provides method for translation from visual
 * row/column indexes to HF's row/column indexes.
 */
var _axis = /*#__PURE__*/new WeakMap();
var _indexMapper = /*#__PURE__*/new WeakMap();
var _indexSyncer = /*#__PURE__*/new WeakMap();
var _indexesSequence = /*#__PURE__*/new WeakMap();
var _movedIndexes = /*#__PURE__*/new WeakMap();
var _finalIndex = /*#__PURE__*/new WeakMap();
var _removedIndexes = /*#__PURE__*/new WeakMap();
class AxisSyncer {
  constructor(axis, indexMapper, indexSyncer) {
    /**
     * The axis for which the actions are performed.
     *
     * @private
     * @type {'row'|'column'}
     */
    _classPrivateFieldInitSpec(this, _axis, void 0);
    /**
     * Reference to index mapper.
     *
     * @private
     * @type {IndexMapper}
     */
    _classPrivateFieldInitSpec(this, _indexMapper, void 0);
    /**
     * The index synchronizer for both axis (is storing some more general information).
     *
     * @private
     * @type {IndexSyncer}
     */
    _classPrivateFieldInitSpec(this, _indexSyncer, void 0);
    /**
     * Sequence of physical indexes stored for watching changes and calculating some transformations.
     *
     * @private
     * @type {Array<number>}
     */
    _classPrivateFieldInitSpec(this, _indexesSequence, []);
    /**
     * List of moved HF indexes, stored before performing move on HOT to calculate transformation needed on HF's engine.
     *
     * @private
     * @type {Array<number>}
     */
    _classPrivateFieldInitSpec(this, _movedIndexes, []);
    /**
     * Final HF's place where to move indexes, stored before performing move on HOT to calculate transformation needed on HF's engine.
     *
     * @private
     * @type {number|undefined}
     */
    _classPrivateFieldInitSpec(this, _finalIndex, void 0);
    /**
     * List of removed HF indexes, stored before performing removal on HOT to calculate transformation needed on HF's engine.
     *
     * @private
     * @type {Array<number>}
     */
    _classPrivateFieldInitSpec(this, _removedIndexes, []);
    _classPrivateFieldSet(_axis, this, axis);
    _classPrivateFieldSet(_indexMapper, this, indexMapper);
    _classPrivateFieldSet(_indexSyncer, this, indexSyncer);
  }

  /**
   * Sets removed HF indexes (it should be done right before performing move on HOT).
   *
   * @param {Array<number>} removedIndexes List of removed physical indexes.
   * @returns {Array<number>} List of removed visual indexes.
   */
  setRemovedHfIndexes(removedIndexes) {
    _classPrivateFieldSet(_removedIndexes, this, removedIndexes.map(physicalIndex => {
      const visualIndex = _classPrivateFieldGet(_indexMapper, this).getVisualFromPhysicalIndex(physicalIndex);
      return this.getHfIndexFromVisualIndex(visualIndex);
    }));
    return _classPrivateFieldGet(_removedIndexes, this);
  }

  /**
   * Gets removed HF indexes (right before performing removal on HOT).
   *
   * @returns {Array<number>} List of removed HF indexes.
   */
  getRemovedHfIndexes() {
    return _classPrivateFieldGet(_removedIndexes, this);
  }

  /**
   * Gets corresponding HyperFormula index for particular visual index. It's respecting the idea that HF's engine
   * is fed also with trimmed indexes (business requirements for formula result calculation also for trimmed elements).
   *
   * @param {number} visualIndex Visual index.
   * @returns {number}
   */
  getHfIndexFromVisualIndex(visualIndex) {
    const indexesSequence = _classPrivateFieldGet(_indexMapper, this).getIndexesSequence();
    const notTrimmedIndexes = _classPrivateFieldGet(_indexMapper, this).getNotTrimmedIndexes();
    return indexesSequence.indexOf(notTrimmedIndexes[visualIndex]);
  }

  /**
   * Synchronizes moves done on HOT to HF engine (based on previously calculated positions).
   *
   * @private
   * @param {Array<{from: number, to: number}>} moves Calculated HF's move positions.
   */
  syncMoves(moves) {
    const NUMBER_OF_MOVED_INDEXES = 1;
    const SYNC_MOVE_METHOD_NAME = `move${(0, _string.toUpperCaseFirst)(_classPrivateFieldGet(_axis, this))}s`;
    _classPrivateFieldGet(_indexSyncer, this).getEngine().batch(() => {
      moves.forEach(move => {
        const moveToTheSamePosition = move.from !== move.to;
        // Moving from left to right (or top to bottom) to a line (drop index) right after already moved element.
        const anotherMoveWithoutEffect = move.from + 1 !== move.to;
        if (moveToTheSamePosition && anotherMoveWithoutEffect) {
          _classPrivateFieldGet(_indexSyncer, this).getEngine()[SYNC_MOVE_METHOD_NAME](_classPrivateFieldGet(_indexSyncer, this).getSheetId(), move.from, NUMBER_OF_MOVED_INDEXES, move.to);
        }
      });
    });
  }

  /**
   * Stores information about performed HOT moves for purpose of calculating where to move HF elements.
   *
   * @param {Array<number>} movedVisualIndexes Sequence of moved visual indexes for certain axis.
   * @param {number} visualFinalIndex Final visual place where to move HOT indexes.
   * @param {boolean} movePossible Indicates if it's possible to move HOT indexes to the desired position.
   */
  storeMovesInformation(movedVisualIndexes, visualFinalIndex, movePossible) {
    if (movePossible === false) {
      return;
    }
    _classPrivateFieldSet(_movedIndexes, this, movedVisualIndexes.map(index => this.getHfIndexFromVisualIndex(index)));
    _classPrivateFieldSet(_finalIndex, this, this.getHfIndexFromVisualIndex(visualFinalIndex));
  }

  /**
   * Calculating where to move HF elements and performing already calculated moves.
   *
   * @param {boolean} movePossible Indicates if it was possible to move HOT indexes to the desired position.
   * @param {boolean} orderChanged Indicates if order of HOT indexes was changed by move.
   */
  calculateAndSyncMoves(movePossible, orderChanged) {
    if (_classPrivateFieldGet(_indexSyncer, this).isPerformingUndoRedo()) {
      return;
    }
    if (movePossible === false || orderChanged === false) {
      return;
    }
    const calculatedMoves = (0, _moves.getMoves)(_classPrivateFieldGet(_movedIndexes, this), _classPrivateFieldGet(_finalIndex, this), _classPrivateFieldGet(_indexMapper, this).getNumberOfIndexes());
    if (_classPrivateFieldGet(_indexSyncer, this).getSheetId() === null) {
      _classPrivateFieldGet(_indexSyncer, this).getPostponeAction(() => this.syncMoves(calculatedMoves));
    } else {
      this.syncMoves(calculatedMoves);
    }
  }

  /**
   * Gets callback for hook triggered after performing change of indexes order.
   *
   * @returns {Function}
   */
  getIndexesChangeSyncMethod() {
    const SYNC_ORDER_CHANGE_METHOD_NAME = `set${(0, _string.toUpperCaseFirst)(_classPrivateFieldGet(_axis, this))}Order`;
    return source => {
      if (_classPrivateFieldGet(_indexSyncer, this).isPerformingUndoRedo()) {
        return;
      }
      const newSequence = _classPrivateFieldGet(_indexMapper, this).getIndexesSequence();
      if (source === 'update' && newSequence.length > 0) {
        const relativeTransformation = _classPrivateFieldGet(_indexesSequence, this).map(index => newSequence.indexOf(index));
        const sheetDimensions = _classPrivateFieldGet(_indexSyncer, this).getEngine().getSheetDimensions(_classPrivateFieldGet(_indexSyncer, this).getSheetId());
        let sizeForAxis;
        if (_classPrivateFieldGet(_axis, this) === 'row') {
          sizeForAxis = sheetDimensions.height;
        } else {
          sizeForAxis = sheetDimensions.width;
        }
        const numberOfReorganisedIndexes = relativeTransformation.length;

        // Sheet dimension can be changed by HF's engine for purpose of calculating values. It extends dependency
        // graph to calculate values outside of a defined dataset. This part of code could be removed after resolving
        // feature request from HF issue board (handsontable/hyperformula#1179).
        for (let i = numberOfReorganisedIndexes; i < sizeForAxis; i += 1) {
          relativeTransformation.push(i);
        }
        _classPrivateFieldGet(_indexSyncer, this).getEngine()[SYNC_ORDER_CHANGE_METHOD_NAME](_classPrivateFieldGet(_indexSyncer, this).getSheetId(), relativeTransformation);
      }
      _classPrivateFieldSet(_indexesSequence, this, newSequence);
    };
  }

  /**
   * Initialize the AxisSyncer.
   */
  init() {
    _classPrivateFieldSet(_indexesSequence, this, _classPrivateFieldGet(_indexMapper, this).getIndexesSequence());
  }
}
var _default = exports.default = AxisSyncer;