# Research Log — Day 1

Date: 2026-08-13

## Research objective

Establish the B0 hop-count Dijkstra routing baseline.

## Research question

Can route caching reduce unnecessary Dijkstra executions while
still recalculating routes correctly after a topology change?

## Hypothesis

For repeated requests under an unchanged topology, the routing
table should reuse the cached route without rerunning Dijkstra.

When a structural topology change occurs, the cached route should
be invalidated and Dijkstra should execute again.

## Baseline

Baseline ID:
B0

Algorithm:
Hop-count Dijkstra

Routing mode:
Single deterministic path

Code under test:
- Graph.kt
- DijkstraEngine.kt
- RoutingTable.kt

Tests:
- cached route avoids repeated dijkstra execution
- topology change invalidates cached route and recalculates

Git commit:


## Experiment 1 — Cache behavior

Topology:

A -- B -- C

Request:

A -> C

Number of identical requests:
2

Expected:

Dijkstra executions = 1

Observed:

Dijkstra executions = 1

Result:

PASS

### Experiment 2 — Topology failure

Initial topology:

A-B-D
A-C-D

Initial route:
A -> B -> D

Initial cost:
2

Failure injected:
Remove B-D

Repaired route:
A -> C -> D

Repaired cost:
4

Dijkstra executions before failure:
1

Dijkstra executions after failure:
2

Result:
PASS

Interpretation:
The cached route was reused while topology remained unchanged.
After the B-D link was removed, the topology version changed,
the stale cache was invalidated, and the routing engine calculated
the available alternative route.

## Experiment 3 — Sequential topology evolution

Sequence:

1. Initial network
   A-B-D and A-C-D available

2. Remove B-D
   Expected route: A-C-D

3. Remove C-D
   Expected result: destination unreachable

4. Restore B-D
   Expected route: A-B-D

Observed Dijkstra executions:
Step 1: 1
Step 2: 2
Step 3: 3
Step 4: 4

Step 1:
- Route: A -> B -> D
- Cost: 2
- Dijkstra executions: 1

Step 2:
- Route: A -> C -> D
- Cost: 4
- Dijkstra executions: 2

Step 3:
- Route: unreachable
- Dijkstra executions: 3

Step 4:
- Route: A -> B -> D
- Cost: 2
- Dijkstra executions: 4

Result:
PASS 

Interpretation:
The B0 routing baseline correctly follows structural topology
changes, handles complete disconnection, and restores routing
when connectivity returns.


### Research relevance

This establishes the baseline failure/recovery behavior that
future protocol mechanisms will be compared against.

Future mechanisms such as:
- backup routing,
- confidence-aware routing,
- candidate forwarding,
- store-carry-forward

should be evaluated against this B0 behavior to determine whether
they improve route repair, reliability, or disconnected delivery.

## Experiment 4 — Repeated Routing Workload

### Objective

Measure how effectively the B0 RoutingTable reuses cached routes
during repeated route requests and how many route calculations are
required after a structural topology change.

### Research Question

How many Dijkstra calculations are required when many route
requests are made while the topology remains mostly unchanged?

### Hypothesis

The B0 RoutingTable should calculate a route only once for repeated
requests under a stable topology.

After a topology change, the cached route should be invalidated and
exactly one new route calculation should be required.

### Baseline

Baseline ID:
B0

Algorithm:
Hop-count Dijkstra

Routing mode:
Single deterministic path

Route cache:
Enabled

Cache invalidation:
Structural topology-version change

### Initial Topology

A --1-- B --1-- D
\              /
--2-- C --2--

Primary route:

A -> B -> D

Primary route cost:

2

Alternative route:

A -> C -> D

Alternative route cost:

4

#### Phase 1 — Stable Topology

Request the route:

A -> D

100 times without modifying the topology.

Expected behavior:

- First request: cache miss
- Dijkstra calculation: 1
- Remaining 99 requests: cache hits

#### Phase 2 — Link Failure

Remove:

B -> D

This invalidates the previously cached route.

#### Phase 3 — Stable Topology After Failure

Request:

A -> D

another 100 times.

Expected new route:

A -> C -> D

Expected behavior:

- First request after failure: cache miss
- One new Dijkstra calculation
- Remaining 99 requests: cache hits

### Experiment Parameters

Baseline:
B0

Number of nodes:
4

Total route requests:
200

Requests before failure:
100

