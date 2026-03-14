{
  description = "mcp-injector - HTTP shim for injecting MCP tools into OpenAI-compatible chat completions";

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

        babashka = pkgs.babashka;

        mcp-injector = pkgs.stdenv.mkDerivation {
          pname = "mcp-injector";
          version = "0.1.0";

          src = ./.;

          nativeBuildInputs = [pkgs.makeWrapper];
          buildInputs = [babashka];

          installPhase = ''
            mkdir -p $out/bin $out/share/mcp-injector

            cp -r src $out/share/mcp-injector/
            cp bb.edn $out/share/mcp-injector/
            cp mcp-servers.example.edn $out/share/mcp-injector/mcp-servers.edn

            # Use bb serve to start the mcp-injector server
            makeWrapper ${babashka}/bin/bb $out/bin/mcp-injector \
              --prefix PATH : ${babashka}/bin \
              --run "cd $out/share/mcp-injector && exec bb serve" \
              --set MCP_INJECTOR_HOME "$out/share/mcp-injector"
          '';

          meta = with pkgs.lib; {
            description = "HTTP shim for injecting MCP tools into OpenAI-compatible chat completions";
            homepage = "https://github.com/anomalyco/mcp-injector";
            license = licenses.mit;
            maintainers = [];
            platforms = platforms.unix;
          };
        };
      in {
        formatter = pkgs.alejandra;
        packages = {
          default = mcp-injector;
        };

        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            babashka
            clojure
            clj-kondo
            cljfmt
            mdformat
          ];

          shellHook = ''
            echo "mcp-injector Dev Environment"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "Babashka version: $(bb --version)"
            echo ""
            echo "Quick start:"
            echo "  bb run          - Start the server"
            echo "  bb test         - Run tests"
            echo "  bb repl         - Start REPL"
            echo ""
            echo "  nix build       - Build the package"
            echo "  nix run         - Run the built package"
            echo ""
            export SHELL=$OLDSHELL
          '';
        };

        apps = {
          default = {
            type = "app";
            program = "${mcp-injector}/bin/mcp-injector";
            meta = {
              description = "Start mcp-injector server";
            };
          };
        };
      }
    )
    // {
      nixosModules.default = {
        config,
        lib,
        pkgs,
        ...
      }:
        with lib; let
          cfg = config.services.mcp-injector;

          mcp-injector-pkg = self.packages.${pkgs.system}.default;

          mcpServersConfig =
            pkgs.runCommand "mcp-servers.edn" {
              nativeBuildInputs = [pkgs.jet];
            } ''
              # Merge mcpServers and governance into a single EDN file
              # mcpServers should contain {:servers {...} :llm-gateway {...}}
              echo '${builtins.toJSON (cfg.mcpServers // { governance = cfg.governance; })}' | jet -i json -o edn -k > $out
            '';
        in {
          options.services.mcp-injector = {
            enable = mkEnableOption "mcp-injector HTTP server";

            port = mkOption {
              type = types.port;
              default = 8088;
              description = "Port for the mcp-injector HTTP server";
            };

            host = mkOption {
              type = types.str;
              default = "127.0.0.1";
              description = "Host address to bind to";
            };

            llmUrl = mkOption {
              type = types.str;
              default = "http://localhost:8080";
              description = "URL of OpenAI-compatible LLM endpoint";
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
                  };
                }
              '';
            };

            governance = mkOption {
              type = types.submodule {
                options = {
                  mode = mkOption {
                    type = types.enum ["permissive" "strict"];
                    default = "permissive";
                    description = "Governance mode. Strict requires explicit allow-rules.";
                  };
                  policy = mkOption {
                    type = types.attrs;
                    default = {};
                    description = "Allow/Deny lists and model rules.";
                  };
                  pii = mkOption {
                    type = types.attrs;
                    default = {
                      enabled = true;
                      mode = "replace";
                    };
                    description = "PII scanning configuration.";
                  };
                  audit = mkOption {
                    type = types.attrs;
                    default = {
                      enabled = true;
                      path = "logs/audit.log.ndjson";
                    };
                    description = "Audit trail configuration.";
                  };
                };
              };
              default = {};
              description = "Governance and security framework configuration.";
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

            timeoutMs = mkOption {
              type = types.int;
              default = 1800000;
              description = "Request timeout in milliseconds";
            };

            user = mkOption {
              type = types.str;
              default = "mcp-injector";
              description = "User to run the service as";
            };

            group = mkOption {
              type = types.str;
              default = "mcp-injector";
              description = "Group to run the service as";
            };

            openFirewall = mkOption {
              type = types.bool;
              default = false;
              description = "Open firewall port for mcp-injector";
            };

            environmentFile = mkOption {
              type = types.nullOr types.path;
              default = null;
              description = "Path to environment file containing secrets (e.g. /etc/secrets/mcp-injector.env)";
              example = "/etc/secrets/mcp-injector.env";
            };
          };

          config = mkIf cfg.enable {
            users.users.${cfg.user} = {
              isSystemUser = true;
              group = cfg.group;
              description = "mcp-injector service user";
            };

            users.groups.${cfg.group} = {};

            systemd.services.mcp-injector = {
              description = "mcp-injector HTTP server";
              wantedBy = ["multi-user.target"];
              after = ["network.target"];

              environment = {
                HOME = "/var/lib/mcp-injector";
                JAVA_TOOL_OPTIONS = "-Duser.home=/var/lib/mcp-injector";
                MCP_INJECTOR_PORT = toString cfg.port;
                MCP_INJECTOR_HOST = cfg.host;
                MCP_INJECTOR_LLM_URL = cfg.llmUrl;
                MCP_INJECTOR_LOG_LEVEL = cfg.logLevel;
                MCP_INJECTOR_MAX_ITERATIONS = toString cfg.maxIterations;
                MCP_INJECTOR_TIMEOUT_MS = toString cfg.timeoutMs;
                MCP_INJECTOR_MCP_CONFIG = mcpServersConfig;
              };

              serviceConfig = {
                Type = "simple";
                User = cfg.user;
                Group = cfg.group;
                ExecStart = "${mcp-injector-pkg}/bin/mcp-injector";
                Restart = "on-failure";
                RestartSec = "5s";
                StateDirectory = "mcp-injector";
                EnvironmentFile = mkIf (cfg.environmentFile != null) cfg.environmentFile;

                NoNewPrivileges = true;
                PrivateTmp = true;
                ProtectSystem = "strict";
                ProtectHome = true;

                MemoryMax = "2G";
                TasksMax = 100;
              };
            };

            networking.firewall.allowedTCPPorts = mkIf cfg.openFirewall [cfg.port];
          };
        };
    };
}
