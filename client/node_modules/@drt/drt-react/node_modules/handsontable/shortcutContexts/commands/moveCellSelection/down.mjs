export const command = {
  name: 'moveCellSelectionDown',
  callback(_ref) {
    let {
      selection
    } = _ref;
    selection.transformStart(1, 0);
  }
};