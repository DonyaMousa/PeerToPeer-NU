# Peer2Peer NU

### Offline Android Messaging with Multi-Metric Routing and CARBLE Adaptive Forwarding

Peer2Peer NU is an Android networking prototype that enables private and group messaging without internet access or a central messaging server. It uses Bluetooth Low Energy (BLE) for device discovery and communication, with participating devices forwarding messages toward destinations beyond direct Bluetooth range.

The project connects routing research with practical Android development, combining graph-based path selection, adaptive forwarding, distributed delivery tracking, and on-device diagnostics.

| Screen | What it illustrates |
| --- | --- |
| **Home** | Local identity, peer-service activity, advertising status, visible peers, and the active protocol |
| **Private Chat** | Conversation history, delivery states, and a visible CARBLE forwarding indicator |
| **Group Chat** | Shared conversations with per-recipient delivery tracking and partial-delivery status |

Screenshots illustrate the application interface at capture time. Packet-level logs provide the supporting evidence for interpreting routing and forwarding behavior.

## Project Motivation

Infrastructure-free mobile communication must handle more than device discovery. A destination may be outside direct range, a visible connection may perform poorly, or an intermediate device may become unavailable during delivery.

Peer2Peer NU explores how quality-aware route selection and adaptive forwarding can address these conditions within an Android BLE application.

The prototype supports investigation of three questions:

- How should communication quality influence selection between direct and relay paths?
- How should forwarding respond when confidence in a link deteriorates?
- How can delivery remain independently managed when different group members have different connectivity?

## Core Capabilities

- **Private messaging:** Direct and relayed communication between devices.
- **Multi-hop routing:** Forwarding through intermediate BLE peers.
- **Quality-aware path selection:** Comparison of candidate routes using multiple communication metrics.
- **Adaptive forwarding:** CARBLE behavior for healthy, degraded, and low-confidence conditions.
- **Shared group conversations:** Membership synchronization and independently routed recipient copies.
- **Delivery tracking:** Hop acknowledgements, destination acknowledgements, and per-member status.
- **Recovery mechanisms:** Retries, offline queues, duplicate suppression, and packet expiration.
- **Observability:** Live Diagnostics and structured CSV exports.

## System Architecture

The application separates the user interface, communication runtime, routing logic, and delivery state.

| Layer | Responsibility |
| --- | --- |
| **User Interface** | Registration, peer visibility, private chats, groups, and diagnostics |
| **Background Runtime** | Peer-service execution and ongoing message processing |
| **BLE Transport** | Advertising, scanning, GATT connections, and packet exchange |
| **Routing** | Topology maintenance and multi-metric path selection |
| **CARBLE Forwarding** | Confidence assessment and forwarding or recovery decisions |
| **Local State** | Identity, conversations, groups, pending packets, and delivery records |
| **Evaluation** | Diagnostic events and research exports |

Each relay communicates directly with its immediate next hop. End-to-end multi-hop delivery is achieved through a sequence of these local BLE transmissions.

## Multi-Metric Route Selection

Multi-metric routing represents the available network as a weighted graph. Devices form the nodes, and usable communication links form the edges.

Each edge receives a cost reflecting:

- Recent delivery reliability.
- Communication delay.
- Queue pressure.
- Link instability.
- Resource suitability.
- The additional forwarding hop.

Dijkstra’s algorithm selects the path with the lowest accumulated cost. The preferred path therefore depends on communication quality, rather than hop count alone.

A direct connection remains a candidate alongside relay paths. If its assessed cost exceeds that of an available relay route, the relay route can be selected. A switching margin reduces frequent changes between paths with similar costs.

## CARBLE Adaptive Forwarding

CARBLE complements route selection by determining how a packet should proceed under current network conditions.

Its confidence assessment considers delivery reliability, communication freshness, link stability, timeliness, signal suitability, and resource suitability.

| State | Forwarding behavior |
| --- | --- |
| **HIGH** | Normal forwarding on the selected route |
| **MEDIUM** | Graduated monitoring and recovery through M1, M2, and M3 |
| **LOW** | Retain the packet and re-evaluate forwarding opportunities |

Within MEDIUM, the prototype distinguishes forwarding with monitoring, timeout-triggered backup activation, and delayed backup transmission. Backup actions require a usable alternative; stage classification alone does not establish that a backup was executed.

