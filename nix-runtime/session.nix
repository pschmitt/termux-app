{ source }:
let
  flake = builtins.getFlake source;
  host = flake.inputs.nixpkgs.legacyPackages.x86_64-linux;
  pkgs = flake.inputs.nixpkgs.legacyPackages.aarch64-linux;
  packages = with pkgs; [
    bashInteractive
    zsh
    coreutils
    findutils
    gnugrep
    gnused
    gawk
    less
    gitMinimal
    python3
    ncurses
    cacert
    nix
  ];
in
host.writeText "termuxpp-nix-session.json" (
  builtins.toJSON {
    schema = 1;
    system = "aarch64-linux";
    pageSize = 4096;
    inherit packages;
    shell = "${pkgs.bashInteractive}/bin/bash";
    zsh = "${pkgs.zsh}/bin/zsh";
    python = "${pkgs.python3}/bin/python3";
    nix = "${pkgs.nix}/bin/nix";
    certificate = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
    terminfo = "${pkgs.ncurses}/share/terminfo";
    path = pkgs.lib.makeBinPath packages;
  }
)
