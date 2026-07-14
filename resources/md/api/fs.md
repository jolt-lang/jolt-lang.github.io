Jolt ships [babashka.fs](https://github.com/babashka/fs) as a built-in
file-system library, over a `java.nio.file` shim. Require it directly, or use
`jolt.fs`, which re-exports the supported subset under a stable public name.

```clojure
(require '[babashka.fs :as fs])

(fs/exists? "deps.edn")                 ; => true
(fs/create-dirs "target/classes")       ; => #object[Path ...]
(fs/glob "src" "**.clj")                ; => seq of Path under src ending .clj
(fs/which "sh")                         ; => #object[Path /bin/sh]
```

Path-valued results are `java.nio.file.Path` values, matching babashka. The
[babashka.fs API docs](https://github.com/babashka/fs) describe every function;
the mental model transfers directly.

## What's covered

The core surface works: coercion (`path`/`file`), predicates (`exists?`,
`directory?`, `regular-file?`, `sym-link?`, `readable?`/`writable?`/
`executable?`, `hidden?`), path pieces (`file-name`, `parent`, `components`,
`extension`, `strip-ext`, `absolutize`, `canonicalize`, `real-path`,
`relativize`, `cwd`), tree reading (`list-dir`, `walk-file-tree`, `glob`,
`match`), creation and deletion (`create-dir(s)`, `create-file`,
`create-temp-file`/`-dir`, `create-sym-link`/`create-link`, `delete`,
`delete-if-exists`, `delete-tree`), copy and move (`copy`, `copy-tree`, `move`),
times (`last-modified-time`/`set-last-modified-time`, `creation-time`), and
POSIX permissions (`posix-file-permissions`/`set-posix-file-permissions`,
`posix->str`/`str->posix`). Symbolic links, creation time, and permissions
work through the shim's `stat`, `realpath`, `symlink`, and `chmod` bindings.

## Gaps

The `zip`/`unzip`/`gzip`/`gunzip` helpers need `java.util.zip`, which Jolt does
not shim yet, so those functions are unavailable. A few edge cases around
attribute preservation on copy remain. Everything else in babashka.fs runs.
