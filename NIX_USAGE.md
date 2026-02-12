# Nix Usage Guide for Situated Agent Runtime

## Quick Start

### Development

```bash
# Enter dev shell
nix develop

# Start the runtime
bb run

# Run tests
bb test

# Start REPL
bb repl
```

### Build & Run

```bash
# Build the package
nix build

# Run directly
nix run

# Install to profile
nix profile install
```

## NixOS Module Usage

### Basic Configuration

Add to your `configuration.nix`:

```nix
{
  inputs.sar.url = "github:yourusername/situated-agent-runtime";
  
  outputs = { self, nixpkgs, sar }: {
    nixosConfigurations.yourhostname = nixpkgs.lib.nixosSystem {
      modules = [
        sar.nixosModules.default
        {
          services.situated-agent-runtime = {
            enable = true;
            port = 8080;
            bifrostUrl = "http://localhost:8081";
          };
        }
      ];
    };
  };
}
```

### Full Production Configuration

```nix
{ config, pkgs, ... }:

{
  services.situated-agent-runtime = {
    enable = true;
    
    # Network settings
    port = 8080;
    host = "0.0.0.0";  # Bind to all interfaces
    openFirewall = true;
    
    # LLM endpoint
    bifrostUrl = "http://bifrost.internal:8081";
    
    # MCP server configurations
    mcpServers = {
      stripe = {
        url = "http://localhost:3001/mcp";
        tools = [ 
          "retrieve_customer" 
          "list_charges" 
          "create_refund" 
        ];
        inject = "lazy";
      };
      
      postgres = {
        url = "http://localhost:3002/mcp";
        tools = [ "query" "execute" "list_tables" ];
        inject = "lazy";
      };
      
      filesystem = {
        url = "http://localhost:3003/mcp";
        tools = [ "read" "write" "ls" ];
        inject = "full";  # Always inject
      };
      
      podcast-api = {
        url = "http://api.podcast.internal/mcp";
        tools = [ 
          "upload_episode" 
          "validate_episode" 
          "publish_to_rss"
          "get_analytics"
        ];
        inject = "lazy";
      };
    };
    
    # Custom skills directory
    skillsDir = ./skills;
    
    # Runtime configuration
    logLevel = "info";
    maxIterations = 10;
    
    # Service user
    user = "sar";
    group = "sar";
  };
  
  # Reverse proxy with Caddy (optional)
  services.caddy = {
    enable = true;
    virtualHosts."sar.example.com".extraConfig = ''
      reverse_proxy localhost:8080
    '';
  };
  
  # Monitoring (optional)
  services.prometheus.scrapeConfigs = [{
    job_name = "sar";
    static_configs = [{
      targets = [ "localhost:8080" ];
    }];
  }];
}
```

### Local Development Override

```nix
# Override for local development
{ config, pkgs, ... }:

{
  services.situated-agent-runtime = {
    enable = true;
    port = 8080;
    host = "127.0.0.1";
    bifrostUrl = "http://localhost:8081";
    logLevel = "debug";
    
    mcpServers = {
      # Minimal config for testing
      filesystem = {
        url = "http://localhost:3003/mcp";
        tools = [ "read" "write" "ls" ];
        inject = "full";
      };
    };
  };
}
```

## Docker with Nix

Build a Docker image:

```bash
# Build Docker image
nix build .#dockerImage

# Load into Docker
docker load < result

# Run
docker run -p 8080:8080 situated-agent-runtime:latest
```

Add to `flake.nix` for Docker support:

```nix
# In outputs, add:
dockerImage = pkgs.dockerTools.buildLayeredImage {
  name = "situated-agent-runtime";
  tag = "latest";
  
  contents = [ sar ];
  
  config = {
    Cmd = [ "${sar}/bin/sar" ];
    ExposedPorts = {
      "8080/tcp" = {};
    };
    Env = [
      "SAR_PORT=8080"
      "SAR_HOST=0.0.0.0"
    ];
  };
};
```

## Deployment Patterns

### Home Server Setup

