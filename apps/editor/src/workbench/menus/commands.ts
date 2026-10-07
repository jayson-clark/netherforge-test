/**
 * Everything the app's menus and its keyboard shortcuts do, in one table:
 * each command's label, keys, when it's enabled or checked, and what it
 * runs. `buildMenus` lays them out as File, Edit, View, Run and Help (plus
 * macOS's app and Window menus); `commandForKey` is the global key handler's
 * lookup, so a shortcut a menu shows is the shortcut that works; and
 * `paletteCommands` lists them for the command palette (with a few only it
 * has: one per settings page, per new resource, per resource a kind's
 * command can act on). Kinds contribute commands through the registry
 * (`<kind>.<name>`, see `KindCommand`) and dock panels their View items;
 * each command's `when` says when it can run.
 */
import type { AppMenu, MenuEntry } from '@/core/backend/types'
import { MANIFEST_FILE, type KindId } from '@/core/format'
import { resourceOf } from '@/core/paths'
import type { CommandContext, CommandPlace, KindCommand } from '@/editors/contributions'
import { KIND_ORDER, kindContribution } from '@/editors/registry'
import { matchesShortcut, parseShortcut } from '@/core/shortcut'
import type { IconName } from '@/ui/Icon'
import { DOCK_PANELS, showPanel } from '../contributions'
import { closeProjectAsking, closeTabAsking, closeTabsAsking, stepTab } from '../editorArea/tabs'
import { EXPLORER_FOLDERS, folderByKey, resourceAt } from '../explorer/folders'
import { openCommandPalette, openQuickOpen } from '../quickOpen/store'
import { resourceActions } from '../resourceActions'
import { SETTINGS_PAGES } from '../settings/pages'
import { isEditableTarget } from '@/ui/editable'
import { activeDocPath, undoesDocument } from '../shortcuts'
import { startServer } from '../toolbar/RunControls'
import { askToUntrust } from '../TrustBanner'
import { openProjectAt, useWelcome } from '../welcome/Welcome'
import { focusedCode } from '@/editors/script/focusedCode'
import { editText } from './editing'

export const GITHUB_URL = 'https://github.com/netherforge/netherforge'

export type { CommandContext }

interface Command {
  label: string | ((c: CommandContext) => string)
  /** The first is the one menus show; Mod is Cmd on macOS, Ctrl elsewhere. */
  keys?: (c: CommandContext) => string[]
  /** Whether it can run now: the menus grey it out otherwise. */
  when?: (c: CommandContext) => boolean
  checked?: (c: CommandContext) => boolean
  run: (c: CommandContext) => unknown
  /** Under its name in the command palette: what it does, or where. */
  hint?: string | ((c: CommandContext) => string)
  icon?: IconName
  /**
   * Its name in the palette, where there's no menu around it to say what it
   * means ("Outline" is "Hide Outline"); false leaves it out (the clipboard
   * and undo act on the focused field, which the palette itself would be).
   */
  palette?: false | ((c: CommandContext) => string)
  /**
   * Whether the global key handler takes [event] (the default once a key
   * matches). False leaves it to the focused field or Monaco, or, on
   * macOS, to the menu bar, which then runs the command itself.
   */
  takesKey?: (event: KeyboardEvent, c: CommandContext) => boolean
}

const keys =
  (...shortcuts: string[]) =>
  () =>
    shortcuts

const project = (c: CommandContext) => c.app.workspace.getState().project !== null

/** The active tab's model document (not a text one: Monaco keeps that undo). */
function activeModel(c: CommandContext) {
  const path = activeDocPath(c.app.workspace)
  const doc = path ? c.app.workspace.getState().docs[path] : undefined
  return doc && !doc.raw && doc.history ? { path: path!, history: doc.history } : null
}

/** The id of the active tab's resource, when it's one of [kind]'s (its own tab, or a file inside it). */
function activeOf(c: CommandContext, kind: KindId): string | null {
  const { tabs, activeTab } = c.app.workspace.getState()
  const tab = tabs.find((it) => it.id === activeTab)
  const resource = tab ? resourceOf(tab.path) : null
  return resource?.kind === kind ? resource.id : null
}

const serverUp = (c: CommandContext) => {
  const phase = c.app.run.getState().server.phase
  return (
    phase === 'preparing' || phase === 'starting' || phase === 'running' || phase === 'stopping'
  )
}

