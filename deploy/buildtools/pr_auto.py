"""Automatic partial reconfiguration (F2 nested DFX): pick the reconfigurable partition (RP) from the RTL change.

A recipe sets only `enable_pr: true`. Before Golden Gate runs, the build hashes the target CHIRRTL
(`make hash-manifest`) and compares it with the hash manifests of earlier automatic builds in results-build:
  child: some earlier base's RP covers every change and keeps its ports -> RM swap into that base;
  base:  otherwise the RP is the lowest common ancestor of the changes against the earlier automatic build that
         gives the smallest one (one instance, unique module, not the top, at most `MAX_RP_FRACTION` of the
         design) -> new base,
         whose pass 1 derives the RP region from where that RP places (pr_derive_region.tcl);
  flat:  no earlier automatic build, or no usable RP -> flat build (its manifest is the next reference).
Each successful build leaves `pr_auto.json` and its manifest in its results-build directory.
"""

from __future__ import annotations

import glob
import json
import os
import re
import sys
from collections import defaultdict
from typing import Any, Dict, List, Optional

RECORD = "pr_auto.json"
MANIFEST = "pr_auto.hash-manifest.json"
MAX_RP_FRACTION = 0.25


class Manifest:
    """A `chisel-hash-manifest/1` file (midas.passes.ReconfigurablePartitionHashManifest)."""

    def __init__(self, path: str) -> None:
        m = json.load(open(path))
        mods = m["modules"]
        self.top: str = m["top"]
        self.body = {n: v["body"] for n, v in mods.items()}
        self.full = {n: v["full"] for n, v in mods.items()}
        self.iface = {n: v["iface"] for n, v in mods.items()}
        self.insts = {n: [(c, i) for i, c in v["children"] if c in mods] for n, v in mods.items()}
        self.n_ops = {n: v["n_ops"] for n, v in mods.items()}
        self._size: Dict[str, int] = {}
        self.uses: Dict[str, int] = defaultdict(int)
        self.module_at: Dict[str, str] = {}
        stack = [(self.top, self.top)]
        while stack:
            path, mod = stack.pop()
            self.module_at[path] = mod
            self.uses[mod] += 1
            stack.extend((f"{path}/{i}", c) for c, i in self.insts[mod])

    def size(self, n: str) -> int:
        if n not in self._size:
            self._size[n] = self.n_ops[n] + sum(self.size(c) for c, _ in self.insts[n])
        return self._size[n]


def _stem(mod: str) -> str:
    return re.sub(r"(_\d+)+$", "", mod)


def diff(a: Manifest, b: Manifest) -> Dict[str, Any]:
    """Changed instances of b against a: `pairs` maps each new path to (old module, new module); `cuts` are the
    lowest ancestors with unchanged ports covering every change."""
    sys.setrecursionlimit(200000)
    dirty: List[str] = []
    added: List[str] = []
    removed: List[str] = []
    pairs: Dict[str, Any] = {}

    def rec(pa: str, pb: str, ma: str, mb: str) -> None:
        pairs[pb] = (ma, mb)
        if a.full[ma] == b.full[mb]:
            return
        if a.body[ma] != b.body[mb]:
            dirty.append(pb)
        ca = {i: mt for mt, i in a.insts[ma]}
        cb = {i: mt for mt, i in b.insts[mb]}
        ua, ub = set(ca), set(cb)

        def pair(i: str, j: str) -> None:
            ua.discard(i)
            ub.discard(j)
            rec(f"{pa}/{i}", f"{pb}/{j}", ca[i], cb[j])

        # same name and subtree; same subtree under another name; same name; same module-name stem
        for i in sorted(ua & ub):
            if a.full[ca[i]] == b.full[cb[i]]:
                pair(i, i)
        byk = defaultdict(list)
        for i in sorted(ua):
            byk[a.full[ca[i]]].append(i)
        for j in sorted(ub):
            if byk.get(b.full[cb[j]]):
                pair(byk[b.full[cb[j]]].pop(0), j)
        for i in sorted(ua & ub):
            pair(i, i)
        byk = defaultdict(list)
        for i in sorted(ua):
            byk[_stem(ca[i])].append(i)
        for j in sorted(ub):
            if byk.get(_stem(cb[j])):
                pair(byk[_stem(cb[j])].pop(0), j)
        added.extend(f"{pb}/{j}" for j in sorted(ub))
        removed.extend(f"{pa}/{i}" for i in sorted(ua))

    rec(a.top, b.top, a.top, b.top)
    seeds = sorted(set(dirty) | {p.rsplit("/", 1)[0] for p in added + removed})

    def stable(p: str) -> bool:
        ma, mb = pairs[p]
        return a.iface[ma] == b.iface[mb]

    lowest = set()
    for s in seeds:
        p = s
        while p != b.top and not stable(p):
            p = p.rsplit("/", 1)[0]
        lowest.add(p)
    cuts = sorted(c for c in lowest if not any(c != o and c.startswith(o + "/") for o in lowest))
    return {"pairs": pairs, "cuts": cuts, "stable": stable}


def _under(path: str, rp: str) -> bool:
    return path == rp or path.startswith(rp + "/")


