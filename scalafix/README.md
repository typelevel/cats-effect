# Scalafix rules for Cats Effect

## How to use

1. [Install the Scalafix sbt plugin](https://scalacenter.github.io/scalafix/docs/users/installation)
1. Run the rules appropriate to your Cats Effect version (see below)

## Migration to Cats Effect 2.4.0

```sh
sbt ";scalafixEnable ;scalafixAll github:typelevel/cats-effect/v2_4_0?sha=v2.4.0"
```

## Migration to Cats Effect 2.5.3

```sh
sbt ";scalafixEnable ;scalafixAll github:typelevel/cats-effect/v2_5_3?sha=v2.5.3"
```
