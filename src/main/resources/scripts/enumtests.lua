-- Lists every test the project defines, one `target/test<TAB>group` line each.
--
-- usage: xmake l enumtests.lua
--
-- The group comes from the test's own extraconf when set, then from the target's;
-- xmake test accepts it as `-g <group>` on the filtering side. Verified against
-- live projects: ordertargets() returns targets in project order, and a target
-- without tests contributes nothing.
import("core.project.config")
import("core.project.project")

function main(...)
    config.load()
    for _, target in ipairs(project.ordertargets()) do
        for _, name in ipairs(target:get("tests") or {}) do
            local extra = target:extraconf("tests", name) or {}
            local group = extra.group or target:get("group") or ""
            print(target:name() .. "/" .. name .. "\t" .. group)
        end
    end
end
