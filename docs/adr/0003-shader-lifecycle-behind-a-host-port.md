# The shader lifecycle sits behind a `ShaderHost` port, with the renderer behind a handle

`ShaderRuntime` owns the shader lifecycle — the config, the scan cache, the prepared renderer and the
resource-reload orchestration — as instance state built from a `ShaderHost` port, rather than as
static fields that reach directly into `Minecraft`, `FabricLoader` and `NativePackRuntime`.

As recovered from the 0.5.1 jar, this was a single class of static fields whose methods took
`Minecraft` as a parameter and called `NativePackRuntime` directly. Every ordering constraint existed
only in the code: the reload flag had to be set before `reloadResourcePacks()`, the candidate renderer
had to be activated only after the reload succeeded, and the previous config had to be written back on
two different failure paths. None of it could be exercised, because there was no way to observe those
transitions without a GPU and a running game — and the `resourceReloading` flag has twenty mixin call
sites depending on it as a gate, so a wrong set/clear sequence silently corrupts a frame rather than
failing loudly. This is the same argument recorded in ADR-0002 against the 0.3.1 design, which 0.3.1
had already won and 0.5.1 had lost again.

The renderer therefore sits behind the port too, as a `PreparedRenderer` handle carrying `activate`
and `close` together. The handle is **never null**, which is the one non-obvious requirement here:
when the config is disabled or the backend is not Vulkan, `NativePackRuntime.prepare` returns null and
the original code still calls `NativePackRuntime.activate(null, id)` — and that is not a no-op, it
clears the active renderer and queues the previous one for closing. That is the only path that tears
down GPU resources when the user switches shaders off, so a null handle would leak them, and no test or
log would show it.

The static surface is kept as a facade over the single `instance`, because mixins are instantiated by
the game and can only reach static members. `resourceReloading()`, `config()`, `shadersEnabled()` and
`packs()` stay static and return a safe answer when no instance is installed yet, matching the initial
values the old static fields had. `install()` is the only static entry point; `uninstall()` is
package-private and exists so a test JVM can return to the "not installed" state, which is what makes
the safe-answer contract verifiable at all.

`MinecraftShaderHost` is deliberately nothing but a one-line delegation per method. It is the one place
where drift is possible without any test noticing, since the tests only ever see a fake; any edit to it
that changes the *order* or *arguments* of those calls is a behaviour change, not a refactor.

Known gap, accepted rather than solved: the port is covered by sixteen lifecycle tests, and the
refactored build was run on Vulkan through a full in-world session with no Caldera warnings or errors —
but no side-by-side screenshot against the reference build was captured, so "renders the same picture"
remains unverified. The failure mode this leaves open is the quiet one: a build that loads, runs
cleanly and draws nothing.
