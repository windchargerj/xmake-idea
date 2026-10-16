-- Runs a target under an external analysis tool, from inside the xmake environment.
--
-- usage: xmake l runtool.lua <targetname> -- <tool> [tool arguments with {report} and {binary}]
--
-- The tool and its arguments come from the IDE. Placeholders:
--   {report}  the report path; the tool writes it to the native temp directory
--             first (some tools cannot write onto mounted drives), and the file
--             is copied to XMAKE_RUNTOOL_REPORT (project-relative) afterwards
--   {binary}  the target's executable; when absent it is appended at the end
-- The target binary and its run directory are resolved by xmake itself, which
-- keeps every path in the environment xmake actually runs in.
import("core.project.config")
import("core.project.project")

function main(targetname, sep, tool, ...)

    -- load config
    config.load()
    if not os.isfile(os.projectfile()) then
        return
    end

    -- resolve the target the same way the run action does
    local target = nil
    if targetname then
        target = project.target(targetname)
    end
    if not target then
        for _, t in pairs(project.targets()) do
            local default = t:get("default")
            if (default == nil or default == true) and t:get("kind") == "binary" then
                target = t
                break
            end
        end
    end
    assert(target, "runtool: target not found: " .. (targetname or "<default>"))

    local binary = target:targetfile()
    if not path.is_absolute(binary) then
        binary = path.absolute(binary, os.projectdir())
    end

    local rundir = target:rundir()
    if rundir and not path.is_absolute(rundir) then
        rundir = path.absolute(rundir, os.projectdir())
    end
    rundir = rundir or path.directory(binary)

    -- the final report location is project-relative on the IDE side; the tool
    -- writes to the native temp directory and the file is copied back after the
    -- run, so tools that cannot write onto mounted drives still work
    local report_final = os.getenv("XMAKE_RUNTOOL_REPORT")
    local report_for_tool = nil
    if report_final and not path.is_absolute(report_final) then
        report_final = path.absolute(report_final, os.projectdir())
    end
    if report_final then
        report_for_tool = path.join(os.tmpdir(), path.filename(report_final))
        if os.isfile(report_for_tool) then
            os.rm(report_for_tool)
        end
    end

    -- build the tool argument list: substitute the placeholders, append the
    -- binary when the template did not place it explicitly
    assert(tool, "runtool: no tool given")
    local toolargs = {}
    local binary_placed = false
    for _, arg in ipairs({...}) do
        if report_for_tool then
            arg = (arg:gsub("{report}", report_for_tool))
        end
        if arg == "{binary}" then
            table.insert(toolargs, binary)
            binary_placed = true
        else
            table.insert(toolargs, arg)
        end
    end
    if not binary_placed then
        table.insert(toolargs, binary)
    end

    os.cd(rundir)
    os.execv(tool, toolargs, { curdir = rundir })
    -- the sandbox has no pcall; narration is plain prints, errors end the script visibly
    print("[runtool] tool finished")

    -- bring the report back to the project tree where the IDE can see it
    if report_for_tool and os.isfile(report_for_tool) then
        os.cp(report_for_tool, report_final)
        print("[runtool] report copied: " .. tostring(report_final) .. " exists=" .. tostring(os.isfile(report_final)))
        os.rm(report_for_tool)
    else
        print("[runtool] tool produced no report at " .. tostring(report_for_tool))
    end

end
