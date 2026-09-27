"""Where the store spends its CPU: the hottest frames of a JFR recording.

  LT_JFR=1 scripts/load-test.sh store restaurant   # writes .loadtest/store-*/store.jfr
  python3 loadtest/lt/jfrtop.py .loadtest/store-restaurant/store.jfr
"""
import collections
import json
import subprocess
import sys


def main(path: str, top: int = 25) -> None:
    out = subprocess.run(["jfr", "print", "--json", "--stack-depth", "64", "--events", "jdk.ExecutionSample", path],
                         capture_output=True, text=True, check=True).stdout
    events = json.loads(out)["recording"]["events"]
    leaf, ours = collections.Counter(), collections.Counter()
    for e in events:
        frames = e["values"]["stackTrace"]["frames"]
        if not frames:
            continue
        m = frames[0]["method"]
        leaf[f"{m['type']['name']}.{m['name']}"] += 1
        # the first frame in our own code: which feature is burning the CPU
        for f in frames:
            t = f["method"]["type"]["name"]
            if t.replace("/", ".").startswith("dev.dwhipstock") and "Lambda" not in t:
                ours[f"{t}.{f['method']['name']}"] += 1
                break
    n = len(events)
    print(f"{n} samples\n\nhottest leaf frames:")
    for k, v in leaf.most_common(top):
        print(f"  {v * 100 / n:5.1f}%  {k}")
    print("\nfirst frame in our code:")
    for k, v in ours.most_common(top):
        print(f"  {v * 100 / n:5.1f}%  {k}")


if __name__ == "__main__":
    main(sys.argv[1])
