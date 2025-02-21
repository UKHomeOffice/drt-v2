import Handsontable from 'handsontable';
import { HotTableProps } from './types';
export declare class SettingsMapper {
    /**
     * Parse component settings into Handosntable-compatible settings.
     *
     * @param {Object} properties Object containing properties from the HotTable object.
     * @returns {Object} Handsontable-compatible settings object.
     */
    static getSettings(properties: HotTableProps): Handsontable.GridSettings;
}
