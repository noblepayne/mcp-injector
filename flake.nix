{
  description = "Situated Agent Runtime - Production-ready agent orchestration for business automation";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = nixpkgs.legacyPackages.${system};

        # Babashka is already in nixpkgs
        babashka = pkgs.babashka;

        # Build the SAR package
        sar = pkgs.stdenv.mkDerivation {
          pname = "situated-agent-runtime";
          version = "0.1.0";

          src = ./.;

          nativeBuildInputs = [pkgs.makeWrapper];
          buildInputs = [babashka];

          installPhase = ''
            mkdir -p $out/bin $out/share/sar

            # Copy source files
            cp -r src $out/share/sar/
            cp -r skills $out/share/sar/
            cp bb.edn $out/share/sar/
            cp mcp-servers.edn $out/share/sar/

            # Create wrapper script
            makeWrapper ${babashka}/bin/bb $out/bin/sar \
              --add-flags "-f $out/share/sar/src/sar/core.clj" \
              --set SAR_HOME "$out/share/sar"
          '';

          meta = with pkgs.lib; {
            description = "Situated Agent Runtime for business automation";
            homepage = "https://github.com/yourusername/situated-agent-runtime";
            license = licenses.mit;
            maintainers = [];
            platforms = platforms.unix;
          };
        };
      in {
        # Package output
        packages = {
          default = sar;
          situated-agent-runtime = sar;
        };

        # Development shell
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            babashka
            clojure
            clj-kondo # Linter
	    cljfmt
	    mdformat
          ];

          shellHook = ''
            echo "🤖 Situated Agent Runtime Dev Environment"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "Babashka version: $(bb --version)"
            echo ""
            echo "Quick start:"
            echo "  bb run          - Start the runtime"
            echo "  bb test         - Run tests"
            echo "  bb repl         - Start REPL"
            echo ""

            # Set up local config
            export SAR_HOME="$PWD"
            export SAR_PORT="''${SAR_PORT: -8080}"
            export SAR_LOG_LEVEL="''${SAR_LOG_LEVEL: -info}"
          '';
        };

        # Apps for easy running
        apps = {
          default = {
            type = "app";
            program = "${sar}/bin/sar";
          };
        };
      }
    )
    // {
      # NixOS module
      nixosModules.default = {
        config,
        lib,
        pkgs,
        ...
      }:
        with lib; let
          cfg = config.services.situated-agent-runtime;

          # Get the package from this flake
          sar-pkg = self.packages.${pkgs.system}.default;

          # Configuration file for MCP servers
          mcpServersConfig = pkgs.writeText "mcp-servers.edn" (builtins.toJSON cfg.mcpServers);
        in {
          options.services.situated-agent-runtime = {
            enable = mkEnableOption "Situated Agent Runtime";

            port = mkOption {
              type = types.port;
              default = 8080;
              description = "Port for the SAR HTTP server";
            };

            host = mkOption {
              type = types.str;
              default = "127.0.0.1";
              description = "Host address to bind to";
            };

            bifrostUrl = mkOption {
              type = types.str;
              default = "http://localhost:8081";
              description = "URL of Bifrost or OpenAI-compatible LLM endpoint";
            };

            mcpServers = mkOption {
              type = types.attrs;
              default = {};
              description = "MCP server configurations";
              example = literalExpression ''
                {
                  stripe = {
                    url = "http://localhost:3001/mcp";
                    tools = ["retrieve_customer" "list_charges"];
                    inject = "lazy";
                  };
                  postgres = {
                    url = "http://localhost:3002/mcp";
                    tools = ["query" "execute"];
                    inject = "lazy";
                  };
                }
              '';
            };

            skillsDir = mkOption {
              type = types.path;
              default = "${sar-pkg}/share/sar/skills";
              description = "Directory containing skill definitions";
            };

            logLevel = mkOption {
              type = types.enum ["debug" "info" "warn" "error"];
              default = "info";
              description = "Logging level";
            };

            maxIterations = mkOption {
              type = types.int;
              default = 10;
              description = "Maximum agent loop iterations";
            };

            user = mkOption {
              type = types.str;
              default = "sar";
              description = "User to run the service as";
            };

            group = mkOption {
              type = types.str;
              default = "sar";
              description = "Group to run the service as";
            };

            openFirewall = mkOption {
              type = types.bool;
              default = false;
              description = "Open firewall port for SAR";
            };
          };

          config = mkIf cfg.enable {
            # Create user and group
            users.users.${cfg.user} = {
              isSystemUser = true;
              group = cfg.group;
              description = "Situated Agent Runtime user";
            };

            users.groups.${cfg.group} = {};

            # Systemd service
            systemd.services.situated-agent-runtime = {
              description = "Situated Agent Runtime";
              wantedBy = ["multi-user.target"];
              after = ["network.target"];

              environment = {
                SAR_PORT = toString cfg.port;
                SAR_HOST = cfg.host;
                SAR_BIFROST_URL = cfg.bifrostUrl;
                SAR_LOG_LEVEL = cfg.logLevel;
                SAR_MAX_ITERATIONS = toString cfg.maxIterations;
                SAR_SKILLS_DIR = cfg.skillsDir;
                SAR_MCP_CONFIG = mcpServersConfig;
              };

              serviceConfig = {
                Type = "simple";
                User = cfg.user;
                Group = cfg.group;
                ExecStart = "${sar-pkg}/bin/sar";
                Restart = "on-failure";
                RestartSec = "5s";

                # Hardening
                NoNewPrivileges = true;
                PrivateTmp = true;
                ProtectSystem = "strict";
                ProtectHome = true;
                ReadOnlyPaths = [cfg.skillsDir];

                # Resource limits
                MemoryMax = "2G";
                TasksMax = 100;
              };
            };

            # Firewall
            networking.firewall.allowedTCPPorts = mkIf cfg.openFirewall [cfg.port];
          };
        };
    };
}
