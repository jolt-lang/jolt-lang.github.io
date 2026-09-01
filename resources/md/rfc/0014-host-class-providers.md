# RFC 0014 — Host classes are provided by declaration, not by a list in the runtime

- **Status**: Implemented
- **Champions**: jolt maintainers

## Summary

A JVM library reaches for `MessageDigest/getInstance` or `java.sql.ResultSet`
without requiring an install namespace first, because on the JVM the class is
simply *there*. Jolt has no such class, so something must load the library that
emulates it — and it must happen on the *first reference*, before anything of
that library has loaded.

The runtime used to answer that with a hardcoded table. `host-static.ss` named
`jolt.crypto`, `io.github.jolt-lang/jolt-crypto`, `jolt.socket`, `jolt.time`
and every class each of them registers, in both the simple and fully-qualified
spelling. Roughly 140 of that file's 552 lines were this bookkeeping, in two
parallel mechanisms — a bespoke one for `java.time` and a general
`lib-class-providers` list — invoked side by side at five call sites, plus a
sixth copy in the build path whose comment said it "mirrors the runtime
predicates".

This RFC replaces both with one mechanism: **a library declares the host classes
it provides in its own `deps.edn`; jolt collects those declarations through the
dependency walk it already performs; the runtime consults the collected table.**
Core names no library.

> The immediate trigger was jolt-lang/jolt#810, which added a `java.sql` entry
> for jolt-lang/db and was reverted in #812. The entry was correct by the
> existing design and still wrong to merge: the language does not get to know
> what `db.jdbc-shim` is.

## The problem

The old design conflated two responsibilities and hardcoded both:

1. **Lazy autoload.** On an unresolved class, load a namespace that may register
   it. This must be lazy — a program that never hashes anything should not pay
   for loading OpenSSL.
2. **Diagnostics.** When the class is still unresolved, name the coordinate to
   add to `deps.edn`.

Both are keyed off one literal mapping `class name → (install-ns, coordinate)`.
The consequences are not stylistic:

- **Core references specific libraries.** The language's runtime contains the
  string `io.github.jolt-lang/jolt-crypto`.
- **Exactly one provider per class, chosen by the runtime.** There is no way to
  supply a different `java.sql` implementation, or any implementation of a class
  the list does not mention. Providers are not swappable, which is the part that
  makes this an architectural problem rather than an untidy one.
- **Adding a provider requires a jolt release.** A library cannot become
  autoloadable on its own schedule.
- **The list is hand-synchronised, and drifts.** The source says so directly:

  > This list has to name EVERY class jolt-lang/time registers, in both the
  > simple and the fully-qualified spelling. […] a name missing here fails only
  > for the IMPORTED SIMPLE form — which is how libraries actually write it, and
  > why `DateTimeFormatterBuilder` being absent kept malli's `transform.cljc`
  > from loading while `(java.time.format.DateTimeFormatterBuilder.)` worked.
  > When the library grows a class, add it here.

  A comment instructing a human to keep a table in sync, with a recorded
  instance of the bug it causes, is the design asking to be replaced.

## The constraint any solution must respect

The miss happens **before** the provider has loaded. That is the entire purpose
of the mechanism, and it rules out the obvious inversion: a library cannot
register itself as a provider *when it loads*, because nothing has loaded it.

So the class → provider mapping has to be **discoverable without loading the
provider**, while the load itself stays lazy. Any design that satisfies only the
first half degenerates into eagerly loading every candidate provider at startup.

## Design

Two phases, mirroring how `:jolt/native` already works.

### Phase 1 — declaration (data, discoverable ahead of load)

A library declares what it provides, in its own `deps.edn`:

```clojure
;; jolt-lang/db
{:jolt/provides {db.jdbc-shim ["java.sql.ResultSet"
                               "java.sql.Connection"
                               "java.sql.Statement"
                               "java.sql.DriverManager"]}}
```

```clojure
;; jolt-lang/jolt-crypto
{:jolt/provides {jolt.crypto ["java.security.MessageDigest"
                              "javax.crypto.Mac"
                              "javax.crypto.Cipher"
                              "javax.crypto.spec.SecretKeySpec"
                              "javax.crypto.spec.IvParameterSpec"]}}
```

Fully-qualified names only. The runtime derives the simple spelling, which
deletes the hand-sync failure mode described above.

