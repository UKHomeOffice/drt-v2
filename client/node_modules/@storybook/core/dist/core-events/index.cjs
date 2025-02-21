"use strict";
var N = Object.defineProperty;
var r = Object.getOwnPropertyDescriptor;
var D = Object.getOwnPropertyNames;
var I = Object.prototype.hasOwnProperty;
var C = (R, _) => {
  for (var T in _)
    N(R, T, { get: _[T], enumerable: !0 });
}, o = (R, _, T, A) => {
  if (_ && typeof _ == "object" || typeof _ == "function")
    for (let S of D(_))
      !I.call(R, S) && S !== T && N(R, S, { get: () => _[S], enumerable: !(A = r(_, S)) || A.enumerable });
  return R;
};
var L = (R) => o(N({}, "__esModule", { value: !0 }), R);

// src/core-events/index.ts
var iE = {};
C(iE, {
  ARGTYPES_INFO_REQUEST: () => PE,
  ARGTYPES_INFO_RESPONSE: () => GE,
  CHANNEL_CREATED: () => G,
  CHANNEL_WS_DISCONNECT: () => P,
  CONFIG_ERROR: () => a,
  CREATE_NEW_STORYFILE_REQUEST: () => Y,
  CREATE_NEW_STORYFILE_RESPONSE: () => t,
  CURRENT_STORY_WAS_SET: () => e,
  DOCS_PREPARED: () => H,
  DOCS_RENDERED: () => d,
  FILE_COMPONENT_SEARCH_REQUEST: () => W,
  FILE_COMPONENT_SEARCH_RESPONSE: () => l,
  FORCE_REMOUNT: () => M,
  FORCE_RE_RENDER: () => i,
  GLOBALS_UPDATED: () => p,
  NAVIGATE_URL: () => u,
  PLAY_FUNCTION_THREW_EXCEPTION: () => F,
  PRELOAD_ENTRIES: () => s,
  PREVIEW_BUILDER_PROGRESS: () => y,
  PREVIEW_KEYDOWN: () => c,
  REGISTER_SUBSCRIPTION: () => h,
  REQUEST_WHATS_NEW_DATA: () => rE,
  RESET_STORY_ARGS: () => f,
  RESULT_WHATS_NEW_DATA: () => DE,
  SAVE_STORY_REQUEST: () => LE,
  SAVE_STORY_RESPONSE: () => UE,
  SELECT_STORY: () => Q,
  SET_CONFIG: () => x,
  SET_CURRENT_STORY: () => m,
  SET_FILTER: () => V,
  SET_GLOBALS: () => w,
  SET_INDEX: () => B,
  SET_STORIES: () => X,
  SET_WHATS_NEW_CACHE: () => IE,
  SHARED_STATE_CHANGED: () => q,
  SHARED_STATE_SET: () => b,
  STORIES_COLLAPSE_ALL: () => K,
  STORIES_EXPAND_ALL: () => n,
  STORY_ARGS_UPDATED: () => j,
  STORY_CHANGED: () => k,
  STORY_ERRORED: () => z,
  STORY_FINISHED: () => _E,
  STORY_INDEX_INVALIDATED: () => J,
  STORY_MISSING: () => Z,
  STORY_PREPARED: () => $,
  STORY_RENDERED: () => EE,
  STORY_RENDER_PHASE_CHANGED: () => v,
  STORY_SPECIFIED: () => RE,
  STORY_THREW_EXCEPTION: () => SE,
  STORY_UNCHANGED: () => TE,
  TELEMETRY_ERROR: () => oE,
  TESTING_MODULE_CANCEL_TEST_RUN_REQUEST: () => HE,
  TESTING_MODULE_CANCEL_TEST_RUN_RESPONSE: () => dE,
  TESTING_MODULE_CONFIG_CHANGE: () => lE,
  TESTING_MODULE_CRASH_REPORT: () => aE,
  TESTING_MODULE_PROGRESS_REPORT: () => YE,
  TESTING_MODULE_RUN_ALL_REQUEST: () => eE,
  TESTING_MODULE_RUN_REQUEST: () => tE,
  TESTING_MODULE_WATCH_MODE_REQUEST: () => WE,
  TOGGLE_WHATS_NEW_NOTIFICATIONS: () => CE,
  UNHANDLED_ERRORS_WHILE_PLAYING: () => g,
  UPDATE_GLOBALS: () => NE,
  UPDATE_QUERY_PARAMS: () => OE,
  UPDATE_STORY_ARGS: () => AE,
  default: () => U
});
module.exports = L(iE);
var O = /* @__PURE__ */ ((E) => (E.CHANNEL_WS_DISCONNECT = "channelWSDisconnect", E.CHANNEL_CREATED = "channelCreated", E.CONFIG_ERROR = "co\
nfigError", E.STORY_INDEX_INVALIDATED = "storyIndexInvalidated", E.STORY_SPECIFIED = "storySpecified", E.SET_CONFIG = "setConfig", E.SET_STORIES =
"setStories", E.SET_INDEX = "setIndex", E.SET_CURRENT_STORY = "setCurrentStory", E.CURRENT_STORY_WAS_SET = "currentStoryWasSet", E.FORCE_RE_RENDER =
"forceReRender", E.FORCE_REMOUNT = "forceRemount", E.PRELOAD_ENTRIES = "preloadStories", E.STORY_PREPARED = "storyPrepared", E.DOCS_PREPARED =
"docsPrepared", E.STORY_CHANGED = "storyChanged", E.STORY_UNCHANGED = "storyUnchanged", E.STORY_RENDERED = "storyRendered", E.STORY_FINISHED =
"storyFinished", E.STORY_MISSING = "storyMissing", E.STORY_ERRORED = "storyErrored", E.STORY_THREW_EXCEPTION = "storyThrewException", E.STORY_RENDER_PHASE_CHANGED =
"storyRenderPhaseChanged", E.PLAY_FUNCTION_THREW_EXCEPTION = "playFunctionThrewException", E.UNHANDLED_ERRORS_WHILE_PLAYING = "unhandledErro\
rsWhilePlaying", E.UPDATE_STORY_ARGS = "updateStoryArgs", E.STORY_ARGS_UPDATED = "storyArgsUpdated", E.RESET_STORY_ARGS = "resetStoryArgs", E.
SET_FILTER = "setFilter", E.SET_GLOBALS = "setGlobals", E.UPDATE_GLOBALS = "updateGlobals", E.GLOBALS_UPDATED = "globalsUpdated", E.REGISTER_SUBSCRIPTION =
"registerSubscription", E.PREVIEW_KEYDOWN = "previewKeydown", E.PREVIEW_BUILDER_PROGRESS = "preview_builder_progress", E.SELECT_STORY = "sel\
ectStory", E.STORIES_COLLAPSE_ALL = "storiesCollapseAll", E.STORIES_EXPAND_ALL = "storiesExpandAll", E.DOCS_RENDERED = "docsRendered", E.SHARED_STATE_CHANGED =
"sharedStateChanged", E.SHARED_STATE_SET = "sharedStateSet", E.NAVIGATE_URL = "navigateUrl", E.UPDATE_QUERY_PARAMS = "updateQueryParams", E.
REQUEST_WHATS_NEW_DATA = "requestWhatsNewData", E.RESULT_WHATS_NEW_DATA = "resultWhatsNewData", E.SET_WHATS_NEW_CACHE = "setWhatsNewCache", E.
TOGGLE_WHATS_NEW_NOTIFICATIONS = "toggleWhatsNewNotifications", E.TELEMETRY_ERROR = "telemetryError", E.FILE_COMPONENT_SEARCH_REQUEST = "fil\
eComponentSearchRequest", E.FILE_COMPONENT_SEARCH_RESPONSE = "fileComponentSearchResponse", E.SAVE_STORY_REQUEST = "saveStoryRequest", E.SAVE_STORY_RESPONSE =
"saveStoryResponse", E.ARGTYPES_INFO_REQUEST = "argtypesInfoRequest", E.ARGTYPES_INFO_RESPONSE = "argtypesInfoResponse", E.CREATE_NEW_STORYFILE_REQUEST =
"createNewStoryfileRequest", E.CREATE_NEW_STORYFILE_RESPONSE = "createNewStoryfileResponse", E.TESTING_MODULE_CRASH_REPORT = "testingModuleC\
rashReport", E.TESTING_MODULE_PROGRESS_REPORT = "testingModuleProgressReport", E.TESTING_MODULE_RUN_REQUEST = "testingModuleRunRequest", E.TESTING_MODULE_RUN_ALL_REQUEST =
"testingModuleRunAllRequest", E.TESTING_MODULE_CANCEL_TEST_RUN_REQUEST = "testingModuleCancelTestRunRequest", E.TESTING_MODULE_CANCEL_TEST_RUN_RESPONSE =
"testingModuleCancelTestRunResponse", E.TESTING_MODULE_WATCH_MODE_REQUEST = "testingModuleWatchModeRequest", E.TESTING_MODULE_CONFIG_CHANGE =
"testingModuleConfigChange", E))(O || {}), U = O, {
  CHANNEL_WS_DISCONNECT: P,
  CHANNEL_CREATED: G,
  CONFIG_ERROR: a,
  CREATE_NEW_STORYFILE_REQUEST: Y,
  CREATE_NEW_STORYFILE_RESPONSE: t,
  CURRENT_STORY_WAS_SET: e,
  DOCS_PREPARED: H,
  DOCS_RENDERED: d,
  FILE_COMPONENT_SEARCH_REQUEST: W,
  FILE_COMPONENT_SEARCH_RESPONSE: l,
  FORCE_RE_RENDER: i,
  FORCE_REMOUNT: M,
  GLOBALS_UPDATED: p,
  NAVIGATE_URL: u,
  PLAY_FUNCTION_THREW_EXCEPTION: F,
  UNHANDLED_ERRORS_WHILE_PLAYING: g,
  PRELOAD_ENTRIES: s,
  PREVIEW_BUILDER_PROGRESS: y,
  PREVIEW_KEYDOWN: c,
  REGISTER_SUBSCRIPTION: h,
  RESET_STORY_ARGS: f,
  SELECT_STORY: Q,
  SET_CONFIG: x,
  SET_CURRENT_STORY: m,
  SET_FILTER: V,
  SET_GLOBALS: w,
  SET_INDEX: B,
  SET_STORIES: X,
  SHARED_STATE_CHANGED: q,
  SHARED_STATE_SET: b,
  STORIES_COLLAPSE_ALL: K,
  STORIES_EXPAND_ALL: n,
  STORY_ARGS_UPDATED: j,
  STORY_CHANGED: k,
  STORY_ERRORED: z,
  STORY_INDEX_INVALIDATED: J,
  STORY_MISSING: Z,
  STORY_PREPARED: $,
  STORY_RENDER_PHASE_CHANGED: v,
  STORY_RENDERED: EE,
  STORY_FINISHED: _E,
  STORY_SPECIFIED: RE,
  STORY_THREW_EXCEPTION: SE,
  STORY_UNCHANGED: TE,
  UPDATE_GLOBALS: NE,
  UPDATE_QUERY_PARAMS: OE,
  UPDATE_STORY_ARGS: AE,
  REQUEST_WHATS_NEW_DATA: rE,
  RESULT_WHATS_NEW_DATA: DE,
  SET_WHATS_NEW_CACHE: IE,
  TOGGLE_WHATS_NEW_NOTIFICATIONS: CE,
  TELEMETRY_ERROR: oE,
  SAVE_STORY_REQUEST: LE,
  SAVE_STORY_RESPONSE: UE,
  ARGTYPES_INFO_REQUEST: PE,
  ARGTYPES_INFO_RESPONSE: GE,
  TESTING_MODULE_CRASH_REPORT: aE,
  TESTING_MODULE_PROGRESS_REPORT: YE,
  TESTING_MODULE_RUN_REQUEST: tE,
  TESTING_MODULE_RUN_ALL_REQUEST: eE,
  TESTING_MODULE_CANCEL_TEST_RUN_REQUEST: HE,
  TESTING_MODULE_CANCEL_TEST_RUN_RESPONSE: dE,
  TESTING_MODULE_WATCH_MODE_REQUEST: WE,
  TESTING_MODULE_CONFIG_CHANGE: lE
} = O;
