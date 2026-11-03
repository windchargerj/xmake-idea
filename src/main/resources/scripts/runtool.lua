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

    -- optional: fold a perf recording into the collapsed-stacks format the IDE
    -- profiler view consumes (XMAKE_RUNTOOL_COLLAPSE, project-relative)
    local collapse_final = os.getenv("XMAKE_RUNTOOL_COLLAPSE")
    if collapse_final and report_final and os.isfile(report_final) then
        if not path.is_absolute(collapse_final) then
            collapse_final = path.absolute(collapse_final, os.projectdir())
        end
        fold_perf_samples(report_final, collapse_final)
        print("[runtool] folded stacks written: " .. tostring(collapse_final))
    else
        print(("[runtool] folding skipped: collapse=%s report=%s exists=%s"):format(
            tostring(collapse_final), tostring(report_final),
            tostring(report_final and os.isfile(report_final))))
    end
end

-- Reads `perf script` output and writes folded stacks: one `frame;frame;... count`
-- line per sample. Header lines (command pid/tid timestamp) start a sample, indented
-- lines are frames with the leaf first, blank lines end the sample.
function fold_perf_samples(datafile, outfile)
    local counts = {}
    local stack = {}
    -- the xmake lua sandbox has no io.popen; os.iorun captures the output instead
    -- (it returns stdout first, stderr/exit info second). gmatch splits the lines; a
    -- gsub-with-callback silently produces nothing inside this sandbox.
    local stdout = os.iorun("perf script -i " .. datafile)
    for line in string.gmatch(stdout or "", "[^\r\n]+") do
        if line:find("^[ \t]%S") then
            local symbol = line:match("^[ \t]+%S+[ \t]+([^ \t]+)") or line:match("^[ \t]+([^ \t]+)")
            if symbol then
                table.insert(stack, 1, symbol)
            end
        else
            -- a header line starts the next sample: fold what collected before it. The
            -- blank separator is optional in some perf script builds, so the header and
            -- a blank line end the sample the same way.
            if #stack > 0 then
                local key = table.concat(stack, ";")
                counts[key] = (counts[key] or 0) + 1
                stack = {}
            end
        end
    end
    if #stack > 0 then
        local key = table.concat(stack, ";")
        counts[key] = (counts[key] or 0) + 1
    end
    local out = io.open(outfile, "w")
    if out then
        for key, count in pairs(counts) do
            out:write(key, " ", count, "\n")
        end
        out:close()
    end
    local total = 0
    for _ in pairs(counts) do total = total + 1 end
end