/** Whether a breakpoint holds the dev server. */
const paused = (c: CommandContext) => c.app.debug.getState().session === 'paused'

const leaveProject = (c: CommandContext) => closeProjectAsking(c.app.workspace)

/** The resource the active tab is in, for the palette's rename, duplicate and delete. */
function activeResource(c: CommandContext) {
  const path = activeDocPath(c.app.workspace)
  return path ? resourceAt(path, c.app.workspace.getState().files) : null
}

/** "Hide Outline" or "Show Outline", by whether it's showing. */
const toggleName = (name: string, open: (c: CommandContext) => boolean) => (c: CommandContext) =>
  `${open(c) ? 'Hide' : 'Show'} ${name}`

/**
 * The Edit menu's clipboard and selection: the focused field's or Monaco's,
 * never a model's. Nothing in the app selects many things at once, so Select
 * All outside a field or Monaco does nothing, and claims its key so the
 * browser doesn't select the whole page.
 */
const textOnly = (action: 'cut' | 'copy' | 'paste' | 'selectAll'): Command => ({
  label: { cut: 'Cut', copy: 'Copy', paste: 'Paste', selectAll: 'Select All' }[action],
  keys: keys(`Mod+${{ cut: 'X', copy: 'C', paste: 'V', selectAll: 'A' }[action]}`),
  run: () => void editText(action),
  palette: false,
  takesKey: (event) => action === 'selectAll' && !isEditableTarget(event.target),
})

