-- Sets or clears one xmake service kind's endpoint, on the toolkit's own host.
--
-- usage: xmake l setservice.lua
--
-- The IDE's endpoint editor writes through this script instead of touching any file
-- itself: the project config directory - and with it the project-local client.conf -
-- lives on the toolkit's host (a plain path on local toolkits, a WSL/SSH one
-- otherwise), so the write must run inside xmake on the executor channel, exactly
-- like the probe (probeservices.lua) runs its reads.
--
-- Inputs arrive as environment variables (the runtool.lua trick - a plain script
-- cannot receive the IDE's quoting-safe arguments through every host channel):
--
--   XMAKE_SERVICE_SET_KIND      the kind's status directory name, one of
--                               remote_build / distcc_build / remote_cache
--   XMAKE_SERVICE_SET_ENDPOINT  "addr:port" to set, or empty to clear the entry
--
-- What gets written, following xmake's own config shapes (the same ones the probe
-- reads, see probeservices.lua's header):
--
--   * project-local client.conf (project-local wins over the global one):
--     remote_cache keeps its single-connect shape ({connect = ...}), the
--     multi-host kinds keep their hosts list and get its first entry - the one
--     the client effectively talks to - pointed at the new address. Other kinds'
--     entries and every other field (token, name, fallback hosts) survive.
--   * status.txt of the kind is reset to xmake's own post-failed-connect shape
--     ({connected = false}): the persisted address/session/token describe the
--     *previous* endpoint, so keeping them would leave the client reporting a
--     connection it can no longer have, and the address wins over client.conf on
--     the next connect (probeservices.lua reads it the same way).
--
-- Output is a short human-readable line per change; failures print an error and
-- leave a non-zero exit to the command channel.

-- imports
import("core.project.config")

function main ()

    -- load config (the config directory env decides which profile's conf this is)
    config.load()
    if not os.isfile(os.projectfile()) then
        raise("setservice: no xmake.lua in the working directory")
    end

    local kind = os.getenv("XMAKE_SERVICE_SET_KIND")
    local endpoint = os.getenv("XMAKE_SERVICE_SET_ENDPOINT")
    if not kind then
        raise("setservice: XMAKE_SERVICE_SET_KIND is not set")
    end

    local confdir = config.directory()
    local conffile = path.join(confdir, "service", "client.conf")

    -- merge into whatever is already there: one kind's entry must not erase the others
    local configs = {}
    if os.isfile(conffile) then
        configs = io.load(conffile) or {}
    end

    if endpoint == nil or endpoint == "" then
        if configs[kind] == nil then
            print(string.format("setservice: %s has no project-local endpoint entry, nothing to clear", kind))
            return
        end
        configs[kind] = nil
        os.mkdir(path.directory(conffile))
        io.save(conffile, configs)
        _reset_status(confdir, kind)
        print(string.format("setservice: cleared the %s endpoint in %s", kind, conffile))
        return
    end

    -- keep xmake's own shapes: single-connect for remote_cache, hosts list otherwise
    local entry = configs[kind] or {}
    if kind == "remote_cache" then
        entry.connect = endpoint
    else
        entry.hosts = entry.hosts or {}
        if entry.hosts[1] == nil then
            entry.hosts[1] = {}
        end
        entry.hosts[1].connect = endpoint
    end
    configs[kind] = entry
    os.mkdir(path.directory(conffile))
    io.save(conffile, configs)
    _reset_status(confdir, kind)
    print(string.format("setservice: set the %s endpoint to %s in %s", kind, endpoint, conffile))
end

-- resets the kind's persisted connection state to xmake's own post-failed-connect
-- shape; the previous record belongs to the previous endpoint
function _reset_status (confdir, kind)
    local statusfile = path.join(confdir, "service", kind, "status.txt")
    os.mkdir(path.directory(statusfile))
    io.save(statusfile, {connected = false})
end
