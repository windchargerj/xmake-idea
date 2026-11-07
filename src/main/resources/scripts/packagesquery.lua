--!A Xmake integration in IntelliJ IDEA/Clion
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- Copyright (C) 2015-present, Xmake Open Source Community.

-- One spawn, two answers. The plugin asks one package question per selection;
-- instead of two CLI invocations (require --info, require --scan), each with its
-- own flag surface that moved between xmake versions, this script calls the same
-- action modules the CLI itself calls - private.action.require.info and .scan -
-- so the answers carry no flag-version sensitivity at all. The plugin reads the
-- whole output with its existing text parsers; the markers bracket the payload,
-- the way probeservices.lua brackets its record grammar.
--
-- usage: xmake l packagesprobe.lua <package>
--        e.g. xmake l packagesprobe.lua nlohmann_json

function main(asked)
    print("__packages_probe_begin__")
    import("private.action.require.info", {alias = "require_info"})
    import("private.action.require.scan", {alias = "require_scan"})
    require_info.main({asked})
    require_scan.main({asked})
    print("__packages_probe_end__")
end
