# Documentation

This branch contains the shared content and infrastructure for the Cats Effect website. You will find a pair of submodules for series/2.x and series/3.x within the **website/** directory. When the site is built, these respective builds are run and their mdoc and scaladoc outputs are extracted.

To build and locally host the full website, run the following:

```bash
$ ./build.sh host
```

You will need NodeJS version 12 (higher versions probably work as well) as well as [Yarn](https://yarnpkg.com), which you can install either via `npm` or via your system's package manager (e.g `brew install yarn`).
