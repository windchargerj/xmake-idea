-- Probes the build cache and the xmake service state of the current project.
--
-- usage: xmake l probeservices.lua
--
-- The IDE runs this one probe instead of inspecting the toolkit host itself: the
-- cache option/program and the per-service state files live under the project
-- config directory, which on WSL/SSH toolkits is on the other host, and every
-- xmake interaction must ride the executor channel anyway. One run answers
-- machine-readably:
--
--   * the build cache: the persisted `ccache` option plus the cache program xmake
--     actually resolves ("ccache, else sccache"). The option alone overpromises --
--     xmake labels jobs "cache compiling" even with no cache tool installed.
--   * every service kind (remote_build, distcc_build, remote_cache): the endpoint
--     the client would talk to and the persisted `connected` flag from status.txt,
--     plus a fresh, deliberately short TCP reachability probe. Persisted and
--     reachable are reported apart: "connected (persisted) but unreachable now"
--     is exactly the state after a build server rebooted.
--
-- The endpoint follows xmake's own client: the address persisted in status.txt
-- wins, else the first host of client.conf (project-local wins over global).
--
-- Output is one record per line, wrapped in __begin__/__end__ markers (the
-- targetpath.lua trick that ignores anything xmake logs to stdout ahead of the
-- payload):
--
--   ccache-enabled: true
--   cache-program: C:\tools\ccache\ccache.exe   (or: none)
--   service: kind=remote_build endpoint=127.0.0.1:9691 connected=true reachable=no token=yes
--
-- `endpoint=none` means no endpoint is configured for that kind and
-- `reachable=untested` means there was nothing to connect to. `token=yes` means a
-- token is stored for the endpoint (persisted by the last connect, or configured in
-- client.conf); without one, `xmake service --connect` prompts for a password on
-- stdin, which the IDE's command channel cannot answer. remote_build and distcc_build
-- accept several hosts in client.conf; only the effective one (what the client
-- connects to) is reported here.

-- imports
import("core.base.socket")
import("core.project.config")
import("private.tools.ccache", {alias = "cachetool"})
import("private.service.client_config", {alias = "clientconf"})

-- reachability probe timeout: much shorter than the client's own 10 s connect
-- default, the panel polls and must stay snappy
local REACHABLE_TIMEOUT_MS = 2000

-- the service kinds xmake ships, in the client.conf order
local SERVICE_KINDS = {"remote_build", "distcc_build", "remote_cache"}

-- split "addr:port" into address and numeric port
function _addr_and_port(endpoint)
    local parts = endpoint:split(":", {plain = true})
    if #parts ~= 2 then
        return nil, nil
    end
    local port = tonumber(parts[2])
    if not port then
        return nil, nil
    end
    return parts[1], port
end

-- read the persisted status of [kind] (a plain io.load of status.txt; the full
-- client classes are not instantiated -- connecting side effects stay out of a probe)
function _status_of(kind)
    local statusfile = path.join(config.directory(), "service", kind, "status.txt")
    if os.isfile(statusfile) then
        return io.load(statusfile) or {}
    end
    return {}
end

-- the endpoint the client of [kind] would talk to, or nil when unconfigured;
-- also answers _endpoint_token with the token configured for that endpoint, if any
function _endpoint_of(kind, status)
    -- the address persisted by the last connect wins (xmake's client does the same)
    if status.addr and status.port then
        return status.addr .. ":" .. tostring(status.port), status.token
    end
    if not os.isfile(clientconf.configfile()) then
        return nil, nil
    end
    local configs = io.load(clientconf.configfile())
    local conf = configs and configs[kind]
    if not conf then
        return nil, nil
    end
    -- single-connect format (remote_cache) and old flat format
    if conf.connect then
        return conf.connect, conf.token
    end
    -- the hosts-list format connects to the first host by default
    local host = conf.hosts and conf.hosts[1]
    if host and host.connect then
        return host.connect, host.token
    end
    return nil, nil
end

-- print one service record with a fresh reachability probe
function _report_service(kind)
    local status = _status_of(kind)
    local endpoint, token = _endpoint_of(kind, status)
    if not endpoint then
        print(string.format("service: kind=%s endpoint=none connected=%s reachable=untested token=no",
            kind, tostring(status.connected == true)))
        return
    end
    local reachable = "untested"
    local addr, port = _addr_and_port(endpoint)
    if addr and port then
        local sock, errors = socket.connect(addr, port, {timeout = REACHABLE_TIMEOUT_MS})
        reachable = sock and "yes" or "no"
        if sock then
            sock:close()
        end
    end
    print(string.format("service: kind=%s endpoint=%s connected=%s reachable=%s token=%s",
        kind, endpoint, tostring(status.connected == true), reachable, token and "yes" or "no"))
end

-- run [task] and swallow its errors: one failing record must not take the rest down
function _best_effort(task)
    try
    {
        function ()
            task()
        end,
        catch
        {
            function (errors)
                -- leave the record out; the missing line is the observable failure
            end
        }
    }
end

function main ()

    -- load config
    config.load()
    if not os.isfile(os.projectfile()) then
        return
    end

    -- denote the start of probe information to ignore anything logging to stdout before this point
    print("__begin__")

    -- the persisted option (nil keeps xmake's on-by-default), then the tool it resolves to
    print(string.format("ccache-enabled: %s", tostring(config.get("ccache") ~= false)))
    local cache_program = "none"
    _best_effort(function ()
        local tool = cachetool.get()
        if tool and tool.program then
            cache_program = tool.program
        end
    end)
    print(string.format("cache-program: %s", cache_program))

    -- TODO: cache statistics (ccache --print-stats / sccache --print-stats, run on the
    -- toolkit's own host from here) are deliberately not probed yet: no cache tool was
    -- installed on the development machine, so the output formats could not be verified.
    -- Design them per docs/roadmap-designs/build-services.md section 2 and validate on a
    -- host with a cache tool before surfacing hit rates in the panel.

    -- one record per service kind
    for _, kind in ipairs(SERVICE_KINDS) do
        _best_effort(function ()
            _report_service(kind)
        end)
    end

    -- print end tag to ignore other deprecated/warnings infos
    print("__end__")
end
