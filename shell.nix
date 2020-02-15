with (import <nixpkgs> {});
let
  env = bundlerEnv {
    name = "cats-effect-bundler-env";
    inherit ruby_2_6;
    gemfile  = ./site/Gemfile;
    lockfile = ./site/Gemfile.lock;
    gemset   = ./gemset.nix;
  };
in stdenv.mkDerivation {
  name = "cats-effect";
  buildInputs = [
    env
    git
    nodejs
    sbt
  ];
}
