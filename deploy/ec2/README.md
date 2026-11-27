# Deploying on EC2 (next to the MCP server)

```
React app (https://dzo4l1wjzweva.cloudfront.net)
   │  POST https://<orchestrator distribution>.cloudfront.net/api/v1/chat
   ▼
CloudFront ── VPC origin (private) ──▶ ┌──────── EC2 t3.micro (no public inbound ports) ────────┐
                                       │  docker network "transport"                             │
                                       │    transport-mcp-orchestrator :8081 (actuator :8082,    │
                                       │      127.0.0.1 only) ─▶ http://transport-mcp-server:8080 │
                                       │    transport-mcp-server :8080 (127.0.0.1 only)          │
                                       └──────────────────────────────────────────────────────────┘
```

The orchestrator runs on the MCP server's instance (`i-01166c968e71e477a`, us-east-2), set up by
[springboot_MCP_server's deploy docs](https://github.com/mr-mtd/springboot_MCP_server/blob/main/deploy/ec2/README.md).
It joins the `transport` Docker network and calls the MCP server by container name, so the MCP
server needs no change and still has no published port beyond `127.0.0.1:8080`.

- **Image:** CI pushes `ghcr.io/mr-mtd/transport-mcp-orchestrator` on every push to `main` and
  `feature/dev` (tags `main`, `feature-dev`, `sha-<commit>`). The GHCR package must be public, or
  the instance would need a pull token.
- **OpenAI key:** SSM Parameter Store SecureString `/transport-mcp/openai-api-key`, read by
  [`run.sh`](run.sh) at container start. The instance role `transport-mcp-ec2` has an inline policy
  `read-openai-api-key` allowing `ssm:GetParameter` on that one parameter.
- **Public endpoint:** a CloudFront distribution whose origin is a CloudFront **VPC origin**
  (`transport-mcp-orchestrator`) pointing at the instance on HTTP port 8081. CloudFront gives HTTPS
  on its `*.cloudfront.net` name. Caching is off and all methods and headers are forwarded; the
  origin response timeout is 60 s, because a chat with several tool calls can take a while.
- **Security groups:** the instance now has two. `transport-mcp-sg` is unchanged (no inbound
  rules). `transport-orchestrator-sg` allows TCP 8081 only from the service-managed
  `CloudFront-VPCOrigins-Service-SG`, so the port can't be reached from the internet directly.
- **Actuator** (health, metrics, Prometheus) is on port 8082, published on `127.0.0.1` only, so
  it isn't exposed through CloudFront. Reach it with an SSM tunnel (`portNumber=8082`).
- **CORS:** `CORS_ALLOWED_ORIGINS` defaults to `https://dzo4l1wjzweva.cloudfront.net` in `run.sh`.
  Override it with the `CORS_ALLOWED_ORIGINS` repo variable (comma separated, no trailing slash).
- **Memory:** the t3.micro has ~900 MB of RAM plus 1 GB of swap, shared with the MCP server
  (600 MB cap). The orchestrator runs with a 200 MB heap and a 450 MB container cap. If it
  struggles, move to a t3.small.

## Updating

Automatic: every push to `main` runs `build` → `publish` → `deploy` in
[`build.yml`](../../.github/workflows/build.yml). The `deploy` job signs in to AWS with GitHub OIDC
(no AWS keys stored in GitHub), copies `run.sh` from the commit to
`/opt/transport-mcp/run-orchestrator.sh` with SSM Run Command, runs it with the commit's image tag,
and fails unless `/actuator/health` reports `UP` within 150 s.

| Where | Name | Value |
| --- | --- | --- |
| IAM identity provider | `token.actions.githubusercontent.com` | shared with the MCP server's deploy |
| IAM role | `transport-orchestrator-github-deploy` | trusted only for this repo's `main` branch (`sub` = `repo:mr-mtd@49491060/springboot_mcp_orchestrator@1474177883:ref:refs/heads/main`, or the older `repo:mr-mtd/springboot_mcp_orchestrator:ref:refs/heads/main`); may only `ssm:SendCommand` `AWS-RunShellScript` on the instance and read the result |
| GitHub repo variable | `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::988233322191:role/transport-orchestrator-github-deploy` |
| GitHub repo variable | `EC2_INSTANCE_ID` | `i-01166c968e71e477a` |
| GitHub repo variable | `CORS_ALLOWED_ORIGINS` | optional |

No GitHub secrets are needed.

Manual: in a Session Manager shell, `sudo /opt/transport-mcp/run-orchestrator.sh` (restarts the
`main` tag). Logs: `sudo docker logs -f transport-mcp-orchestrator`.

## Cost and abuse

CloudFront, VPC origins and SSM Parameter Store (standard tier) add nothing at this traffic.
The chat endpoint is public and every call spends OpenAI tokens, so set a monthly budget limit in
the OpenAI dashboard. If the endpoint gets abused, add a rate-based AWS WAF rule to the
distribution (paid) or require a key.
