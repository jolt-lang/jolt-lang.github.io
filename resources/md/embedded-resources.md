# Embedded Resources

A binary built with `jolt build` carries its resources inside it: the
directories named by `:jolt/build {:embed [...]}` in the project's `deps.edn`,
and the resources of its dependencies, the way an uberjar packs them (see
[Building & Running](building-and-deps.html#deps.edn_build_options)).
`io/resource` serves them with no file on disk.

Code written for the JVM finds resources inside a jar through `java.net.URL`,
`java.util.jar.JarFile` and the zip `FileSystem`, and that code runs unchanged:
the embedded resources read as a jar named `jolt-embedded-resources.jar`, which
is never a file on the host. A migration scanner such as Migratus discovers
migrations inside a built binary without extracting anything.

## Listing resources

`(jolt.host/embedded-resource-names)` returns a sorted vector of the project and
dependency resource names baked into the image, excluding jolt's own runtime
files. Each name can be passed to `io/resource`:

```clojure
(require '[clojure.string :as str])

(filter #(str/starts-with? % "migrations/")
        (jolt.host/embedded-resource-names))
```

## Resource URLs

`io/resource` and `ClassLoader.getResource` / `getResources` answer the `jar:`
URL a class loader answers for an entry inside a jar:

```clojure
(str (io/resource "migrations/001 init.sql"))
;; => "jar:file:jolt-embedded-resources.jar!/migrations/001%20init.sql"
```

- A directory resolves too: `(io/resource "migrations")` is
  `jar:file:jolt-embedded-resources.jar!/migrations`, and `getResources` lists it.
  A directory exists only by holding a resource, so with nothing embedded there
  is none, and the empty name answers `nil`, as it does for a jar.
- Names are percent-encoded the way the JDK's class loader encodes them:
  UTF-8, lowercase escapes, with `;` `=` `[` `]` and space escaped.
- In a multi-release archive (`Multi-Release: true` in `META-INF/MANIFEST.MF`)
  a resource URL names the entry the runtime version selects, for example
  `META-INF/versions/17/greeting.txt`, as a class loader does on JDK 9 and later.
- `openConnection` answers a `JarURLConnection`: entry name, `JarFile`, `JarEntry`,
  manifest and attributes, content and length. Output is refused.

## `JarFile`

`(java.util.jar.JarFile. "jolt-embedded-resources.jar")`, from a String or a
`File`, with any of the mode, charset and version constructors, opens the
archive:

- `entries` and `stream` list every resource and a directory entry for each
  directory, in name order; `getEntry`, `getJarEntry` and `getInputStream` read
  them.
- `getManifest` returns the parsed `META-INF/MANIFEST.MF`, the same `Manifest`
  on every call.
- A jar opened at a version above 8 reads `META-INF/versions/N/name` for the
  highest `N` from 8 up to that version, and `versionedStream` lists the names
  that version sees. `JarFile/runtimeVersion` and `Runtime/version` report the
  Java version jolt answers as, the same one `java.version` and
  `java.specification.version` report.
- Entries carry zero times and no certificates; `close` is idempotent and every
  later call refuses.

`java.util.jar.Manifest`, `Attributes` and `Attributes.Name` read and write as the
JDK's do: attributes keep insertion order, `getEntries` is the live map of
sections, a `Name:` line inside the main section is a main attribute, lines may
end in `\r\n`, `\n` or `\r`, and `write` wraps at 72 bytes and ends every
section with a blank line.

## The `jar` file system

```clojure
(def fs (java.nio.file.FileSystems/newFileSystem
          (java.net.URI. "jar:file:jolt-embedded-resources.jar") {}))
(java.nio.file.Files/readString (.getPath fs "/migrations/001 init.sql" (make-array String 0)))
```

The file system is read-only and has one root, `/`. Its paths follow the zip
file system: `/` separates, `\` is an ordinary character, and `toUri` answers
`jar:file:jolt-embedded-resources.jar!/...` with the characters a URI cannot
hold escaped; `Path/of` reads such a URI back.

Every `java.nio.file.Files` operation dispatches to the provider of the path it
is given, as on the JDK, so the operations below answer for embedded paths and
host paths alike:

| Operations | Embedded paths |
|---|---|
| `newInputStream`, `newBufferedReader`, `readAllBytes`, `readString`, `readAllLines`, `lines` | the resource's bytes; text is decoded strictly, so malformed input raises `MalformedInputException` |
| `newByteChannel`, `FileChannel/open` | read, position and size; write and truncate refuse |
| `newDirectoryStream`, `list`, `walk`, `find`, `walkFileTree` | children in name order, spelled under the directory's own spelling |
| `exists`, `isDirectory`, `isRegularFile`, `size`, `readAttributes`, `getAttribute`, `getLastModifiedTime` | the `basic` attributes; times are zero |
| `getFileAttributeView` | `basic` only; `setTimes` refuses |
| `getFileStore` | a read-only store named `jolt-embedded-resources` |
| `copy`, `move`, `mismatch`, `isSameFile` | a copy to a host path or an output stream reads the resource; between file systems a copy checks the target first, as `CopyMoveHelper` does |
| `createDirectory`, `delete`, `write`, `writeString`, `setOwner` and every other write | `ReadOnlyFileSystemException` |
| `getOwner`, `getPosixFilePermissions` | `UnsupportedOperationException`: the archive has no owners |

Watch services and keys exist and never report an event; there are no user
principals to look up.

## How it is tested

`make embeddedresources` builds fixture apps and runs them from `/`, so no file
on disk can answer for a resource. The JDK's answers for the same resources,
read from a real jar through `JarFile`, its versions, its manifest and a
`URLClassLoader`, are recorded in
[`test/chez/embedded-resource-oracle.edn`](https://github.com/jolt-lang/jolt/blob/main/test/chez/embedded-resource-oracle.edn)
and compared on every run, with no JVM needed.
`sh test/chez/embedded-resource-oracle.sh --record` re-records them from a JDK
and audits the
[signature ledger](https://github.com/jolt-lang/jolt/blob/main/test/chez/embedded-resource-api-coverage.edn),
which names the test covering each public method of the classes above.
`make libconformance` also runs upstream Migratus's migration discovery inside
a built binary.
