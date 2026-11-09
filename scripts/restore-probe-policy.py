"""Restore only the probe's previously recorded OriginOS smart background policy.

Use only after an explicitly authorized temporary policy test. No retries.
"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    marker = args.output_dir / "policy-restore-required.json"
    permission = json.loads(marker.read_text(encoding="utf-8"))
    if permission.get("package") != "org.starlive.probe" or permission.get("original") != "smart" or not permission.get("user_authorized"):
        raise ValueError("No matching recorded temporary-policy authorization")
    adb = Path(__file__).resolve().parents[2] / "tools/platform-tools/adb.exe"

    def run(*command):
        result = subprocess.run([str(adb), "-s", args.device] + list(command), capture_output=True, timeout=20)
        if result.returncode:
            raise RuntimeError((result.stderr or result.stdout).decode("utf-8", errors="replace").strip())
        return result.stdout.decode("utf-8", errors="strict")

    def snapshot():
        remote = "/data/local/tmp/starlive-probe-restore.xml"
        run("shell", "uiautomator", "dump", "--compressed", remote)
        result = ET.fromstring(run("shell", "cat", remote).strip())
        run("shell", "rm", remote)
        return result

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        if x2 <= x1 or y2 <= y1:
            raise ValueError("Required navigation item is not visible")
        run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def find(tree, key, value):
        node = next((node for node in tree.iter("node") if node.attrib.get(key) == value), None)
        if node is None:
            raise ValueError("Expected probe settings control not found")
        return node

    run("shell", "am", "start", "-W", "-f", "0x34000000", "-a", "android.settings.APPLICATION_DETAILS_SETTINGS", "-d", "package:org.starlive.probe")
    tree = snapshot()
    find(tree, "text", "StarLive \u80fd\u529b\u9a8c\u8bc1")
    tap(find(tree, "text", "\u7535\u91cf"))
    tap(find(snapshot(), "text", "\u540e\u53f0\u8017\u7535\u7ba1\u7406"))
    tree = snapshot()
    find(tree, "text", "StarLive \u80fd\u529b\u9a8c\u8bc1")
    smart_id = "com.iqoo.powersaving:id/all_intel"
    allow_id = "com.iqoo.powersaving:id/all_opt"
    if find(tree, "resource-id", smart_id).attrib.get("checked") != "true":
        tap(find(tree, "resource-id", smart_id))
        tree = snapshot()
    restored = find(tree, "resource-id", smart_id).attrib.get("checked") == "true" and find(tree, "resource-id", allow_id).attrib.get("checked") == "false"
    if not restored:
        raise RuntimeError("Original smart policy could not be verified; manual restoration is required")
    result = {"package": "org.starlive.probe", "restored_policy": "smart", "verified": True}
    (args.output_dir / "policy-restored.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    marker.unlink()  # Consume the one temporary-change authorization after verification.
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, ET.ParseError) as error:
        print(json.dumps({"restore_error": str(error), "manual_restoration_may_be_required": True, "retry_attempted": False}), file=sys.stderr)
        sys.exit(1)