Requests after failure:
100

Structural topology changes:
1

Link failure:
B-D removed

Mobility:
None

Packet loss:
None

Traffic simulation:
Not yet implemented

BLE transport:
Not used

Randomness:
None

### Expected Metrics

Route requests:
200

Cache hits:
198

Cache misses:
2

Route calculations:
2

Cache invalidations:
1

Successful route calculations:
2

Unreachable route calculations:
0

### Observed Results

Route requests:
200

Cache hits:
198

Cache misses:
2

Route calculations:
2

Cache invalidations:
1

Successful routes:
2

Unreachable routes:
0

### Result

PASS 

### Interpretation

If the expected values are observed, the experiment demonstrates
that B0 does not execute Dijkstra for every route request.

Instead, route computation occurs once for each stable topology
state encountered by this workload.

For 200 route requests across two valid topology states, only two
route calculations should be required.

This result establishes the computational reuse behavior of the B0
routing baseline.

It does not yet demonstrate a runtime speedup because execution
time has not been measured.

### Research Relevance

This experiment establishes a baseline against which future routing
mechanisms can be compared.

Future algorithms may introduce additional computational work from:

- dynamic link metrics,
- confidence estimation,
- backup-route calculation,
- candidate ranking,
- route hysteresis.

Their routing cost can later be compared against the route
calculation and caching behavior measured here.


## Experiment 5 — Preliminary B0 Dijkstra Computation-Time Scaling

### Objective

Obtain an initial measurement of the computational cost of the B0
hop-count Dijkstra implementation as graph size increases.

### Research Question

How does route-computation time change when the number of nodes in
a simple line topology increases?

### Hypothesis

B0 Dijkstra computation time should generally increase as the graph
contains more nodes and edges.

This experiment is not intended to establish a precise complexity
curve. It provides an initial computational baseline for later
comparison with DM-Dijkstra, confidence estimation, and backup-route
calculation.

### Baseline

Baseline ID:
B0

Algorithm:
Hop-count Dijkstra

Routing cache:
Bypassed for this experiment

Reason:
The experiment measures the route-calculation algorithm itself,
rather than cached route lookup.

### Topology

Topology type:
Line

Example:

N000 -- N001 -- N002 -- ... -- N(n-1)

Graph sizes:

- 10 nodes
- 25 nodes
- 50 nodes
- 100 nodes

Edges:

For N nodes:

N - 1 undirected links

Edge cost:

1 per link

Source:

First node

Destination:

Last node

### Experiment Parameters

Warm-up runs per graph size:
20

Measured runs per graph size:
200

Total measured Dijkstra calculations:
800

Packet simulation:
Disabled

BLE:
Disabled

Mobility:
None

Packet loss:
None

Congestion:
None

Random topology:
No

Routing cache:
Not used

Timing source:
System.nanoTime()

### Observed Results

| Nodes | Median (µs) | Average (µs) | Minimum (µs) | Maximum (µs) |
|------:|------------:|-------------:|-------------:|-------------:|
| 10 |        25.2 |      28.0285 |         19.2 |         74.9 |
| 25 |        16.1 |      21.3870 |         13.6 |        247.1 |
| 50 |        25.6 |      29.7820 |         23.5 |         58.8 |
| 100 |        47.0 |      50.0135 |         35.0 |         98.0 |

### Result

PASS

The routing algorithm returned valid routes for all tested graph
sizes and the timing experiment completed successfully.

### Initial Interpretation

The results do not show a perfectly monotonic increase in execution
time across all tested graph sizes.

The 25-node case produced a lower median and average execution time
than the 10-node case:

- 10 nodes median: 25.2 µs
- 25 nodes median: 16.1 µs

This is most likely evidence of measurement variability rather than
evidence that a 25-node graph is inherently faster to process than a
10-node graph.

The 25-node experiment also produced the largest observed maximum
time:

247.1 µs

while its median was only:

16.1 µs

This large difference between the median and maximum suggests that
occasional JVM, operating-system scheduling, garbage collection, or
other runtime effects influenced individual measurements.

For the larger graph sizes, the trend becomes clearer:

- 50 nodes median: 25.6 µs
- 100 nodes median: 47.0 µs

The 100-node graph therefore required approximately 1.84 times the
median computation time of the 50-node graph in this particular run.

However, this single experiment is not sufficient to estimate the
algorithm's empirical scaling behavior.

