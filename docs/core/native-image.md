---
id: native-image
title: GraalVM Native Image
---

Cats Effect 3.3.0 brought support for printing a
[snapshot of live fibers](./live-fiber-snapshot.md) to the standard error
stream. This functionality can be triggered using POSIX signals. Unfortunately,
due to the `sun.misc.Signal` API being an unofficial JDK API, in order to
achieve maximum compatibility, this functionality was implemented using the
[`java.lang.reflect.Proxy`](https://docs.oracle.com/javase/8/docs/api/)
reflective approach.

Luckily, GraalVM Native Image has full support for both `Proxy` and POSIX
signals. Unfortunately, this support does not come out of the box. While
building a Native Image of a Cats Effect application will succeed without any
extra configuration, it will crash at runtime with a message similar to the
following:

```
Exception in thread "main" com.oracle.svm.core.jdk.UnsupportedFeatureError: Proxy class defined by interfaces [interface sun.misc.SignalHandler] not found. Generating proxy classes at runtime is not supported. Proxy classes need to be defined at image build time by specifying the list of interfaces that they implement. To define proxy classes use -H:DynamicProxyConfigurationFiles=<comma-separated-config-files> and -H:DynamicProxyConfigurationResources=<comma-separated-config-resources> options.
        at com.oracle.svm.core.util.VMError.unsupportedFeature(VMError.java:87)
        at com.oracle.svm.reflect.proxy.DynamicProxySupport.getProxyClass(DynamicProxySupport.java:146)
        at java.lang.reflect.Proxy.getProxyConstructor(Proxy.java:66)
        at java.lang.reflect.Proxy.newProxyInstance(Proxy.java:1037)
        at cats.effect.Signal$2.handleSignal(Signal.java:115)
        at cats.effect.Signal.handleSignal(Signal.java:64)
        at cats.effect.IOApp.$anonfun$main$7(IOApp.scala:251)
        at cats.effect.IOApp.$anonfun$main$7$adapted(IOApp.scala:247)
        at scala.collection.immutable.List.foreach(List.scala:333)
        at cats.effect.IOApp.main(IOApp.scala:247)
        at cats.effect.IOApp.main$(IOApp.scala:200)
        at org.example.Main$.main(Main.scala:7)
        at org.example.Main.main(Main.scala)
```

This exact error and related functionality is documented in the official GraalVM
Native Image documentation
[here](https://www.graalvm.org/reference-manual/native-image/DynamicProxy/).

Essentially, we need to configure the Native Image build process to retain these
classes in the final image, so they can be accessed reflectively. The following
configuration is valid for Native Images of Cats Effect applications.

The following contents should be added to a `dynamic-proxy-config.json` (the
file itself can be named anything, it just needs to be a `.json` file):

```json
[
    ["sun.misc.SignalHandler"]
]
```

Furthermore, the GraalVM Native Image process needs to be notified of the
existence of this configuration file. The following parameter needs to be passed
to the build process:

```
-H:DynamicProxyConfigurationFiles=path/to/dynamic-proxy-config.json
```

If using specific sbt plugins for generating GraalVM Native Images, this
parameter can be provided as follows:

## sbt-native-image

```scala
.settings(
  nativeImageOptions += "-H:DynamicProxyConfigurationFiles=path/to/dynamic-proxy-config.json"
)
```

## sbt-native-packager

```scala
.settings(
  graalVMNativeImageOptions += "-H:DynamicProxyConfigurationFiles=path/to/dynamic-proxy-config.json"
)
```