export const COMMANDS = {
  'app.settings': {
    palette: () => 'Editor Settings',
    hint: 'Minecraft versions, the dev server, updates and agents',
    icon: 'gear',
    label: (c) => (c.mac ? 'Settings…' : 'Settings'),
    keys: keys('Mod+,'),
    when: project,
    run: (c) => c.app.workspace.getState().openSettings(),
  },
  'app.checkForUpdates': {
    hint: 'Look for a newer NetherForge',
    icon: 'refresh',
    label: 'Check for Updates…',
    when: (c) => !c.app.updates.getState().checking,
    run: async (c) => {
      // Settings → Updates says what the check found; a newer version also shows its banner.
      if (project(c)) c.app.workspace.getState().openSettings('updates')
      await c.app.updates.getState().check()
    },
  },
  'app.exit': {
    palette: (c) => (c.mac ? 'Quit NetherForge' : 'Exit'),
    icon: 'close',
    label: 'Exit',
    run: (c) => c.app.backend.windowAction('close'),
  },

  'file.newProject': {
    hint: 'Start a NetherForge project in a new folder',
    icon: 'newFolder',
    label: 'New Project…',
    run: async (c) => {
      if (await leaveProject(c)) useWelcome.setState({ creating: true, error: null })
    },
  },
  'file.openProject': {
    hint: 'Open a project folder',
    icon: 'folderOpen',
    label: 'Open Project…',
    keys: keys('Mod+O'),
    run: async (c) => {
      const root = await c.app.backend.pickFolder('Open a NetherForge project')
      if (root && (await leaveProject(c))) await openProjectAt(c.app.workspace, root)
    },
  },
  'file.save': {
    hint: 'Save the active tab',
    icon: 'save',
    label: 'Save',
    keys: keys('Mod+S'),
    when: (c) => {
      const path = activeDocPath(c.app.workspace)
      return !!path && !!c.app.workspace.getState().docs[path]?.dirty
    },
    run: (c) => {
      const path = activeDocPath(c.app.workspace)
      if (path) void c.app.workspace.getState().save(path)
    },
  },
  'file.saveAll': {
    hint: 'Save every unsaved tab',
    icon: 'saveAll',
    label: 'Save All',
    keys: keys('Mod+Shift+S'),
    when: (c) => Object.values(c.app.workspace.getState().docs).some((it) => it.dirty),
    run: (c) => c.app.workspace.getState().saveAll(),
  },
  'file.closeTab': {
    hint: 'Close the active tab',
    icon: 'close',
    label: 'Close Tab',
    // Never the window: with no tab left, Cmd+W does nothing.
    keys: keys('Mod+W'),
    when: (c) => c.app.workspace.getState().activeTab !== null,
    run: (c) => {
      const tab = c.app.workspace.getState().activeTab
      if (tab) void closeTabAsking(c.app.workspace, tab)
    },
  },
  'file.closeOtherTabs': {
    label: 'Close Other Tabs',
    hint: 'Keep only the active tab',
    icon: 'close',
    when: (c) => c.app.workspace.getState().tabs.length > 1,
    run: (c) => {
      const { tabs, activeTab } = c.app.workspace.getState()
      const others = tabs.filter((it) => it.id !== activeTab).map((it) => it.id)
      return closeTabsAsking(c.app.workspace, others)
    },
  },
  'file.closeAllTabs': {
    label: 'Close All Tabs',
    icon: 'close',
    when: (c) => c.app.workspace.getState().tabs.length > 0,
    run: (c) =>
      closeTabsAsking(
        c.app.workspace,
        c.app.workspace.getState().tabs.map((it) => it.id),
      ),
  },
  'file.projectSettings': {
    label: 'Project Settings',
    hint: `${MANIFEST_FILE}: the project's name, Minecraft version and managed worlds`,
    icon: 'gear',
    when: project,
    run: (c) => c.app.workspace.getState().openFile(MANIFEST_FILE),
  },
  'file.renameResource': {
    label: 'Rename Resource…',
    palette: (c) => `Rename ${activeResource(c)?.id}…`,
    hint: (c) => `The active ${activeResource(c)?.folder.one}, and what refers to it`,
    icon: 'edit',
    when: (c) => activeResource(c) !== null,
    run: (c) => {
      const at = activeResource(c)
      if (at) return resourceActions(c.app.workspace).renameAsking(at.folder, at.id)
    },
  },
  'file.duplicateResource': {
    label: 'Duplicate Resource',
    palette: (c) => `Duplicate ${activeResource(c)?.id}`,
    hint: (c) => `Copy the active ${activeResource(c)?.folder.one} under a new id`,
    icon: 'copy',
    when: (c) => activeResource(c) !== null,
    run: async (c) => {
      const at = activeResource(c)
      const copy = at && (await resourceActions(c.app.workspace).duplicate(at.folder, at.id))
      if (at && copy) await c.app.workspace.getState().openFile(at.folder.openPath(copy))
    },
  },
  'file.deleteResource': {
    label: 'Delete Resource…',
    palette: (c) => `Delete ${activeResource(c)?.id}…`,
    hint: (c) => `Delete the active ${activeResource(c)?.folder.one} (asks first)`,
    icon: 'trash',
    when: (c) => activeResource(c) !== null,
    run: (c) => {
      const at = activeResource(c)
      if (at) return resourceActions(c.app.workspace).remove(at.folder, at.id)
    },
  },
  'file.closeProject': {
    hint: 'Back to the welcome screen',
    icon: 'arrowLeft',
    label: 'Close Project',
    when: project,
    run: leaveProject,
  },

  'edit.undo': {
    palette: false,
    label: 'Undo',
    keys: keys('Mod+Z'),
    run: (c) => {
      if (editText('undo')) return
      // The active document's last edit or the project's last refactor, whichever came later.
      const workspace = c.app.workspace.getState()
      const model = activeModel(c)
      return model ? workspace.undo(model.path) : workspace.undoRefactor()
    },
    // Monaco and a field with uncommitted typing keep their own undo.
    takesKey: (event, c) =>
      undoesDocument(event.target) &&
      (activeModel(c) !== null || c.app.workspace.getState().refactors.done.length > 0),
  },
  'edit.redo': {
    palette: false,
    label: 'Redo',
    keys: (c) => (c.mac ? ['Mod+Shift+Z', 'Mod+Y'] : ['Mod+Y', 'Mod+Shift+Z']),
    run: (c) => {
      if (editText('redo')) return
      const workspace = c.app.workspace.getState()
      const model = activeModel(c)
      return model ? workspace.redo(model.path) : workspace.redoRefactor()
    },
    takesKey: (event, c) =>
      undoesDocument(event.target) &&
      (activeModel(c) !== null || c.app.workspace.getState().refactors.undone.length > 0),
  },
  'edit.cut': textOnly('cut'),
  'edit.copy': textOnly('copy'),
  'edit.paste': textOnly('paste'),
  'edit.selectAll': textOnly('selectAll'),
  'edit.commandPalette': {
    label: 'Command Palette…',
    keys: keys('Mod+Shift+P'),
    palette: false,
    when: project,
    run: () => openCommandPalette(),
  },
  'edit.goTo': {
    palette: false,
    label: 'Go to Resource or File…',
    keys: keys('Mod+P'),
    when: project,
    run: () => openQuickOpen(),
  },

  'view.outline': {
    palette: toggleName('Outline', (c) => c.app.layout.getState().outlineOpen),
    hint: "The left dock: the active resource's parts and files",
    icon: 'panelLeft',
    label: 'Outline',
    keys: keys('Mod+B'),
    when: project,
    checked: (c) => c.app.layout.getState().outlineOpen,
    run: (c) => c.app.layout.getState().toggle('outline'),
  },
  'view.inspector': {
    palette: toggleName('Inspector', (c) => c.app.layout.getState().inspectorOpen),
    hint: "The right dock: the selection's fields",
    icon: 'panelRight',
    label: 'Inspector',
    keys: keys('Mod+Alt+B'),
    when: project,
    checked: (c) => c.app.layout.getState().inspectorOpen,
    run: (c) => c.app.layout.getState().toggle('inspector'),
  },
  'view.bottom': {
    palette: toggleName('Bottom Dock', (c) => c.app.layout.getState().bottomOpen),
    hint: 'The project explorer, instances, problems and the console',
    icon: 'panelBottom',
    label: 'Bottom Dock',
    keys: keys('Mod+J'),
    when: project,
    checked: (c) => c.app.layout.getState().bottomOpen,
    run: (c) => c.app.layout.getState().toggle('bottom'),
  },
  'view.nextTab': {
    icon: 'arrowRight',
    label: 'Next Tab',
    // Ctrl on every OS: Cmd+Tab is the Mac's app switcher.
    keys: keys('Ctrl+Tab'),
    when: (c) => c.app.workspace.getState().tabs.length > 1,
    run: (c) => stepActiveTab(c, 1),
  },
  'view.previousTab': {
    icon: 'arrowLeft',
    label: 'Previous Tab',
    keys: keys('Ctrl+Shift+Tab'),
    when: (c) => c.app.workspace.getState().tabs.length > 1,
    run: (c) => stepActiveTab(c, -1),
  },
  'view.zoomIn': {
    hint: 'Make the whole editor larger',
    icon: 'plus',
    label: 'Zoom In',
    keys: keys('Mod+='),
    run: (c) => c.app.zoom.step(1),
  },
  'view.zoomOut': {
    hint: 'Make the whole editor smaller',
    icon: 'search',
    label: 'Zoom Out',
    keys: keys('Mod+-'),
    run: (c) => c.app.zoom.step(-1),
  },
  'view.actualSize': {
    hint: 'Reset the zoom',
    icon: 'search',
    label: 'Actual Size',
    keys: keys('Mod+0'),
    run: (c) => c.app.zoom.reset(),
  },

  'run.server': {
    hint: (c) =>
      serverUp(c) ? 'Stop the Paper dev server' : 'Run the project on a Paper dev server',
    icon: 'play',
    label: (c) => (serverUp(c) ? 'Stop Dev Server' : 'Start Dev Server'),
    when: (c) => project(c) && c.app.run.getState().server.phase !== 'stopping',
    run: (c) => (serverUp(c) ? c.app.run.getState().stop() : startServer(c.app)),
  },
  'run.tests': {
    hint: "Run the project's script tests (*_test.lua) on fake servers",
    icon: 'check',
    label: 'Run Tests',
    when: (c) => project(c) && c.app.tests.getState().running === false,
    run: async (c) => {
      showPanel(c.app.layout, 'tests')
      await c.app.tests.getState().run()
    },
  },
  'run.continue': {
    hint: 'Let the paused dev server run on',
    icon: 'resume',
    label: 'Continue',
    keys: keys('F5'),
    when: paused,
    run: (c) => c.app.debug.getState().continue(),
  },
  'run.stepOver': {
    hint: 'Run to the next line, over calls',
    icon: 'stepOver',
    label: 'Step Over',
    keys: keys('F10'),
    when: paused,
    run: (c) => c.app.debug.getState().stepOver(),
  },
  'run.stepInto': {
    hint: 'Run to the next line, into a call',
    icon: 'stepInto',
    label: 'Step Into',
    keys: keys('F11'),
    when: paused,
    run: (c) => c.app.debug.getState().stepInto(),
  },
  'run.stepOut': {
    hint: 'Run until the function returns',
    icon: 'stepOut',
    label: 'Step Out',
    keys: keys('Shift+F11'),
    when: paused,
    run: (c) => c.app.debug.getState().stepOut(),
  },
  'run.pause': {
    hint: 'Stop the dev server at the next line of script it runs',
    icon: 'pause',
    label: 'Pause',
    when: (c) => c.app.debug.getState().session === 'running',
    run: (c) => c.app.debug.getState().pause(),
  },
  'run.stopDebugging': {
    hint: 'Let the dev server run on without breakpoints',
    icon: 'stop',
    label: 'Stop Debugging',
    when: (c) => c.app.debug.getState().session !== 'detached',
    run: (c) => c.app.debug.getState().stopDebugging(),
  },
  'run.toggleBreakpoint': {
    hint: "On the code editor's line",
    icon: 'breakpoint',
    label: 'Toggle Breakpoint',
    keys: keys('F9'),
    when: () => focusedCode() !== null,
    run: () => focusedCode()?.('netherforge.toggleBreakpoint'),
    // In the code editor, its own F9 does it.
    takesKey: (event) => !isEditableTarget(event.target),
  },
  'run.breakpoints': {
    hint: 'Whether breakpoints stop the dev server',
    icon: 'breakpoint',
    label: 'Breakpoints Active',
    when: project,
    checked: (c) => c.app.debug.getState().active,
    run: (c) => c.app.debug.getState().setActive(!c.app.debug.getState().active),
  },
  'run.breakOnErrors': {
    hint: "Stop the dev server where a script's error goes unhandled",
    icon: 'error',
    label: 'Break on Script Errors',
    when: project,
    checked: (c) => c.app.debug.getState().breakOnErrors,
    run: (c) => c.app.debug.getState().setBreakOnErrors(!c.app.debug.getState().breakOnErrors),
  },
  'run.untrust': {
    hint: 'Stop the dev server and put the project back in restricted mode',
    icon: 'lock',
    label: 'Untrust Project…',
    when: (c) => c.app.workspace.getState().project?.trusted === true,
    run: (c) => askToUntrust(c.app.workspace),
  },
  'run.clearConsole': {
    label: 'Clear Console',
    icon: 'trash',
    when: project,
    run: (c) => c.app.run.getState().clearConsole(),
  },
  'run.launcher': {
    hint: 'To join the dev server from Minecraft',
    icon: 'cube',
    label: 'Open Minecraft Launcher',
    run: async (c) => {
      try {
        await c.app.backend.openLauncher()
      } catch (error) {
        c.app.workspace
          .getState()
          .notify('error', error instanceof Error ? error.message : String(error))
      }
    },
  },

  'help.github': {
    hint: 'The source, the docs and the releases',
    icon: 'link',
    label: 'NetherForge on GitHub',
    run: (c) => c.app.backend.openExternal(GITHUB_URL),
  },
  'help.issue': {
    hint: 'Tell us what went wrong, on GitHub',
    icon: 'link',
    label: 'Report an Issue…',
    run: (c) => c.app.backend.openExternal(`${GITHUB_URL}/issues/new`),
  },
} satisfies Record<string, Command>

