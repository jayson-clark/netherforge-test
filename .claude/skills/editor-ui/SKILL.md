---
name: editor-ui
description: How the editor's React side (apps/editor/src) is put together - the Backend contract and the memory backend, the workspace store and its invariants (format is the only writer, undo per document, gestures), external-change rules, how the viewport gets transforms and renders block models from the client cache, the menu/dialog/pack editors, the shared item editor and item icons, window/skin/dialog/text previews from format's numbers, glyph advances, updates, adding an inspector field or a new resource editor end to end, and vitest vs Playwright. Read before changing anything in apps/editor/src or apps/editor/e2e.
---

# The editor UI

React 19 + zustand + react-three-fiber + Monaco, in a Tauri webview. It
talks to the world only through `Backend` (`src/core/backend/types.ts`), and to
project files only through `format` (`src/format.ts`).

## Where things are

`src/` is layered; a layer imports only from itself and those above it in
this list, and every import across folders uses the `@/` alias
(`@/core/paths`). `eslint-plugin-boundaries` enforces it (`LAYERS` in
`eslint.config.js`, resolving `@/` through the TypeScript resolver): a test
may import any layer and `testing/`, `testing/` builds on `core/`, `state/` and
`minecraft/`, and the memory backend (`core/backend/{memory,seed,seeded}.ts`)
may use fixtures. Something two layers need lives in the higher one (the
image facts and the default font in `core/`, `isEditableTarget` in `ui/`,
the window's shape in `minecraft/window`).

| Folder       | Owns                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `core/`      | No React. `backend/` (`generated/bindings.ts`, every command, event and payload type, generated from Rust by tauri-specta (see tauri-backend); `types.ts`, the `Backend` interface, those types re-exported, `BackendError` with its `code`; `tauri.ts` calling the generated commands; `memory.ts` for vitest and Playwright only, held to the Rust side by `contract.json`/`contract.test.ts`; `seed.ts` bundling `examples/basic`, `projectBytes.ts`), `format.ts` (typed wrapper over `@netherforge/format`'s JSON-string exports; nothing else imports `@netherforge/format`), `paths.ts`, `draft.ts` (`setKey`: an optional key set, or deleted for `undefined` or `''`; every editor's), `schemas.ts`, `agentDocs.ts`, `image.ts` (a picture's size and alpha, loaded once per URL), `defaultFont.ts` (`fonts/default.json` from the import), `debug/` (`client.ts`, the Debug Adapter Protocol client: see "The debugger"), `luals/` (what lua-language-server is given: `stubs.ts` the project's names, `follow.ts` keeping `.netherforge/luals/` written, `settings.ts` the client's `Lua` settings, `transport.ts` the JSON-RPC connection over the backend), `validation/` (the validation worker: `requests.ts` what the main thread sends, only what changed (`ChangeTracker`); `validator.ts` the worker's side over format's `ProjectValidator`; `validation.worker.ts` exposing it with Comlink; `client.ts` `validationWorker()`), `mcp/` (the MCP server agents call: the official SDK's `McpServer` with `registerTool` + zod schemas in `tools.ts`, `BackendTransport` over `mcp://message`/`mcpSend` in `transport.ts`, `.mcp.json` with the token in `config.ts`; `ToolError` → `isError`; tests drive it with `MemoryBackend.testMcpCall`, which plays the Rust pipe), and `store/`: `workspace.ts` (`createWorkspace`, one zustand store composed of slices, each a module owning its state and actions and reaching the others only through `get()`: `project.ts` the project, what's known about it, read-only paths, notices, hot reload; `files.ts` create, copy, rename, delete and following references; `documents.ts` documents and undo; `refactors.ts` project-level undo (a rename, move, delete or copy as one transaction); `tabs.ts` tabs and focus requests; `views.ts` per-document view state and selection; `validation.ts` validation through the worker; `packages.ts` the dependencies, `netherforge.lock` and copying from a package; `external.ts` changes on disk; `slice.ts` what they share; `followPaths` tells what's kept by path outside the workspace about renames and deletes), `history.ts`, `run.ts`, `debug.ts` (breakpoints and the debugger's session: see "The debugger"), `updates.ts`, `packs.ts`, `layout.ts` (the workbench's docks, which panel each bottom column shows (by the panel's id), explorer view, tree expansion and open tabs, remembered per project in localStorage by `followProject`). |
| `ui/`        | The design system, each piece with its own `.module.css`: `Button`/`IconButton`, `layout` (`Spacer`, `Divider`, `Bar`, `PanelHeader`, `Stack`), `text` (`Hint`, `Empty`, `Muted`, `Tone`, `Mono`), `Badge` (`Count`, `ErrorCount`, `DirtyDot`), `Callout`/`Banner`, `fields` (`Row`, `Section`, number/vec3/text/select fields, `Check`, `FieldGroup`, `FormLabel`/`FormError`/`FormActions`), `dialogs` (`Modal`, `ask.prompt`/`ask.confirm`), `ContextMenu` (`openMenu(event, items)`), `editable.ts` (`isEditableTarget`: typing in a field or Monaco), `Keys` (a shortcut as the menus write it, `Mod+Shift+S`, drawn one key at a time; `caps` for keycaps), `Tree`, `Pane`, `Tabs`, `Splitter`, `Icon`, `cx`. Menus, dialogs, the tree and tabs are react-aria-components, splitters react-resizable-panels (see "Widgets"). Nothing here knows the project.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `state/`     | The stores in React: `providers.tsx` (`createApp(backend)` wires the stores; `AppProvider`, `useApp`, `useWorkspace`/`useRun`/`useUpdates`/`useResourcePacks`/`useLayout`, `useBackend`), `useResourcePacks.ts` (selectors over the resource packs store, `useProjectFileUrl`), `useExpanded.ts` (`usePane`, `useTreeExpansion`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `minecraft/` | Minecraft itself, shared by every editor: `client/` (client assets: `model.ts`, `geometry.ts`, `render.ts`, `boxes.ts`, `blockMesh.ts`, `bake.ts`, `atlas.ts`, `iconRender.ts`, `MeshGroups`, `models.tsx` (`BlockModel`, `ItemModel`, `Placeholder` in a scene), `useClientAssets`, `usePickerIds`), `item/` (`ItemForm`, the shared `ItemDef` form, with its equipment look and slot pickers (`setEquipment`); `ItemIcon`; `ItemTooltip` and `tooltip.ts`, the game's tooltip, drawn because WebKit never shows `title`; `icon.ts`; `item.ts` pure edits; `projectItems.ts`, files as they are now via `currentText`/`parseCached`), `window/` (`window.ts` vanilla window layout and a menu's `shapeOf`, `WindowFrame`), `text/` (`minimessage.ts`, `MiniText`/`TextPreview`, `glyphs.ts`: `useGlyphMap`, `glyphAdvancesOf`), `nbt/` (NBTify read and written as Java Edition's files are, and value accessors; it's patched, `patches/nbtify@*.patch`, to check an array's length before allocating it), `structure/`, `world/` (level.dat, Anvil regions, chunk sections, LZ4, the terrain worker).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `editors/`   | One folder per resource editor (`centity/`, `menu/`, `dialog/`, `resource_pack/`, `particles/`, `cutscene/`, `terrain/`, `item/`, `recipe/`, `loot/`, `structure/`, `world/`, `script/` (Monaco: `CodeEditor`, `monaco.ts`, `luaClient.ts`; see "Lua"), `project/`), each with its pure `ops.ts`, its editor and its outline (and its thumbnail and its `view.ts`, the registration of its per-document view, when it has them); `contributions.ts` (what can be contributed: `KindContribution`, `TabView`, `KindCommand`, `DockPanel`, `ViewStateSpec`, `CommandContext`); `registry.tsx` (`KIND_CONTRIBUTIONS`, one entry per kind, and the file tabs; see "Contributions"); `views.ts` (a document's view typed by its kind: `useViewState`, `useSelection`, `usePrimary`, `useView`/`viewOf`); `shared/` (`EditorScreen`/`EditorBar`/`Stage`/`Page`, `InspectorPanel`, `viewport/` (see "Viewports": `Viewport3D`, `ViewportHost`, `ViewportCanvas`, `scenes.ts`, `camera.ts`, `Gizmo`, and `parts` for the toolbar, notes and fallback), `timeline/` (see "Timelines": `Timeline`, `time.ts`, `keys.ts`, `usePlayback`), `KeyedList` (entries kept by name, as an outline pane, under a `NameRule`: parts' names by default, `ID_NAMES` for a loot table's pools), `Gallery` (a gallery split into sections of cards), `entries.module.css`, `ScriptField`, `ServerWorlds`, `modelDoc`, `RawDocView`, `useFocusRequests`, `focus.ts`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `workbench/` | The shell: `contributions.tsx` (`TAB_VIEWS`, every tab type's view including the settings', and `DOCK_PANELS`), `Workbench` (docks and splitters), `toolbar/` (title bar, run controls), `editorArea/` (tabs, banners), `outline/` (the left dock: `subject.ts`, `FilesPane`, `fileTree.ts`), `docks/` (inspector and bottom docks), `explorer/` (the project explorer: `folders.ts`, tiles, thumbnails), `panels/` (Problems, Console, Instances, Profiler, Debug), `debugStops.ts`, `quickOpen/`, `settings/` (the settings tab: `SettingsScreen`, its pages in the outline), `welcome/`, `resourceActions.ts`, `shortcuts.ts`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `app/`       | `main.tsx`, `App.tsx`, `styles/` (`tokens.css`, `base.css`: the only global CSS).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `testing/`   | Hand-written fixtures: `clientFixture.ts` (`?assets=fixture`), example files, NBT and region builders; `workspace.ts`, the tests' shared set-up (`openExampleWorkspace`, `openExampleApp`, `settle`; see "Testing").                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |

## Contributions

Everything a kind, a tab type, a dock panel or a kind's command adds to the
workbench is registered once, and the shell only reads the registries:

- **Kinds**: `KIND_CONTRIBUTIONS` in `editors/registry.tsx`, one entry per
  kind in format's generated `KINDS` (`satisfies` a mapped type over
  `KindId`, so a kind without one is a type error, and a kind whose files
  aren't Lua must have a `view`). An entry is the kind's title, one, icon,
  `tone`, id-prompt `message`; its tab's `view` (lazy: the JSON kinds'
  editors and the binary kinds' screens alike, given the tab's path); its
  `outline`; its `thumbnail` (and a `studio` the workbench mounts while it
  has work, through `useSyncExternalStore`); `viewState` (see below); and
  `commands`. Its key
  order is the explorer's. `KIND_ORDER` and `kindContribution(kind)` read it.
  A module has no view: it opens its entry (`opensAs`) in a script tab.
- **Tabs**: `TAB_VIEWS` (`workbench/contributions.tsx`), a `Record<TabType,
TabView>`: the kinds' tabs and the file tabs (`script`, `json`, `project`)
  from the registry, and `settings`. A view says its icon, its strip label,
  whether a document backs it (banners, save, undo, read-only), whether it's
  a resource's own (the tab shows the thumbnail) and, for a tab that isn't
  inside a resource, its outline (`{ subtitle, view }`). `EditorArea`,
  `TabStrip` and `OutlineDock` have no switch over tab types.
- **Commands**: a kind's `KindCommand`s get the id `<kind>.<name>` and run
  through the same table as `COMMANDS` (`workbench/menus/commands.ts`). Every
  command has a `when(c)`: when it can run (the menus grey it out otherwise).
  A `resource` command acts on the active tab's resource of its kind (its own
  tab or a file in it), so it's enabled only then; the palette lists it per
  resource (`<kind>.<name>:<id>`), its `menu` places it (`'run'`), and
  `toolbar` gives it a button beside the dev server's controls
  (`TOOLBAR_COMMANDS`; Spawn at me). A `kind` command needs no resource
  (Stop Particle Effects).
- **Dock panels**: `DOCK_PANELS`, each with a persistent `id` (the layout
  keeps which panel each bottom column shows by it, and a stored id that's
  gone shows the column's first), its column, view, `badge` and View-menu
  item (`view.panel:<id>`). `showPanel(layout, id)` opens one.
- **Per-document view state**: a kind registers a `ViewStateSpec<V, S>`
  (`editors/<kind>/view.ts`): its view's `initial` state `V` (a centity's
  clip, playhead, gizmo; a particle preview's tick and loop; a recipe's grid
  offset; every 3D kind's `camera`, see "Viewports"; a map's radius), what its selection holds `S` (node names, slot indexes, dialog
  entries, resource pack picks, particle emitters and keys), each item's `key`, and
  `opensInside` (what opening a non-text file inside one of its resources
  selects: a resource pack's pictures and sounds). `ViewStateOf<K>`/`SelectionOf<K>`
  type a kind's from its entry, and `editors/views.ts` reads and changes a
  document's view through them (`useViewState('centity', path)`,
  `usePrimary`, `useView(kind, path).select(...)`). The workspace keeps it
  (`core/store/views.ts`): see "View state" below.
- **Inspectors** stay part of their editor's view, portalled into the dock
  (`InspectorPanel`), because they share the editor's state; the dock itself
  is generic.
- **Read-only documents** (a dependency package's): `setReadOnly(path,
reason)` marks a file or folder; `readOnlyReason(readOnly, path)` is the
  innermost reason. The store refuses (with a notice) to edit, save, create,
  copy into, rename or delete one, and rename-references skips it. The editor
  area shows a "Read-only: …" banner and wraps the view in a disabled
  `<fieldset>` (so every control in any view is disabled), the inspector dock
  does the same, and Monaco is set `readOnly`.

`editors/registry.node.test.ts` reads the app with the TypeScript checker
and fails on a list of kinds anywhere but the registry (a switch, an `a ===
'x' || a === 'y'`, an array, object or union of kind ids) and on any kind id
in `core/`, `workbench/` or `app/`; an editor naming its own kind is fine.
Its `EXCEPTIONS` say why a file may (LuaLS's project names, which read a
centity's nodes and a dialog's buttons).

## The workbench

- **The Profiler panel** (`workbench/panels/Profiler.tsx`, `ProfileTimeline.tsx`)
  reads `core/store/profiler.ts` (`createProfiler`, in `AppStores` as
  `profiler`, `useProfiler`): it subscribes (`profiler_subscribe`) whenever
  the bridge comes up (`createRun`'s `onBridgeUp`), adds each `profiler`
  batch with the pure `addSample` (functions summed by script, kind and
  place; scopes; the last `TIMELINE_TICKS` ticks), and `reset()` starts
  over. The table is a plain `<table>` with `aria-sort` headers
  (`sortRows`); a function's place opens its script at the line. The
  timeline is a canvas of stacked bars, one per tick, the steps in
  `PHASES` order with the dataviz palette's dark categorical steps (checked
  for colour-vision separation against `--bg-panel`), a legend, and a hover
  tooltip per tick. The memory backend fakes the stream while subscribed
  (`profileEveryMs`, a batch timing every `on("<event>"` in the project's
  scripts at its line).

- **The Tests panel** (`workbench/panels/Tests.tsx`) shows the last run of the
  project's script tests: `core/store/tests.ts` (`createTests`, in `AppStores` as
  `tests`, `useTests`) calls `backend.runTests(filter)` (the `tests_run` command:
  the test runner jar with the editor's Java, see tauri-backend; the project must
  be trusted) and keeps the `TestReport` or why it couldn't run. The panel groups
  results by file with a pass or fail icon, the time, and for a failure where,
  why, the script's `log()` lines and the traceback; a click opens the file at the
  line (the failing script's own for an error). Run → Run Tests (`run.tests`)
  opens the panel and runs; the tab's badge counts what didn't pass. The memory
  backend has no Lua: `testTestReport(report)` sets what `runTests` answers (and
  applies the filter as the runner does); without one it throws `unavailable`.

- **Docks.** The outline (left), the inspector (right) and the bottom dock
  (two columns: the project explorer and Instances on the left, Problems,
  the Console, the Profiler and Debug on the right; `showBottom(tab)` picks the tab's own column)
  resize, hide
  (toolbar, Ctrl/Cmd+B, Ctrl/Cmd+Alt+B, Ctrl/Cmd+J) and are remembered per
  project with the open tabs (`core/store/layout.ts`). The inspector dock
  stays mounted while hidden, so an editor's inspector always has a target.
- **Editors don't lay out docks.** An editor renders `EditorScreen` (marked
  `data-editor`) with its canvas, and its inspector inside `InspectorPanel`,
  which portals into the right dock (`data-inspector`). Focus requests look in
  the inspector, then the editor, and open the inspector dock.
- **Outlines.** A kind's `outline` in the registry renders `Pane`s of
  `Tree`s for its main file (`{ path }`, loaded by the dock first). The dock
  shows the outline of the resource the active tab belongs to, so a script's
  tab shows its resource's outline too; selecting there calls
  `select(path, …)` then `openFile(path)`. A resource's main document stays
  loaded while any tab inside that resource is open (`closeTab`).
  `FilesPane` lists the resource's folder VS Code style; the dock hands it to
  the outline as `files` (`OutlineProps`), which places it (last, or for a
  centity between Animations and the long Nodes list) and still renders it
  when the model doesn't parse. A node row's `picture` replaces its icon: a
  block or item display shows its `ItemIcon`.
- **Trees** (`ui/Tree`) own only the transient (inline rename, drag);
  selection, expansion (`useTreeExpansion`, in the layout store) and edits
  are the caller's. Keyboard: react-aria's (arrows, Left/Right close and
  open, Home/End, typeahead, Space selects; `selectOnFocus` makes arrows
  select), plus ours: Enter and double-click `onOpen`, F2, Delete/Cmd+Backspace,
  Ctrl/Cmd+D, the context-menu key. With `multiple` (`{ selection,
onChange }`, oldest first, the row acted on last being the primary:
  `selectionOrder`) it's react-aria's multiple selection, and a context
  menu on a selected row keeps the others selected.
- **The explorer** (with each dependency's resources read-only under the
  kinds: see "Packages") lists resources as atoms (`explorer/folders.ts`, one
  folder per registry entry: ids, location, what opens, how to create, from
  `KINDS`). `Thumbnail` draws the kind's registered `thumbnail` over its
  icon. Centity thumbnails are drawn one at a
  time in one hidden canvas (`editors/centity/CentityThumbnail.tsx`), keyed by
  the centity's text and the client version, so an unchanged centity is
  never drawn twice, even across sessions: each picture is saved as
  `.netherforge/thumbnails/<id>.png` with `index.json` (id → key), read once
  per project (`loadThumbnails`); a saved picture whose key matches is shown
  from disk, a changed centity keeps its old picture until the new one is
  drawn, and the index drops centities that are gone. The workbench mounts that studio (the centity's registered `studio`) while anything is queued, so the outline
  header's picture (the same `Thumbnail`) draws with the explorer hidden.
- **Settings** are a tab of their own (`openSettings(page?)`, type
  `settings`, path `SETTINGS_PATH`, which is no file: nothing loads, saves,
  validates or renames it, and the layout restores it like any tab). The
  outline lists its pages (`SettingsPages`, its `TabView`'s outline); the open page is
  what the settings path's view selects (`openSettings(page)`).
- **Widgets.** Focus, keyboard and ARIA are never hand-rolled: menus,
  context menus, dialogs, the tree, tabs and the palette are
  react-aria-components, splitters react-resizable-panels (react-aria has
  none). Style them through their CSS module and react-aria's data
  attributes (`[data-focused]`, `[data-selected]`, `[data-hovered]`,
  `[data-focus-visible]`, `[data-disabled]`, `[data-drop-target]`; a
  separator's `[data-separator='hover'|'active']`), not `:hover` or our own
  state classes. Specifics:
  - `openMenu(event, items)` draws a `Menu` in a `Popover` anchored at the
    pointer (`ContextMenuHost`); react-aria gives focus back on close.
  - `Modal` is `ModalOverlay`/`Modal`/`Dialog` with a `Heading slot="title"`:
    focus is contained and restored, Escape or a click outside closes, the
    page behind is inert. Any new dialog uses `Modal`.
  - `Tree` is react-aria's `Tree`, a `treegrid` of `row`s (rows render from
    `items` and are cached: anything a row reads goes in `dependencies`).
    Dragging is react-aria's drag and drop (`onMove`/`canMove`; a drop _on_ a
    row that takes rows goes inside it, else beside it; a fixed row is never
    dropped). In a tree with `onMove` every row, the create row too, has a
    `slot="drag"` button for keyboard dragging, disabled on a fixed or
    renaming row: react-aria counts every row as draggable and warns about
    one mounted without it (`Tree.test.tsx` fails on that warning). Keys react-aria doesn't
    take (F2, Delete…) are read on a wrapper; Enter is caught in capture,
    before react-aria would select. An inline field stops its own key, press
    and focus events, so the tree doesn't act on them.
  - Tabs are react-aria `Tabs`/`TabList`/`Tab`/`TabPanel` (`ui/Tabs` for the
    bottom dock; `EditorArea`'s open files, whose drag to reorder is native
    drag events through `Tab`'s `render` prop; the explorer's kinds; the
    ingredient picker). A `TabPanel`'s id is the selected tab's.
  - The menu bar is a `MenuTrigger` per menu with a non-modal `Popover`; the
    bar itself (Left/Right between menus, Alt alone) is ours, since react-aria
    has no menubar. Its buttons don't take focus (`Pressable
preventFocusOnPress`), and a chosen item runs a frame after its menu
    closes, once focus is back where it was, so Edit acts on the focused
    field. Toggles sit in a `MenuSection selectionMode="multiple"`, so
    they're `menuitemcheckbox`es.
  - Docks are `Group`/`Panel`/`Separator` (`ui/Splitter`, double-click
    resets): sizes in pixels within `LIMITS`, a panel's `defaultSize` taken
    once when it appears (`DockPanel`; changing it would relayout mid-drag),
    written back from `onResize`. The inspector panel collapses rather than
    unmounting. The library sets a separator's `flex: 0 0 auto` inline, so
    `Splitter.module.css` gives the 1px line a width or height (a
    `flex-basis` is overridden and the line takes no space), and keeps its
    invisible 7px grip box: see "Testing" for why the previews depend on it.
- **Menus and shortcuts** are one table, `workbench/menus/commands.ts`
  (label, keys, `when`/checked, run), with the kinds' commands and the
  panels' View items added from their registries; `buildMenus` lays out File, Edit,
  View, Run, Help, and `useAppMenus` (mounted in `App`) runs every shortcut
  a menu shows from one keydown listener. macOS gets the system menu bar
  (`backend.setAppMenu`, `core/backend/tauriMenu.ts`: built once per shape,
  then only relabelled/enabled/checked); Windows and Linux draw the same
  model in the title bar (`MenuBar`, which never takes focus, so Edit acts on
  the focused field or Monaco via `editors/script/focusedCode.ts`). Undo in a
  field marked `data-commit="clean"` is the document's. Select All works only
  in a field or Monaco: elsewhere its key is claimed and does nothing, and
  `base.css` makes the app unselectable (inputs, `code`/`pre`, console lines
  opt back in). A new command is an entry in `COMMANDS` (with a `hint` and
  `icon` for the palette) and a place in `buildMenus`; a command about one
  kind is a `KindCommand` in its registry entry instead.
- **The palette** (`quickOpen/`, Cmd/Ctrl+P; Cmd/Ctrl+Shift+P or a leading
  `>` for commands alone) lists resources (with their explorer `Thumbnail`),
  text files and `paletteCommands`: every enabled command (`palette` renames
  one or, `false`, leaves it out), plus ids only it has: `settings:<page>`,
  `<kind>.<name>:<id>` (a kind's resource command on one resource, e.g.
  `centity.spawn:tower`), `file.new:<folder>`, `file.recent:<root>`. It runs
  them through `useMenus`'s `run` and `context`, as the menus do. A
  resource's own tab shows the same thumbnail.
- **Zoom**: Cmd/Ctrl +, - and 0 scale the whole UI (`useZoomShortcuts`,
  mounted in `App`, so the welcome screen zooms too) through
  `backend.setZoom`, the webview's own zoom (the memory backend sets CSS
  `zoom`). The level is this machine's, kept in localStorage
  (`core/zoom.ts`), and `main.tsx` applies it before the first frame.
- **Modules** have no editor: a module opens its `init.lua`, and its files
  are in the outline.

## The debugger

The dev server's plugin is a Debug Adapter Protocol adapter (the hot-reload
and plugin-runtime skills); the editor is its client. Every DAP message rides
one `dap` notification on the bridge, both ways: `backend.dapSend(message)`
(`BridgeClient.notify` in Tauri) and `onBridgeEvent('dap')`. Rust passes them
through like any frame. Message types are `@vscode/debugprotocol`'s
`DebugProtocol`; nothing is hand-declared.

- **`core/debug/client.ts`** (`DapClient`): `seq`/`request_seq` correlation,
  `request(command, args)` typed per command (`DapRequests`), events by name,
  a 10 s timeout (a plugin without the debugger never answers, and the store
  gives up quietly), `reset()` failing what waits when the bridge drops.
- **`core/store/debug.ts`** (`createDebug`, `app.debug`, `useDebug`): breakpoints
  as `{ path: lines }` (project paths, the ones script errors use), whether
  they're active, break on script errors (the adapter's `errors` exception
  filter), remembered per project root in localStorage (`netherforge.debug:<root>`,
  through `LayoutStorage`). It attaches whenever the bridge comes up (its own
  `onServerState`): `initialize`, `attach`, `setBreakpoints` per file,
  `setExceptionBreakpoints`, `configurationDone`; the bridge going down ends
  the session (`detached`), and the next bridge starts a new one. On
  `stopped` it fetches the stack, selects the top frame and fetches its scopes
  and the first scope's variables; anything else is fetched when it's
  expanded (`expand(reference)`), cached by reference until the server runs
  (`continued`, or the answer to `continue`/`next`/`stepIn`/`stepOut`). Stop
  (`stopDebugging`) is DAP's `disconnect` plus breakpoints deactivated;
  activating them attaches again.
- **Saves wait while paused.** The plugin refuses every request that needs
  the main thread while a breakpoint holds it, a reload included, so
  `RunHooks.holdReload` (wired in `state/providers.tsx`) queues the paths and
  the store reloads them once the server runs.
- **Breakpoints follow their file.** The refactors slice's `followPaths`
  reports moves (a rename, a folder's, their undo and redo) and deletes; the
  store moves or drops the lines and resends both files. Edits move them with
  their line: `editors/script/breakpoints.ts` (`useBreakpoints`, called by
  `CodeEditor` after its model effect) draws them as glyph-margin decorations
  that Monaco tracks (`NeverGrowsWhenTypingAtEdges`), and after every content
  change reports their lines back (`setBreakpointLines`, a no-op when nothing
  moved). A click in the glyph margin or F9 (`netherforge.toggleBreakpoint`,
  also the Run menu's Toggle Breakpoint through `focusedCode`) toggles one;
  the selected frame's line in this file is marked whole.
- **The Debug panel** (`workbench/panels/Debug.tsx`, a `DOCK_PANELS` entry with a
  badge while paused): the toolbar, the stop line (`describeStop`), the call
  stack, breakpoints, and the variables as a `Tree` whose unfetched
  expandable rows hold one "Loading…" row (so they have a twisty) until
  `expand` brings their children. `workbench/debugStops.ts`
  (`useFollowDebugStops`, mounted by `Workbench`) shows the panel when the
  server stops and opens the stop's file at its line. The Run menu has
  Continue (F5), Step Over (F10), Step Into (F11), Step Out (Shift+F11), Pause,
  Stop Debugging, Toggle Breakpoint (F9), Breakpoints Active and Break on
  Script Errors.
- **The memory backend's adapter** (`core/backend/memoryDebug.ts`,
  `FakeDebugAdapter`) answers every request above, records them (`dapLog`),
  keeps what it was sent (`testBreakpoints()`), stops when a test says so
  (`testDebugStop({ file, line, reason, description })`, with a fixed stack:
  locals with a nested table and a `Player` handle, an upvalue, a scope's
  `this`), and refuses main-thread bridge requests while stopped, as the plugin does.
- **Tests**: `core/debug/client.test.ts`, `core/store/debug.test.ts` (attach,
  stops and variables, steps, held reloads, renames with undo and folders,
  deletes, moved lines, deactivating, stop, a plugin that never answers,
  persistence), `workbench/panels/Debug.test.tsx`, and `e2e/debug.spec.ts`
  (F9, a stop, a table opened, a save held until Continue, a rename taking
  its breakpoint along).

## Lua: lua-language-server

Every Lua language feature (diagnostics, completion, hover, signature help,
go to definition, references, rename) comes from lua-language-server (LuaLS),
which the backend runs per open project (see tauri-backend); the editor has
no Lua analysis of its own.

- **Monaco is `@codingame/monaco-vscode-api`** (`@codingame/monaco-vscode-editor-api`
  in place of `monaco-editor`), started once in "classic" mode (Monarch
  highlighting, the standalone editor, no workbench) by
  `MonacoVscodeApiWrapper` in `editors/script/monaco.ts`, because
  `monaco-languageclient` needs VS Code's services. `setupMonaco(app)`
  resolves when editors can be made; **nothing may touch Monaco's API before
  it** (even `getModels()` starts the services with defaults, and the real
  start then fails with "Services are already initialized"). Languages are
  registered after the start; Vite pre-bundles the whole stack in one run
  (`optimizeDeps.include` in `vite.config.ts`), or dev ends up with two
  copies of VS Code's service modules.
- **Models are keyed by the file's real URI** (`modelUri(path)`, under the
  project root), which is how LuaLS names files; `pathOf(uri)` goes back. A
  file no tab has open is read through `ProjectFiles` (a read-only file
  system over the store and the backend) when a definition is in it, and
  `openEditorFunc` opens another file's definition in its tab. Files outside
  the project (the API's stubs in `.netherforge/docs/`) are only peeked.
- **The client** (`luaClient.ts`) is one `MonacoLanguageClient` per open
  project, started with the first script editor and stopped when the project
  closes or changes, over `core/luals/transport.ts` (`lualsStart`/`lualsSend`,
  `luals://message`, ignoring other generations). It answers LuaLS's
  `workspace/configuration` with `settings.ts` (the plugin path and its
  arguments: each scripted folder kind and its main file, from format's `KINDS`), and trusts the plugin
  (`initializationOptions.trustByClient`). The project's `.luarc.json` wins
  where both say something. A backend with no LuaLS (the memory backend; a
  dev build that never ran `tools/luals.mjs`) rejects `lualsStart`: the
  client is skipped and scripts are highlighted only.
- **What LuaLS knows about the project**: the API from a per-project copy
  under `.netherforge/luals/` (`nf.lua`, `surfaces/`), written by `core/luals/api.ts`
  (`tailoredApiStubs`, from `apiStubs.ts`'s lazy bundle of the generated stubs and
  `gates.json`) with every function the project can't use marked `---@deprecated` and
  the reason: newer than the project's `minecraft` (`since`), or needing something its
  `netherforge.json` hasn't declared (`requires`, via `granted`). The copy is rewritten
  when the manifest changes, and the project's `.luarc.json` turns on `not-yieldable`
  and `hint.awaitPropagate`. `api.ts` stays free of `import.meta.glob` so the Node
  tests can import it. And the project's names from `.netherforge/luals/project.lua`,
  written by `followLualsStubs` 300 ms after the outline or a document
  changes (unsaved edits included, only when the text changes): each
  `NameKind` is an alias (`NodeName`, `ButtonKey`, `CentityId`...) that
  `nf.lua` declares as `string` and this file declares again with the
  project's names, which LuaLS merges. `require` beside a resource's script
  resolves through the backend's LuaLS plugin. The packages the project
  depends on directly (`packageSources`: their outlines, and the files the
  packages slice read, modules' Lua included) add what they export: their
  names as more of the aliases (`library:gem`, `library:gems/gem`), and a stub
  per file of each exported module under `.netherforge/luals/packages/<ns>/`
  (`packageModuleStubs`), which the plugin resolves `require("library:greetings")`
  to (`packages=` in `pluginArgs`; `luals.node.test.ts` goes to a definition
  through one). Stubs of a package no longer depended on are deleted.
- **Tests**: the pure parts in vitest (`stubs.test.ts`, `transport.test.ts`);
  the real server in `luals.node.test.ts` (Node, the pinned LuaLS). Playwright
  has no LuaLS: it checks a script still opens, highlights and edits.

## Styling

Every component styles itself with a CSS module beside it
(`Thing.module.css`, camelCase classes, `cx(styles.a, on && styles.b)`).
Colours, radii and fonts come from `app/styles/tokens.css`; `base.css` holds
element defaults only. Don't add global classes. Hashed class names mean
nothing outside the component: find elements by role and accessible name,
or by a `data-*` attribute (`data-twisty`, `data-skin`, `data-path`,
`data-inspector`, `data-editor`), never by class. A selector must never
return a fresh `[]` or `{}` (`?? []` in a selector loops forever before the
first validation): use a module-level empty constant.

## The workspace store: invariants

- **`format` is the only writer.** A model document saves as
  `JSON.stringify(model)` → `canonicalize` → `writeText`. Raw JSON (a file
  that doesn't parse, or "Edit as JSON") saves canonically if it parses and
  as typed if not, so nothing the user wrote is lost. Never format JSON by
  hand, never write a default where you can leave a key out (`ops.setChannel`
  shows how: the canonical writer keeps explicit defaults).
- **A document** (`Doc`) is either a model (`history` is authoritative,
  `raw: false`) or text (`raw: true`, `text` is authoritative: Lua, raw
  JSON). `savedText` is the last text we read from or wrote to disk;
  `savedModel` is its parse, for the dirty check (structural, so editing a
  value and back is clean again).
- **Undo is per document.** Model documents keep snapshot histories
  (`history.ts`); text documents use Monaco's own undo (one Monaco model per
  path, kept across tab switches). This stays deliberately: moving Lua/raw
  JSON onto store snapshots would lose Monaco's cursor-aware, per-keystroke
  grouping and fight its model, for no gain (a text doc has one editor). Ctrl/Cmd+Z in a text field or Monaco is
  theirs; elsewhere it's the active document's, where a refactor is one
  step too (see "Undo for refactors").
- **Gestures**: `beginGesture(path)` … edits … `endGesture(path)` make one
  undo entry. Use them for anything continuous: gizmo drags, label scrubs,
  keyframe drags. Discrete edits (Enter in a field, a select) are one
  `edit()` each. Fields commit on Enter/blur, never per keystroke.
- **Edits** go through `edit<T>(path, draft => …)` using the pure functions
  in `editors/centity/ops.ts`. The recipe gets an Immer draft: the new model shares
  every subtree it didn't touch (so snapshots are cheap and `sameJson`, the
  dirty check, mostly compares references), and a recipe that changes nothing
  commits nothing. Models aren't frozen, but treat them as immutable outside
  `edit`.
- **View state** is per document, beside it and not in it
  (`views[path]`: `selection`, oldest first, the last being the primary an
  inspector shows; `state`, only what was changed from the kind's
  `initial`). It's never saved or undone. A rename moves it with the
  documents and tabs in one `set` (`files.ts`), and it goes once neither a
  document nor a tab has its path (`liveViews`, the one subscription that
  drops views: closing a tab, deleting, a file gone from disk, closing the
  project). So it survives tab switches: a particle preview's playhead, a
  centity's clip. Selection is typed and multiple: `select(...items)`,
  `toggle(item)` (Shift or Cmd/Ctrl-click in the centity viewport, whose
  Delete removes every selected node), `mapSelection` after an edit renames
  or removes what was selected. The centity's Nodes tree selects several
  (Tree's `multiple`, bound to the view: Cmd/Ctrl-click, Shift-click,
  Shift+arrows), and Delete on a selected row deletes them all; the other
  outlines select one.
- **Validation** runs in a Web Worker (`core/validation/`), never on the
  main thread, debounced 250 ms after any change. The project as format
  reads it is every file: current in-memory text for open JSON docs (a
  model's text is worked out once per model, `modelText`), disk text for the
  rest, `null` for non-JSON, plus the cached `GameDataBundle` and the
  packages (`packages`, a `PackageInputs` from the packages slice: folders
  by location and git fetches by key). `ChangeTracker`
  sends the worker only what changed since its last request (files,
  deletions, game data, the packages when they were read again;
  `reset` for another project), so an edit sends one file. The worker holds format's `ProjectValidator`, whose
  `ProjectCache` keeps what each file validated to by its content (see
  project-format): it validates the changed file alone, then runs the checks
  across files (references, `crossCheck`) over everything, and answers the
  outline and the files it validated afresh. RPC is Comlink (`wrap` on the
  main side, `expose(…, self)` in the worker); the worker is Vite's
  `new Worker(new URL('./validation.worker.ts', import.meta.url), { type:
'module' })`, bundled with its own copy of format's JS build (~0.9 MB
  minified; importing it takes 100–200 ms, in the worker). `validateNow()`
  resolves once the problems are in the store; an answer to a request made
  before `cancelValidation` (closing the project) is dropped, and a failed
  request makes the next one start the worker over. `createWorkspace(backend,
{ validation })` takes another client factory (a test's, to watch requests
  and what was validated). Problems carry `file`, `line` (parse errors) or
  `path` (`$.nodes.top.display.block`); `openFile(file, { line, jsonPath })`
  turns either into a focus request.
- **Save** then hot reload: if the bridge is connected, `bridgeRequest('reload', { paths: [path] })`, result logged in the console.
- **The dev bridge** is JSON-RPC (`core/bridge/client.ts`, vscode-jsonrpc
  over the backend's relay; see the hot-reload skill): `backend.bridgeRequest(method,
params)` typed by format's `BridgeRequests`, and `backend.onBridgeEvent(event,
fn)` typed by `BridgeEvents` for `console`, `problems` and `status`. Don't
  switch over message types; subscribe to the event you need. The memory
  backend answers requests itself and records each as `{ method, params }` in
  `bridgeLog`; tests push notifications with `testBridgeEvent(event, params)`.
- **The console** is a ring buffer (`core/store/consoleBuffer.ts`) the run
  store mutates in place: select `consoleVersion` and read
  `console.lines()` (cached per change) or `console.since(id)`; `append`
  takes a whole batch in one store update.

## Undo for refactors (`core/store/refactors.ts`)

A rename (or a move: the same thing), a delete, a duplicate (`copyPath`) or
a copy into the project (`copyFromPackage`) changes several files, some never
open, and what's kept by path. Each runs as one **transaction**
(`transact(label, work)`; a call inside another joins it, as a rename's
`renameReferences` does, and the resource pack outline wraps a key's rename and its
references in one), recorded as it goes:

- **Files**: `touch(path)` before anything changes a file or folder reads what's
  there (text for the text extensions, bytes for the rest); when the
  transaction ends, everything under its roots is read again. Undo writes
  each file's "before", redo its "after", so a file no tab has open comes
  back exactly; a root empty on the other side is deleted whole (no empty
  folder left).
- **Moves** (`movePaths`): documents, tabs, views and disk texts, replayed
  backwards on undo.
- **Document steps**: a `commitModel` while a transaction runs (a reference
  rewritten in an open document) is tagged with it in the document's history
  (`Step.tx`): it's never undone alone, only with its transaction.
- **A shelf** (`shelvePaths`): what was open at paths that go (a delete; a
  copy, on undo): documents with their histories and unsaved edits, tabs
  where they were in the strip (and active if one was), views. Undo or redo
  puts it back.

**How it meets per-document undo.** Every step and every transaction takes a
tick on one clock (`nextSeq`). Ctrl/Cmd+Z in a document (`undo(path)`) takes
back its own last edit or the project's last refactor, **whichever came
later**; a step tagged with a transaction undoes the whole transaction. So a
refactor is one step in every document's undo, in time order: a rename just
made is undone from whatever tab is active, and an edit made after it comes
off first. Redo takes the one undone last (the older of the document's next
step and the project's next refactor). With no model document active (a
script, a map, no tab), Edit → Undo/Redo is the project's
(`undoRefactor`/`redoRefactor`). A new refactor ends the redo stack; 100 are
kept. Two refactors started at once would join one transaction: they're
user actions, awaited one at a time.

**When something changed underneath**, undo and redo check before touching
anything, and refuse with a notice:

| Found                                                                           | Then                                                                    |
| ------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| a file it changed differs from what it left (version control, an agent, a save) | refused; the transaction is forgotten (its steps become documents' own) |
| a file added inside a root it would take away                                   | the same                                                                |
| a document it stepped has later edits (undo)                                    | refused, kept: undo those there first                                   |
| that document's step is gone (redo after an edit)                               | refused, forgotten                                                      |
| an open document it would write over has unsaved edits                          | refused, kept: save or revert it first                                  |

Otherwise the workspace (histories, moves, saved texts, the shelf, disk texts)
changes in one `set` **before** the disk, so the watcher's echo of each write
already matches `savedText` and is ignored, as our saves are.

## External changes (`core/store/external.ts`)

The watcher reports every write, ours included. Each changed path that's
open is re-read and judged against `savedText` (a `rescan` event, when the
watcher lost events, counts every known file as changed):

| Disk now           | Clean doc                  | Dirty doc                                    |
| ------------------ | -------------------------- | -------------------------------------------- |
| equals `savedText` | ignore (our own save echo) | ignore                                       |
| different          | reload silently            | banner: keep mine / take theirs / diff       |
| gone               | close its tab              | keep it, marked deleted; saving recreates it |

"Keep mine" sets `savedText` to theirs, so the doc stays dirty and the next
save overwrites. A doc open as JSON only because it didn't parse leaves raw
mode when a reload makes it parse (`keepsRaw`). Renames move open docs,
tabs and views (`renamePath`), then follow the renamed thing in every file
that refers to it.

## Packages (dependencies)

A project's dependencies (`netherforge.json`'s `dependencies`, see
docs/format/packages.md) are read-only folders: beside it (`path`), or a git
package's checkout in the package cache (`git`). A package's **location**
says which: `../library`, or `git:<commit>`; every backend package command
takes it. The workspace's packages slice
(`core/store/packages.ts`): `refreshPackages` (on open, when the manifest or
the lock changes, on a rescan) asks format what resolving the tree needs
(`packagesNeeded`, one round at a time, since a package's own dependencies
are only known once it's read): git dependencies to fetch, each at the
commit the lock pins when it does (`packageFetchGit`, answered `{ commit }` or
`{ error }` by the request's `key`; `fetchingPackages` is set meanwhile), and
folders to read, each through the backend (`packageFiles`, `packageReadText`:
JSON as text, the rest null, as the project's own) and hashed (`packageHash`:
the backend's per-file SHA-256, format's listing, WebCrypto's SHA-256 in
`core/sha256.ts`). The result is the store's `packages` (`PackageInputs`:
`folders` by location, null for nothing there, and `git` by key), cached and
handed to every `loadProject`; the outline then has `packages` (by namespace:
location, origin, version, resources, exports) and `lock`.
`syncLock` keeps `netherforge.lock` what format says it should be (written when
it differs, deleted once the manifest names no dependency, never over a dirty
lock doc, never when a git package isn't what the lock pinned (format's lock
is null then); then hot-reloaded, which the plugin treats as a full reload,
and the packages refreshed, so a git dependency is asked for at its new pin).
`updatePackages` (the explorer's **Update from git** on a git package)
resolves the tree with the lock left out, so every `rev` is fetched afresh,
and writes the lock that makes. `describeOrigin` is a package's source in
words. `isProjectJson` counts `netherforge.lock` as JSON format reads.

A package's files are named by **package path**, `ns:path`
(`library:items/gem/item.json`): problems in a package and script errors in
it come that way. Each package the outline has is marked read-only as a
whole (`setReadOnly('library:', …)`; a mark ending in `:` covers its package
paths, `markPackages` after every validation), and `loadDoc` reads a package
path through `packageReadText`, so opening one (a problem, the explorer)
shows it in its kind's own view, read-only, as E1's read-only documents are.
The explorer's **Dependencies** (under the kinds,
`explorer/Dependencies.tsx`, its groups from the registry's explorer
folders) lists each package's resources; double-click opens one read-only,
and **Copy into project**: `copyFromPackage` copies the folder (`packageCopy`),
then rewrites each JSON document with format's `moveRefs` (the package's own
things become `ns:…`, the resource itself the copy), then validates and
hot-reloads.

**Templates (W3.2).** The minigame, shop and RPG mob templates are the
packages `examples/template_*`, bundled by `core/templates.ts`
(`import.meta.glob` of the example's files, without `tests/` and `.luarc.json`;
`TEMPLATES`, `templateLocation`). The packages slice's `addTemplate(id)` writes
one into the project as `templates/<namespace>/` (one transaction: undo removes
the folder), adds the `path` dependency to `netherforge.json` through the
manifest's model (`edit`, then `save`), and the usual refresh makes it a
package under Dependencies, where **Copy into project** works as for any. The
explorer's **Add template…** (`templateMenu` in `Dependencies.tsx`; one the
project uses is disabled) and **Create project**'s **Start with** select
(`Welcome.tsx`, which calls `addTemplate` once the project is open) offer
them. The memory backend serves a package folder inside the open project
(`folderOfOpenProject`), as the Rust side does by path. The templates' own
tests are the repository's (testing skill), not the editor's.

**A dependency's resources render wherever the project's do** (L5): a menu
slot, recipe or item icon naming `library:gem`, an item picker, a glyph in
text. The pieces: the resource packs store builds every package's resource pack beside the
project's, keyed `ns:id` as format keys them (`resourcePackTexts` reads them from
`packages`; format's `compileResourcePacks` numbers glyphs across all, the
project's first); `resourcePackTexturePath('library:gems', …)` is a package path, and
`fileUrl` (and so `useProjectFileUrl`) serves one from the package's folder
(`Backend.packageFileUrl`, `nfproject://…/:package/<location>/<path>`);
`useProjectItem("library:gem")` reads the package's item file as it was read
(`packageText`) with every reference written in full (format's
`qualifyRefs`), so its look resolves beside the project's; `splitResourcePackRef`
answers a package's resource pack as `ns:id`; `useNameableIds(kind)` lists what a
project file may name of a kind: the project's ids and what its direct
dependencies (`directDependencies`) export (`exportedIds`, as `ns:id`);
`useProjectItemIds` is its items'. The
memory backend serves `examples/library` at `/memory/library`
beside `/memory/basic`; tests take both from `exampleProjects()` and
`examplePackages` (`testing/fixtures.ts`).

## References: rename, usages, delete

The editor never walks a model for references itself: format's walker does
(the project-format skill). `renameReferences(target, to)` runs format's
`renameRefs` over every project JSON file, for a `RefTarget`: `renamePath`
calls it with a `file` target for a file or folder inside a resource (a
script, textures, sounds) and a `resource` target for a resource's new id (an
item, a dialog, a resource pack, which takes every reference into it along); the resource pack
outline calls it with a `resource_pack_entry` target when a skin's or glyph's key is
renamed. An open model document gets the edit (a step of the rename's
transaction, see "Undo for refactors"), saved if it was clean; a closed file is rewritten from disk, canonical; unsaved raw text
is left alone, for validation to point at. `usagesOf(target)` is format's
`findUsages` over the project as it is now; deleting a resource from the
explorer names the files that refer to it. Resource pack references in previews
(`splitResourcePackRef`, `useSkin`, item icons, glyphs) resolve through format's
`resolveReference` in the namespace of the document on show
(`useReferenceNamespace`: a package's document's is its package's, through
`DocumentNamespace`, which the editor area sets around its view; the
project's otherwise, `useHomeNamespace`).

## The viewport gets transforms from format

Never compose transforms in TS. `usePose` (`editors/centity/pose.ts`) poses
the document with `centityPoser` (the server's own `Composer`, compiled once
per model) and every node is a
`<group matrixAutoUpdate={false} matrix={world}>`. A model with errors shows
the latest model in its undo history that composes, at the same clip and
time, with a note (nothing kept by path: it's the document's own history). The one inverse is the gizmo
(the shared `Gizmo` hands the centity a dragged stand-in; `editors/centity/gizmo.ts`): `local = parentWorld⁻¹ · world`, decomposed as XYZ Euler
degrees, which matches `Matrix4.fromTrs` (`T·Rx·Ry·Rz·S`); its test checks
that against `poseCentity`. While a clip is previewed and the node's channel
has a track, a drag keys that channel at the playhead instead of editing the
base transform.

## Viewports (`editors/shared/viewport/`)

**The structure screen's generation section** (`editors/structure/Generation`):
a structure's `structures/<id>.json` (format's `StructureGeneration`, the
companion document in `KINDS.structure.companion`) isn't a tab of its own, so
the section loads it with `loadDoc`, edits it with `edit` and saves each edit at
once; the switch writes a canonical new file (`canonicalizeModel`) or deletes it.
Pools are listed and edited in the file. `isProjectJson` lets a binary folder's
format-owned documents through, and rename/duplicate/delete of a structure carry
the companion (`ExplorerFolder.companion`, `resourceActions`).

Every 3D view (a centity, a particle effect, a cutscene, a structure, a map)
is a `Viewport3D`: the editor gives it its document's
`path` and kind, a `scene` (its content, its `home` camera, grid, lens,
background, `onMissed`) and lays its toolbar and notes over it
(`ViewportToolbar`, `ViewportNote`). The shared parts are the same
everywhere: the background, an ambient light, the grid (`BLOCK_GRID`, or
the scene's), orbit controls with damping, the `Gizmo` (a stand-in at a
world matrix, one undo gesture per drag, the release not read as a click
into nothing), block and item models (`minecraft/client/models.tsx`) and
**Copy picture** (the scene drawn and read in one go, to the clipboard).

- **One canvas.** The workbench mounts `ViewportHost` around the editor
  area: it keeps the scenes (`scenes.ts`, a store with no three.js in it)
  and renders the app's one `<Canvas>` (`ViewportCanvas`, loaded with the
  first scene) through a portal into an element of its own, which it moves
  into whichever viewport is on screen (`attach`). React never sees the
  canvas move, so it isn't remounted; it sits inside the viewport like any
  element, under its toolbar and notes, and `[data-viewport] canvas` finds
  it. Each document's scene is an r3f `createPortal` with its own scene,
  camera, raycaster and pointer; only the active one is drawn (a
  priority-1 `useFrame`) and hit (`setEvents({ enabled })`), and its orbit
  controls alone listen. With nothing on screen the frame loop stops.
- **Why not a canvas per viewport**: a browser keeps a handful of WebGL
  contexts (WebKit and Chromium drop the oldest past 16), and a context
  made again on every tab switch uploads every texture and compiles every
  shader again. drei's `View` (one full-window canvas scissored under DOM
  elements) needs every element over a viewport to be transparent and puts
  the canvas over or under the toolbars; and keeping inactive editors
  mounted (paused with `frameloop="demand"`) still holds a context each and
  every editor's inspector portal at once. Moving one canvas has neither
  problem.
- **Inactive tabs keep their scene.** `Viewport3D` hands the store its scene
  on every render (`show`); when its tab is hidden the editor unmounts but
  the scene stays, with the last content it was given, undrawn and deaf,
  until no tab has its path (`retain`, following the tabs). So whatever a
  scene builds is built in the scene, keyed by what it's built from: the
  structure's mesh (by the file's stamp) and the map's
  `TerrainClient` (the map follows the orbit point through the scene's
  `controls`) survive a tab switch; a scene reports what the DOM shows
  (faces drawn, chunks loaded) through a callback it calls again when the
  tab comes back. Scene content may use hooks and the app's stores (r3f
  bridges React context), and must ask `useScene().active` before
  listening to the pointer itself (`Gizmo` does). A scene that throws shows
  "The preview failed" and is tried again when its viewport comes back.
