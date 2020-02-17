with (import <nixpkgs> {});
let
  jekyll = writeScriptBin "jekyll" ''
    ${pkgs.bundler}/bin/bundle && ${pkgs.bundler}/bin/bundle exec jekyll "$@"
  '';
in stdenv.mkDerivation {
  name = "cats-effect";
  buildInputs = [
    git
    jekyll
    nodejs
    sbt
  ];
}
