import { AfterEach } from 'storybook/internal/types';

declare const experimental_afterEach: AfterEach<any>;
declare const initialGlobals: {
    a11y: {
        manual: boolean;
    };
};

export { experimental_afterEach, initialGlobals };
