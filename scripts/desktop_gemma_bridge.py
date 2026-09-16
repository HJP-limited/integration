"""JSON-lines local subprocess bridge; tools are executed ONLY by the JVM kernel.

Uses the installed LiteRT-LM native runtime and the deployment's actual model.
No network server, automatic tool execution, fallback model or fake responses.
"""
import hashlib
import json
import sys
from pathlib import Path

PREFIX = "HJP_BRIDGE:"
MODEL_SHA = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"


def main():
    import litert_lm
    from litert_lm.interfaces import Tool

    class CatalogTool(Tool):
        def __init__(self, description):
            self.description = description

        def get_tool_description(self):
            return {"type": "function", "function": self.description}

        def execute(self, param):
            raise RuntimeError("Only the JVM kernel may execute tools")

    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    model = Path(sys.argv[1])
    with model.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != MODEL_SHA:
            raise RuntimeError("Deployment Gemma SHA-256 mismatch")
    engine = litert_lm.Engine(str(model), backend=litert_lm.Backend.CPU(),
                              max_num_tokens=8192, cache_dir=sys.argv[2])
    conversation = None
    config = None
    try:
        for line in sys.stdin:
            try:
                request = json.loads(line)
                op = request["op"]
                if op in ("open", "reset"):
                    if op == "open":
                        config = request
                    if conversation is not None:
                        conversation.close()
                    conversation = engine.create_conversation(
                        system_message=config["system"],
                        tools=[CatalogTool(t) for t in config["tools"]],
                        automatic_tool_calling=False,
                        sampler_config=litert_lm.SamplerConfig(
                            temperature=config["temperature"], top_k=config["top_k"],
                            top_p=config["top_p"]),
                    )
                    response = {"ready": True}
                elif op == "read":
                    read_conversation = engine.create_conversation(
                        system_message=config["system"], tools=[], automatic_tool_calling=False,
                        sampler_config=litert_lm.SamplerConfig(temperature=config["temperature"],
                            top_k=config["top_k"], top_p=config["top_p"]),
                    )
                    try:
                        response = read_conversation.send_message(request["message"])
                        if response.get("tool_calls"):
                            raise RuntimeError("Tool calls are forbidden in read-answer generation")
                    finally:
                        read_conversation.close()
                elif op == "send":
                    response = conversation.send_message(request["message"])
                else:
                    raise ValueError(f"Unknown operation: {op}")
                print(PREFIX + json.dumps({"result": response}, ensure_ascii=False), flush=True)
            except Exception as error:
                print(PREFIX + json.dumps({"error": str(error)}, ensure_ascii=False), flush=True)
    finally:
        if conversation is not None:
            conversation.close()
        engine.close()


if __name__ == "__main__":
    main()
