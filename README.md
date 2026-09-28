# Peer2Peer NU — v16 final physical-experiment release

Offline Android BLE chat and store-carry-forward prototype for two phones plus one Android 8 tablet. The physical app compares CARBLE (HIGH, M1, M2, M3, LOW) with 2BRH (HIGH, LOW) over the same BLE transport and link-quality inputs.

Start with [START_HERE_V16.md](START_HERE_V16.md). Use [PHYSICAL_TEST_MATRIX_V16.md](PHYSICAL_TEST_MATRIX_V16.md) for the three-phone doctor demonstration.

The project retains the B0/MM simulation and routing source as research history and evaluation tools. The submission MVP is focused on a three-node physical network. A three-device setup can test one relay path, adaptation, delivery, latency, and recovery; choosing between two concurrent backup relays requires a fourth device.

## v16 changes

- First-run identity is committed before navigation, and Activity resume refreshes onboarding state. Entering a name no longer requires closing the app to reveal the User ID or permission page.
- D is a rolling 20-attempt application HOP_ACK rate. GATT write success is exported separately and never counts as delivery success.
- F uses the age of valid decoded traffic; RSSI advertisements do not refresh it. R uses real GATT failure, receipt timeout, link-change and RSSI-swing events. T uses median HOP_ACK RTT plus queue occupancy. S uses median RSSI from the latest five scans.
- B remains a controlled value of 1.0 (`B_source=CONTROLLED_CONSTANT`) because the simulation also used zero normalized energy penalty. The app does not claim battery-aware routing.
- The Research screen adds 20 one-hop direct probes that never relay. Five control warm-up probes run before each formal 50-packet trial. Formal results do not begin while first-hop measurements remain warming up.
- Every formal run creates an isolated CARBLE or 2BRH CSV; completed evidence is never overwritten by the next run.
- M2 waits for a real HOP_ACK before activating its selected backup; M3 activates the selected backup after its controlled delay. A GATT write callback never resolves recovery.
- Direct destination delivery sends HOP_ACK before the end-to-end ACK, so D and T receive valid physical evidence.
- CSV v16 contains raw and hysteresis-applied stages, every Q component, HOP_ACK/GATT evidence, recovery activations and exactly one `EXPERIMENT_PACKET_RESULT` row per formal packet.
- The Groups page is now a persistent shared group timeline. Groups are synchronized with versioned metadata; each logical group message has per-member routed deliveries and an aggregate Queued / Partially delivered / Delivered to all state.
- CARBLE thresholds remain HIGH/M1/M2/M3/LOW and 2BRH still emits HIGH/LOW only. Two consecutive evaluations are required before a live stage change; no stage is forced.
- App version is 16 and packet format version is 5; minimum Android API remains 26 (Android 8). Install the same v16 APK on every testing device.

## Validation status

Android Gradle tests and APK build must be run in Android Studio before the source is installed on phones. Run `./gradlew test assembleDebug`, install the same APK on every device, then complete the v16 acceptance sequence. Do not describe the physical outcomes as measured results until the live CSV exports are collected.
