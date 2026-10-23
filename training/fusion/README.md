# Fusion experiments

This package follows [`docs/fusion_algorithm_spec.md`](../../docs/fusion_algorithm_spec.md).

Model implementation is intentionally gated on the v2 counterfactual pilot. Do not train a
multi-branch model until the dataset manifest shows the required Activity × Carry pairs and
quality-passing sessions.

Planned run IDs:

| ID | Model |
|---|---|
| B0 | majority + deterministic rules |
| B1 | handcrafted features + classical classifier |
| B2 | accelerometer-only TCN |
| B3 | IMU-only TCN |
| B4 | early fusion |
| B5 | fixed-weight late fusion |
| F1 | quality-aware gated fusion |
| F2 | F1 + temporal state filter |

Build a schema-versioned dataset:

```bash
python3 training/scripts/build_fusion_dataset.py data/fusion_pilot \
  --output data/fusion/fusion_v1.jsonl.gz
```

Every experiment must record:

- input manifest and schema hash;
- session/participant split;
- random seed;
- clean and corruption metrics;
- parameter count and checkpoint digest;
- gate statistics where applicable.
