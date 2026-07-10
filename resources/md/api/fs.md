`jolt.fs` is a file-system utility library over `java.io.File`. Its API mirrors [babashka.fs](https://github.com/babashka/fs) where the two overlap, so the mental model transfers directly if you know babashka. Functions accept a `String` or a `File`; path-valued results are `File`s (Jolt's path value — there is no `java.nio.file.Path`). A handful of operations that need `Path`-level access — symbolic links and creation time — are not available on this host and throw a clean `ex-info`.

```clojure
(require '[jolt.fs :as fs])

(fs/exists? "deps.edn")                       ; => true
(fs/create-dirs "target/classes/jolt")        ; => #object[File ...]
(fs/glob "src" "**.clj")                      ; => seq of File under src ending .clj
(fs/which "sh")                               ; => #object[File /bin/sh]
```

## Coercion and nesting

- `file` `f` — coerce a `String`/`File` to a `File`. With several args, nests: `(file a b c)` is `a/b/c`.

## Predicates

Each takes a path and returns a boolean.

- `exists?`, `directory?`, `regular-file?`, `absolute?`, `relative?`
- `readable?`, `writable?`, `executable?`, `hidden?`
- `sym-link?` — **not supported** on this host; throws `ex-info`.

## Path pieces

- `file-name` `f` — final path segment as a string.
- `parent` `f` — parent as a `File`; `nil` at a root or a bare name.
- `extension` `f` — extension without the dot; `nil` when there is none.
- `strip-ext` `f` — file name without its extension.
- `absolutize` `f` — absolute `File` (resolves against CWD, no symlink walk).
- `canonicalize` `f` — canonical `File` (resolves `.`/`..` and symlinks).
- `cwd` `[]` — current working directory as a `File`.
- `relativize` `root other` — path of `other` relative to `root`, as a `File`. Throws when `other` is not nested under `root`.

```clojure
(fs/extension "src/jolt/core.clj")            ; => "clj"
(fs/extension "Makefile")                     ; => nil
(fs/relativize "/a" "/a/b/c")                 ; => #object[File "b/c"]
```

## Reading the tree

- `list-dir` `dir` — immediate children as a (possibly empty) seq of `File`s.
- `walk` `root` — depth-first seq of every file and directory under `root`, `root` first.
- `glob` `root pattern` — files under `root` whose path relative to `root` matches a glob. `**` crosses directory separators, `*` and `?` stay within a segment, `{a,b}` alternates. Matches against the `/`-separated relative path; returns `File`s.

```clojure
(fs/glob "src" "**.clj")                      ; every .clj under src, recursively
(fs/glob "test" "*.{clj,ss}")                 ; top-level clj/ss files in test/
```

## Creation and deletion

- `create-dir` `dir` — one directory level; the parent must exist. Returns the `File`, throws if it can't be created.
- `create-dirs` `dir` — a directory and any missing parents. Returns the `File`.
- `create-file` `f` — an empty file. Returns the `File`.
- `create-temp-dir` `[{:keys [prefix] :or {prefix "jolt-"}}]` — fresh directory under the system temp dir.
- `create-temp-file` `[{:keys [prefix suffix] :or {prefix "jolt-" suffix ".tmp"}}]` — fresh file under the system temp dir.
- `delete` `f` — a file or empty directory; throws when it doesn't exist.
- `delete-if-exists` `f` — delete when present; `true` when something was deleted.
- `delete-tree` `root` — a file or directory recursively. A missing path is a no-op.

## Copy and move

- `size` `f` — file size in bytes.
- `copy` `src dst [{:keys [replace-existing]}]` — copy a regular file to `dst`. Throws if `src` is a directory (use `copy-tree`) or if `dst` exists without `:replace-existing`. Returns `dst`.
- `copy-tree` `src dst [opts]` — copy a directory tree recursively. Returns `dst`.
- `move` `src dst [{:keys [replace-existing]}]` — move (rename) `src` to `dst`. Falls back to copy-then-delete for a regular file when rename fails (cross-device). Returns `dst`.

## Times

- `last-modified-time` `f` — last-modified time as a `java.time.Instant`.
- `set-last-modified-time` `f inst` — set the last-modified time from an `Instant`.
- `creation-time` `f` — **not supported** on this host; throws `ex-info`.

## Lookup

- `which` `nm` — the first executable named `nm` on `PATH`, as a `File`, else `nil`.
- `read-link` `f` — **not supported** on this host; throws `ex-info`.