- **The camera is view state.** Each 3D kind's view has `camera:
CameraPose | null` (null is the scene's `home`; `structure` and
  `map` register views for it, the map with its `radius`). The
  slot puts the view's camera on the camera when it differs from what it
  last showed, writes where the user leaves it when a drag ends and again
  once damping settles (`SETTLE_MS`), and drops leftover momentum when it
  places one. A rename moves the view, so the camera follows; Spawn sets
  `camera: null`.

## Timelines (`editors/shared/timeline/`)

`Timeline` is the keyframe strip under a preview, for any editor and
either time model (`time.ts`: `seconds` for a centity's clips and a
cutscene's camera, `ticks` for a particle effect; snapping, the ruler's
marks, a tick's step and how a time is written): a toolbar (the editor's
`leading` controls, play/pause and its time, the editor's `toolbar`), a row
per track (`TimelineTrack`: keys, a `band` drawn under them such as an
emitter's window and bursts, `nested`, `onSelect`, `onAddKey` on
double-click), the playhead across them and the editor's key fields
beside them. The editor owns the keys: the timeline reports `onMoveKey`
(which returns the key's index after the re-sort, so a drag keeps hold of
it), `onDeleteKey`, `onSelectKey`, and wraps each drag in `gesture`.

- **Keyboard.** The playhead is react-aria's `Slider` (its track is the
  ruler: press or drag on it, arrows a tick, Home/End). Each key is a
  `role="slider"` moved by react-aria's `useMove`, so pointer and keyboard
  share one path: Left/Right a tick, Shift ten, Delete removes it (not the
  editor's Delete), focus (Tab or a press) selects it; focus follows a key
  the arrows moved past another. A key's shape says its easing (diamond
  linear, square step, circle the others; `data-easing`). Pressing on a
  row seeks there, so a double-click keys a curve where it was clicked.
- **Keys** (`keys.ts`): `putKey` (in time order; a key at a time that has
  one replaces it), `moveKey`, `removeKey`, `setEasing` (linear by leaving
  it out), on any `{ time, easing? }`; `centity/ops.ts` and
  `particles/ops.ts` apply them to their own tracks.
- **Playback** (`usePlayback`): every animation frame while playing, the
  editor gets where the playhead was when playing began and the seconds
  since, and applies its own rules (a clip loops, holds or stops; the
  particle preview counts whole ticks at 20 a second).

## Keyed lists and galleries

- **`KeyedList`** is an outline pane of entries kept by name (a centity's
  animations, a particle effect's emitters): add, select, inline rename
  checked against the name rule and the names taken (`nameProblem`),
  duplicate, delete, one context menu, an optional `lead` row that isn't an
  entry (the effect's own), and each row's `data-path` (`emitters.ring`).
- **`Gallery`/`GallerySection`**: a gallery split into sections, each a
  header with its count and actions over react-aria's `GridList` in a grid
  layout (one tab stop, arrows between cards, Space or a click picks; a
  card is a `row` with `aria-selected`).

## Block models from the cache

The import (Rust) copies the jar's `assets/minecraft/{blockstates,models,items,textures,font,particles}`
into the per-user cache; `backend.assetUrl(version, 'assets/minecraft/…')` serves them.
`minecraft/client/assets.ts` fetches and caches JSON per version; `minecraft/client/model.ts` resolves
block state → blockstate file (best variant match, or every matching
multipart part; game-data defaults fill unspecified properties) → model
parent chain (elements inherited, textures merged, `#vars` resolved);
`minecraft/client/geometry.ts` bakes elements (UVs, face rotation, element rotation +
rescale, blockstate x/y about the centre, per-face shade) grouped by
texture; `minecraft/client/render.ts` caches the bakes and loads textures nearest-filtered
(first frame of animated strips, magenta chequer if missing); `minecraft/client/models.tsx`
draws them in a scene (`BlockModel`, `ItemModel`). No client
assets → placeholder cubes, never an error. Items: block-like models render
as their elements, generated models as a flat quad; text displays are a
canvas texture laid out by format's `layoutText` (the game's wrapping and
widths, so it fills exactly what Fit measures), coloured per run by
`styledChars`; only the font itself is the browser's.

