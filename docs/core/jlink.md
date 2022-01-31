---
id: jlink
title: Jlink image
---

While Scala does not officially offer support for the
[Java Platform Module System](https://openjdk.java.net/projects/jigsaw/spec/),
and Cats Effect is itself built using JDK 8, Cats Effect applications can be
run on JDKs derived using `jlink` (bundling only the classes required for
the application to run).

Cats Effect applications only depend on JDK classes defined in the `java.base`
module. Building a JDK derived using `jlink` always contains these classes, so
projects that depend on `cats-effect` jars can be run out-of-the-box without
additional configuration.

There is one exception to this. The `java.base` module does not bundle support
for POSIX signals, which means that the
[a fiber dump](./fiber-dump.md) functionality cannot be
triggered by sending POSIX signals to the Cats Effect application process.

The POSIX signal functionality can be restored by adding a dependency on the
`jdk.unsupported` module, which unlocks access to the `sun.misc.Signal` API.

## sbt-native-packager
This sbt plugin can build `jlink` derived JDKs and can be configured to include
the `jdk.unsupported` module as follows:

```scala
.settings(
  jlinkModules += "jdk.unsupported"
)
```
