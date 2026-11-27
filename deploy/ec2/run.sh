#!/bin/bash
# (Re)starts the orchestrator container on the MCP server's EC2 host.
#
# The deploy job in .github/workflows/build.yml copies this file to /opt/transport-mcp/run-orchestrator.sh
# and runs it with IMAGE_TAG and CORS_ALLOWED_ORIGINS set. To redeploy by hand, in a Session Manager
# shell: sudo /opt/transport-mcp/run-orchestrator.sh
#
# - Joins the "transport" Docker network and calls the MCP server by container name, so the MCP
#   server keeps no published port beyond 127.0.0.1:8080.
# - Publishes 8081 (the chat API) for CloudFront. The instance's security group only lets in
#   CloudFront's VPC origin, so the port isn't reachable from the internet directly.
# - Puts actuator (health, metrics) on 8082, published on 127.0.0.1 only, so it isn't public.
# - Reads the OpenAI key from SSM Parameter Store at start; it's never written to disk.
set -euo pipefail

REGION=us-east-2
NAME=transport-mcp-orchestrator
IMAGE="ghcr.io/mr-mtd/transport-mcp-orchestrator:${IMAGE_TAG:-main}"
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-https://dzo4l1wjzweva.cloudfront.net}"

OPENAI_API_KEY=$(aws ssm get-parameter --region "$REGION" --name /transport-mcp/openai-api-key \
  --with-decryption --query Parameter.Value --output text)
export OPENAI_API_KEY

docker network inspect transport >/dev/null 2>&1 || docker network create transport
docker pull "$IMAGE"
docker rm -f "$NAME" >/dev/null 2>&1 || true
# The t3.micro has ~900 MB of RAM shared with the MCP server (600 MB cap), plus 1 GB of swap.
docker run -d --name "$NAME" --network transport --restart unless-stopped \
  -p 8081:8081 -p 127.0.0.1:8082:8082 --memory 450m \
  -e JAVA_TOOL_OPTIONS='-Xmx200m -XX:+UseSerialGC' \
  -e MANAGEMENT_SERVER_PORT=8082 \
  -e MCP_SERVER_URL=http://transport-mcp-server:8080 \
  -e CORS_ALLOWED_ORIGINS \
  -e OPENAI_API_KEY \
  "$IMAGE"
docker image prune -f >/dev/null
echo "Started $IMAGE"
