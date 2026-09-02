# Roadmap

Version numbers here track **evidence, not features**. Every stage of the analysis
pipeline described in the README is already implemented and tested; what separates this
from a 1.0 is validation, and each release below is gated on a specific piece of it.

The gates are deliberately written so they can fail. If a gate does not pass, the version
does not ship.

---

## v0.7.0 — feature-complete pipeline *(current)*

The full Step 1–7 workflow: import and greyscale, perspective correction, background
correction, Rf lines, spot detection, calibration with LOD/LOQ, and CSV/PNG/TIFF export.

**Passed:**

- End-to-end run on a real plate, verified interactively, not only in tests.
- 125 automated tests, including a synthetic-plate suite with exactly known ground truth
  and regression fixtures pinning detection geometry on a real photograph.
- Every analysis parameter written to the CSV, so a result can be reproduced from the
  exported file.

**Known limitations, stated rather than hidden:** the reference corpus is three
supplementary plates from a single imaging setup; quantification is measurably sensitive
to plate-corner placement; three of the four detection methods are unvalidated betas.

---

## v0.8.0 — reproducibility characterised

Nothing here needs new plates. It is about knowing, and stating, how much the result
depends on the operator.

**Gates:**

- [ ] Corner sensitivity quantified and reported as a **distribution** — median and worst
      case under bounded corner jitter — rather than a single figure from one placement.
- [ ] Jitter measured with each corner perturbed independently, not only all four together,
      since independent misplacement is the realistic model of operator error.
- [ ] The test suite asserts on **false positives**. It currently cannot see them: the
      benchmark counts how many reference spots were matched and ignores extra detections,
      so a run finding the real spots plus phantoms scores identically to a clean one.
- [ ] Step 2 gives explicit corner-placement guidance in the UI, if the evidence supports a
      rule.

---

## v0.9.0 — external data

**Gates:**

- [ ] At least three plates from outside the TLCyzer supplementary material, covering at
      least two different imaging setups.
- [ ] At least one compound with **true replicates**, so repeatability can be computed as
      ICH Q2(R1) defines it. The current figure is a leave-one-out recovery RSD across
      concentration levels, which is a different quantity and must not be compared with
      published repeatability values.
- [ ] The benchmark reproduced by someone other than us, on their own machine, from the
      documented command.

---

## v1.0.0 — validated

**Gates:**

- [ ] Mean recovery and repeatability on the external corpus reported against the
      benchmarks in the TLCyzer paper, whatever the outcome.
- [ ] Operator reproducibility measured: the same plates analysed independently by at least
      two people, with the spread reported.
- [ ] Every shipped default validated on that corpus. Beta methods are either validated or
      removed — a 1.0 should not ship code we cannot vouch for.
- [ ] Methods paper submitted or preprinted, so the numbers are on the record and citable.

---

## Not gating any release

- Fiji update site publication (distribution convenience, independent of validation).
- User manual and the common-stains guide.
- Batch processing, project management and archiving — explicitly out of scope for 1.0.

---

## Contributing plates

The single thing that would most advance this is data. If you have TLC plates with known
concentrations and would be willing to share images, please get in touch:
david@katalystnord.com. Replicates of the same compound are more valuable than variety.
