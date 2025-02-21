import { command as down } from "./down.mjs";
import { command as downByViewportHeight } from "./downByViewportHeight.mjs";
import { command as left } from "./left.mjs";
import { command as right } from "./right.mjs";
import { command as toColumns } from "./toColumns.mjs";
import { command as toMostBottom } from "./toMostBottom.mjs";
import { command as toMostInlineEnd } from "./toMostInlineEnd.mjs";
import { command as toMostInlineStart } from "./toMostInlineStart.mjs";
import { command as toMostLeft } from "./toMostLeft.mjs";
import { command as toMostRight } from "./toMostRight.mjs";
import { command as toMostTop } from "./toMostTop.mjs";
import { command as toRows } from "./toRows.mjs";
import { command as up } from "./up.mjs";
import { command as upByViewportHeight } from "./upByViewportHeight.mjs";
/**
 * Returns complete list of the shortcut commands for the cells selection extending feature.
 *
 * @returns {Function[]}
 */
export function getAllCommands() {
  return [down, downByViewportHeight, left, right, toColumns, toMostBottom, toMostInlineEnd, toMostInlineStart, toMostLeft, toMostRight, toMostTop, toRows, up, upByViewportHeight];
}