# Fraud Vector Detector - Rinha de Backend 2026

An ultra-performance, zero-allocation Kotlin implementation designed to pass the Brazilian backend stress-test competition with sub-millisecond p99 latencies and maximum assertiveness.

---

## 🚀 Key Architectural Pillars

### 1. Ahead-Of-Time (AOT) Indexing & Data Quantization
To eliminate heavy calculations at runtime, all compute-heavy workloads are shifted to build-time.
* **Int8 Quantization:** Bounded float dimensions (`0.0` to `1.0`) are mapped to signed bytes (`0` to `127`), reducing the 3,000,000 vector memory footprint from ~168MB to a cache-friendly **~42MB**. Sentinels (`-1.0`) map directly to `-128`.
* **K-Means Clustering Index (IVF):** Pre-clusters vectors into 1024 groups during image assembly. At runtime, the query vector is compared against 1024 centroids first, reducing the kNN search space from 3,000,000 to ~1,500–3,000 vectors per request ($O(\sqrt{N})$ complexity).
* **Memory-Mapped Binary Packing:** Index is written as a flat, contiguous binary file (`index.bin`) and loaded into RAM using zero-overhead memory maps (`FileChannel.MapMode.READ_ONLY`).

### 2. Zero-Allocation Runtime Engine
Garbage Collection pauses are eliminated entirely by running a 100% allocation-free path for inbound HTTP requests.
* **Primitive Array Layouts:** All cluster indexes, centroids, distances, and scores are navigated using primitive types (`ByteArray`, `IntArray`) to bypass JVM object headers.
* **Recycled Mutability:** Reuses thread-local input/output byte arrays, eliminating heap generation per request.

### 3. High-Performance Network & I/O
Standard HTTP frameworks are dropped in favor of raw socket manipulation.
* **Unix Domain Sockets (UDS):** Bypasses the local loopback TCP/IP stack (checksums, sequencing, ACK steps) by binding HAProxy to the application via `/tmp/app.sock`.
* **Single-Threaded Event Loop:** Set to exactly 1 execution thread matching the allocated hardware boundary, removing CPU context-switching overhead entirely.
* **Zero-Copy Parser:** Scans the inbound socket `ByteBuffer` sequentially, extracting JSON string arrays directly into primitive buffers without generating intermediate `String` or `Data Class` objects.

---

## 📂 Core Codebase Layout

* **`src/main/rinha/BuildIndex.kt`**: Runs during build/CI pipeline. Parses source data streams directly from GZIP, performs multi-iteration K-Means clustering, and builds the raw layout for `index.bin`.
* **`src/main/rinha/Server.kt`**: The runtime API. Opens the UDS socket, handles zero-copy HTTP serialization, executes optimized distance steps, and returns responses instantly.

---

## ⚡ Mathematical Optimization (SIMD/AVX2 Fast-Path)

* **Squared Euclidean Distance:** Distance loops avoid expensive square root steps, using standard integer differences ($diff^2$) which easily compile to clean CPU vector registers.
* **Top-5 Inline Register Selection:** Replaces traditional priority queues with manual variable registers (`top1Dist` to `top5Dist`) to achieve zero allocations and maximum cache-line speed during sorting.

---

## 🛠️ Next Steps Roadmap

1. **GraalVM Native Compilation:** Build ahead-of-time executables targeting `x86-64-v3` architecture to maximize loop unrolling and AVX2 hardware optimization.
2. **Infrastructure Hook:** Configure HAProxy routing parameters to cleanly distribute high-throughput traffic directly across the UNIX socket path.
3. **Instrumentation Profiles:** Verify allocation stability via `async-profiler` to confirm the code maintains its exact 0-byte heap profile under heavy concurrent load.