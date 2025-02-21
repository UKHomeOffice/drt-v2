import ClipboardData from "./clipboardData.mjs";
/**
 * @private
 */
export default class PasteEvent {
  constructor() {
    this.clipboardData = new ClipboardData();
  }
  preventDefault() {}
}