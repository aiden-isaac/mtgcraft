"""Run: python3 tests/lifecycle.py. Disposable Minecraft/engine/native-client JVMs."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

root = Path(__file__).resolve().parents[1]
directory = Path(tempfile.mkdtemp(prefix="lifecycle-", dir=root / ".cache"))
mc = directory / "minecraft"
mc.mkdir()
world_dir = mc / "world"
processes = []
logs = []
measurements = {"control_ms": [], "memory": [], "startup_ms": []}

def launch(args, log_name, cwd=root):
    log = (directory / log_name).open("w")
    logs.append(log)
    process = subprocess.Popen(args, cwd=cwd, stdout=log, stderr=log)
    processes.append(process)
    return process

def wait(condition, seconds=90):
    deadline = time.monotonic() + seconds
    while not condition():
        assert time.monotonic() < deadline, f"Deadline; inspect {directory}"
        time.sleep(0.1)

def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

def minecraft(mode):
    return launch(["bash", str(root / ".cache/upstream/mtgcard/gradlew"), "-p", str(root / "addon"),
                   f"-Dcustody.runDir={mc}", f"-Dcustody.lifecycle={mode}", "runCustodyProbe", "--no-daemon", "--console=plain"], f"minecraft-{mode}.log")

try:
    subprocess.run(["bash", "scripts/check.sh"], cwd=root, check=True)
    eula = (root / ".cache/upstream/mtgcard/fabric/run/eula.txt").read_text()
    assert eula.strip().endswith("eula=true"), "Needs the previously approved disposable EULA"
    (mc / "eula.txt").write_text(eula)
    (mc / "server.properties").write_text('server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=minecraft:flat\n'
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n'
        'generate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-tick-time=60000\n')
    seed = minecraft("seed")
    assert seed.wait(timeout=90) == 0
    assert "PASS real lifecycle seed" in (directory / "minecraft-seed.log").read_text()
    requests = [json.loads((world_dir / f"request-{i}.json").read_text()) for i in range(2)]
    world = requests[0]["worldId"]
    token = secrets.token_urlsafe(32)
    token_file = directory / "token"
    token_file.write_text(token)
    token_file.chmod(0o600)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    endpoint = f"http://127.0.0.1:{port}"
    headers = {"Authorization": f"Bearer {token}", "X-Mtgcraft-World": world, "X-Mtgcraft-Version": "1", "Content-Type": "application/json"}
    config = directory / "service.properties"
    config.write_text(f"worldId={world}\ntokenFile={token_file}\nmappingFile={root}/service/catalog/manifest.json\n"
                      f"nativeConfig={root}/spikes/xmage/config.xml\nport={port}\nactiveMatchLimit=2\n")
    (mc / "config").mkdir(exist_ok=True)
    (mc / "config/mtgcraft.properties").write_text(f"endpoint={endpoint}\ntokenFile={token_file}\nadmissionEnabled=false\n")
    def call(method, path, body=None):
        started = time.monotonic()
        request = urllib.request.Request(endpoint + path, data=None if body is None else json.dumps(body).encode(), method=method, headers=headers)
        with urllib.request.urlopen(request, timeout=12) as response:
            value = json.load(response)
        measurements["control_ms"].append({"operation": method + " " + path.split("/")[-1], "ms": (time.monotonic() - started) * 1000})
        return value
    def start_service(attempt):
        started = time.monotonic()
        process = launch(["bash", str(root / "scripts/run-service.sh"), str(config), str(directory / "state")], f"engine-{attempt}.log")
        health = None
        def ready():
            nonlocal health
            assert process.poll() is None, f"Engine exited; inspect {directory}"
            try:
                health = call("GET", "/v1/health")
                return health["ready"]
            except urllib.error.URLError:
                return False
        wait(ready, 90)
        measurements["startup_ms"].append((time.monotonic() - started) * 1000)
        return process, health
    def memory(process, phase):
        rss = next(line for line in Path(f"/proc/{process.pid}/status").read_text().splitlines() if line.startswith("VmRSS:"))
        heap = subprocess.check_output(["jcmd", str(process.pid), "GC.heap_info"], text=True)
        (directory / f"heap-{phase}.txt").write_text(heap)
        measurements["memory"].append({"phase": phase, "rss_kib": int(rss.split()[1]), "heap_info": heap})
    engine, health = start_service(0)
    incarnation = health["serviceIncarnation"]
    memory(engine, "ready")
    statuses = []
    for i, request in enumerate(requests):
        status = call("PUT", "/v1/matches/" + request["matchId"], request)
        assert status["state"] == "PREPARED" and status["snapshotDigest"] == request["snapshot"]["digest"]
        assert call("PUT", "/v1/matches/" + request["matchId"], request) == status
        statuses.append(status)
        memory(engine, f"prepared-{i + 1}")
    native_cp = ":".join([str(root / ".cache/project/shared"), str(root / ".cache/project/service"), str(root / ".cache/project/tests"),
                          str(root / ".cache/upstream/mage/Mage.Server/target/mage-server-1.4.61.jar"), (root / ".cache/xmage-server-classpath.txt").read_text().strip()])
    native = []
    paths = []
    for i, request in enumerate(requests):
        match = request["matchId"]
        access = call("POST", f"/v1/matches/{match}/seat-access", {"protocolVersion": 1, "worldId": world, "playerId": request["seats"][0]["playerId"]})
        record = json.loads((directory / "state" / f"match-{match}.json").read_text())
        other = json.loads((directory / "state" / f'match-{requests[1 - i]["matchId"]}.json').read_text())
        properties = {"username": access["username"], "password": access["password"], "room": record["roomId"], "table": record["tableId"],
                      "player": record["humanPlayerId"], "otherTable": other["tableId"], "play": "true", "hold": "true", "deadlineSeconds": "240"}
        path = directory / f"native-{i}.properties"
        path.write_text("".join(f"{key}={value}\n" for key, value in properties.items()))
        path.chmod(0o600)
        client = launch(["java", "-ea", "-Xmx512m", "--add-opens=java.base/java.io=ALL-UNNAMED", "-cp", native_cp, "mtgcraft.NativeCheck", str(path)], f"native-{i}.log", cwd=directory)
        native.append(client)
        paths.append(path)
        wait(lambda: Path(str(path) + ".ready").exists() or client.poll() is not None, 60)
        assert client.poll() is None
        operation = {"protocolVersion": 1, "worldId": world, "requestDigest": request["requestDigest"]}
        assert call("POST", f"/v1/matches/{match}/start", operation)["state"] == "ACTIVE"
        wait(lambda: Path(str(path) + ".playing").exists() or client.poll() is not None, 60)
        assert client.poll() is None
    memory(engine, "active-two")
    active = minecraft("active")
    assert active.wait(timeout=90) != 0 and (world_dir / "mc-active").exists()
    assert len(list((directory / "state").glob("match-*.json"))) == 2
    print("PASS Minecraft hard restart while two real XMage games/native clients survive", flush=True)
    Path(str(paths[0]) + ".release").write_text("concede")
    def completed():
        return call("GET", f'/v1/matches/{requests[0]["matchId"]}')["state"] == "COMPLETED"
    wait(completed, 60)
    Path(str(paths[0]) + ".done").write_text("durable")
    assert native[0].wait(timeout=15) == 0
    memory(engine, "one-cleaned")
    finish = minecraft("finish")
    wait(lambda: (world_dir / "mc-completed").exists() or finish.poll() is not None, 90)
    assert finish.poll() is None
    stop(engine)
    stop(native[1])
    time.sleep(6)  # Demonstrate unreachability while Minecraft remains alive and keeps custody.
    assert finish.poll() is None and not (world_dir / "interrupted.json").exists()
    engine, replacement = start_service(1)
    assert replacement["serviceIncarnation"] != incarnation
    assert finish.wait(timeout=120) == 0
    assert "PASS real engine lifecycle" in (directory / "minecraft-finish.log").read_text()
    completed_status = json.loads((world_dir / "completed.json").read_text())
    interrupted_status = json.loads((world_dir / "interrupted.json").read_text())
    assert completed_status == call("GET", f'/v1/matches/{requests[0]["matchId"]}')
    assert interrupted_status["serviceIncarnation"] == incarnation and interrupted_status["state"] == "INTERRUPTED"
    assert all(outcome["outcome"] == "NONE" for outcome in interrupted_status["result"]["outcomes"])
    assert len(list((directory / "state").glob("match-*.json"))) == 2
    (mc / "config/mtgcraft.properties").write_text(f"endpoint={endpoint}\ntokenFile={token_file}\nadmissionEnabled=true\n")
    unsupported = minecraft("unsupported")
    assert unsupported.wait(timeout=90) == 0
    assert "PASS real unsupported command" in (directory / "minecraft-unsupported.log").read_text()
    assert len(list((directory / "state").glob("match-*.json"))) == 2
    print("PASS unsupported real MTGCard submission over authenticated control with no reservation or engine match", flush=True)
    measurements["workload"] = "Two 40-Plains human decks vs pinned 60-Plains MAD AI; native concession, then process-fenced interruption"
    (directory / "measurements.json").write_text(json.dumps(measurements, indent=2))
    print(f"PASS actual lifecycle/restart/fencing/one-time physical returns; diagnostics: {directory}", flush=True)
finally:
    for process in reversed(processes):
        stop(process)
    for log in logs:
        log.close()
