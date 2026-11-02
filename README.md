<div align="center">
  <a href="https://xmake.io">
    <img width="200" heigth="200" src="https://github.com/xmake-io/xmake-idea/raw/master/res/logo256.png">
  </a>

  <h1>xmake-idea</h1>

  <div>
    <a href="https://plugins.jetbrains.com/plugin/17406-xmake">
      <img src="https://img.shields.io/jetbrains/plugin/v/17406-xmake.svg?style=flat-square" alt="Version" />
    </a>
    <a href="https://plugins.jetbrains.com/plugin/17406-xmake">
      <img src="https://img.shields.io/jetbrains/plugin/d/17406-xmake.svg?style=flat-square" alt="Downloads" />
    </a>
  </div>
  <div>
    <a href="https://github.com/xmake-io/xmake-idea/blob/master/LICENSE.md">
      <img src="https://img.shields.io/github/license/xmake-io/xmake-idea.svg?colorB=f48041&style=flat-square" alt="license" />
    </a>
    <a href="https://www.reddit.com/r/xmake/">
      <img src="https://img.shields.io/badge/chat-on%20reddit-ff3f34.svg?style=flat-square" alt="Reddit" />
    </a>
    <a href="https://t.me/tbooxorg">
      <img src="https://img.shields.io/badge/chat-on%20telegram-blue.svg?style=flat-square" alt="Telegram" />
    </a>
    <a href="https://jq.qq.com/?_wv=1027&k=5hpwWFv">
      <img src="https://img.shields.io/badge/chat-on%20QQ-ff69b4.svg?style=flat-square" alt="QQ" />
    </a>
    <a href="https://discord.gg/xmake">
      <img src="https://img.shields.io/badge/chat-on%20discord-7289da.svg?style=flat-square" alt="Discord" />
    </a>
    <a href="https://xmake.io/about/sponsor">
      <img src="https://img.shields.io/badge/donate-us-orange.svg?style=flat-square" alt="Donate" />
    </a>
  </div>

  <p>A XMake integration in IntelliJ IDEA</p>
</div>

## Introduction

A Xmake integration in IntelliJ IDEA/Clion.