export type CommandId = keyof typeof COMMANDS

/** A View item per dock panel: `view.panel:<id>` shows it. */
const PANEL_COMMANDS: Record<string, Command> = Object.fromEntries(
  DOCK_PANELS.map((panel) => [
    `view.panel:${panel.id}`,
    {
      palette: () => `Show ${panel.menu.label}`,
      hint: panel.menu.hint,
      icon: panel.menu.icon,
      label: panel.menu.label,
      when: project,
      run: (c) => showPanel(c.app.layout, panel.id),
    } satisfies Command,
  ]),
)

/** A kind's command, with the kind it came from. */
interface Contributed {
  kind: KindId
  command: KindCommand
}

/** Every command a kind contributes, by id (`<kind>.<name>`). */
const CONTRIBUTED: Record<string, Contributed> = Object.fromEntries(
  KIND_ORDER.flatMap((kind) =>
    (kindContribution(kind).commands ?? []).map((command) => [
      `${kind}.${command.name}`,
      { kind, command },
    ]),
  ),
)

/**
 * A kind's command as the menus and keys see it: a resource command acts on
 * the active tab's resource, so it can run only while one of its kind is
 * active; the palette offers it per resource instead (`paletteCommands`).
 */
function asCommand({ kind, command }: Contributed): Command {
  const allowed = (c: CommandContext) => command.when?.(c) ?? true
  if (command.scope === 'kind') {
    return {
      label: command.label,
      hint: command.hint,
      icon: command.icon,
      when: allowed,
      run: command.run,
    }
  }
  return {
    label: (c) => command.label(activeOf(c, kind)),
    hint: command.hint,
    icon: command.icon,
    palette: false,
    when: (c) => activeOf(c, kind) !== null && allowed(c),
    run: (c) => {
      const id = activeOf(c, kind)
      if (id !== null) return command.run(c, id)
    },
  }
}

