# Scalafix rules for Cats Effect

## How to use

1. [Install the Scalafix sbt plugin](https://scalacenter.github.io/scalafix/docs/users/installation)
1. Run the rules appropriate to your Cats Effect version (see below)

## Migration to Cats Effect 3.0.0

First configure the SemanticDB compiler plugin to enable synthetics:
```
ThisBuild / scalacOptions += "-P:semanticdb:synthetics:on"
```

Then run Scalafix:
```sh
sbt ";scalafixEnable ;scalafixAll github:typelevel/cats-effect/v3_0_0?sha=v3.0.0"
```