```nix
{ config, pkgs, ... }:

{
  # SAR service
  services.situated-agent-runtime = {
    enable = true;
    port = 8080;
    bifrostUrl = "http://localhost:8081";
    openFirewall = false;  # Use Tailscale/VPN instead
  };
  
  # Run Bifrost alongside
  virtualisation.oci-containers.containers.bifrost = {
    image = "bifrost:latest";
    ports = [ "8081:8080" ];
  };
  
  # MCP servers via Docker
  virtualisation.oci-containers.containers = {
    stripe-mcp = {
      image = "stripe-mcp:latest";
      ports = [ "3001:3000" ];
      environment = {
        STRIPE_API_KEY = "sk_live_...";
      };
    };
    
    postgres-mcp = {
      image = "postgres-mcp:latest";
      ports = [ "3002:3000" ];
      environment = {
        DATABASE_URL = "postgresql://...";
      };
    };
  };
}
```

### Multi-Instance Setup

```nix
{ config, pkgs, lib, ... }:

let
  mkSarInstance = name: port: mcpServers: {
    "situated-agent-runtime-${name}" = {
      enable = true;
      port = port;
      mcpServers = mcpServers;
    };
  };
in {
  services = lib.mkMerge [
    # Production instance
    (mkSarInstance "prod" 8080 {
      stripe = { /* ... */ };
      postgres = { /* ... */ };
    })
    
    # Staging instance
    (mkSarInstance "staging" 8081 {
      stripe-test = { /* ... */ };
      postgres-staging = { /* ... */ };
    })
  ];
}
```

## Environment Variables

The NixOS module sets these automatically, but for manual runs:

```bash
export SAR_PORT=8080
export SAR_HOST="127.0.0.1"
export SAR_BIFROST_URL="http://localhost:8081"
export SAR_LOG_LEVEL="info"
export SAR_MAX_ITERATIONS=10
export SAR_SKILLS_DIR="/path/to/skills"
export SAR_MCP_CONFIG="/path/to/mcp-servers.edn"
```

## Testing

```nix
# Add to flake.nix checks
checks = {
  test-sar = pkgs.runCommand "test-sar" {
    buildInputs = [ sar pkgs.curl pkgs.jq ];
  } ''
    # Start SAR in background
    ${sar}/bin/sar &
    SAR_PID=$!
    
    # Wait for startup
    sleep 2
    
    # Test endpoint
    curl -X POST http://localhost:8080/v1/chat/completions \
      -H "Content-Type: application/json" \
      -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"test"}],"stream":true}' \
      | grep -q "data:" || exit 1
    
    # Cleanup
    kill $SAR_PID
    
    touch $out
  '';
};
```

## Common Operations

### View logs

```bash
# On NixOS
journalctl -u situated-agent-runtime -f

# In dev
bb run  # Logs to stdout
```

### Restart service

```bash
sudo systemctl restart situated-agent-runtime
```

### Check status

```bash
sudo systemctl status situated-agent-runtime
```

### Update configuration

```bash
# Edit configuration.nix
sudo nixos-rebuild switch
```

## Tips

1. **Skills directory**: Use a Git repo for skills and point `skillsDir` to it
1. **MCP servers**: Run them as separate containers/services
1. **Secrets**: Use `agenix` or `sops-nix` for API keys
1. **Monitoring**: Add Prometheus exporters to MCP servers
1. **Backup**: The runtime is stateless; backup your MCP data sources

## Troubleshooting

### SAR won't start

```bash
# Check logs
journalctl -u situated-agent-runtime -n 50

# Check if port is in use
sudo ss -tlnp | grep 8080

# Test Bifrost connection
curl http://localhost:8081/health
```

### MCP tools not working

```bash
# Verify MCP server is running
curl http://localhost:3001/mcp -d '{"jsonrpc":"2.0","method":"tools/list","params":{},"id":"1"}'

# Check SAR config
cat /nix/store/*/mcp-servers.edn
```

### High memory usage

Adjust in `configuration.nix`:

```nix
systemd.services.situated-agent-runtime.serviceConfig.MemoryMax = "4G";
```