/** Every command there is: the table's, the panels' and the kinds'. */
const ALL_COMMANDS: Record<string, Command> = {
  ...COMMANDS,
  ...PANEL_COMMANDS,
  ...Object.fromEntries(Object.entries(CONTRIBUTED).map(([id, it]) => [id, asCommand(it)])),
}

/** The kinds' commands for one menu, in the registry's order. */
const contributedIn = (place: CommandPlace) =>
  Object.entries(CONTRIBUTED)
    .filter(([, it]) => it.command.menu === place)
    .map(([id]) => id)

/** A toolbar button a kind contributes: what it says, and whether and how it runs now. */
export interface ToolbarCommand {
  id: string
  text: string
  icon: IconName
  /** Its tooltip: what it would do now, or what it needs. */
  title: (c: CommandContext) => string
  enabled: (c: CommandContext) => boolean
}

export const TOOLBAR_COMMANDS: ToolbarCommand[] = Object.entries(CONTRIBUTED).flatMap(
  ([id, { kind, command }]) =>
    command.scope === 'resource' && command.toolbar
      ? [
          {
            id,
            text: command.toolbar,
            icon: command.icon,
            title: (c: CommandContext) => {
              const active = activeOf(c, kind)
              return active ? command.label(active) : `Open a ${kindContribution(kind).one} first`
            },
            enabled: (c: CommandContext) => ALL_COMMANDS[id]!.when?.(c) ?? true,
          },
        ]
      : [],
)