**Fit to display** (`editors/centity/fit.ts`) uses the same resolution, then
`minecraft/client/boxes.ts` turns elements into block-unit boxes (rotations applied,
flat elements one pixel thick, contained boxes dropped), writes `boxes` and
`fittedTo` = `fitKey(display)`, which is format's own
`CentityValidator.fitKey` (canonical block state) through the JS exports. Model boxes from the game-data cache win when present.

## Adding an inspector field

1. The key exists in `format` (and `docs/format/centity.md`) first; the
   generated type in `@netherforge/format/types` gives you its shape. An enum
   field comes with its values (`BillboardValues`) for a select's options,
   and a default or limit belongs in format's generated
   `@netherforge/format/constants` (`codegen/Constants.kt`), never a literal
   here. `core/format.ts` re-exports both.
2. Add the control in its section in `editors/centity/Inspector.tsx` with
   `dataPath` = its JSON path inside the node (`physics.maxSpin`), so a
   problem at `$.nodes.<n>.physics.maxSpin` focuses it.
3. Write through `editNode(target => setKey(target, 'maxSpin', value))`
   (`core/draft.ts`, the one helper every editor uses): `undefined` (or `''`) deletes the key. Pass `onGestureStart/End` if it can scrub.
4. If it changes how something looks, update the viewport too.
5. If its edit is non-trivial (a rename that must follow references), put it
   in `editors/centity/ops.ts` with a unit test.

