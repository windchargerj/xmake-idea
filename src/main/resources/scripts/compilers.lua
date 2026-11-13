-- imports
import("core.project.config")
import("core.project.project")
import("core.tool.compiler")
import("core.base.json")

-- Print the compiler program of every target that builds compilable sources.
-- The compile-commands generator selects its clang-only include flags from the
-- compiler, so one query over all targets answers that question at once.
function main ()

    -- load config
    config.load()
    if not os.isfile(os.projectfile()) then
        return
    end

    local programs = {}
    local seen = {}
    local sourcekinds = {c = true, cxx = true, objc = true, objcxx = true, cu = true}
    local rulenames = {
        ["c.build"] = true,
        ["c++.build"] = true,
        ["cuda.build"] = true,
        ["objc.build"] = true,
        ["objc++.build"] = true,
    }
    for _, target in pairs(project.targets()) do
        for _, sourcebatch in pairs(target:sourcebatches()) do
            if sourcekinds[sourcebatch.sourcekind] and rulenames[sourcebatch.rulename] then
                local sourcefile = sourcebatch.sourcefiles and sourcebatch.sourcefiles[1]
                local objectfile = sourcebatch.objectfiles and sourcebatch.objectfiles[1]
                if sourcefile and objectfile then
                    -- compargv returns the program followed by its arguments;
                    -- only the program identifies the compiler family.
                    local program = compiler.compargv(sourcefile, objectfile, {
                        target = target,
                        sourcekind = sourcebatch.sourcekind,
                        rawargs = true
                    })
                    if program then
                        program = tostring(program)
                        if not seen[program] then
                            seen[program] = true
                            table.insert(programs, program)
                        end
                    end
                end
            end
        end
    end
    table.sort(programs)

    -- denote the start and end of the payload to ignore anything logging to
    -- stdout before or after this point
    print("__begin__")
    print(json.encode(programs))
    print("__end__")
end
