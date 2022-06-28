---
id: native-image
title: GraalVM Native Image
---

Cats Effect 3.3.0 brought support for printing [a fiber dump](./fiber-dumps.md)
to the standard error stream.
This functionality can be triggered using POSIX signals. Unfortunately,
due to the `sun.misc.Signal` API being an unofficial JDK API, in order to
achieve maximum compatibility, this functionality was implemented using the
[`java.lang.reflect.Proxy`](https://docs.oracle.com/javase/8/docs/api/)
reflective approach.

Luckily, GraalVM Native Image has full support for both `Proxy` and POSIX
signals. Cats Effect jars contain extra metadata that makes building native
images seamless, without the need of extra configuration. The only caveat
is that this configuration metadata is specific to GraalVM 21.0 and later.
Previous versions of GraalVM are supported, but Native Image support requires
at least 21.0.