### Research Observation

Median values appear more stable than maximum values for this
experiment.

The maximum measurements contain substantial outliers, especially
for the 25-node graph.

For this reason, future routing-performance experiments should not
rely only on mean or maximum execution time.

Median and percentile-based measurements should be emphasized.

### Research Relevance

This experiment establishes the first preliminary computational
baseline for B0.

It confirms that route computation remains very small for the tested
line graphs on the current JVM environment, even at 100 nodes.

More importantly, these measurements give us a reference point for
future routing mechanisms that introduce additional computation

Later experiments can evaluate whether the additional computation
introduced by these mechanisms remains small enough to justify their
network-level benefits.

### Important Limitation

These measurements must not be treated as final algorithm-performance
results.

The experiment runs as a JVM unit test and may be affected by:

- JVM warm-up,
- JIT compilation,
- garbage collection,
- operating-system scheduling,
- CPU frequency changes,
- background applications,
- test-framework overhead.

The experiment also evaluates only one topology type: a line graph.

The results should therefore currently be described as:

"Preliminary B0 JVM timing measurements on deterministic line
topologies."

They should not yet be described as:

"Android BLE routing latency"

or:

"Final Dijkstra performance."

### Follow-Up Questions

The current results introduce several questions that should be tested
later:

1. Does the same scaling trend appear across repeated experiment runs?
2. How does topology density affect Dijkstra computation time?
3. How does a grid topology compare with a line topology?
4. How do random sparse and dense graphs behave?
5. How does the runtime change on an actual Android phone?
6. How much additional computation does DM-Dijkstra introduce?
7. How expensive is calculating a backup route?
8. Does confidence estimation materially affect routing computation
   time?

## Experiment 6 — Structured B0 Routing Experiment

### Objective

Validate the reusable experiment framework by running the B0 routing
baseline through a structured experiment configuration and collecting
routing results through the telemetry system.

### Research Question

Can the B0 routing baseline be executed in a reproducible experiment
format that separates controlled parameters from measured outcomes?

### Hypothesis

A structured experiment configuration should allow the same B0 routing
scenario to be described explicitly, while telemetry should produce
machine-readable result values for later baseline comparison.

### Experiment Configuration

Experiment ID:

B0-E001

Baseline:

B0

Topology:

line-with-backup-path

Number of nodes:

4

Total route requests:

200

Topology changes:

1

Random seed:

None

Experiment notes:

100 requests before failure and 100 requests after failure.

### Topology

Initial topology:

A --1-- B --1-- D
\              /
--2-- C --2--

Initial preferred route:

A -> B -> D

Alternative route:

A -> C -> D


#### Phase 1

Issue 100 route requests from:

A -> D

while the topology remains unchanged.

#### Failure Injection

Remove the link:

B-D

#### Phase 2

Issue another 100 route requests from:

A -> D

using the modified topology.

Expected repaired route:

A -> C -> D

### Observed Results

Route requests:

200

Cache hits:

198

Cache misses:

2

Route calculations:

2

Cache invalidations:

1

Successful route calculations:

2

Unreachable route calculations:

0

### Result

PASS

The structured experiment produced the expected routing behavior and
all configuration/result values were collected successfully.

### Interpretation

The experiment demonstrates that the B0 routing baseline can now be
represented using two separate research objects:

ExperimentConfig

which describes the controlled experimental conditions,

and:

ExperimentResult

which contains the measured routing outcomes.

For 200 route requests across two stable topology states, only two
route calculations were required.

The remaining 198 requests were served using cached routing state.

Exactly one cache invalidation occurred after the B-D link was removed.

This behavior matches the expected B0 routing design.

### Research Relevance

This experiment is important primarily as research infrastructure.

It establishes a structured format that can later be reused across
different routing algorithms and network scenarios.

Future experiments can use the same structure while changing variables
such as:

- algorithm,
- node count,
- topology,
- mobility,
- packet-loss probability,
- traffic rate,
- payload size,
- TTL,
- queue capacity,
- random seed.

The resulting metrics can then be compared systematically across:

- B0 Hop-count Dijkstra,
- DM-Dijkstra,
- DM-Dijkstra with hysteresis,
- backup routing,
- candidate forwarding,
- confidence-gated routing,
- store-carry-forward,
- full DynaBLE-RX.

### Important Observation

The current values:

