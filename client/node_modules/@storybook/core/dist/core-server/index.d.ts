import { loadAllPresets } from '@storybook/core/common';
export { getPreviewBodyTemplate, getPreviewHeadTemplate } from '@storybook/core/common';
import { CLIOptions, LoadOptions, BuilderOptions, StorybookConfigRaw, IndexInputStats, NormalizedStoriesSpecifier, Path, Tag, IndexEntry, DocsIndexEntry, StoryIndex, Indexer, DocsOptions, StoryIndexEntry, Options } from '@storybook/core/types';
import { EventType } from '@storybook/core/telemetry';

type BuildStaticStandaloneOptions = CLIOptions & LoadOptions & BuilderOptions & {
    outputDir: string;
};
declare function buildStaticStandalone(options: BuildStaticStandaloneOptions): Promise<void>;

declare function buildDevStandalone(options: CLIOptions & LoadOptions & BuilderOptions & {
    storybookVersion?: string;
    previewConfigPath?: string;
}): Promise<{
    port: number;
    address: string;
    networkAddress: string;
}>;

type TelemetryOptions = {
    cliOptions: CLIOptions;
    presetOptions?: Parameters<typeof loadAllPresets>[0];
    printError?: (err: any) => void;
    skipPrompt?: boolean;
};
type ErrorLevel = 'none' | 'error' | 'full';
declare function getErrorLevel({ cliOptions, presetOptions, skipPrompt, }: TelemetryOptions): Promise<ErrorLevel>;
declare function sendTelemetryError(_error: unknown, eventType: EventType, options: TelemetryOptions): Promise<void>;
declare function withTelemetry<T>(eventType: EventType, options: TelemetryOptions, run: () => Promise<T>): Promise<T | undefined>;

declare function build(options?: any, frameworkOptions?: any): Promise<void | {
    port: number;
    address: string;
    networkAddress: string;
}>;

declare const mapStaticDir: (staticDir: NonNullable<StorybookConfigRaw['staticDirs']>[number], configDir: string) => {
    staticDir: string;
    staticPath: string;
    targetDir: string;
    targetEndpoint: string;
};

/**
 * A function that json from a file
 */
interface ReadJsonSync {
    (packageJsonPath: string): any | undefined;
}

/**
 * Function that can match a path
 */
interface MatchPath {
    (requestedModule: string, readJson?: ReadJsonSync, fileExists?: (name: string) => boolean, extensions?: ReadonlyArray<string>): string | undefined;
}

declare class IndexingError extends Error {
    importPaths: string[];
    constructor(message: string, importPaths: string[], stack?: string);
    pathsString(): string;
    toString(): string;
}

type IndexStatsSummary = Record<keyof IndexInputStats, number>;

type StoryIndexEntryWithExtra = StoryIndexEntry & {
    extra: {
        metaId?: string;
        stats: IndexInputStats;
    };
};
/** A .mdx file will produce a docs entry */
type DocsCacheEntry = DocsIndexEntry;
/** A `_.stories._` file will produce a list of stories and possibly a docs entry */
type StoriesCacheEntry = {
    entries: (StoryIndexEntryWithExtra | DocsIndexEntry)[];
    dependents: Path[];
    type: 'stories';
};
type ErrorEntry = {
    type: 'error';
    err: IndexingError;
};
type CacheEntry = false | StoriesCacheEntry | DocsCacheEntry | ErrorEntry;
type StoryIndexGeneratorOptions = {
    workingDir: Path;
    configDir: Path;
    indexers: Indexer[];
    docs: DocsOptions;
    build?: StorybookConfigRaw['build'];
};
/**
 * The StoryIndexGenerator extracts stories and docs entries for each file matching (one or more)
 * stories "specifiers", as defined in main.js.
 *
 * The output is a set of entries (see above for the types).
 *
 * Each file is treated as a stories or a (modern) docs file.
 *
 * A stories file is indexed by an indexer (passed in), which produces a list of stories.
 *
 * - If the stories have the `parameters.docsOnly` setting, they are disregarded.
 * - If the stories have `autodocs` enabled, a docs entry is added pointing to the story file.
 *
 * A (modern) docs (.mdx) file is indexed, a docs entry is added.
 *
 * In the preview, a docs entry with the `autodocs` tag will be rendered as a CSF file that exports
 * an MDX template on the `docs.page` parameter, whereas other docs entries are rendered as MDX
 * files directly.
 *
 * The entries are "uniq"-ed and sorted. Stories entries are preferred to docs entries and MDX docs
 * entries are preferred to CSF templates (with warnings).
 */
declare class StoryIndexGenerator {
    readonly specifiers: NormalizedStoriesSpecifier[];
    readonly options: StoryIndexGeneratorOptions;
    private specifierToCache;
    private lastIndex?;
    private lastStats?;
    private lastError?;
    constructor(specifiers: NormalizedStoriesSpecifier[], options: StoryIndexGeneratorOptions);
    initialize(): Promise<void>;
    /** Run the updater function over all the empty cache entries */
    updateExtracted(updater: (specifier: NormalizedStoriesSpecifier, absolutePath: Path, existingEntry: CacheEntry) => Promise<CacheEntry>, overwrite?: boolean): Promise<void>;
    isDocsMdx(absolutePath: Path): boolean;
    ensureExtracted({ projectTags, }: {
        projectTags?: Tag[];
    }): Promise<{
        entries: (IndexEntry | ErrorEntry)[];
        stats: IndexStatsSummary;
    }>;
    findDependencies(absoluteImports: Path[]): StoriesCacheEntry[];
    /**
     * Try to find the component path from a raw import string and return it in the same format as
     * `importPath`. Respect tsconfig paths if available.
     *
     * If no such file exists, assume that the import is from a package and return the raw
     */
    resolveComponentPath(rawComponentPath: Path, absolutePath: Path, matchPath: MatchPath | undefined): string;
    extractStories(specifier: NormalizedStoriesSpecifier, absolutePath: Path, projectTags?: Tag[]): Promise<StoriesCacheEntry | DocsCacheEntry>;
    extractDocs(specifier: NormalizedStoriesSpecifier, absolutePath: Path, projectTags?: Tag[]): Promise<false | DocsIndexEntry>;
    chooseDuplicate(firstEntry: IndexEntry, secondEntry: IndexEntry, projectTags: Tag[]): IndexEntry;
    sortStories(entries: StoryIndex['entries'], storySortParameter: any): Promise<Record<string, IndexEntry>>;
    getIndex(): Promise<StoryIndex>;
    getIndexAndStats(): Promise<{
        storyIndex: StoryIndex;
        stats: IndexStatsSummary;
    }>;
    invalidateAll(): void;
    invalidate(specifier: NormalizedStoriesSpecifier, importPath: Path, removed: boolean): void;
    getPreviewCode(): Promise<string | undefined>;
    getProjectTags(previewCode?: string): string[];
    storyFileNames(): string[];
}

declare function loadStorybook(options: CLIOptions & LoadOptions & BuilderOptions & {
    storybookVersion?: string;
    previewConfigPath?: string;
}): Promise<Options>;

export { type BuildStaticStandaloneOptions, StoryIndexGenerator, build, buildDevStandalone, buildStaticStandalone, loadStorybook as experimental_loadStorybook, getErrorLevel, mapStaticDir, sendTelemetryError, withTelemetry };
