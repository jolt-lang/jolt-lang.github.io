# Finding Native Libraries (jolt.configure)

`jolt.configure` finds the shared libraries a project's `:jolt/native` specs name on the machine it runs on, and
records where they are in the project's [`deps.local.edn`](/docs/building-and-deps.html#local_overrides:_deps.local.edn). It does for a Jolt project what `./configure`
does for a C one. The `deps.edn` stays portable (`{:name "sqlite3" :linux ["libsqlite3.so.0"]}`), and the paths that are
true only on this machine (a Homebrew prefix, a Nix store path, `/opt`, a library built into `~/.local`) live in a file
that is not committed.

## Running it from a task

```clojure
{:tasks {configure {:exec-fn jolt.configure/configure!}}}
```

```bash
$ jolt run configure
jolt.configure: 3 native libraries on linux
  sqlite3              found    /lib/x86_64-linux-gnu/libsqlite3.so.0 (system)
  z                    present  (a declared candidate opens)
  pq                   missing  (optional)
wrote /home/me/app/deps.local.edn
```

It looks at every `:jolt/native` library the project resolves to: its own, its dependencies', and what
`deps.local.edn` already says. A library whose declared candidates already open is left alone (`present`). For the rest it
searches, and records each path it finds for the current platform. A required library it cannot find
makes the task exit 1, so a task that `:depends` on `configure` stops there.

| flag | effect |
| --- | --- |
| `--all` | look for every library, including the ones that already open, and pin them all |
| `--dry-run` / `-n` | report what it would record without writing |
| `--no-verify` | take the first matching file without opening it |
| `--file PATH` | write somewhere other than the project's local file |
| `LIB …` | only these library names |

`jolt -m jolt.configure …` takes the same flags, but `jolt -m` opens the project's required libraries before
`-main` runs, so a missing one stops it there. A task run only warns about a missing library, which is why
the task is the way to run it.

The file it writes is ordinary EDN, merged with whatever the file already holds:

```clojure
;; Local overrides for this machine, merged over deps.edn by jolt.
;; Written by jolt.configure; keep it out of version control.
{:jolt/native
 [{:name "sqlite3", :linux ["/lib/x86_64-linux-gnu/libsqlite3.so.0"]}]}
```

## Where it looks

Everything is read by the runtime itself. Nothing runs `pkg-config`, `ldconfig` or `brew`, so discovery
works the same in a container that has none of them. In order:

1. **pkg-config**: the `libdir` (and any `-L` directories in `Libs`) of the `.pc` file for the spec's
   `:pkg-config` package, by default the `:name` and then `lib<name>`. `.pc` files are read from `$PKG_CONFIG_PATH`, then
   `$PKG_CONFIG_LIBDIR` or the usual prefixes' `lib/pkgconfig` and `share/pkgconfig`.
2. **Homebrew** (macOS): `opt/<formula>/lib` under `/opt/homebrew` and `/usr/local`, for the spec's `:brew` formula
   (by default the `:name`).
3. **The spec's `:search` directories**, relative to the `deps.edn` that declared the spec.
4. **The library path**: `$JOLT_LIBRARY_PATH`, `$LD_LIBRARY_PATH`, `$DYLD_LIBRARY_PATH`, `$DYLD_FALLBACK_LIBRARY_PATH`,
   `$LIBRARY_PATH` (and `$PATH` on Windows), `~/.local/lib`, `~/.nix-profile/lib`, the dynamic linker's configured
   directories (`/etc/ld.so.conf` and the files it includes), the multiarch directories, and `/usr/lib`, `/lib`.
5. **Every Homebrew formula's `lib`** (macOS), for a library whose formula is not named after it:
   `libcrypto.3.dylib` belongs to `openssl@3`.

A file counts only if it **opens**, so a library built for another architecture, or one whose own dependencies
are missing, is passed over. Among the files in one directory it prefers a name the spec lists, then the unversioned
name, then the soname (`libz.so.1` over the `libz.so.1.2.13` it links to, so the recorded path survives an
upgrade), then the newest version.

A spec can steer the search with keys older Jolt versions ignore:

```clojure
{:name "crypto"
 :darwin ["libcrypto.3.dylib"]
 :linux  ["libcrypto.so.3"]
 :pkg-config "libcrypto"     ; or a list of package names
 :brew "openssl@3"           ; or a list of formulas
 :search ["vendor/lib"]}     ; directories relative to this deps.edn
```

## Discovery at load time

The same search runs on its own when a library fails to load. A `:jolt/native` candidate that is a bare filename
(`"libcrypto.3.dylib"`, no `/` in it) is tried in this order:

1. beside the `deps.edn` that declared it (a project that keeps the library in its own folder gets that copy)
2. wherever the platform's loader finds it
3. if neither opens it, autodiscovery looks for that exact file in the places above

Discovery runs only after every candidate has failed, so a library that already loads costs nothing extra.
`JOLT_NATIVE_DISCOVER=0` turns it off. Running the `configure` task instead pins the paths, so the next start skips the
search. A `jolt build` binary loads the candidates it was built with, as before.

## Functions

| function | answers |
| --- | --- |
| `(configure! opts)` | the task: `{:all :dry-run :no-verify :file :dir :libs}` → the results it reported |
| `(discover specs opts)` | `{:name :status :path :via :optional}` per spec, `:status` one of `:found`, `:present`, `:missing` |
| `(find-library spec)` / `(find-library spec {:verify? false})` | `{:path :via}` for a library name or spec, or nil |
| `(locate spec filename root)` | the path of that exact file that opens, looking in `root` first, or nil |
| `(pkg-config pkg)` | `{:file :version :libdir :includedir :cflags :libs}` from the package's `.pc` file, or nil |
| `(write-local! results)` | merge the `:found` results into the project's `deps.local.edn` |
| `(project-natives dir)` | the reconciled `:jolt/native` specs the project at `dir` resolves to |
| `(system-dirs)`, `(pkg-config-dirs)`, `(ld-conf-dirs)` | the directories each step searches |