A centity's `spawning` block is edited in the inspector's **Natural spawning**
section (`SpawningSection` in `editors/centity/Inspector.tsx`), shown with the
Centity section when no node is selected (it belongs to the centity, not a
node). The section's switch adds or removes the block; lists are typed text
(`parseList`: commas, spaces or lines), ranges are two number fields, and
every change is a `spawning.ts` function through `workspace.edit`
(`spawning.test.ts`, `Inspector.test.tsx`), leaving a cleared key out and a
range with neither end away. Problems at `$.spawning...` select no node and
focus the field by its `centity.spawning.<key>` data path.

## The resource editors (menus, dialogs, resource packs)

All three are model documents through the same store, so undo, gestures,
save → canonicalize → write → hot reload, validation and external changes
are the generic ones. What's specific:

- **Selection** is the document's view (`usePrimary(kind, path)`), typed
  per kind: a slot index (`13`), `{ list: 'buttons', index: 1 }`,
  `{ kind: 'skins', key: 'shop' }` / `{ kind: 'textures', key: 'gui/shop.png' }`.
  An outline's row ids are the kind's `key` of each item.
  `useFocusRequests(path, route)` turns a problem's JSON path into
  that selection and focuses the field whose `data-path` matches
  (`slots["13"].item.count`, `buttons[1].key`, `skins.shop.ascent`;
  `ui/focus.ts#fieldPath` writes paths the way format does).