- 198 cache hits,
- 2 cache misses,
- 2 route calculations,
- 1 cache invalidation,

should not be interpreted as network-performance superiority.

They describe the behavior of the routing cache under this specific,
deterministic workload.

## Experiment 7 — B0 Dynamic Topology Adaptation

### Objective

Evaluate how the B0 hop-count Dijkstra baseline reacts to a larger
topology containing multiple possible paths while the network changes
across several structural states.

### Research Question

Can B0 continue to return the correct shortest available route as links
fail, connectivity is lost, and links later return with different costs?

### Hypothesis

B0 should:

1. select the minimum-cost route in the initial topology,
2. invalidate cached routing state after every structural topology change,
3. recalculate the shortest available route,
4. report the destination as unreachable when no path exists,
5. correctly switch routes again when links are restored with different costs.

### Baseline

Baseline ID:
B0

Algorithm:
Hop-count / static-weight Dijkstra

Routing mode:
Single deterministic path

Routing cache:
Enabled

Cache invalidation:
Structural topology change

### Topology

Nodes:

A, B, C, D, E, F

Initial links:

- A-B, cost 1
- B-D, cost 1
- D-F, cost 1
- A-C, cost 2
- C-E, cost 1
- E-F, cost 2
- B-C, cost 2
- D-E, cost 2

Initial preferred route:

A -> B -> D -> F

Initial route cost:

3

#### Step 1 — Initial topology

Expected route:

A -> B -> D -> F

Expected cost:

3

Observed route:

A -> B -> D -> F

Observed cost:

3

#### Step 2 — Primary link failure

Injected failure:

Remove D-F

Expected alternative route:

A -> C -> E -> F

Expected cost:

5

Observed route:

A -> C -> E -> F

Observed cost:

5

#### Step 3 — Destination becomes unreachable

Injected failure:

Remove E-F

At this point, F has no remaining connection to the routing graph.

Expected result:

Unreachable

Observed result:

Unreachable

#### Step 4 — Connectivity restored through original branch

Restored link:

D-F

Restored cost:

3

Expected route:

A -> B -> D -> F

Expected cost:

5

Observed route:

A -> B -> D -> F

Observed cost:

5

#### Step 5 — Lower-cost alternative restored

Restored link:

E-F

Restored cost:

1

Expected route:

A -> C -> E -> F

Expected cost:

4

Observed route:

A -> C -> E -> F

Observed cost:

4

### Observed Routing Telemetry

Route requests:

5

Cache hits:

0

Cache misses:

5

Route calculations:

5

Cache invalidations:

4

Successful routes:

4

Unreachable routes:

1

### Result

PASS

All expected routes and route costs were observed.

### Interpretation

The B0 baseline successfully adapted to every structural topology change.

The route sequence was:

A -> B -> D -> F

then:

A -> C -> E -> F

then:

unreachable

then:

A -> B -> D -> F

then:

A -> C -> E -> F

This demonstrates that the baseline does not remain locked to a
previously preferred route after topology or edge-cost changes.

Each topology change invalidated the existing routing cache, which
caused a new route calculation on the following request.

### Cache Observation

The experiment produced:

- 5 route requests
- 5 cache misses
- 0 cache hits
- 4 cache invalidations

This is expected because every route request after the first followed
a topology change.

Therefore, there was no opportunity to reuse the previously cached
route between steps.

This result also demonstrates an important limitation of B0:

When topology changes frequently, the routing cache provides little or
no computational reuse.

### Research Relevance

This experiment establishes an important future comparison point.

B0 currently reacts only after a structural topology change has already
occurred.

The future protocol will attempt to improve this behavior through:

- link-quality monitoring,
- route hysteresis,
- backup routes,
- confidence estimation,
- candidate forwarding.

Future research should measure whether these mechanisms can reduce:

- route recalculation frequency,
- route-repair time,
- failed transmissions,
- stale-route usage,

while maintaining or improving packet delivery.
This experiment suggests a future hypothesis:

> The effectiveness of route caching decreases as topology-change
> frequency increases.

This should later be tested explicitly by varying the topology-change
rate while keeping the route-request workload fixed.

For example:

- low topology-change rate,
- medium topology-change rate,
- high topology-change rate.

Metrics should include:

- cache hit rate,
- route calculations,
- route invalidations,
- route-repair delay,
- packet delivery ratio.
