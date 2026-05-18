# Rinha de Backend 2026 - Fraud Vector Detector (Kotlin Native)

This repository contains an ultra-high-performance, zero-allocation Kotlin implementation designed specifically for the Rinha de Backend 2026 stress-test competition. 

**Primary Objective:** Maximize throughput, achieve a p99 latency of < 1ms, and maintain 100% assertiveness precision.
**Core Strategy:** Zero-Allocation Request Lifecycle, Ahead-Of-Time (AOT) Indexing, Int8 Quantization, and TCP/IP stack bypass.

---

## Architectural Pillars and System Optimizations

### 1. Data Quantization and Memory Compression
The original dataset of 3,000,000 vectors requires ~168MB in raw 32-bit `Float` arrays, severely limiting available RAM for the OS and Native VM within the 350MB container constraint.
* **Mechanism:** Bounded float dimensions (`0.0` to `1.0`) are quantized to signed 8-bit integers (`0` to `127`). The sentinel `-1.0` maps directly to `-128`.
* **Impact:** The entire vector dataset footprint is reduced by 75% to **~42MB**, ensuring it fits comfortably in RAM while preventing page faults.

### 2. AOT K-Means Clustering Index (IVF)
A brute-force kNN search against 3,000,000 vectors per request results in unacceptable CPU cycle consumption.
* **Build-Time Computation:** During the Docker image build phase, a clustering algorithm groups the dataset into 1,024 centroids. 
* **Sub-linear Runtime Search ($O(\sqrt{N})$):** Incoming requests are evaluated against the 1,024 centroids. The exact kNN search is then restricted to the ~1,500 vectors belonging to the closest cluster.
* **L1 Cache Optimization:** 1,500 quantized vectors require ~22KB of memory. This fits entirely within the CPU L1 Cache, effectively reducing memory access latency to zero during the search loop.

### 3. SIMD/AVX2 Hardware Acceleration
* **Integer Arithmetic:** The search engine relies on Squared Euclidean Distance. Avoiding the floating-point `Math.sqrt` operation saves critical clock cycles while preserving distance ordering.
* **Auto-Vectorization:** By using flat, primitive arrays (`ByteArray`, `IntArray`) and manually unrolling the distance calculation loops, the GraalVM compiler is forced to auto-vectorize the code into hardware-level SIMD AVX2 instructions (`-march=x86-64-v3`).

### 4. Zero-Allocation Network Stack
Garbage Collection pauses are entirely eliminated by enforcing an allocation rate of 0 bytes per request.
* **Unix Domain Sockets (UDS):** The reverse proxy (HAProxy) communicates with the API via a local socket file (`/tmp/app.sock`). This bypasses the loopback TCP/IP stack (checksums, sequencing, ACK handling), stripping overhead.
* **Single Event-Loop:** The application runs on exactly 1 thread to match the allocated hardware boundary (~0.5 vCPU per instance). This completely eliminates CPU context-switching.
* **Zero-Copy Serialization:** Standard reflective parsers (Jackson, Gson) are discarded. A custom byte-level state machine reads the raw socket `ByteBuffer`, extracts numeric targets, and writes directly into pre-allocated, thread-local primitive arrays.

---

## Implementation Execution Plan

### Phase 1: Pre-processing and Math Setup (Build Script)
- [x] Stream source GZIP efficiently without loading the full tree into RAM.
- [x] Implement byte-level state-machine JSON parser focusing strictly on the `vector` and `fraud` keys.
- [x] Apply `Float32` to `Int8` data quantization.
- [x] Execute multi-iteration K-Means clustering (1024 clusters).
- [x] Serialize the Inverted File Index (IVF) to a contiguous binary structure: `index.bin`.

### Phase 2: Core Search Engine (Runtime)
- [x] Map `index.bin` directly into virtual memory via `FileChannel.map` (`READ_ONLY`).
- [x] Implement the Int8 Squared Euclidean Distance algorithm.
- [x] Build an inline Top-5 selection mechanism using local primitive registers (`top1Dist` to `top5Dist`) to replace Heap-allocated Priority Queues.
- [x] Pre-allocate static byte-buffers for standard HTTP responses.

### Phase 3: Network and I/O Layer
- [x] Initialize non-blocking `ServerSocketChannel` over `StandardProtocolFamily.UNIX` mapped to `/tmp/app.sock`.
- [x] Implement synchronous Event Loop for socket readiness.
- [x] Inject the Zero-Copy JSON scanner to populate the static `queryVector` array on inbound requests.

### Phase 4: Infrastructure and Compilation (NEXT STEPS)
- [ ] Construct the multi-stage `Dockerfile`.
    - Stage 1: Execute `BuildIndex.kt` to generate `index.bin`.
    - Stage 2: Compile `Server.kt` via GraalVM Native Image with aggressive flags (`-O3`, `-march=x86-64-v3`, `--gc=epsilon`).
- [ ] Optimize `haproxy.cfg`:
    - Route traffic symmetrically across instance sockets.
    - Tune kernel connection buffers.
- [ ] Finalize `docker-compose.yml` enforcing strict 1.5 vCPU and 350MB RAM limits for the cluster.

---

## Local Environment Instructions

**1. Generate the AOT Binary Index (Run only on dataset changes):**
```bash
kotlinc src/main/rinha/BuildIndex.kt -include-runtime -d builder.jar
java -jar builder.jar
```

**2. Initialize the API Server:**
```bash
kotlinc src/main/rinha/Server.kt -include-runtime -d server.jar
java -jar server.jar
```
*The server will block and listen passively on `/tmp/app.sock`.*