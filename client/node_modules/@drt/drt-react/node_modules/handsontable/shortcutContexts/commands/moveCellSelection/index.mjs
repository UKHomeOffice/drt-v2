import { command as down } from "./down.mjs";
import { command as downByViewportHeight } from "./downByViewportHeight.mjs";
import { command as inlineEnd } from "./inlineEnd.mjs";
import { command as inlineStart } from "./inlineStart.mjs";
import { command as left } from "./left.mjs";
import { command as right } from "./right.mjs";
import { command as toMostBottom } from "./toMostBottom.mjs";
import { command as toMostBottomInlineEnd } from "./toMostBottomInlineEnd.mjs";
import { command as toMostInlineEnd } from "./toMostInlineEnd.mjs";
import { command as toMostInlineStart } from "./toMostInlineStart.mjs";
import { command as toMostLeft } from "./toMostLeft.mjs";
import { command as toMostRight } from "./toMostRight.mjs";
import { command as toMostTop } from "./toMostTop.mjs";
import { command as toMostTopInlineStart } from "./toMostTopInlineStart.mjs";
import { command as up } from "./up.mjs";
import { command as upByViewportHeight } from "./upByViewportHeight.mjs";
/**
 * Returns complete list of the shortcut commands for the cells moving feature.
 *
 * @returns {Function[]}
 */
export function getAllCommands() {
  return [down, downByViewportHeight, inlineEnd, inlineStart, left, right, toMostBottom, toMostBottomInlineEnd, toMostInlineEnd, toMostInlineStart, toMostLeft, toMostRight, toMostTop, toMostTopInlineStart, up, upByViewportHeight];
}