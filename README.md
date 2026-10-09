# Agentbox

A small self-hosted platform that runs each AI agent in its own Docker container. You start agents with a name and a role, send one a task, and a lead can hand part of that task to another agent by name.

## Stack

Java 21 and Spring Boot 3 serve both processes from one jar: the orchestrator, and the agent HTTP server inside each container. [docker-java](https://github.com/docker-java/docker-java) talks to the Docker Engine API. The [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) is the default model client. `LLM_PROVIDER=openai` uses a small HTTP client against any OpenAI-compatible `/chat/completions` endpoint, behind the same interface the tests mock. Maven builds the jar. Agent containers use `eclipse-temurin:21-jre`. The UI is one static page served by the orchestrator.

## What this does

- Build an agent image whose process receives a task over HTTP, calls the model with four tools (`run_shell`, `read_file`, `write_file`, `message_agent`), and loops until the model answers or it hits a step limit.
- Start, list, message, and remove agents through the orchestrator. Each agent is a container on the Compose network, with its own workspace at `/workspace`.
- Let one agent delegate by calling `message_agent` with another agent's name. The call goes back through the orchestrator, which forwards it to that container.
- Create agents, list them, and chat from the page at `/`, or with `curl`.

Each task is independent. The agent does not remember earlier messages.

## What is left out

No memory, schedulers, browser tools, authentication, streaming, or multi-user accounts. The page is not a product console. Agents are not given the Docker socket. The orchestrator API is open on its port and on the Docker network: any agent can call it, including create and remove. Do not publish port 8091 beyond your machine.

A shell command runs as the same user as the agent server. The child process environment is scrubbed so `env` does not print API keys, but this is not a stronger boundary than the container.

## Quickstart

You need Docker with Compose, and a model key.

```bash
cp .env.example .env
```

Set `ANTHROPIC_API_KEY` in `.env`. To use an OpenAI-compatible server instead, set `LLM_PROVIDER=openai`, `OPENAI_API_KEY`, `OPENAI_BASE_URL`, and `OPENAI_MODEL`.

Ollama on the host is that kind of server. From a container, Docker Desktop reaches the host as `host.docker.internal` (Ollama's default port is 11434). In `.env`:

```bash
LLM_PROVIDER=openai
OPENAI_BASE_URL=http://host.docker.internal:11434/v1
OPENAI_MODEL=gemma4:12b
```

Ollama does not check `OPENAI_API_KEY`; leave it empty. The same variables are commented in `.env.example`.

Build the agent image, then start the orchestrator. Compose only builds the orchestrator; agents are started later from the image tag.

```bash
docker build -f Dockerfile --target agent -t agentbox-agent:latest .
docker compose up --build
```

Open [http://127.0.0.1:8091](http://127.0.0.1:8091).

Start two agents:

- Name `worker`, role `You do hands-on work in your workspace. Use the shell and files. Do not delegate.`
- Name `lead`, role `You are the lead. Delegate hands-on file and shell work to the agent named worker, then summarize the worker reply.`

Select `lead` and send:

```text
Ask worker to write hello.txt containing exactly hello from worker, then tell me what the worker replied.
```

The lead should call `message_agent`. The worker writes the file in its own container and the lead summarizes the reply. The page shows the tool trace under the answer.

The same steps with curl:

```bash
curl -s -X POST http://127.0.0.1:8091/agents \
  -H 'content-type: application/json' \
  -d '{"name":"worker","role":"You do hands-on work in your workspace. Use the shell and files. Do not delegate."}'

curl -s -X POST http://127.0.0.1:8091/agents \
  -H 'content-type: application/json' \
  -d '{"name":"lead","role":"You are the lead. Delegate hands-on file and shell work to the agent named worker, then summarize the worker reply."}'

curl -s -X POST http://127.0.0.1:8091/agents/lead/message \
  -H 'content-type: application/json' \
  -d '{"message":"Ask worker to write hello.txt containing exactly hello from worker, then tell me what the worker replied."}'

curl -s http://127.0.0.1:8091/agents
curl -s -X DELETE http://127.0.0.1:8091/agents/lead
curl -s -X DELETE http://127.0.0.1:8091/agents/worker
```

`docker logs agentbox-lead` shows tool calls. `docker ps --filter label=agentbox.managed=true` lists agent containers.

## Tests

Tests mock the model and the Docker client. They do not need an API key or a running daemon.

```bash
mvn test
```

## API

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| `GET` | `/` | | HTML page |
| `GET` | `/health` | | `{"status":"ok"}` |
| `GET` | `/agents` | | array of `{name, role, status, container}` |
| `POST` | `/agents` | `{"name","role"}` | `201` and the new agent |
| `POST` | `/agents/{name}/message` | `{"message","hops?"}` | `{reply, steps, trace}` |
| `DELETE` | `/agents/{name}` | | `204` |

Names are 1–32 characters: a lowercase letter, then lowercase letters, digits, or hyphens. `hops` limits delegation chains (default 4). The original request uses `0`. Each `message_agent` call sends `hops + 1`.

`DELETE /agents/{name}` removes the container when it is already exited. `POST /agents` with the same name replaces that exited container and starts a new one. A running agent with that name is still a conflict.

Inside an agent container, `GET /health` and `POST /task` are the only routes the orchestrator calls. Those ports are not published on the host.

## Configuration

Compose reads `.env` for substitution. Empty defaults still start the orchestrator; a task fails with a clear error if the selected provider has no key.

| Variable | Default | Used for |
| --- | --- | --- |
| `LLM_PROVIDER` | `anthropic` | `anthropic` or `openai` |
| `ANTHROPIC_API_KEY` | empty | Claude requests |
| `ANTHROPIC_MODEL` | `claude-sonnet-5-5` | Claude model id |
| `OPENAI_API_KEY` | empty | Bearer token for the compatible endpoint |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Compatible server, no trailing path beyond `/v1` |
| `OPENAI_MODEL` | `gpt-4o-mini` | Model name sent to that server |
| `LLM_MAX_TOKENS` | `8192` | Max tokens per model call |
| `AGENT_IMAGE` | `agentbox-agent:latest` | Image the orchestrator starts |
| `DOCKER_NETWORK` | `agentbox_default` | Network agents join. This matches `name: agentbox` in Compose |
| `AGENT_MEM_LIMIT_BYTES` | `536870912` | Memory limit per agent (512 MiB) |
| `AGENT_NANO_CPUS` | `1000000000` | CPU quota per agent (1 CPU) |
| `TASK_TIMEOUT` | `180s` | How long the orchestrator waits for an agent |
| `MAX_HOPS` | `4` | Delegation depth |
| `AGENT_MAX_STEPS` | `15` | Tool rounds inside one task |

`ORCHESTRATOR_URL` inside Compose is `http://orchestrator:8091`. The orchestrator injects that into each agent so `message_agent` can reach it by service name. Do not point it at `127.0.0.1`; that address inside a container is the container itself.

## Troubleshooting

- **Image not found.** Build the agent target and tag it `agentbox-agent:latest` before creating an agent.
- **Network not found.** Leave `DOCKER_NETWORK` as `agentbox_default`, or set it to the network Compose created (`docker network ls`).
- **Agent did not become ready.** `docker logs agentbox-<name>` shows the agent process. A missing API key surfaces when you send a task, not when the container starts.
- **Delegation timed out.** The lead's request includes the worker's whole task. Both have to finish within `TASK_TIMEOUT`.
