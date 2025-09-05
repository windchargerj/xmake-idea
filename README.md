<div align="center">
  <a href="http://xmake.io">
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
    <a href="https://github.com/xmake-io/xmake-idea/blob/master/LICENSE.md">
      <img src="https://img.shields.io/github/license/tboox/xmake-idea.svg?colorB=f48041&style=flat-square" alt="license" />
    </a>
  </div>
  <div>
    <a href="https://github.com/xmake-io/xmake-idea/blob/master/LICENSE.md">
      <img src="https://img.shields.io/github/license/tboox/xmake-idea.svg?colorB=f48041&style=flat-square" alt="license" />
    </a>
    <a href="https://www.reddit.com/r/tboox/">
      <img src="https://img.shields.io/badge/chat-on%20reddit-ff3f34.svg?style=flat-square" alt="Reddit" />
    </a>
    <a href="https://gitter.im/tboox/tboox?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge">
      <img src="https://img.shields.io/gitter/room/tboox/tboox.svg?style=flat-square&colorB=96c312" alt="Gitter" />
    </a>
    <a href="https://t.me/tbooxorg">
      <img src="https://img.shields.io/badge/chat-on%20telegram-blue.svg?style=flat-square" alt="Telegram" />
    </a>
    <a href="https://jq.qq.com/?_wv=1027&k=5hpwWFv">
      <img src="https://img.shields.io/badge/chat-on%20QQ-ff69b4.svg?style=flat-square" alt="QQ" />
    </a>
    <a href="http://xmake.io/pages/donation.html#donate">
      <img src="https://img.shields.io/badge/donate-us-orange.svg?style=flat-square" alt="Donate" />
    </a>
  </div>

  <p>A XMake integration in IntelliJ IDEA</p>
</div>

## Introduction

A XMake integration in IntelliJ IDEA.

It is deeply integrated with [xmake](https://github.com/xmake-io/xmake) and Intellij-IDEA to provide a convenient and fast cross-platform c/c++ development and building.

And It also support other Intellij-based platform, like Clion, Android Studio and etc.

You need install [xmake](https://github.com/xmake-io/xmake) first and a project with `xmake.lua`.

Please see [xmake-github](https://github.com/xmake-io/xmake) and [website](http://xmake.io) if you want to known more about xmake.

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
<img src="https://raw.githubusercontent.com/tboox/xmake-idea/master/res/quickstart.gif" width="80%" />
</div>

## Parse errors and goto file

<div align="center">
<img src="https://raw.githubusercontent.com/tboox/xmake-idea/master/res/problem.gif" width="80%" />
</div>

## Output panel

<img src="https://raw.githubusercontent.com/tboox/xmake-idea/master/res/output_panel.png" width="100%" />

## Create project

<img src="https://raw.githubusercontent.com/tboox/xmake-idea/master/res/create_project.png" width="100%" />

## Project configuration

<img src="https://raw.githubusercontent.com/tboox/xmake-idea/master/res/project_configuration.png" width="100%" />

## Run configuration

<img src="https://raw.githubusercontent.com/tboox/xmake-idea/master/res/run_configuration.png" width="100%" />

## Menu tools

<div align="center">
<img src="https://raw.githubusercontent.com/tboox/xmake-idea/master/res/menu.png" width="80%" />
</div>

## C/C++ intellisense

> Only support CLion (>= 2020.1)

1. Click "Update compile commands" to create or update "compile_commands.json" file
2. Click "File > open..." to choose this file.

## Debug

> Only support Clion (>= 2020.1)

1. Click "Update CmakeLists" to create or update "CmakeLists.txt" file.
2. Click "File > open..." to choose this file.
3. Choose "Run > Debug..." or "Run > Debug 'project name'" into debug mode.

## How to contribute?

Due to limited personal time, I cannot maintain this plug-in all the time. If you encounter problems, you are welcome to download the plug-in source code to debug it yourself and open pr to contribute.

### Build this project

Use IDEA Intellji open this project source code, and click `Build` button.

### Run and debug this project

Open and edit `Run configuration`, and add a gradle run configuration, then write run arguments: `runIde --stacktrace` and save it.

<img src="https://raw.githubusercontent.com/tboox/xmake-idea/master/res/edit_configuration.png" width="100%" />

Select this run configuration and click run button to load it.

<img src="https://raw.githubusercontent.com/tboox/xmake-idea/master/res/run_plugin.png" width="20%" />

For more details, please visit: [CONTRIBUTING](https://github.com/xmake-io/xmake-idea/blob/master/CONTRIBUTING.md)

## Powered by

[![JetBrains logo.](https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg)](https://jb.gg/OpenSource)