function stepActiveTab(c: CommandContext, step: 1 | -1) {
  const ws = c.app.workspace.getState()
  const next = stepTab(
    ws.tabs.map((it) => it.id),
    ws.activeTab,
    step,
  )
  if (next) ws.activate(next)
}

/** Menu items made per project or per recent folder: `file.new:<explorer folder>`, `file.recent:<root>`. */
const NEW_PREFIX = 'file.new:'
const RECENT_PREFIX = 'file.recent:'
/** Only in the palette: `settings:<page>`, and a kind's resource command on one resource, `<kind>.<name>:<id>`. */
const SETTINGS_PREFIX = 'settings:'

/** Runs a menu item's command; unknown ids (a stale menu) do nothing. */
export function runCommand(id: string, c: CommandContext) {
  if (id.startsWith(NEW_PREFIX)) {
    if (!project(c)) return
    const folder = folderByKey(id.slice(NEW_PREFIX.length))
    void resourceActions(c.app.workspace).create(folder)
    return
  }
  if (id.startsWith(RECENT_PREFIX)) {
    const root = id.slice(RECENT_PREFIX.length)
    void leaveProject(c).then(async (left) => {
      if (left) await openProjectAt(c.app.workspace, root)
    })
    return
  }
  if (id.startsWith(SETTINGS_PREFIX)) {
    const page = SETTINGS_PAGES.find((it) => it.id === id.slice(SETTINGS_PREFIX.length))
    if (project(c) && page) c.app.workspace.getState().openSettings(page.id)
    return
  }
  const at = id.indexOf(':')
  const contributed = at > 0 ? CONTRIBUTED[id.slice(0, at)] : undefined
  if (contributed?.command.scope === 'resource') {
    if (contributed.command.when?.(c) ?? true) void contributed.command.run(c, id.slice(at + 1))
    return
  }
  const command: Command | undefined = ALL_COMMANDS[id]
  if (command && (command.when?.(c) ?? true)) void command.run(c)
}

