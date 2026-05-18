# Rinha de Backend 2026 - Fraud Vector Detector (Kotlin Native)

This repository contains an ultra-high-performance, zero-allocation Kotlin implementation designed specifically for the Rinha de Backend 2026 stress-test competition. 

**Primary Objective:** Maximize throughput, achieve a p99 latency of < 1ms, and maintain 100% assertiveness precision to reach the +6000 points ceiling.
**Core Strategy:** Zero-Allocation Request Lifecycle, Ahead-Of-Time (AOT) Indexing, Int8 Quantization, Real-time Vectorization, and TCP/IP stack bypass.

---

## Architectural Pillars and System Optimizations

### 1. Real-Time Zero-Allocation Vectorization
The API receives raw JSON transactions which must be converted into 14-dimensional vectors.
* **Custom Byte-Scanner:** We discard standard reflective parsers (Jackson, Gson). A custom byte-level state machine reads the raw socket `ByteBuffer`, extracts numeric and string targets directly, and applies the normalization rules (clamping, `mcc_risk` lookups) on the fly.
* **Pre-loaded Dictionaries:** `mcc_risk.json` and `normalization.json` are loaded into perfect-hash tables or direct array indices during startup to guarantee `O(1)` memory-fetch vectorization without object allocation.

### 2. Data Quantization and Memory Compression
The original dataset of 3,000,000 vectors requires ~168MB in raw 32-bit `Float` arrays, severely limiting available RAM for the OS and Native VM within the 350MB container constraint.
* **Mechanism:** Bounded float dimensions (`0.0` to `1.0`) are quantized to signed 8-bit integers (`0` to `127`) using nearest-neighbor rounding to prevent truncation drift. The sentinel `-1.0` maps directly to `-128`.
* **Impact:** The entire vector dataset footprint is reduced by 75% to **~42MB**, ensuring it fits comfortably in RAM while preventing page faults.

### 3. AOT K-Means Clustering Index (IVF) with Boundary Smoothing
A brute-force kNN search against 3,000,000 vectors per request results in unacceptable CPU cycle consumption.
* **Build-Time Computation:** During the Docker image build phase, a clustering algorithm groups the dataset into 1,024 centroids. 
* **Multi-Probe Search (nprobe=2):** Incoming vectors are evaluated against the 1,024 centroids. To prevent cluster-boundary inaccuracies (False Positives/Negatives), the engine restricts the exact kNN search to the **top 2** closest clusters. 
* **L1/L2 Cache Optimization:** Searching ~3,000 quantized vectors requires ~45KB of memory. This fits entirely within the CPU L1/L2 Cache, effectively reducing memory access latency to near-zero.

### 4. SIMD/AVX2 Hardware Acceleration
* **Integer Arithmetic:** The search engine relies on Squared Euclidean Distance. Avoiding the floating-point `Math.sqrt` operation saves critical clock cycles while preserving distance ordering.
* **Auto-Vectorization:** By using flat, primitive arrays (`ByteArray`, `IntArray`) and manually unrolling the distance calculation loops, the GraalVM compiler is forced to auto-vectorize the code into hardware-level SIMD AVX2 instructions (`-march=x86-64-v3`).

### 5. Zero-Allocation Network Stack
Garbage Collection pauses are entirely eliminated by enforcing an allocation rate of 0 bytes per request.
* **Unix Domain Sockets (UDS):** The reverse proxy (HAProxy) communicates with the API via a local socket file (`/tmp/app.sock`). This bypasses the loopback TCP/IP stack (checksums, sequencing, ACK handling).
* **Pre-Baked HTTP Responses:** The six possible response states (0/5 to 5/5 frauds) are pre-serialized into byte arrays with the required `{"approved": boolean, "fraud_score": float}` format.
* **Single Event-Loop:** The application runs natively with highly optimized non-blocking I/O multiplexing, eliminating OS context-switching overhead.

---

## Implementation Execution Plan

### Phase 1: Pre-processing and Math Setup (Build Script)
- [x] Stream source GZIP efficiently without loading the full tree into RAM.
- [x] Apply `Float32` to `Int8` data quantization (using `Math.round()` instead of truncation for accuracy).
- [x] Execute multi-iteration K-Means clustering (1024 clusters).
- [x] Serialize the Inverted File Index (IVF) to a contiguous binary structure: `index.bin`.

### Phase 2: Core Search Engine (Runtime)
- [x] Map `index.bin` directly into virtual memory via `FileChannel.map` (`READ_ONLY`).
- [ ] Implement multi-cluster probe (`nprobe=2`) to resolve cluster boundary inaccuracies.
- [x] Implement the Int8 Squared Euclidean Distance algorithm.
- [x] Build an inline Top-5 selection mechanism using local primitive registers (`top1Dist` to `top5Dist`).
- [x] Pre-allocate static byte-buffers for the exact standard HTTP responses required by the rules.

### Phase 3: Network and Vectorization Layer
- [x] Initialize `ServerSocketChannel` over `StandardProtocolFamily.UNIX` mapped to `/tmp/app.sock`.
- [ ] Implement HTTP Router to intercept `GET /ready` and `POST /fraud-score`.
- [ ] Construct the Zero-Copy JSON scanner to extract raw transaction properties.
- [ ] Implement the 14-dimension normalization logic in-memory using `mcc_risk.json` and static constraints.

### Phase 4: Infrastructure and Compilation
- [ ] Construct the multi-stage `Dockerfile`.
    - Stage 1: Execute `BuildIndex.kt` to generate `index.bin`.
    - Stage 2: Compile `Server.kt` via GraalVM Native Image with aggressive flags (`-O3`, `-march=x86-64-v3`, `--gc=epsilon`).
- [ ] Optimize `haproxy.cfg`:
    - Terminate port 9999 HTTP traffic and route seamlessly to the UDS backend.
- [ ] Finalize `docker-compose.yml` enforcing strict 1.0 CPU and 350MB RAM limits for the cluster.

---

## Local Environment Instructions

**1. Generate the AOT Binary Index:**
```bash
kotlinc src/main/rinha/BuildIndex.kt -include-runtime -d builder.jar
java -jar builder.jar
```

**2. Initialize the API Server:**
```Bash
kotlinc src/main/rinha/Server.kt -include-runtime -d server.jar
java -jar server.jar
```
*The server blocks passively on `/tmp/app.sock` awaiting HAProxy routing.*