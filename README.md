# PeerToPeer Messaging App

An offline, full Peer-to-Peer (P2P) messaging application for Android that enables communication in avoided network.

##  Overview

This application leverages Bluetooth Low Energy (BLE) to create a mesh network of devices, allowing users to send and receive messages even when completely offline. It implements advanced routing algorithms and security protocols to ensure reliable and private communication.

##  Key Features

- **Full P2P Mesh Networking**: Operates entirely offline using BLE to discover and connect with nearby nodes.
- **Intelligent Routing**: Uses **Dijkstra's Algorithm** to find the most efficient path for message delivery across multiple hops in the network.
- **Node Stability**: Implements a **Queue-based Node Management** system to prevent buffer overflows and ensure network stability during high traffic.
- **End-to-End Security**: All communications are encrypted using the **AES-256** algorithm, ensuring that messages remain private and secure.
- **Adaptive UI**: Built with **Jetpack Compose** for a modern, responsive, and intuitive user experience.

##  Technology Stack

- **Kotlin**: Primary programming language.
- **Jetpack Compose**: Declarative UI toolkit for building the modern Android interface.
- **Coroutines & Flow**: For asynchronous programming and reactive data streams.
- **Bluetooth Low Energy (BLE)**: The underlying protocol for device discovery and data transmission.
- **AES-256 Encryption**: Industry-standard security for message protection.
- **Dijkstra's Algorithm**: For pathfinding and mesh network routing.
