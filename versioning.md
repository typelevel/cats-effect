# Versioning and Compatibility

This project is intended to be an upstream dependency of a large swath of the ecosystem, similar in many respects to cats-core itself. For that reason, source/binary compatibility and stability is *incredibly* important. Breaking changes to major releases are going to be almost universally rejected, and will require extreme justification (and likely blood sacrifice) to obtain approval.

All stable releases will have a version of the form `(\d+)\.(\d+)(?:\.(\d+))?`, where the three groups of the regular expression denote *major*, *minor*, and *build* respectively. Some examples (for those who aren't fluent in regular expressions):

- `0.2` – major = 0, minor = 2
- `3.5.4` – major = 3, minor = 5, build = 4
- `1.0` – major = 1, minor = 0

By default, `sbt` (with both Ivy and Coursier) will assume that versions which have the same major and minor components are binary compatible, and will evict between build versions as necessary to resolve dependency conflicts. Thus, *all* releases with the same *major* and *minor* components are binary compatible, and this is mechanically checked with [MiMa](https://github.com/typesafehub/migration-manager).

## Source Compatibility

Within a single *major* version (e.g. the **1** in **1.0**), all versions are guaranteed to be *forward source compatible*. This means that code written against 1.0 will compile and run against 1.9.3, as well as anything else in the 1.x line, without any modifications and without any change in semantics. Unfortunately, we cannot mechanically check this behavior (unlike binary compatibility), so you'll just have to take our word for it that we're being very careful.

*Backward source compatibility* is not guaranteed. Thus, code written against 1.9.3 may not compile and run against 1.0, or it may compile and run but may have different semantics. Don't re-version things backwards.

## Binary Compatibility

Within a single *major/minor* version (e.g. the **1.0** in **1.0.5**), all releases are guaranteed to be *forward binary compatible*. This means that artifacts *compiled* against 1.0 will also run with 1.0.5 on the classpath, and the semantics will be identical (modulo critical bug fixes). This guarantee is mechanically checked before release, and so within the limits of this tooling, we can be pretty sure it is true.

*Backward binary compatibility* is not guaranteed. Thus, code compiled against 1.0.5 may not run with the same semantics against 1.0, though it is *very* likely that it will.

## Snapshots

Snapshots may be published to sonatype at any time. They will be denoted as versions of the form `major.minor-hash`, where the `hash` is the 7 character git hash prefix of the commit from which the snapshot was published. Thus, "snapshots" are in fact stable, and can be used as repeatable upstream dependencies if you're feeling courageous. A snapshot with a `major` version of *x* and a `minor` version of *y* is expected (and indeed, machine-checked in so far as possible) to be binary compatible with the full release with version *x*.*y* (and all its subsequent minor versions). Thus, eviction works basically the way you expect. The only exception to this is *unreleased* incompatible versions, since such versions are still in flux and early snapshots may be incompatible with this future release.

Note that this implies that snapshots of a particular release line may be published *after* the main release. You can find more details [here](https://github.com/typelevel/cats-effect/blob/563d29ee01885b00613a3dd8eb6f12c56aa126b2/build.sbt#L80-L110).

It is appropriate to use snapshots if you need some unreleased changes from the active development line. The hashing scheme means that you can safely push your own snapshots to a local Nexus instance if you *need* work that the maintainers just haven't published yet; if that particular commit is ever given a published snapshot, it will have the same contents and version as the one you locally published.

In general though, if you don't need the absolute bleeding edge, stick with the stable releases. They're called "stable" for a reason.
