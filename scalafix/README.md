# Scalafix rules for Cats Effect

## How to use

1. [Install the Scalafix sbt plugin](https://scalacenter.github.io/scalafix/docs/users/installation)
1. Run the rules appropriate to your Cats Effect version (see below)

## Migration to Cats Effect 3.0.0

The `v3_0_0` rule migrates Cats Effect 2.x code to Cats Effect 3.0.0.

First configure the SemanticDB compiler plugin to enable synthetics:
```
ThisBuild / scalacOptions += "-P:semanticdb:synthetics:on"
```

Then run Scalafix:
```sh
sbt ";scalafixEnable ;scalafixAll github:typelevel/cats-effect/v3_0_0?sha=v3.0.0"
```

Finally, bump the Cats Effect version in your build to 3.0.0 and fix any remaining
compile  errors. Note that it is important to *not* bump the version before running
Scalafix for the rule to work correctly.
