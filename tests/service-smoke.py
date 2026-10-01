"""Run: python3 tests/service-smoke.py. Starts/stops real pinned XMage processes."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="service-smoke-", dir=root / ".cache") as temporary:
    directory = Path(temporary)
    token = secrets.token_urlsafe(32)
    token_file = directory / "token"
    token_file.write_text(token)
    token_file.chmod(0o600)
    world = str(uuid.uuid4())
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    config = directory / "config.properties"
    config.write_text(f"worldId={world}\ntokenFile={token_file}\n"
                      f"mappingFile={root}/service/catalog/manifest.json\n"
                      f"nativeConfig={root}/spikes/xmage/config.xml\nport={port}\nactiveMatchLimit=2\n")
    incarnations = []
    for attempt in range(2):
        with (directory / f"process-{attempt}.log").open("w") as log:
            process = subprocess.Popen(["bash", str(root / "scripts/run-service.sh"), str(config), str(directory / "state")],
                                       cwd=root, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 60
                while True:
                    assert process.poll() is None, "service exited; inspect the smoke log"
                    request = urllib.request.Request(f"http://127.0.0.1:{port}/v1/health", headers={
                        "Authorization": f"Bearer {token}", "X-Mtgcraft-World": world, "X-Mtgcraft-Version": "1"})
                    try:
                        with urllib.request.urlopen(request, timeout=3) as response:
                            health = json.load(response)
                        break
                    except urllib.error.URLError:
                        assert time.monotonic() < deadline, "service did not reach HTTP readiness"
                        time.sleep(0.2)
                assert health["worldId"] == world and health["activeMatchLimit"] == 2
                assert health["compatibility"]["engineBuild"] == "7f42784132e054d0c5b6c581fa63fed5893327ab"
                incarnation = health["serviceIncarnation"]
                assert (directory / "state/incarnation").read_text().strip() == incarnation
                incarnations.append(incarnation)
                with socket.create_connection(("127.0.0.1", 17171), timeout=3):
                    pass
                print(f"PASS real XMage startup/HTTP health, attempt={attempt + 1}", flush=True)
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    assert incarnations[0] != incarnations[1]
    print("PASS exclusive durable service incarnation changes on process restart")
