# Historical snapshots

`scenarios_v1.json` (130/377) and `scenarios_v2.json` (139/386) are historical
exports, not the current dynamic evaluator's v1/v2 versions. Their presence does
not mean they should replace the current 165-scenario suite.

Current source of truth: `scripts/eval_multiturn.py`, `build_scenarios(..., "v1")`.
Export: `python scripts/export_multiturn_app_suite.py` (165 scenarios / 435 turns).
App replay: `CurrentMultiturn165InstrumentedTest`, through
`scripts/install_real_device_debug.ps1 -RunMultiturn165` with all required models
on a physical arm64 test device. Inventory unit tests do not execute the models.