It is deeply integrated with [xmake](https://github.com/xmake-io/xmake) and Intellij-IDEA to provide a convenient and fast cross-platform c/c++ development and building.

And It also support other Intellij-based platform, like Clion, Android Studio and etc.

You need install [xmake](https://github.com/xmake-io/xmake) first and a project with `xmake.lua`.

Please see [xmake-github](https://github.com/xmake-io/xmake) and [website](https://xmake.io) if you want to known more about xmake.

## Features

* Quickstart
* Create project
* Project configuration
* Run configuration
* Menu tools
* Tool windows
* Build and run
* Parse errors and goto file
* C/C++ intellisense
* Debug

## Quickstart

<div align="center">
<img src="https://raw.githubusercontent.com/xmake-io/xmake-idea/master/res/quickstart.gif" width="80%" />
</div>

## Parse errors and goto file

<div align="center">
<img src="https://raw.githubusercontent.com/xmake-io/xmake-idea/master/res/problem.gif" width="80%" />
</div>

## Output panel

<img src="https://raw.githubusercontent.com/xmake-io/xmake-idea/master/res/output_panel.png" width="100%" />

## Create project

<img src="https://raw.githubusercontent.com/xmake-io/xmake-idea/master/res/create_project.png" width="100%" />

## Project configuration

You can configure Xmake path, build settings, and Intellisense options in `Settings > Build, Execution, Deployment > Xmake`.

<img src="https://raw.githubusercontent.com/xmake-io/xmake-idea/master/res/project_configuration.png" width="100%" />

## Run configuration

<img src="https://raw.githubusercontent.com/xmake-io/xmake-idea/master/res/run_configuration.png" width="100%" />

## Menu tools

<div align="center">
<img src="https://raw.githubusercontent.com/xmake-io/xmake-idea/master/res/menu.png" width="80%" />
</div>

## C/C++ intellisense

> Only support CLion (>= 2020.1)

1. The plugin generates `compile_commands.json` for project code completion and navigation.
2. You can configure the output path and auto-update behavior in `Settings > Build, Execution, Deployment > Xmake`.
3. To manually generate it, click `Update compile commands` in the main menu or context menu.
4. CLion should automatically detect `compile_commands.json`. If not, you can open it via `File > Open...`.

## Debug

### DAP Debugging (Recommended)

> Only support CLion (>= 2026.1)

XMake now supports native debugging via the Debug Adapter Protocol (DAP). This allows you to debug your XMake targets directly without generating CMakeLists.txt.

1. Install `lldb-dap` (recommended) or `gdb-dap` on your system.
2. Open the "Run Configuration" for your XMake target.
3. In the "Debug Configuration" section, you can:
    - Enable "Auto-detect DAP driver" to let the plugin find the driver automatically.
    - Or manually select/input the path to your DAP driver executable (e.g., `/usr/bin/lldb-dap`).
4. Click the Debug button to start debugging.

### Legacy Debugging (CMake)

> Support CLion (>= 2020.1)

1. Click "Update CmakeLists" to create or update "CmakeLists.txt" file.
2. Click "File > open..." to choose this file.
3. Choose "Run > Debug..." or "Run > Debug 'project name'" into debug mode.

## Analysis

The run configuration and CLion's own toolbars carry the analysis entries. Every analysis
run configures and rebuilds the target itself - the build profile you edit keeps its own
mode - and each one presents its results in a viewer built for that kind of data.

Coverage needs no project setup: the run configures `-m coverage` and injects gcc's
`--coverage` into the compiler, linker, and shared-library flags itself, so a project
without `add_rules("mode.coverage")` is instrumented all the same. Toolchains without
gcov (MSVC) fall back to the mode plus the project's own rule, and the console says so -
as it does when the finished build holds no instrumented objects.

| Feature | Entry | Results in | IDE |
|---|---|---|---|
| Coverage (`-m coverage`, gcov) | Run with coverage | Coverage view + line highlighting | any |
| Valgrind memcheck (`-m valgrind`) | Valgrind executor | CLion memory view | CLion |
| perf profiling (`-m profile`) | Profiler executor | Flame graph | CLion |
| Sanitizers (ASan/LSan/MSan/TSan/UBSan) | Run configuration | XMake Analysis view, editor marks, Problems view | any |
| clang-tidy | XMake Analysis toolbar | XMake Analysis view, editor marks, Problems view | any |

### Sanitizers

1. Open the target's run configuration and pick a sanitizer - Address, Leak, Memory,
   Thread, or UndefinedBehavior.
2. Run as usual. The run reconfigures with the sanitizer's `build.sanitizer.*` policy
   (not the deprecated `mode.asan`-style rules, so it works on every project), rebuilds
   with full debug information, and runs the target.
3. The findings land in the **XMake Analysis** tool window: grouped by error type,
   identical reports merged with a count, every stack frame clickable back to its source
   line, the full report text in the details pane, and the blamed lines marked in the
   open editors. WSL and SSH runs map the reported paths back to your local files.

Debug runs build sanitized too: the debugger starts inside the instrumented binary, and
the sanitizer's findings still land in the analysis view after the session.

The runtime options the plugin sets (symbolization, UBSan stack traces and error types,
dedup tokens) apply only when the run configuration sets none of its own - your
`ASAN_OPTIONS` and friends always win. Platform support is the compilers', not the
plugin's: MemorySanitizer needs clang on Linux, Thread/LeakSanitizer need Linux or macOS,
and on Windows AddressSanitizer runs under MSVC or clang.

### clang-tidy

The XMake Analysis view's toolbar runs `xmake check clang.tidy` over the project; xmake
builds the compile database the checker reads (and installs clang-tidy itself on first
use), and the findings land in the same view as the sanitizers - findings grouped by
check, repeats merged with a count, every location clickable back to its source line.

## How to contribute?

Due to limited personal time, I cannot maintain this plug-in all the time. If you encounter problems, you are welcome to download the plug-in source code to debug it yourself and open pr to contribute.

### Build this project

Use IDEA Intellji open this project source code, and click `Build` button.

### Run and debug this project

Open and edit `Run configuration`, and add a gradle run configuration, then write run arguments: `runIde --stacktrace` and save it.

<img src="https://raw.githubusercontent.com/xmake-io/xmake-idea/master/res/edit_configuration.png" width="100%" />

Select this run configuration and click run button to load it.

<img src="https://raw.githubusercontent.com/xmake-io/xmake-idea/master/res/run_plugin.png" width="20%" />

For more details, please visit: [CONTRIBUTING](https://github.com/xmake-io/xmake-idea/blob/master/CONTRIBUTING.md)

## Powered by

[![JetBrains logo.](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://jb.gg/OpenSource)