export interface PaletteCommand {
  id: string
  label: string
  hint: string
  icon: IconName
  /** As the menus write it (`Mod+B`), for the palette to show. */
  shortcut?: string
}

/** The palette's order with nothing typed: running things, the docks, settings, files, then the rest. */
const PALETTE_GROUPS = [
  'run.',
  ...KIND_ORDER.map((kind) => `${kind}.`),
  'view.',
  'app.settings',
  SETTINGS_PREFIX,
  'file.projectSettings',
  NEW_PREFIX,
  'file.',
  RECENT_PREFIX,
  'help.',
  'app.',
]
const paletteGroup = (id: string) => PALETTE_GROUPS.findIndex((it) => id.startsWith(it))

/**
 * Everything the command palette can run right now: the commands that are
 * enabled, and one per settings page, new resource kind, recent project and
 * resource a kind's command can act on now (with the dev server's bridge up,
 * every centity to spawn).
 */
export function paletteCommands(c: CommandContext): PaletteCommand[] {
  const out: PaletteCommand[] = []
  for (const [id, command] of Object.entries(ALL_COMMANDS)) {
    if (command.palette === false || !(command.when?.(c) ?? true)) continue
    const label =
      command.palette?.(c) ?? (typeof command.label === 'string' ? command.label : command.label(c))
    const hint = typeof command.hint === 'function' ? command.hint(c) : (command.hint ?? '')
    out.push({
      id,
      label,
      hint,
      icon: command.icon ?? 'chevronRight',
      shortcut: command.keys?.(c)[0],
    })
  }
  // (The palette is the workbench's, so a project is open.)
  for (const page of SETTINGS_PAGES) {
    out.push({
      id: `${SETTINGS_PREFIX}${page.id}`,
      label: `Editor Settings: ${page.title}`,
      hint: page.hint,
      icon: page.icon,
    })
  }
  for (const folder of EXPLORER_FOLDERS) {
    out.push({
      id: `${NEW_PREFIX}${folder.key}`,
      label: `New ${folder.one.charAt(0).toUpperCase()}${folder.one.slice(1)}…`,
      hint: `Create a ${folder.one} in this project`,
      icon: folder.icon,
    })
  }
  const root = c.app.workspace.getState().project?.root
  for (const recent of c.recent) {
    if (recent.root === root) continue
    out.push({
      id: `${RECENT_PREFIX}${recent.root}`,
      label: `Open ${recent.name}`,
      hint: recent.root,
      icon: 'folderOpen',
    })
  }
  const files = c.app.workspace.getState().files
  for (const [id, { kind, command }] of Object.entries(CONTRIBUTED)) {
    if (command.scope !== 'resource' || !(command.when?.(c) ?? true)) continue
    for (const resource of folderByKey(kind).ids(files)) {
      out.push({
        id: `${id}:${resource}`,
        label: command.label(resource),
        hint: command.hint,
        icon: command.icon,
      })
    }
  }
  // Stable: within a group, the table's order.
  return out.sort((a, b) => paletteGroup(a.id) - paletteGroup(b.id))
}

/**
 * The command [event] is the shortcut of, if the global handler should run
 * it. A disabled command still claims its key (Cmd+S with nothing to save
 * mustn't reach the browser), so it comes back with `enabled: false`.
 */
export function commandForKey(
  event: KeyboardEvent,
  c: CommandContext,
): { id: string; enabled: boolean } | null {
  for (const [id, command] of Object.entries(ALL_COMMANDS)) {
    const shortcuts = command.keys?.(c) ?? []
    if (!shortcuts.some((it) => matchesShortcut(event, parseShortcut(it), c.macKeys))) continue
    if (command.takesKey && !command.takesKey(event, c)) return null
    return { id, enabled: command.when?.(c) ?? true }
  }
  return null
}