- **Menu**: `WindowFrame` draws the vanilla window from `window.ts`
  (the game's container sizes and slot positions, which are layout, not
  data), the skin where the server draws it, then the slot buttons. Drag a
  slot onto another: `moveSlot` swaps them whole, one
  `edit()`, so one undo step. Slots past a shrunk window are kept (format
  flags them) and listed.
- **Skins** are placed with `compileResourcePacks` numbers only: the title starts at
  the window's title position (centred for dispenser/dropper/crafter, on the
  words alone), the picture is drawn `offset` from it, `ascent` above a
  baseline 7 below the title's top, `height` tall, width by aspect. The
  prefix is zero wide (format's `titlePrefix` moves back by the skin's
  advance), so the words start at the title position, over the art.
  `placeTitle` assumes that even before the picture is measured. On a
  centred type the server moves the skin back by the words' width when it
  can measure them (no `font.needed` problem for the menu: `MenuWindow`
  passes `measured`), so the preview draws it where a left-aligned title
  would; otherwise the art moves with the words, and `WindowFrame` warns.
  Never invent a second layout.
- **Resource pack pictures' pixels**: the app's resource packs store (`core/store/resourcePacks.ts`, one
  per app, worked out once per change however many components show resource packs)
  loads every skin/glyph texture (`loadImageInfo`, cached per URL), scans
  alpha (`opaqueWidth`), and passes the facts to `compileResourcePacks`, which owns
  the advance rule (`PackFonts.bitmapAdvance`). Don't compute advances in TS:
  an unmeasured glyph is left out and format decides.