`jolt.deps` already walks every dependency's `deps.edn` and collects
`:jolt/native` and `:jolt/min-version` this way (`deps.clj`, the `:natives` and
`:min-versions` keys of the resolve result). `:jolt/provides` is the same shape,
collected as `[install-ns lib class …]` rows, where `lib` is the coordinate of
the declaring dependency (`nil` for the project's own declaration).

### Phase 2 — registration (behaviour, at load)

Unchanged. The install namespace calls `__register-class-statics!`,
`__register-class!`, `jolt.host/register-class-supers!` and friends when it
loads, exactly as today.

### The runtime side

`lib-class-providers` stops being a literal and becomes a table populated at
startup through a host seam, symmetric with `jolt.host/set-source-roots!`:

```clojure
(jolt.host/register-class-provider! install-ns coordinate ["java.sql.ResultSet" …])
```

`lib-provider-for`, `lib-try-autoload!` and the `'ok`/`'failed` latch keep their
current logic — only the source of the table changes. `jolt build` bakes the
collected table into the binary the way `encode-natives` bakes natives.

The bespoke `jt-*` path collapses into this: jolt-lang/time declares
`:jolt/provides`, and `jt-base-names`, `jt-library-names`,
`java-time-prefixed?`, `jt-base-autoload-done` and the `java.time` branch of
`unknown-class-message` are deleted. One mechanism, not two.

### What core keeps

Core still owns the classes **it** implements: the `java.time` value types
(RFC 0008, installed by `jolt.time.base`) and `jolt.socket`'s `java.net`
surface. Those are jolt's own stdlib, not third-party libraries, and core
declaring its own stdlib is not the thing this RFC objects to.

They are a `core-class-providers` list seeding the same table the declarations
append to, so there is one code path and one lookup. Their entries carry **no
coordinate** — there is no dependency for a caller to add, and the autoload
always finds them.

### What this buys

- Core references no library.
- Providers are swappable: whichever dependency claims a class provides it. A
  different `java.sql` implementation needs no change to jolt.
- Adding a provider needs no jolt release.
- Errors stop naming libraries. See *Diagnostics* below — this is a deliberate
  trade, not a free win.
- Conflicts become visible. Two dependencies claiming the same class is a
  detectable condition instead of silent first-wins.

## Alternatives considered

**Pure load-time registration** (`register-class-provider!` called by the
namespace as it loads). Cannot work alone — see the constraint above. It solves
the aesthetic problem and leaves the functional one.

**Eagerly load every declared provider at startup.** Makes registration
sufficient, at the cost of loading OpenSSL, libsqlite3 and a timezone database
for a program that touches none of them. Rejected: the laziness is the feature.

**A separate metadata file per library** (`jolt-provides.edn` at the dependency
root). Works, but `deps.edn` is already the declaration point and is already
walked; a second mechanism buys nothing.

**Leave it alone.** Defensible while there were two entries. It stops being
defensible as soon as a third library wants in — which is what #810 was — and
the swappability problem does not go away by not looking at it.

## Diagnostics

Removing the table removes what the runtime knew about libraries it has not
loaded, and the error message is where that shows. Before:

> `java.security.MessageDigest` is provided by the io.github.jolt-lang/jolt-crypto
> library, not core. Add it to your deps.edn.

After:

> No dependency provides `java.security.MessageDigest` — a concrete
> implementation of the JDK classes must be provided.

This is deliberate, and it is not only a cost. Which library supplies `java.sql`
or `java.time` is not the runtime's to say: a caller may write the shim
themselves, and declaring it through `:jolt/provides` is exactly the point.
Naming a specific library in the error would put the coupling back as a string —
the runtime would once again ship an opinion about which library you ought to
depend on, going stale as the ecosystem grows.

Two unit tests pinned the old wording (`insttime`, on `DateTimeFormatter` and
`ZoneOffset`), which is how deliberate the old message was; they now assert the
general form, plus a third asserting that no library name appears — so the
coupling cannot return as a string.

A claim that IS declared but whose provider raised while loading still names that
provider. That is not a catalogue: it is the dependency the caller declared,
read back from their own `deps.edn`, and the fix is the opposite of adding
something, so the two cases must not read alike.

## Decisions

1. **Trust.** A dependency may not claim a class the runtime already
   *implements*; the registration is refused, naming the classes. "Implements"
   is the statics/ctor tables, **not** the class hierarchy — the hierarchy knows
   names it does not implement (`java.time.ZoneId` is in it so `isa?` and
   `instance?` answer correctly, while the implementation is the library's to
   install), and checking it rejected every provider for the classes the
   mechanism exists to provide.
2. **Conflicts.** Two dependencies claiming one class is an **error at resolve
   time**, naming both claimants. Whichever won would decide what
   `java.sql.Connection` means for the whole program, and the loser's shim would
   be half-installed — registered for the classes nobody else claimed, silently
   absent for the rest.
3. **Scope of a claim.** Exact class names only; no prefix claims. Each declared
   name is indexed under both its fully-qualified and simple spelling, derived by
   the runtime, which is what removes the hand-sync failure the old table kept
   hitting.

## Compatibility

`:jolt/provides` is a new `deps.edn` key. Older jolt ignores unknown `deps.edn`
keys silently, so libraries can declare it before the runtime understands it —
the declaration is inert until a jolt that reads it. `:jolt/min-version` (jolt
0.8.0) is how a library states that it needs the newer behaviour.

Removing the hardcoded table is a behaviour change for any project that relies
on the autoload today without declaring the dependency it is getting the class
from. Those projects already declare the dependency in practice — the autoload
only fires when the install namespace is on the source roots, which means the
dependency is present — so the migration is for the libraries, which gain a
`:jolt/provides` key, and not for their consumers.
