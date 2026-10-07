---
layout: home

hero:
  name: NetherForge
  text: An editor for Minecraft server content
  tagline: Build animated, scripted entities, Lua modules, menus, dialogs and resource packs. Run them on a real Paper server and see every save in-game, without a restart.
  image:
    src: /logo.svg
    alt: NetherForge
  actions:
    - theme: brand
      text: Get started
      link: /guide/
    - theme: alt
      text: Lua API
      link: /reference/
    - theme: alt
      text: File format
      link: /format/project

features:
  - title: Your project is a folder
    details: Every centity, module, menu, dialog and resource pack is a plain file. Version it with git, review it in pull requests, edit it in any editor or with a coding agent.
  - title: Real Minecraft is the preview
    details: The editor downloads and runs a Paper server for your project's Minecraft version, with the NetherForge plugin installed. Join it and play what you built.
  - title: Hot reload on save
    details: Save a script, a centity, a menu or a dialog and the dev server reloads just that resource. Live instances pick up the change in place.
  - title: Lua, sandboxed
    details: Lua 5.4 with a small, documented API, an instruction budget per call, and completions in the editor and in VS Code.
  - title: Every platform
    details: The editor runs on macOS, Windows and Linux. The plugin runs on any Paper server for a supported version.
  - title: Open source
    details: The editor, the file-format library and the Paper plugin are all Apache-2.0.
---
