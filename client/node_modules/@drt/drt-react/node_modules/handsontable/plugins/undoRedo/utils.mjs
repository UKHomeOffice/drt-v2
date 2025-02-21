import "core-js/modules/es.array.push.js";
import "core-js/modules/es.object.from-entries.js";
import "core-js/modules/esnext.iterator.constructor.js";
import "core-js/modules/esnext.iterator.filter.js";
import { rangeEach } from "../../helpers/number.mjs";
/**
 * Gets all cell metas from the provided range.
 *
 * @param {Core} hot The Handsontable instance.
 * @param {number} fromRow The starting row index.
 * @param {number} toRow The ending row index.
 * @param {number} fromColumn The starting column index.
 * @param {number} toColumn The ending column index.
 * @returns {Array} Returns an array of cell metas.
 */
export function getCellMetas(hot, fromRow, toRow, fromColumn, toColumn) {
  const genericKeys = ['visualRow', 'visualCol', 'row', 'col', 'prop'];
  const genericKeysLength = genericKeys.length;
  const cellMetas = [];
  rangeEach(fromColumn, toColumn, columnIndex => {
    rangeEach(fromRow, toRow, rowIndex => {
      const cellMeta = hot.getCellMeta(rowIndex, columnIndex);
      if (Object.keys(cellMeta).length !== genericKeysLength) {
        const uniqueMeta = Object.fromEntries(Object.entries(cellMeta).filter(_ref => {
          let [key] = _ref;
          return genericKeys.includes(key) === false;
        }));
        cellMetas.push([cellMeta.visualRow, cellMeta.visualCol, uniqueMeta]);
      }
    });
  });
  return cellMetas;
}