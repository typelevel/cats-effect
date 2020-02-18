let
  nixpkgs-version = "19.09";
  pkgs = import (builtins.fetchTarball {
    name = "nixpkgs-${nixpkgs-version}";
    url = "https://github.com/nixos/nixpkgs/archive/${nixpkgs-version}.tar.gz";
    sha256 = "0mhqhq21y5vrr1f30qd2bvydv4bbbslvyzclhw0kdxmkgg3z4c92";
  }) {};
  jekyll = pkgs.writeScriptBin "jekyll" ''
    ${pkgs.bundler}/bin/bundle && ${pkgs.bundler}/bin/bundle exec jekyll "$@"
  '';
in pkgs.stdenv.mkDerivation {
  name = "cats-effect";
  buildInputs = [
    jekyll
    pkgs.git
    pkgs.nodejs
    pkgs.sbt
  ];
}
