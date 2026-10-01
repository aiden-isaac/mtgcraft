"""Inventory pinned artifacts and locally declared licenses; no inferred legal grants."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
maven = Path.home() / ".m2/repository"
gradle = Path.home() / ".gradle/caches/modules-2/files-2.1"

def pom(group, artifact, version):
    path = maven / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.pom"
    return path if path.exists() else next((gradle / group / artifact / version).glob(f"*/{artifact}-{version}.pom"), None)

def licenses(path, seen=None):
    seen = set() if seen is None else seen
    if path is None or path in seen:
        return None
    seen.add(path)
    document = ET.parse(path).getroot()
    names = [" ".join(node.findtext("{*}name", "").split()) for node in document.findall("{*}licenses/{*}license")]
    if any(names):
        return "; ".join(names), "local POM" if len(seen) == 1 else "inherited local POM"
    parent = document.find("{*}parent")
    if parent is not None:
        values = [parent.findtext("{*}" + key, "") for key in ("groupId", "artifactId", "version")]
        if all(values) and not any("$" in value for value in values):
            return licenses(pom(*values), seen)
    return None

rows = []
for item in json.loads((root / "spikes/artifacts.lock.json").read_text())["artifacts"]:
    artifact = item["artifact"]
    if artifact.startswith(".cache/upstream/"):
        mtgcard = "mtgcard/" in artifact
        declared = "MIT; conflicting LICENSE.txt says all rights reserved" if mtgcard else "MIT"
        basis = "pinned upstream source LICENSE files"
    else:
        parts = Path(artifact).parts
        group, name, version = ".".join(parts[:-3]), parts[-3], parts[-2]
        found = licenses(pom(group, name, version))
        if found is None and group == "org.mage":
            found = ("MIT", "pinned XMage source LICENSE.txt")
        declared, basis = found or ("UNKNOWN", "no local declaration resolved")
    rows.append(("xmage", artifact, item["sha256"], declared, basis))
for item in json.loads((root / "spikes/mtgcard/dependencies.lock.json").read_text()):
    coordinate = item["artifact"]
    parts = coordinate.split(":")
    found = licenses(pom(*parts)) if len(parts) == 3 else None
    if found is None and len(parts) == 3:
        jar = next((gradle / parts[0] / parts[1] / parts[2]).glob("*/" + item["filename"]), None)
        if jar is not None:
            with zipfile.ZipFile(jar) as archive:
                if "fabric.mod.json" in archive.namelist():
                    license_value = json.loads(archive.read("fabric.mod.json")).get("license")
                    if license_value:
                        found = ("; ".join(license_value) if isinstance(license_value, list) else license_value, "cached artifact fabric.mod.json")
    declared, basis = found or ("UNKNOWN", "no local declaration resolved")
    rows.append(("fabric", coordinate, item["sha256"], declared, basis))
assert len(rows) == 306 and all(len(row[2]) == 64 for row in rows)
target = root / "docs/dependency-notices.tsv"
target.write_text("scope\tartifact\tobserved_sha256\tdeclared_license\tevidence\n" + "".join("\t".join(row) + "\n" for row in rows))
print(f"Wrote {len(rows)} pinned artifact notices; {sum(row[3] == 'UNKNOWN' for row in rows)} declarations unresolved")
