# RapidWright shell flow for F2 prefetcher swaps (branch `rapidwright-scratch`)

Status: WORKS END TO END. Base and two children (AMPM, Evolved) built with this flow boot Linux on F2. Branch
`rapidwright-scratch` in both repos (from f2-dfx-chia: aws-fpga 946592f, firesim 16302757), committed on
the clone (i-089449788af2c0145) and fetched to the manager as local branches. Not pushed.

## Flow
RapidWright has no PR support of its own (FAQ: "does not have specific support for PR ... importing
them into PR-based projects in Vivado using read_checkpoint -cell ... where the cell is a black box").
Its documented DFX alternative is the tutorial "Reuse Timing-closed Logic As A Shell"; this branch
implements that, replacing our Vivado nested DFX (pr_subdivide / pr_recombine / partition pins).

| Tutorial step | F2 implementation |
|---|---|
| 1. DONT_TOUCH the dynamic module | base `synth_cl_firesim.tcl`: KEEP_HIERARCHY + DONT_TOUCH on the prefetcher |
| 2. Implement the full design | base: unmodified AWS flow (`aws_build_dcp_from_cl.py`, `build_level_1_cl.tcl`, AWS floorplan xdc) |
| 3. OOC-synth the new module | child: `synth_cl_firesim_rm.tcl` |
| 4. `rapidwright MakeBlackBox routed.dcp shell.dcp <cell>` | child `main_pr_rm.tcl`: run on the `firesim_top` cell checkpoint (+ its EDIF); its shell's PROHIBITs (half-used SLICE BELs) are extracted |
| 5. open shell, lock_design -level routing | child: full base opened; Vivado black-boxes the prefetcher (HD.PARTITION set on it), locks routing, reads the shell's PROHIBITs |
| 6. read_checkpoint -cell, waive REQP-1753, place, route | child: same, then AWS check_ddr_bram, timing/route gates, AWS tarball |

In the tutorial itself only step 4 runs in RapidWright; steps 2, 3, 5 and 6 (implement, OOC synth,
open shell + `lock_design -level routing`, `read_checkpoint -cell` + `place_design` + `route_design`)
are Vivado commands. This flow keeps that split; the only deviation is inside step 4/5 below.

Why the split: RapidWright needs a readable netlist. The F2 design (AWS top: `EDIF 20-92`) and
`WRAPPER/CL` (`Designutils 20-2284`, IEEE 1735 V2 sh_ddr helpers) can't be exported; `firesim_top` can,
but its cell checkpoint keeps only placement, never routing (t7, t9). So RapidWright can't produce the
routed shell itself; it computes the shell (black box + PROHIBITs from placement), and Vivado performs
the black box in the routed full design. Vivado allows that only for a partition: HD.PARTITION (a
Hierarchical Design property, not DFX) on the prefetcher avoids `Coretcl 2-1501` (t8).

DDR calibration ELF: the base synthesizes the real prefetcher (no stub/black box), so Vivado loads
`calibration_ddr.elf` normally (Memdata 28-144); children inherit the BRAMs from the base.

## Feasibility trials (clone, ~/rws/t1..t9)
| # | Test | Result |
|---|---|---|
| t1 | `rapidwright MakeBlackBox` on full flat routed DCP | "Unable to find a readable EDIF file for the DCP" |
| t2/t4 | write_edif full design / WRAPPER/CL | EDIF 20-92 / Designutils 20-2284 |
| t1,t3,t5,t6 | Vivado black box / from_file / HD.RECONFIGURABLE / ECO on routed prefetcher | 2-1501 / 2-1292 / 17-69 / 12-12243 |
| t7 | HD.PARTITION on firesim_top post-route | black box OK; cell DCP routing still lost (set after routing) |
| t8 | HD.PARTITION on prefetcher post-route, black box, lock, read_checkpoint -cell | black box + lock OK; read fails Netlist 29-77 (old base lacked step-1 DONT_TOUCH: boundary optimized, 294 ports missing) |
| t9 | implement post_opt with HD.PARTITION on firesim_top, write_checkpoint -cell | routed WNS 0.086/WHS 0.010; cell DCP still 533,134/533,134 nets unrouted -> HD.PARTITION doesn't keep cell routing |
| t10 | `rapidwright MakeBlackBox` on the flat-base firesim_top cell DCP + EDIF | OK, 38 s; shell has 1,828,223 PROHIBITs (half-used SLICE BELs) |
| t11 | t9 design: HD.PARTITION + black box RM, lock, read_xdc of t10 PROHIBITs | OK: bb 99 s, lock 56 s, read_xdc 193 s (1,828,223 BELs prohibited); 158 unrouted + 2 partial boundary nets left for the RM route |

## Major changes (vs f2-dfx-chia)
- Removed nested-DFX scripts: main_pr_subdivide, main_pr_export, pr_nested_impl, pr_seed_reservation,
  pr_static_ip_reuse, pr_ddr_placement, pr_derive_region, pr_nested_shell_timing.xdc.
- Reverted AWS-owned files to upstream: build_level_1_cl.tcl, aws_build_dcp_from_cl.py,
  small_shell_cl_pnr_user.xdc.
- synth_cl_firesim.tcl: no stub, no black box, no OOC RM/assembly in the base; DONT_TOUCH RM (tutorial step 1).
- synth_cl_firesim_rm.tcl: dropped the DFX constant-output-driver fix.
- pr_utils.tcl: dropped nested-only procs (HDPR DRC, region, seed check, constant drivers, report_paths).
- main_pr_rm.tcl: rewritten as tutorial steps 4-6: RapidWright MakeBlackBox (docs' bin/rapidwright) on the
  firesim_top cell checkpoint for the shell PROHIBITs; Vivado black box (HD.PARTITION) + lock + PROHIBITs +
  read_checkpoint -cell + place + route in the full base.
- rw/install_rw.sh: RapidWright per docs (git tag + ./gradlew, Vivado JDK).
- firesim: build-bitstream.sh base = plain AWS flow (+ PR env); child = RM synth + main_pr_rm.tcl + AWS tarball;
  dropped pr_region knob (bitbuilder/buildconfig/recipes).

## Tests (2026-10-24, clone c5.4xlarge manager, m8i.4xlarge build hosts)
| Build | Time to AFI | AFI | Gates | hello | Linux |
|---|---|---|---|---|---|
| `dfx_rws_base` (BestOffset) | 195 min (01:55 -> 05:10) | agfi-078c194afc723e2ae | Memdata 28-144 (ELF loaded by synth), check_ddr_bram initialized, WNS 0.138 / WHS 0.010, 0 ERROR | pass | `buildroot login:` |
| `dfx_rws_ampm` (AMPM child) | 126 min (05:10 -> 07:16) | agfi-0649d28aa33f9f15a | fully routed, WNS 0.138 / WHS 0.010, check_ddr_bram initialized, 0 ERROR, AWS ingest OK | pass | `buildroot login:` |
| `dfx_rws_evolved` (Evolved child, same base) | 133 min (07:28 -> 09:41) | agfi-031c5f6961ee9015e | fully routed, WNS 0.138 / WHS 0.010, check_ddr_bram initialized, 0 ERROR, AWS ingest OK | pass | `buildroot login:` |

Child time split (AMPM): RTL reused, host 3.5 min, RM synth 4 min, swap 70.4 min, AWS AFI ~48 min.
Evolved: swap 70.8 min (read_checkpoint -cell 11.1, place 35.5, route 11.5 min), AWS AFI ~54 min.
AMPM swap step (s): open base 131, firesim_top cell DCP 60 + EDIF 11, RapidWright install 38 (fresh host),
MakeBlackBox 33, black box 82, lock 52, PROHIBITs 206, read_checkpoint -cell 661, place 2101, route 716,
write 67. Only CRITICAL WARNING: Vivado 12-4430 for the tutorial's REQP-1753 waiver.

Compared to the nested-DFX child (swap ~60 min, child ~114-129 min): similar total, no nested DFX,
no partition pins, no pr_subdivide/pr_recombine, no ELF workaround. Place (35 min) dominates; likely
Vivado's full-design placer setup with 1.8M PROHIBITs; not tuned yet.

## Open issues
- Child place 35 min / read_checkpoint -cell 11 min: not tuned (e.g. place directive, PROHIBIT count).
- The clone manager on m8i.4xlarge lost build-host ssh sessions 3 times; c5.4xlarge did not.

## Commits (branch `rapidwright-scratch`, not pushed)
- aws-fpga-firesim-f2 `13300df`: flow (base DONT_TOUCH, RapidWright shell child), nested-DFX removal, AWS files to upstream.
- firesim `45f1d91b`: build-bitstream.sh base/child, drop pr_region, gitlink 13300df, this report (platforms/f2/RAPIDWRIGHT_DFX.md).
- firesim (next commit): report update (Evolved result, final sections).
- Fetched to the manager as local branches `rapidwright-scratch` (no worktree touched).

## Instances
Clone i-089449788af2c0145 stopped (c5.4xlarge); all build/run hosts terminated by FireSim.