def _records(results_build_dir: str) -> List[Dict[str, Any]]:
    """Successful automatic builds, newest first."""
    out = []
    for f in sorted(glob.glob(f"{results_build_dir}/*/{RECORD}"), reverse=True):
        r = json.load(open(f))
        r["dir"] = os.path.dirname(f)
        r["manifest_path"] = os.path.join(r["dir"], MANIFEST)
        out.append(r)
    return out


def _instance_target(m: Manifest, path: str) -> str:
    parent, inst = path.rsplit("/", 1)
    return f"~{m.top}|{m.module_at[parent]}/{inst}:{m.module_at[path]}"


def plan(manifest_path: str, results_build_dir: str) -> Dict[str, Any]:
    """Role (child/base/flat) of the design in `manifest_path`, with its RP and, for a child, its base."""
    new = Manifest(manifest_path)
    records = _records(results_build_dir)

    for r in records:
        if r["role"] != "base":
            continue
        d = diff(Manifest(r["manifest_path"]), new)
        rps = r.get("rp_paths") or [r["rp_path"]]
        # an RP outside `pairs` lies in a subtree that did not change at all
        if all(rp in new.module_at and (rp not in d["pairs"] or d["stable"](rp)) and new.uses[new.module_at[rp]] == 1
               for rp in rps) and all(any(_under(c, rp) for rp in rps) for c in d["cuts"]):
            changed = [rp for rp in rps if any(_under(c, rp) for c in d["cuts"])]
            return {"role": "child", **_rp_fields(new, rps), "changed_rp_paths": changed, "base_dir": r["dir"],
                    "cuts": d["cuts"],
                    "reason": f"all {len(d['cuts'])} change(s) inside RP(s) {', '.join(changed)} of base "
                              f"{os.path.basename(r['dir'])}"}

    if not records:
        return {"role": "flat", "reason": "no earlier automatic build to compare against"}
    # New base: disjoint RPs covering the changes against any earlier automatic build, smallest total first.
    best: Optional[Dict[str, Any]] = None
    for r in records:
        d = diff(Manifest(r["manifest_path"]), new)
        if not d["cuts"]:
            continue
        sticky = [p for p in (r.get("rp_paths") or ([r["rp_path"]] if r.get("rp_path") else []))
                  if r["role"] == "base" and p in new.module_at]
        rps = _group(new, d["cuts"] + sticky)
        frac = sum(new.size(new.module_at[p]) for p in rps) / new.size(new.top)
        if best is None or frac < best["rp_fraction"]:
            best = {"rp_paths": rps, "rp_fraction": round(frac, 4), "cuts": d["cuts"], "ref": os.path.basename(r["dir"])}
    if best is None:
        return {"role": "flat", "reason": "no change against an earlier automatic build and no base covers it"}
    rps, frac = best["rp_paths"], best["rp_fraction"]
    if new.top in rps or frac > MAX_RP_FRACTION:
        return {"role": "flat", "cuts": best["cuts"],
                "reason": f"changes need {', '.join(rps)} ({frac:.0%} of the design) against {best['ref']}"}
    return {"role": "base", **_rp_fields(new, rps), "cuts": best["cuts"], "rp_fraction": frac,
            "reason": f"{len(best['cuts'])} change(s) against {best['ref']}; {len(rps)} RP(s) {', '.join(rps)} "
                      f"({frac:.1%} of the design)"}


def _unique(m: Manifest, p: str) -> str:
    while p != m.top and m.uses[m.module_at[p]] != 1:
        p = p.rsplit("/", 1)[0]
    return p


def _group(m: Manifest, paths: List[str]) -> List[str]:
    """One RP per changed module (each walked up to a unique module); RPs are never merged, only an RP nested
    inside another is dropped (keep granularity)."""
    rps = {_unique(m, p) for p in paths}
    return sorted(p for p in rps if not any(p != o and _under(p, o) for o in rps))


def _rp_fields(m: Manifest, rps: List[str]) -> Dict[str, Any]:
    return {"rp_paths": rps, "rp_modules": [m.module_at[p] for p in rps],
            "rp_targets": [_instance_target(m, p) for p in rps]}


def annotations(p: Dict[str, Any]) -> List[Dict[str, str]]:
    """Golden Gate annotations for a plan: mark the RP (its ports are then kept as written)."""
    if p["role"] == "flat":
        return []
    top = p["rp_targets"][0].split("|")[0]
    return [{"class": "midas.targetutils.FirrtlReconfigurablePartitionAnnotation", "target": t} for t in p["rp_targets"]] \
        + [{"class": "firrtl.transforms.NoDedupAnnotation", "target": f"{top}|{m}"} for m in p["rp_modules"]]


def record(p: Dict[str, Any], manifest_path: str, local_results_dir: str) -> None:
    """Leave the plan and its manifest with a successful build so later builds can use it."""
    os.makedirs(local_results_dir, exist_ok=True)
    with open(manifest_path) as src, open(os.path.join(local_results_dir, MANIFEST), "w") as dst:
        dst.write(src.read())
    with open(os.path.join(local_results_dir, RECORD), "w") as f:
        json.dump(p, f, indent=1)


if __name__ == "__main__":
    # usage: python3 pr_auto.py <manifest.json> <results-build dir>   (prints the plan)
    print(json.dumps(plan(sys.argv[1], sys.argv[2]), indent=1))
