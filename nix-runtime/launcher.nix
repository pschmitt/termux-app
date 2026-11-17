{ pkgs }:
let
  launcher = pkgs.pkgsCross.aarch64-multiplatform-musl.pkgsStatic.callPackage ./upstream { };
in
launcher.overrideAttrs (old: {
  patches = (old.patches or [ ]) ++ [ ./signal-lifetime.patch ];
})
