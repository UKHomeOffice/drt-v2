import { ElementContext, Spec, RunOptions } from 'axe-core';

declare const PARAM_KEY = "a11y";

interface Setup {
    element?: ElementContext;
    config: Spec;
    options: RunOptions;
}
interface A11yParameters {
    element?: ElementContext;
    config?: Spec;
    options?: RunOptions;
    /** @deprecated Use globals.a11y.manual instead */
    manual?: boolean;
    disable?: boolean;
}

export { A11yParameters, PARAM_KEY, Setup };