- **Dialogs**: `arrangeButtons` decides footer vs columns per type;
  message text is wrapped with format's `layoutText` (the import's glyph
  advances) and drawn line by line from its source ranges, so breaks match
  the game. Widgets are drawn at the game's sizes in the editor's style.
- **Resource packs**: a `Gallery` with a section per kind (skins, glyphs, item models,
  tooltips, equipment looks: `ENTRY_KINDS`, each kind's texture fields from
  format's `RESOURCE_PACK_TEXTURE_FIELDS`, so a kind with several layers shows a
  picker per layer) plus every PNG under `textures/` and every sound. Import is
  an `<input type=file>` → bytes → `workspace.writeBinary` (works in Tauri
  and the memory backend alike). Pictures load through
  `backend.projectFileUrl(path, fileStamps[path])`; the store bumps
  `fileStamps` on our writes and on watcher events, so images reload.
  The resource packs store reads each pack.json from the open document first (unsaved
  edits show everywhere at once), else disk; `useResourcePacks.ts` holds its
  selectors (`useCompiledResourcePacks`, `useResourcePackFiles`, `useGlyphMap`). Sounds are a
  gallery of their own: every `.ogg` under `sounds/` (`soundFilesOf`) plus
  `pack.json`'s `sounds` entries (`soundEvents`), picked as `sounds:<key>`,
  with an `<audio>` per file and volume/pitch/subtitle fields that drop an
  entry once it's empty (`setSoundValue`); "Import OGG…" checks the `OggS`
  header.
- **Items** (`minecraft/item/`): one `ItemEditor` for slots and dialog bodies;
  `onEdit(recipe)` applies to the item inside whatever owns it. `ItemIcon`:
  a resource pack `itemModel` draws its `guiTexture ?? texture` (project file), or for
  one drawn as a resource pack block (`block`) the look's faces (`lookOf` in
  `minecraft/block/look.ts`, shared with the block editor) on vanilla's
  `block/cube` (`blockLookPicture`); else
  the client's sprite layers, or its 3D model rendered once per item by
  `minecraft/client/iconRender.ts` with the model's `gui` display transform; else a
  labelled placeholder. A reference to nothing falls back to vanilla, as
  the client does. A stack naming a project item (`{ item: "ruby" }`) is
  drawn as `stackLook(stack, definition)`: the definition fills what the
  stack leaves out, the kind is always the definition's (format's
  `ProjectItems.resolve` rule). `minecraft/item/projectItems.ts` reads project items
  and recipes as they are now (open document first, then disk; parsed once
  per text) for icons and "which recipes use this item". `ItemEditor`'s
  `look` hides the stack's own fields (format's `ITEM_STACK_FIELDS`), and
  `projectItems` offers the "Project item" select (`setProjectItem` drops the
  kind, which the definition owns).
- **Project items** (`minecraft/item/ItemScreen.tsx`, `items/<id>/item.json`): the
  shared form as `look`, a 128 px `ItemIcon` and tooltip, the item's one
  script (`newScript('item')`), and the recipes that make or take it.
- **Recipes** (`editors/recipe/`, `recipes/<id>.json`, a file kind): a station per
  type in the game's window style. A shaped recipe is edited as its grid:
  `ops.ts#shapedOf` derives `pattern` and `key` canonically (empty rows and
  columns trimmed, as the game reads a pattern; one letter per distinct
  ingredient; letters already in the key kept; a new one takes its name's
  first free letter). Where the trimmed pattern shows on the grid is view
  state, the `GridOffset` kept in the document's view (`RecipeView.offset`), so a shape
  stays where the user put it. Which fields, categories, group and default
  cooking time each type takes come from format's `RECIPE_TYPES`.
  `IngredientPicker` has three tabs: Minecraft items (`usePickerIds`), a
  typed tag (the game data has no tags; format validates it), project items.
  Problems show on the slot (`data-path` `key.R`, `ingredients[2]`,
  `template`), in a list under the station, and under inspector fields.
- **Text** (`minecraft/text/`): there's no MiniMessage parsing in
  TypeScript. `styledChars` is format's `styleText` (its `MiniMessagePass`,
  the same pass `TextMetrics` measures, cached by text) with the preview's
  base style under it, so a character index from `layoutText` is the same
  character here; every `<…>` draws nothing except `<glyph:ui/coin>`, one
  `StyledChar` with `glyph` set (a run of its own), drawn as the picture.
  A colour or decoration a preview gets wrong is fixed in format's pass.
  `useGlyphMap` keys glyphs by character and by both ways a tag can name
  them (`ui/coin`, `<namespace>:ui/coin`);
  `glyphAdvancesOf(glyphs)` is what `layoutText`/`fitTextHitbox` take to
  measure tags. There are no `<glyph:` completions in text fields yet.

- **Loot tables** (`editors/loot/`, `loot/<id>.json`, a file kind): pools in
  the outline (`KeyedList`, ids), the selected pool's entries as cards (the
  item's `ItemIcon` and tooltip, weight and its share of a pick from
  `ops.shares`, count, conditions; move and remove), add buttons per entry
  type, and the inspector for the pool (rolls, bonus rolls, conditions) and
  the selected entry (the shared `ItemEditor` with `look`, since an entry's
  count is its own; the table it rolls; count, weight, quality, conditions,
  a tool through the recipe editor's `IngredientPicker`). Selection is a
  `LootPick` (`{ pool, entry? }`) in the view, beside the preview's `seed`.
  "Roll" is format's own roller through `rollLoot`, over every loot table's
  current text, the packages' as read, keyed by full name (`basic:treasure`,
  `library:gems`): format reads each in its own namespace, as the server
  does, so drops name items in full. No rolling rules in TypeScript. A table
  entry offers the project's tables and its dependencies' exported ones
  (`useNameableIds('loot_table')`); a package's table opens read-only in its
  own namespace (`idOf` takes the id from the package path).
- **Advancements** (`editors/advancement/`, `advancements/<id>.json`, a file
  kind): the display as a preview card (frame, `ItemIcon`, MiniMessage title),
  criteria as cards, and requirements summarised; criteria in the outline
  (`KeyedList`, ids), selection is the criterion name. The inspector edits the
  advancement (parent from `useNameableIds('advancement')`, experience), the
  display (an enable toggle; icon from a game item or a project item, never
  both), requirements (custom groups as comma-separated names) and the selected
  criterion (trigger from the game data's trigger registry, conditions as JSON
  the game reads, committed only when they parse as an object). A reload that
  says `restart` makes the run store `restart()` the dev server (providers'
  `reloaded` hook; the MCP `reload` tool does the same); the memory backend
  says it after `testReloadRestarts()`.
- **Migrations** (`migrations/NNN_name.sql`): a file kind with
  `contents: 'sql'`, like a module's Lua it has no view in `KIND_CONTRIBUTIONS`
  (the type allows `lua | sql` without one) and no kind tab; it opens as
  its file in the `sql` file tab (`SqlEditor`, highlighted by Monaco's own SQL
  tokenizer, registered in `monaco.ts` beside Lua's; no SQL language service
  is loaded), which `tabTypeFor` picks for any `.sql`.
