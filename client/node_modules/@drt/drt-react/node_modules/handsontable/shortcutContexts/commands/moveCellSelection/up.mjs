export const command = {
  name: 'moveCellSelectionUp',
  callback(_ref) {
    let {
      selection
    } = _ref;
    selection.transformStart(-1, 0);
  }
};