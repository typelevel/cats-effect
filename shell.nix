{ jdk ? "jdk15" }:

let
  config = {
    packageOverrides = p: rec {
      java = p.${jdk};

      sbt = p.sbt.overrideAttrs (
        old: rec {
          jre = "${java}";
        }
      );
    };
  };

  nixpkgs = fetchTarball {
    name   = "nixos-unstable-2021-02-21";
    url    = "https://github.com/NixOS/nixpkgs/archive/9816b99e71c.tar.gz";
    sha256 = "1dpz36i3vx0c1wmacrki0wsf30if8xq3bnj71g89rsbxyi87lhcm";
  };

  pkgs = import nixpkgs { inherit config; };

  siteDeps = with pkgs; [
    autoconf    # v2.7.0 (autoreconf)
    automake    # v1.16.3 (aclocal)
    nodejs-14_x # v14.15.5
    yarn        # v1.22.10
  ];
in
pkgs.mkShell {
  name = "scala-shell";

  buildInputs = with pkgs; [
    coursier    # v2.0.12
    pkgs.${jdk} # v15.0.1
    sbt         # v1.4.7 (script version)
  ] ++ siteDeps;
}