- **Particle effects** (`editors/particles/`): the preview never invents points:
  `particleEffectSampler` (format's `EffectSampler`, seed 1) says what each
  tick spawns, and `simulate.ts` only approximates what the client does after
  (lifetime, drag, fade). `Simulator.at(tick)` steps forwards and replays from
  tick 0 on a seek, so scrubbing is deterministic. A curve replaces its
  constant in every op (except `rate`, which marks the emitter rate-driven);
  a new key is seeded with `particleCurveAt`, the sampler's own reading.
  "Play on server"/"Stop" (`PlayOnServer.tsx`) are enabled while the bridge
  is connected: Play saves a dirty document first (its reload reaches the
  plugin before the play, one connection in order), then sends
  `play_particle_effect` with the timeline's loop toggle through
  `run.playParticleEffect`; Stop sends `stop_particle_effects`.

- **Cutscenes** (`editors/cutscene/`): the camera path of `cutscenes/<id>.json`
  on the two shared pieces. The viewport (`CutsceneViewport`, a `Viewport3D`)
  draws format's own `CameraPath` through `cutsceneDirector(id, text)` (JS
  export of `CutsceneDirector`: `shot(time)` the camera at the playhead and
  `trail(count)` the path, both a `CutsceneResult`, `failed` with the problems
  when the file doesn't compile): the path as a line, a marker per position key
  (a click selects it), the camera as a frustum at the playhead, framed from the
  path's bounds until the user moves the camera. The timeline (`CutsceneTimeline`,
  the shared `Timeline` in seconds, as a centity's clip) has a row each for
  `position`, `rotation` and `cues`, the keys' fields beside it (`editor` slot:
  time, value or yaw and pitch, easing, a cue's event, text and duration, each
  with a `data-path` like `camera.position[2].value`, so a problem focuses it),
  and **Key position**, **Key rotation** and **Add cue** at the playhead (a
  key is seeded with `shot`, the camera as it is now). The view (`view.ts`) is
  the playhead, whether it plays (`usePlayback`, stopping at the end) and the
  camera; the selection is a `{ track, index }` key (none: the inspector shows the
  cutscene's `length` and `skippable`). `ops.ts` has the pure edits (on the shared
  `keys` helpers, so ordering and easing are the centity timeline's rules) and
  `lookDirection`, the frustum's aim (yaw 0 faces +Z, 90 faces -X, positive pitch
  looks down). Tests: `ops.test.ts` (ops and the director), `e2e/cutscene.spec.ts`.
  There's no "play on the server" button: a cutscene needs a player, which a
  script (or a bot's command) provides.

- **Terrains** (`editors/terrain/`): `terrain/<id>.json`, drawn by the server's own generator. The stage
  has two canvases made from format's `terrainPreviewer(id, text, structures)` (JS export of `TerrainPreviewer`:
  `map(...)` and `slice(...)`, each a `TerrainPreviewResult`, `failed` with the problems when the file doesn't
  validate for its shapes; both take the world's `minY`/`maxY`: the heights the editor bar's **Preview heights** picker chose, `heights.ts`'s `heightChoices` (the overworld's, then each `netherforge.json` world naming this generator and a project dimension type, read from the manifest and `dimension_types/<id>.json` as they are now; references resolved by format's `resolveReference`), the view's `heights` (a world's name, `''` the overworld, null the first world, else the overworld)): a **map from above** (96 cells, 2 to 32 blocks each, heights lit from the north-west, the sea blue,
  biome areas in `areaColor`s that avoid blue) and a **slice** through whole generated chunks along a line (128
  blocks; run-length columns; air transparent, the sea translucent, the project's own blocks pink so ore of a custom
  block stands out, stone grey, other blocks a colour by palette place: the editor ships no pictures, so a legend
  names each). Where surface and sea-floor decorations start is marked on the map in `decorationColor`s with a
  legend (`Decorations`; only on a close map, `decorationsShown`). Dragging the map pans it, a click cuts the slice
  there (`alongX`/`at`), the pointer's cell says its ground, biome and decorations in a readout
  (`terrain-map-readout`; the slice's likewise). **The preview runs in a worker** (`worker/`): `core.ts`
  (`createPreviewCore`: the previewer compiled once per file text and structures, answering a `PreviewRequest` with
  map and slice), `preview.worker.ts` (Comlink), `client.ts` (`terrainPreviewWorker()`, and `LatestPreview`, which
  draws one request at a time and keeps only the newest waiting, so a drag never queues work; a failed worker is
  started over; `useTerrainPreview(request)` owns one per editor). The structures a file's decorations name (the
  project's own; a package's isn't drawn) are read from their `.nbt` (`readProjectBytes`, `readNbt`,
  `parseStructure`, keyed by their stamps) and sent with the request as `TerrainStructureInput`s. The seed (a number, "Random
  seed"), the map's centre and cell size and the slice's line are the view (`view.ts`: not in the file, not undone;
  there's no selection). The inspector (`Inspector.tsx`) has a section per part in the order a column is made
  (Terrain with its named noises and blend, and its 3D ground (`DensityFields`: the `densityNoises` collection,
  `NoiseEntries` with a squash field, the islands with their own `AreaFilter`; `setDensity` off takes the areas'
  densities too), Ground's layers and stone, Floor, Caves, Ores, Decorations, Biomes with
  each area's own layers and terrain (its own noises: `NoiseEntries` with the `{ area }` collection, its own 3D
  noises with `{ area, density: true }`), the climate
  noises and the borders' jitter, Structures). Everything that names a block is a `BlockPicker` (the game's, the
  project's, or for a decoration a structure; `setBlock` keeps one), and an ore, a cave or a decoration ticks the
  areas it keeps to (`AreaFilter`, `setInArea`; renaming or deleting an area follows into every filter),
  every field with a `data-path` that `fieldPath` makes from the JSON path, so a problem focuses it; named entries
  are added with a name prompt (`nameProblem`, `ID_NAMES`), renamed in place and deleted. `ops.ts` is the pure
  edits (`addEntry`/`renameEntry`/`deleteEntry` by collection, layer lists for the file or one biome area, a
  block named one way, the stone, area filters), the pure pictures are `packages/terrain-preview` (`draw.ts`: `mapPixels`, `slicePixels`, `sliceColors`
  and the colours; `previewer.ts`: the typed `terrainPreviewer`, which `core/format.ts` re-exports; `structures.ts`:
  `structuresNamed`, `structureInput`), shared with `netherforge preview` (apps/cli), which draws exactly what this
  does. NBT reading (`nbt.ts`, `structure.ts`) lives there too, for the same reason: the editor imports
  `@netherforge/terrain-preview/nbt` and `/structure`. Tests: `ops.test.ts`,
  `packages/terrain-preview/src/draw.test.ts` (against the real JS build), `worker/core.test.ts` (a structure linked from its input, other
  heights), `worker/client.test.ts` (newest request wins, a failed worker restarts), `e2e/terrain.spec.ts` (the
  preview following a base height and a seed, the example's decorations marked (its tree read from the seed's
  `.nbt`), an ore of a project block added, a decoration added and kept to an area that's then renamed, a new
  generator, a mistake naming why nothing is drawn). The memory backend's seed bundles `.nbt` files as bytes.
  **A file's script** (W5.6) runs in the same worker: `core.ts` sees a `script` (`hasScript`) and first awaits the
  Lua (`createPreviewCore(loadLua)`, default `loadBrowserLua` from `@netherforge/terrain-preview/lua.browser`:
  wasmoon's `glue.wasm` as a `?url` asset of the bundle, loaded once; the CSP's `'wasm-unsafe-eval'` is what lets a
  page compile WebAssembly, and only that). The request carries `sources` (`terrain/<id>.lua` and every module's
  Lua, `scriptPaths`) with a `sourcesKey`: `useScriptSources` in `TerrainEditor` reads an open document's text as it
  is (so typing in the script redraws) and the rest from the backend by stamp. The previewer is kept while file,
  structures and sources are unchanged (its Lua state with it) and `close()`d when replaced. What the script failed
  at (`TerrainMap/TerrainSlice.scriptErrors`: stage, message, file, line) shows over the pictures (`ScriptErrors`,
  an alert named "The script failed", each a link opening the file at its line); the pictures are then the file's own
  result there, as on the server. The inspector's **Script** section (`ScriptSection`) turns `script` on (an empty
  object; `terrain/<id>.lua` made from format's `newTerrainScript()` when it isn't there) or off (`setScripted`),
  opens the script, and edits its budget, its blocks (`setScriptBlocks`) and its noises (the `scriptNoises`
  collection of `ops.ts`). The script's API stubs are `.netherforge/luals/terrain.lua` (`apiStubs.ts`, beside
  `nf.lua`): classes on locals, no `nf`. It runs for an untrusted project too: it's Lua in WebAssembly with only the
  generator's host functions and a budget (tauri-backend's threat model). Tests: `worker/core.node.test.ts` (Node:
  jsdom looks like a browser and Node at once to wasmoon, which then finds its WebAssembly as neither, so a test with
  Lua loads Node's, `loadNodeLua`), `e2e/terrain.spec.ts`'s script flow (the template made, a module required, a
  failure said and opened).

## Map previews

`map/MapView` draws a map's overworld around its spawn, a disc
of chunks whose radius the user picks (`MAX_RADIUS`), and follows the
OrbitControls target: when it enters another chunk, the view moves.

- **Where it runs.** Everything per block (region sector table, chunk
  decompression including LZ4 and `.mcc` files, NBT, palette unpacking,
  meshing with `minecraft/client/blockMesh.ts`) is `TerrainCore` in a web worker
  (`terrain.worker.ts`). The main thread only answers its two questions
  over messages: a region file's bytes (`readProjectBytes`, so path-scoped
  `nfproject://` like every binary resource; no Rust command) and block
  states baked with the client's models (`minecraft/client/bake.ts`, remapped into the
  `minecraft/client/atlas.ts` atlas, since a chunk of 25 textures would otherwise be 25
  draw calls). `TerrainCore` takes its I/O as functions, so vitest drives
  it, and `TerrainClient` over an in-memory channel, without a worker.
