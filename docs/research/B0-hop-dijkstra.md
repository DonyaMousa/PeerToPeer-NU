# B0 — Hop-Count Dijkstra Routing Baseline

## Status

FROZEN BASELINE

Baseline ID:

B0

Baseline name:

Hop-Count Dijkstra

Purpose:

Provide the deterministic path-routing control condition against which
future DynaBLE-RX routing mechanisms will be compared.

---

## 1. Routing Model

Shortest-path algorithm:

Dijkstra

Routing mode:

Single-path deterministic routing

Graph representation:

Adjacency-list graph

Current graph direction:

Undirected

Routing source:

Source node

Routing destination:

Destination node

Route output:

- complete path,
- total path cost,
- next hop.

---

## 2. Link Metric

Current edge metric:

Static positive integer weight

Initial research interpretation:

Hop-count-style routing when all edges use weight 1.

Weighted deterministic experiments may assign different positive
integer costs to test route selection.

Dynamic BLE link metrics:

Disabled

ETX:

Disabled

Delivery-probability metric:

Disabled

Airtime metric:

Disabled

Queue metric:

Disabled

Energy metric:

Disabled

Congestion metric:

Disabled

Instability metric:

Disabled

---

## 3. Dijkstra Properties

Priority queue:

Enabled

Negative edge costs:

Not allowed

Zero edge costs:

Not allowed

Self-loops:

Not allowed

Unknown-node links:

Not allowed

Overflow protection:

Enabled

Unreachable destination:

Returns no route

Source equals destination:

Returns source-only path with cost 0

Equal-cost route behavior:

Deterministic node-ID-based tie handling

---

## 4. Routing Cache

RoutingTable:

Enabled

Cache key:

source node ID + destination node ID

Cache behavior:

A successfully calculated route is reused while the structural
topology remains unchanged.

Topology tracking:

Graph topology version

Invalidation strategy:

Clear cached routes after a structural topology-version change.

Examples of structural changes:

- node added,
- node removed,
- link added,
- link removed,
- link cost changed.

---

## 5. Baseline Telemetry

The B0 routing layer currently measures:

- route requests,
- cache hits,
- cache misses,
- route calculations,
- cache invalidations,
- successful route calculations,
- unreachable route calculations.

These counters measure routing behavior only.

They do not yet represent packet-level network performance.

---

## 6. Advanced Protocol Mechanisms

The following mechanisms are intentionally DISABLED in B0:

Dynamic Multi-Metric Dijkstra:

Disabled

Route hysteresis:

Disabled

Primary + backup routing:

Disabled

Confidence estimation:

Disabled

Candidate forwarding:

Disabled

Opportunistic forwarding:

Disabled

Store-carry-forward:

Disabled

Spray-and-Wait replication:

Disabled

Packet duplicate suppression:

Not part of routing baseline yet

BLE transport:

Not implemented in B0

Mobility prediction:

Disabled

Machine learning:

Disabled

---

## 7. Current Experimental Behavior

### Stable repeated-route workload

Experiment:

200 route requests

Topology states:

2

Structural topology changes:

1

Observed:

- route requests: 200
- cache hits: 198
- cache misses: 2
- route calculations: 2
- cache invalidations: 1
- successful routes: 2
- unreachable routes: 0

Interpretation:

B0 reuses routing calculations while topology remains unchanged and
recalculates after structural topology invalidation.

---

## 8. Dynamic Topology Behavior

Six-node controlled topology experiment:

Step 1:

A -> B -> D -> F

Cost:

3

Step 2 after D-F failure:

A -> C -> E -> F

Cost:

5

Step 3 after E-F failure:

Destination F unreachable

Step 4 after restoring D-F at cost 3:

A -> B -> D -> F

Cost:

5

Step 5 after restoring E-F at cost 1:

A -> C -> E -> F

Cost:

4

Observed telemetry:

- route requests: 5
- cache hits: 0
- cache misses: 5
- route calculations: 5
- cache invalidations: 4
- successful routes: 4
- unreachable routes: 1

Interpretation:

B0 correctly adapts to explicit structural topology changes but requires
a fresh route calculation after every changed topology state.

---

## 9. Preliminary Computational Timing

Environment:

JVM unit-test environment

Topology:

Line graph

Warm-up runs per graph size:

20

Measured runs per graph size:

200

Results:

| Nodes | Median (µs) | Average (µs) | Min (µs) | Max (µs) |
|------:|------------:|-------------:|---------:|---------:|
| 10 | 25.2 | 28.0285 | 19.2 | 74.9 |
| 25 | 16.1 | 21.3870 | 13.6 | 247.1 |
| 50 | 25.6 | 29.7820 | 23.5 | 58.8 |
| 100 | 47.0 | 50.0135 | 35.0 | 98.0 |

Important:

These values are preliminary JVM measurements.

They are NOT Android BLE latency measurements and must not be used as
final routing-performance claims.

---

## 10. What B0 Is Designed to Establish

B0 answers:

1. Can deterministic shortest-path routing work correctly?
2. Can cached routes avoid unnecessary recalculation?
3. Can topology changes invalidate stale routes?
4. Can routing recover when alternative paths exist?
5. Can routing correctly report complete disconnection?
6. What computational behavior does the simple baseline exhibit?

---

## 11. Future Comparison Role

Future protocol versions will be compared against B0.

Planned progression:

B0
Hop-count Dijkstra

B1
Dynamic Multi-Metric Dijkstra

B2
DM-Dijkstra + route hysteresis

B3
DM-Dijkstra + backup routing

B4
Controlled candidate forwarding

B5
Confidence-gated adaptive routing

B6
Bounded store-carry-forward

Final
Full DynaBLE-RX

Future comparisons should measure:

- packet delivery ratio,
- median latency,
- P95 latency,
- transmissions per delivered packet,
- bytes per delivered packet,
- duplicate-forward ratio,
- route-repair time,
- stale-route failures,
- queue drops,
- deadline success,
- routing computation time,
- relay-load fairness,
- energy/resource proxies.

---

## 12. Current Limitations

B0 currently does not represent a complete BLE networking protocol.

It does not model:

- packet transmission,
- packet loss,
- BLE advertisements,
- GATT transfer,
- Android scheduling,
- real mobility,
- queue congestion,
- retransmission behavior,
- asymmetric wireless links,
- network partitions over time,
- delayed packet delivery,
- energy consumption.

These limitations are intentional.

B0 exists as a simple, controlled path-routing baseline.

---
