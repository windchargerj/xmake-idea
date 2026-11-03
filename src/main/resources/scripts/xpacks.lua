-- Enumerates the xpack() packages the project declares, one `name<TAB>formats` line each.
--
-- usage: xmake l xpacks.lua
--
-- The formats column comes from the xpack's own set_formats declaration; an xpack without
-- one prints an empty column (xmake then packs every format it supports for it). The IDE's
-- pickers read the marked payload only, so the output is wrapped in __begin__/__end__
-- markers (the targetpath.lua trick that ignores anything xmake logs to stdout ahead of
-- it). The project must include the xpack addon - `includes("@builtin/xpack")` - for
-- xpack() to exist; the script fails otherwise, exactly like the project load itself does.

-- imports
import("core.project.project")

function main (...)

    -- denote the start of IDE information to ignore anything logging to stdout before this point
    print("__begin__")

    for name, scope in pairs(project.scope("xpack")) do
        local formats = table.concat(scope:get("formats") or {}, ",")
        print(name .. "\t" .. formats)
    end

    -- print end tag to ignore other deprecated/warnings infos
    print("__end__")
end