function item(id: string, c: CommandContext): MenuEntry {
  const command = ALL_COMMANDS[id]!
  const checked = command.checked?.(c)
  return {
    kind: 'item',
    id,
    label: typeof command.label === 'string' ? command.label : command.label(c),
    shortcut: command.keys?.(c)[0],
    enabled: command.when?.(c) ?? true,
    ...(checked === undefined ? {} : { checked }),
  }
}

const separator: MenuEntry = { kind: 'separator' }

/**
 * The menus as they stand: macOS gets its app menu (About, Settings,
 * Services, Hide, Quit) and Window menu and the system's own clipboard
 * items; Windows and Linux find Settings and Exit under File.
 */
export function buildMenus(c: CommandContext): AppMenu[] {
  const open = project(c)
  const i = (id: string) => item(id, c)
  const clipboard: MenuEntry[] = c.mac
    ? [
        { kind: 'native', role: 'cut' },
        { kind: 'native', role: 'copy' },
        { kind: 'native', role: 'paste' },
        i('edit.selectAll'),
      ]
    : [i('edit.cut'), i('edit.copy'), i('edit.paste'), i('edit.selectAll')]

  const file: MenuEntry[] = [
    {
      kind: 'submenu',
      label: 'New',
      enabled: open,
      items: EXPLORER_FOLDERS.map((folder) => ({
        kind: 'item',
        id: `${NEW_PREFIX}${folder.key}`,
        label: `${folder.one.charAt(0).toUpperCase()}${folder.one.slice(1)}…`,
        enabled: open,
      })),
    },
    separator,
    i('file.newProject'),
    i('file.openProject'),
    {
      kind: 'submenu',
      label: 'Open Recent',
      enabled: c.recent.length > 0,
      items: c.recent.map((it) => ({
        kind: 'item',
        id: `${RECENT_PREFIX}${it.root}`,
        label: it.name,
        enabled: true,
      })),
    },
    separator,
    i('file.save'),
    i('file.saveAll'),
    separator,
    i('file.closeTab'),
    i('file.closeProject'),
    separator,
    i('file.projectSettings'),
    ...(c.mac ? [] : [separator, i('app.settings'), separator, i('app.exit')]),
  ]

  const menus: AppMenu[] = [
    { label: 'File', items: file },
    {
      label: 'Edit',
      items: [
        i('edit.undo'),
        i('edit.redo'),
        separator,
        ...clipboard,
        separator,
        i('edit.goTo'),
        i('edit.commandPalette'),
      ],
    },
    {
      label: 'View',
      items: [
        i('view.outline'),
        i('view.inspector'),
        i('view.bottom'),
        separator,
        ...DOCK_PANELS.map((panel) => i(`view.panel:${panel.id}`)),
        separator,
        i('view.nextTab'),
        i('view.previousTab'),
        separator,
        i('view.zoomIn'),
        i('view.zoomOut'),
        i('view.actualSize'),
        ...(c.mac ? [separator, { kind: 'native', role: 'fullscreen' } as const] : []),
      ],
    },
    {
      label: 'Run',
      items: [
        i('run.server'),
        i('run.tests'),
        ...contributedIn('run').map(i),
        separator,
        i('run.continue'),
        i('run.stepOver'),
        i('run.stepInto'),
        i('run.stepOut'),
        i('run.pause'),
        i('run.stopDebugging'),
        separator,
        i('run.toggleBreakpoint'),
        i('run.breakpoints'),
        i('run.breakOnErrors'),
        separator,
        i('run.untrust'),
        i('run.launcher'),
      ],
    },
  ]
  if (c.mac) {
    menus.unshift({
      label: 'NetherForge',
      items: [
        { kind: 'native', role: 'about' },
        separator,
        i('app.settings'),
        i('app.checkForUpdates'),
        separator,
        { kind: 'native', role: 'services' },
        separator,
        { kind: 'native', role: 'hide' },
        { kind: 'native', role: 'hideOthers' },
        { kind: 'native', role: 'showAll' },
        separator,
        { kind: 'native', role: 'quit' },
      ],
    })
    menus.push({
      label: 'Window',
      items: [
        { kind: 'native', role: 'minimize' },
        { kind: 'native', role: 'maximize' },
      ],
    })
  }
  menus.push({
    label: 'Help',
    items: [
      i('help.github'),
      i('help.issue'),
      ...(c.mac ? [] : [separator, i('app.checkForUpdates')]),
    ],
  })
  return menus
}