CARBLE distinguishes immediate-hop confidence from downstream route confidence. This allows the system to recognize a weaker later hop while continuing across a healthy current link.

**Multi-metric routing answers “Which path should be selected?” CARBLE answers “How should forwarding proceed?”**

## Link Measurements and Adaptation

The physical runtime maintains recent, directional link observations. Communication from A to B can therefore have different measurements from communication from B to A.

Evidence includes:

- Application-level hop receipts and receipt timeouts.
- Acknowledgement round-trip times.
- Filtered BLE signal-strength observations.
- Valid communication age.
- Connection changes and transport failures.
- Queue depth and recent successful packet completions.

These observations support updates as devices move or communication conditions change. Adaptive queue pressure relates pending traffic to recently observed service capability.

The resource component is configurable in the routing model. In the inspected v17 implementation, local resource suitability remains a controlled constant; measured battery-aware routing is a further extension.

## Private and Group Delivery

Private messages are routed toward one destination. Group messages create one logical conversation entry and a separate delivery job for every other member.

Each group recipient can have:

- A different selected route.
- A different forwarding decision.
- An independent acknowledgement state.
- A queued copy while other recipients receive theirs.

An unavailable member therefore does not prevent delivery to reachable members. Aggregate status reflects the recipient jobs, while the interface retains one logical group message.

Duplicate suppression prevents repeated display when retries or alternate forwarding paths produce multiple copies.

## Delivery and Recovery

The prototype distinguishes two acknowledgement levels:

- **Hop acknowledgement:** Confirms receipt by the immediate next device.
- **Destination acknowledgement:** Confirms end-to-end receipt at the intended destination.

This distinction supports accurate delivery tracking and link-quality measurement. A successful transport write or intermediate receipt is not treated as final destination delivery.

Pending packets can be retained for subsequent forwarding opportunities. Retries, duplicate suppression, hop limits, and expiration rules help manage recovery and bound repeated forwarding.

## Diagnostics and Research Evaluation

Live Diagnostics exposes the relationship between network observations and application decisions. It provides visibility into:

- Selected routes and candidate costs.
- Current-hop and route confidence.
- CARBLE stage and forwarding action.
- Primary and backup selection.
- Queue depth and pressure.
- Acknowledgements, delivery events, and connection errors.

CSV exports support analysis beyond screenshots, including:

| Evaluation Area | Relevant Evidence |
| --- | --- |
| **Delivery** | Unique generated and acknowledged messages |
| **Latency** | End-to-end delivery time and queue waiting |
| **Transmission Effort** | Attempts, retries, and backup activations |
| **Adaptation** | Route changes and forwarding-state transitions |
| **Recovery** | Queued messages delivered after connectivity returns |
| **Group Communication** | Delivery completion for individual recipients |

The prototype supports controlled simulation and physical device testing. Performance conclusions depend on repeatable experiments and collected measurements.

## Technology Stack

| Area | Technologies |
| --- | --- |
| **Android Development** | Kotlin, Jetpack Compose |
| **Wireless Communication** | BLE advertising, scanning, GATT client/server |
| **Routing Algorithms** | Dijkstra, weighted graph modelling |
| **Runtime** | Android foreground services and asynchronous communication |
| **State Management** | Local identity, chat, group, and packet repositories |
| **Evaluation** | Simulation, device diagnostics, CSV logging |

## Build and Run

### Requirements

- Android Studio and the Android SDK specified by the project.
- Android 8.0 or newer.
- Devices supporting BLE scanning, connections, and advertising.
- The same application build installed on all participating devices.

### Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

### Run

1. Open the project and complete Gradle synchronization.
2. Build and install the APK on the participating devices.
3. Register a local name and grant the requested permissions.
4. Start the peer service and verify discovery.
5. Test direct messages before introducing relay paths.
6. Use Live Diagnostics to inspect decisions and delivery events.

## Engineering Contribution

I developed the project across routing design, simulation, Android implementation, debugging, and physical test preparation.

My work integrated BLE communication with multi-metric routing, CARBLE forwarding, shared group delivery, persistent state, and diagnostic tooling. A central engineering challenge was coordinating algorithmic decisions with asynchronous transport events and changing device connectivity.

The project demonstrates experience in mobile development, graph algorithms, distributed messaging, experimental instrumentation, and systematic debugging across Android devices.

Peer2Peer NU provides a platform for investigating adaptive forwarding over application-level BLE relay networks.
