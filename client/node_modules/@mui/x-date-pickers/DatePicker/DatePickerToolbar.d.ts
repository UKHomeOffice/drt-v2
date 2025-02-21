import * as React from 'react';
import { BaseToolbarProps, ExportedBaseToolbarProps } from '../internals/models/props/toolbar';
import { DateView } from '../models';
import { DatePickerToolbarClasses } from './datePickerToolbarClasses';
export interface DatePickerToolbarProps<TDate> extends BaseToolbarProps<TDate | null, DateView>, ExportedDatePickerToolbarProps {
}
export interface ExportedDatePickerToolbarProps extends ExportedBaseToolbarProps {
    /**
     * Override or extend the styles applied to the component.
     */
    classes?: Partial<DatePickerToolbarClasses>;
}
type DatePickerToolbarComponent = (<TDate>(props: DatePickerToolbarProps<TDate> & React.RefAttributes<HTMLDivElement>) => React.JSX.Element) & {
    propTypes?: any;
};
/**
 * Demos:
 *
 * - [DatePicker](https://mui.com/x/react-date-pickers/date-picker/)
 * - [Custom components](https://mui.com/x/react-date-pickers/custom-components/)
 *
 * API:
 *
 * - [DatePickerToolbar API](https://mui.com/x/api/date-pickers/date-picker-toolbar/)
 */
export declare const DatePickerToolbar: DatePickerToolbarComponent;
export {};