- **Memory follows the view.** The worker keeps decoded blocks for the
  drawn disc plus a ring of one (border faces are culled against the
  neighbour chunk's blocks, so a seam between two solid chunks has no faces)
  and region files only while a kept chunk is in them; everything else goes
  when the view moves. `TerrainClient` keeps meshes `KEEP_MARGIN` chunks
  past the radius and reports drops, so the worker re-sends only what the
  main thread no longer has. Real terrain is about 4000 faces a chunk
  (~100 bytes each): radius 6 is ~550k faces, 10 about 1.4M.
- **What it doesn't draw**: chunks whose `Status` isn't full (the game
  doesn't either), fluids (no block model), block entities' special
  renderers, biome tints (plains colours, as everywhere in the editor), and
  glass doesn't cull against glass.
- A missing chunk, region file or `.mcc` is "nothing there"; an unreadable
  chunk is reported (`Unreadable chunks`) and drawn as nothing, so the faces
  toward it show. A map with no `.mca` files says so instead of a view.

## Glyph advances and text measuring

The import computes the default font's advances (Rust, see tauri-backend);
`refreshGameData` fetches them with `backend.glyphAdvances(version)` into
`glyphAdvances`, kept apart from `gameData` (the server's export, which is
all validation reads: `GameData` holds server facts only). Text measuring
(`layoutText`, `fitTextHitbox`) takes the advances alone; `core/format.ts` caches
their JSON per table, since a call per render would otherwise re-serialise
it. Without an import, format estimates unknown glyphs (6 px, CJK 9), so Fit
for a `fixed` text display still works and says it's approximate.

The same advances are written into the project as `fonts/default.json` for
the server (`minecraft/text/defaultFont.ts`; see the game-data skill): after every
`refreshGameData` and whenever that file changes on disk, the workspace
writes format's canonical text if it differs, never over unsaved edits to it.
A failed write is an `editor.default-font` problem until one succeeds.

## Updates

`core/store/updates.ts`: `app/main.tsx` checks quietly at start (errors stay silent:
offline, a dev build, no signed release yet), Settings → Updates checks
loudly. `UpdateBanner`/`useInstallUpdate` always ask first, then
`install(stopServer)` stops the dev server and calls
`backend.installUpdate`, which relaunches. `MemoryBackend` reports none
unless built with `update` (`?update=1.2.3` in the browser).

## Adding a resource editor

Done for centities, menus, dialogs, particle effects, project items, recipes,
blocks (`editors/block/`: a form and a CSS cube drawn from `ResourcePackPreview.blocks`'s faces,
with pure `ops.ts`), biomes (`editors/biome/`: a CSS landscape painted with its colours, a hatch where
the file leaves one to the world or the climate; `ColorField`s, sections that turn a sound or the
particle on, spawns by category, spawn costs, and each step's ordered features with move buttons; ids
suggested from the game data's registries through `Datalist`s, none until it's exported; new entries start
empty rather than with a game id, so the editor names none; `biomeChoices` is what a biome field
suggests elsewhere, the project's ids before the game's, as the terrain inspector does; the project's ids there are
`ProjectOutline.registryNames` by registry folder, `BIOME_FOLDER`/`PLACED_FEATURE_FOLDER`, so a datapack's biomes and
placed features are suggested too), datapacks (`editors/datapack/`: no editing UI, only `pack.mcmeta`'s formats in words
and the pack's files grouped by `data/` and each overlay with the formats it's for, pure `ops.ts`; a file opens in its
own JSON tab, `pack.mcmeta` by Edit as JSON; problems show as anywhere), dimension types (`editors/dimension_type/`: a form of build limits, light and sky, and rules, the stage saying the world's block range; pure `ops.ts`, defaults from the generated `DIMENSION_TYPE_DEFAULTS`) and resource packs (whose gallery has a `blocks` section: `ENTRY_KINDS`); the same steps each time. Which kinds there are, where they live
and what format makes of them is format's kind table, `KINDS` in the
generated constants (`KindId`; `EditedKind` is every kind whose resources are
JSON, `TemplateKindId`, `ScriptedKindId` and `BinaryKindId` the others the
table derives), and which resource a path is is format's `classify`
(`core/format.ts`, cached per path). Never list kinds or match folder names
here: `core/paths.ts` turns the table into `resourceOf` (kind, id, role, rest
for any path), `locationOf` (what rename and delete move: the folder, or the
file), `mainFileOf`, `opensAs`, `resourceIdsOf` and `isFileKind`.

1. `format` gains the kind: one `KindSpec` in its registry, with its template
   (see project-format; rebuild with `node tools/gradle.mjs :format:jsLibrary`).
   `KINDS`, `classify`, `newResourceFiles(kind, id)`, `newScript(kind, …)`,
   the outline's `resources[kind]` and `tabTypeFor` follow; nothing to add in
   `core/`. Re-export the model's generated types in `core/format.ts`;
   `core/schemas.test.ts` lists its schema.
2. References to it, and from it, are format's `@Ref` fields: renames,
   usages and the delete prompt follow them with nothing to add here (see
   "References" above).
3. `editors/registry.tsx`: its entry in `KIND_CONTRIBUTIONS` (keyed by
   `KindId`, so a kind without one is a type error: title, one, icon, tone,
   the id prompt's message, the lazy view and, as it needs them, its outline,
   thumbnail, view state (its `view.ts`) and commands), placed where it goes
   in the explorer. That's the whole registration: the explorer, the tab and
   its icon, the editor area, the outline, the palette, the menus and the
   toolbar all read it. Never list kinds anywhere else (the registry test
   fails).
4. The editor: an `EditorScreen` with an `EditorBar` and its canvas (a 3D
   one is a `Viewport3D`, with `camera` in its view; a keyframed one has a
   `Timeline`), its inspector in `InspectorPanel`; `useModelDoc<T>(path)` (model and `edit`;
   `RawDocView` when the model is null) and `EditAsJson` in its bar
   (`editors/shared/modelDoc.tsx`), `edit()` with pure ops,
   `useFocusRequests`, fields from `ui/fields.tsx` with `data-path`s and
   `aria-label`s, and one `ScriptField` for the resource's script (a
   resource has at most one; nodes, slots and buttons have none). New
   scripts come from format's `newScript(kind, id)`, whose first line,
   `local this = this --[[@as Centity]]`, tells lua-language-server what
   `this` is.
5. Tests: pure ops in vitest (and a canonical round trip through
   `canonicalizeModel`); the store's create/rename for the kind; one
   Playwright flow for what only the UI can show.

## Testing

- **Vitest** (`pnpm --filter @netherforge/editor test`, jsdom):
  everything pure lives in its own module so it's tested without React,
  three or Monaco: `history`, `external`, the store against a
  `MemoryBackend` (open/save/undo/gestures/conflicts/renames; `views.test.ts`
  for view state moving, going and never being undone; `refactors.test.ts`
  for undoing and redoing renames, deletes and copies, the order against
  documents' own steps, and refusals when the disk moved on), the typed views
  (`editors/views.test.ts`), the centity pose's fallback (`pose.test.ts`), `core/format.ts`
  against the real Kotlin/JS build, `fit`/`boxes`/`model` with hand-written
  asset maps, `gizmo` against `poseCentity`, the viewports store and camera
  poses (`viewport/scenes.test.ts`), the timeline's time model, keys and
  keyboard (`timeline/*.test.ts(x)`), `KeyedList` and `Gallery`, the LuaLS stubs and transport,
  field commit semantics with Testing Library. Assert behaviour; no DOM
  snapshots. Don't import Monaco in tested modules.
- **Set-up and time** (the testing skill has the rules): a test starts from
  `src/testing/workspace.ts` (`openExampleWorkspace()`, `openExampleApp()`,
  `exampleBackend()`), never a hand-built backend and `openProject`. Store
  tests whose code runs on timers use `vi.useFakeTimers()` and `settle(ms)`
  (delays are exported to be asserted at their end: `VALIDATE_DELAY_MS`,
  `SAVE_DELAY_MS`); component tests use real timers and `findBy…`/`waitFor`.
  No fixed sleeps anywhere.
- **Component tests per editor**: each resource editor with an inspector
  gets `<Kind>Editor.test.tsx`, rendering the editor in an `AppProvider` (its
  `InspectorPanel` renders in place outside a workbench; add `<DialogHost />`
  for `ask.prompt`/`ask.confirm`) and asserting on the model
  (`modelOf(docs[path])`). Pick by role and name: an input with a `list`
  (suggestions) is a `combobox`, a repeated set of fields is a named `group`.
  The e2e flow for that editor keeps only what needs a browser.
- **Workers in vitest.** jsdom has no `Worker`; `@vitest/web-worker` (a
  `setupFiles` entry in `vite.config.ts`) gives it one that runs the
  worker's module in the test's process, behind real `postMessage`s with
  structured clones. So every store test validates through the real
  validation worker module and Comlink, as the app does;
  `core/store/validation.test.ts` watches the requests and counts what each
  validation validated (one file after an edit, the cross-file problem it
  causes still found). A worker module calls `expose(…, self)`, never the
  default `globalThis`, which is the test's window there. Playwright runs the
  real worker (the invalid-edit flow checks a `validation.worker` is
  running).
- **The registry**: `editors/registry.node.test.ts` (Node, the TypeScript
  checker over `tsconfig.app.json`) keeps kinds listed only in the registry
  (see "Contributions"), and proves itself on a hand-written list.
- **Playwright** (`pnpm test:e2e`, `e2e/*.spec.ts`): the e2e build (`vite
build --mode e2e`, the only build carrying the memory backend; `pnpm dev`
  and the app always run on Rust) with `?fast` (`&assets=fixture` for real
  rendering and glyph advances). `resources.spec.ts` covers the menu, dialog and resource pack
  editors and text Fit; `centity.spec.ts` the shared canvas (one, moved between tabs,
  each tab's camera kept) and the timeline by pointer and keyboard; `window.spec.ts` finds the title
  bar's empty stretch from its children's boxes (fonts differ by OS). A tree is
  `treegrid` with `row`s (not `tree`/
  `treeitem`), the palette's field a `textbox`. Tests play
  the outside world through `outside(page, backend => …)`: `testWrite`,
  `testDelete`, `testBridgeEvent`, `testConnect`, `testFiles`. Keep it to
  ~15 flows; target by role and accessible name, which is why every control
  has one. Canonical-text assertions call `@netherforge/format` in Node.
- **Restricted mode** (workspace trust; the backend decides, see
  tauri-backend): `ProjectInfo.trusted` is the backend's answer on open, and
  `workspace.trustProject(trusted)` asks it to change (then writes
  `.mcp.json`, which is skipped for an untrusted project since it carries the
  token, and re-resolves packages, whose git fetches were refused).
  `workbench/TrustBanner` shows the banner under the toolbar and
  `askToTrust(workspace)` the confirm; `startServer(app)` asks it first. The
  Lua client (`editors/script/luaClient.ts`) runs only for a trusted project
  and follows the flag. `MemoryBackend`'s `trusted` option is the roots
  trusted before (default: every project it's given; the contract suite
  gives none, the e2e build all but `?untrusted`). `askToUntrust(workspace)`
  (same file) is the confirmed way back, `trustProject(false)` (the backend
  stops the dev server): the Run menu's `run.untrust` command and the button of
  Settings › Server's "Project trust" section (which also offers Trust).
  `trust.spec.ts` is the flow.
- **Requirements** (`netherforge.json`'s `requires`): the project editor's
  "What scripts need" section edits the project's own (`setRequired`,
  `addRequired`/`removeRequired` in `editors/project/ops.ts`, hosts and
  plugins checked against the generated `HTTP_HOST_PATTERN` and
  `PLUGIN_NAME_PATTERN`), and "What running it asks of a server" shows
  `outline.requirements`, format's sum across the tree with who declares
  each, as the server logs it.
- **Mob spawning by world** (`netherforge.json`'s `worlds`): the project
  editor's `WorldSpawning` section, a limit and an interval per spawn category
  (`SpawnCategoryValues`) in a group per world, edited with `setWorldSpawn` and
  `removeWorldSpawn` in `editors/project/ops.ts` (whole numbers of 0 or more;
  worlds and categories kept in format's order; an emptied map, world or `worlds`
  leaves the file, as format drops it). A world with nothing set isn't in the
  file, so one just added is only component state until a value is set.
- **Server-owner settings**: `editors/project/SettingsSection` (in the project
  editor) declares them (the "Add setting…" dialog; `settings.ts` holds the
  pure edits over format's `SettingDef` JSON and `readSetting`), and
  `workbench/settings/OwnerSettings` (Settings › Server-owner settings) shows
  `run.serverSettings`, which the run store fetches when the bridge connects
  and keeps through `settings_changed`; a value is sent with `run.setSetting`
  (`set_setting`), which the fake server in `MemoryBackend` validates with the
  same `readSetting`. A new connect-time bridge request shows in
  `bridgeLog`: tests that assert on it filter `settings` out like
  `profiler_subscribe`. `owner-settings.spec.ts` is the flow.
- **Screenshots** (`pnpm test:screenshots`, `e2e/screenshots/`): the
  previews drawn from format's numbers (window, skin, dialog, glyph text,
  tooltip), pixel for pixel, with the browser in the Playwright container
  (the testing skill has how, and the `--update` command). A change to a
  preview's drawing is a picture to rewrite and review; a new kind of
  preview gets a picture there. The pictures also depend on the layout and
  layering around the preview, so a workbench change can fail them with no
  preview change: text drawn at a fractional offset, or in another
  composited layer, changes anti-aliasing (greyscale vs coloured LCD), and the
  skin (nearest neighbour at 1.75×, where samples tie) picks other source
  columns when the layer it paints into starts elsewhere. The inspector paints
  into a layer of its own (it overlaps nothing composited but comes after the
  gallery's composited scroller) whose bounds are the union of what's in it,
  the inspector splitter's 7px grip included. E7 lost both (0px separators
  moved the editor area 1px; no grip moved the layer's origin 4px), which
  broke the skin, dialog and glyph pictures. Before rewriting a picture after
  a change outside the previews, find what moved: compare each preview
  ancestor's `getBoundingClientRect()` (whole pixels) and the layer tree
  (CDP `LayerTree`, enabled after the page loads) before and after.
- The memory backend stores text or bytes (`FileContents`); the seed
  bundles example PNGs (`?inline` → bytes), `testBytes`/`testWriteBytes`
  move binary files in and out of Playwright, and `projectFileUrl` is a
  `data:` URL. `fixtureGlyphAdvances` (with `?assets=fixture`) feeds text
  fitting in tests.
- `pnpm --filter @netherforge/editor lint` is `tsc -b` + eslint with the React
  Compiler rules: no setState in effect bodies (derive state or set it in a
  callback), no ref reads during render, no mutating props.
- **The memory backend and the contract.** `MemoryBackend` behaves like the
  Rust commands where the UI can tell: results, path rules, and failures as
  `BackendError`s with the same `code` (`fail('notFound', …)`). Every case
  in `core/backend/contract.json` runs on it here (`contract.test.ts`) and
  on the real commands in Rust (see the tauri-backend skill's "The contract
  suite"); when they disagree, fix the memory backend. A new backend
  behaviour the UI relies on gets a case there. UI code that reacts to a
  failure checks `isBackendError(e, 'notFound')`, never the message.
