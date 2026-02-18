# Nix Usage Guide for mcp-injector

## Quick Start

### Development

```bash
# Enter dev shell
nix develop

# Start the server
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

Add to your `flake.nix`:

```nix
{
  inputs.mcp-injector.url = "github:anomalyco/mcp-injector";
  
  outputs = { self, nixpkgs, mcp-injector }: {
    nixosConfigurations.yourhostname = nixpkgs.lib.nixosSystem {
      modules = [
        mcp-injector.nixosModules.default
        {
          services.mcp-injector = {
            enable = true;
            port = 8088;
            llmUrl = "http://localhost:8081";
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
  services.mcp-injector = {
    enable = true;
    
    # Network settings
    port = 8088;
    host = "0.0.0.0";  # Bind to all interfaces
    openFirewall = true;
    
    # LLM endpoint
    llmUrl = "http://llm.internal:8081";
    
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
    
    # Runtime configuration
    logLevel = "info";
    maxIterations = 10;
    timeoutMs = 1800000;
    
    # Service user
    user = "mcp-injector";
    group = "mcp-injector";
  };
  
  # Reverse proxy with Caddy (optional)
  services.caddy = {
    enable = true;
    virtualHosts."mcp-injector.example.com".extraConfig = ''
      reverse_proxy localhost:8088
    '';
  };
  
  # Monitoring (optional)
  services.prometheus.scrapeConfigs = [{
    job_name = "mcp-injector";
    static_configs = [{
      targets = [ "localhost:8088" ];
    }];
  }];
}
```

### Local Development Override

```nix
# Override for local development
{ config, pkgs, ... }:

{
  services.mcp-injector = {
    enable = true;
    port = 8088;
    host = "127.0.0.1";
    llmUrl = "http://localhost:8081";
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
docker run -p 8088:8088 mcp-injector:latest
```

Add to `flake.nix` for Docker support:

```nix
# In outputs, add:
dockerImage = pkgs.dockerTools.buildLayeredImage {
  name = "mcp-injector";
  tag = "latest";
  
  contents = [ mcp-injector ];
  
  config = {
    Cmd = [ "${mcp-injector}/bin/mcp-injector" ];
    ExposedPorts = {
      "8088/tcp" = {};
    };
    Env = [
      "MCP_INJECTOR_PORT=8088"
      "MCP_INJECTOR_HOST=0.0.0.0"
    ];
  };
};
```

## Deployment Patterns

### Home Server Setup

```nix
{ config, pkgs, ... }:

{
  # mcp-injector service
  services.mcp-injector = {
    enable = true;
    port = 8088;
    llmUrl = "http://localhost:8081";
    openFirewall = false;  # Use Tailscale/VPN instead
  };
  
  # Run LLM gateway alongside
  virtualisation.oci-containers.containers.llm = {
    image = "llm:latest";
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
  mkMcpInjectorInstance = name: port: mcpServers: {
    "mcp-injector-${name}" = {
      enable = true;
      port = port;
      mcpServers = mcpServers;
    };
  };
in {
  services = lib.mkMerge [
    # Production instance
    (mkMcpInjectorInstance "prod" 8088 {
      stripe = { /* ... */ };
      postgres = { /* ... */ };
    })
    
    # Staging instance
    (mkMcpInjectorInstance "staging" 8089 {
      stripe-test = { /* ... */ };
      postgres-staging = { /* ... */ };
    })
  ];
}
```

## Environment Variables

The NixOS module sets these automatically, but for manual runs:

```bash
export MCP_INJECTOR_PORT=8088
export MCP_INJECTOR_HOST="127.0.0.1"
export MCP_INJECTOR_LLM_URL="http://localhost:8080"
export MCP_INJECTOR_LOG_LEVEL="info"
export MCP_INJECTOR_MAX_ITERATIONS=10
export MCP_INJECTOR_TIMEOUT_MS=1800000
export MCP_INJECTOR_MCP_CONFIG="./mcp-servers.edn"
```

## Testing

```nix
# Add to flake.nix checks
checks = {
  test-mcp-injector = pkgs.runCommand "test-mcp-injector" {
    buildInputs = [ mcp-injector pkgs.curl pkgs.jq ];
  } ''
    # Start mcp-injector in background
    ${mcp-injector}/bin/mcp-injector &
    MCP_PID=$!
    
    # Wait for startup
    sleep 2
    
    # Test endpoint
    curl -X POST http://localhost:8088/v1/chat/completions \
      -H "Content-Type: application/json" \
      -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"test"}],"stream":true}' \
      | grep -q "data:" || exit 1
    
    # Cleanup
    kill $MCP_PID
    
    touch $out
  '';
};
```

## Common Operations

### View logs

```bash
# On NixOS
journalctl -u mcp-injector -f

# In dev
bb run  # Logs to stdout
```

### Restart service

```bash
sudo systemctl restart mcp-injector
```

### Check status

```bash
sudo systemctl status mcp-injector
```

### Update configuration

```bash
# Edit configuration.nix
sudo nixos-rebuild switch
```

## Tips

1. **MCP servers**: Run them as separate containers/services
2. **Secrets**: Use `agenix` or `sops-nix` for API keys
3. **Monitoring**: Add Prometheus exporters to MCP servers
4. **Backup**: The runtime is stateless; backup your MCP data sources

## Troubleshooting

### mcp-injector won't start

```bash
# Check logs
journalctl -u mcp-injector -n 50

# Check if port is in use
sudo ss -tlnp | grep 8088

# Test LLM gateway connection
curl http://localhost:8080/health
```

### MCP tools not working

```bash
# Verify MCP server is running
curl http://localhost:3001/mcp -d '{"jsonrpc":"2.0","method":"tools/list","params":{},"id":"1"}'

# Check mcp-injector config
cat /nix/store/*/mcp-servers.edn
```

### High memory usage

Adjust in `configuration.nix`:

```nix
systemd.services.mcp-injector.serviceConfig.MemoryMax = "4G";
```
