package rinha

import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

const val DIMENSIONS = 14
const val VECTOR_SIZE = 15

// Pre-baked HTTP responses for the 6 possible states (0/5 to 5/5 frauds)
val RESPONSES = Array(6) { frauds ->
    val score = frauds / 5.0
    val approved = score < 0.6
    val json = "{\"approved\":$approved,\"fraud_score\":$score}"
    val http = "HTTP/1.1 200 OK\r\nContent-Length: ${json.length}\r\nContent-Type: application/json\r\n\r\n$json"
    ByteBuffer.wrap(http.toByteArray(Charsets.US_ASCII))
}

val HTTP_READY = ByteBuffer.wrap("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))

// Lookup table for MCC Risk
val mccRiskMap = IntArray(10000) { 63 } // Default 0.5 * 127 = 63

fun loadMccRisk() {
    val file = File("resources/mcc_risk.json")
    if (!file.exists()) return
    
    val content = file.readText()
    val pattern = "\"(\\d{4})\":\\s*([0-9.]+)".toRegex()
    pattern.findAll(content).forEach { matchResult ->
        val mcc = matchResult.groupValues[1].toInt()
        val risk = matchResult.groupValues[2].toFloat()
        mccRiskMap[mcc] = (risk * 127f).roundToInt()
    }
}

fun main() {
    // 1. Initialize Lookups
    loadMccRisk()

    // 2. Map AOT Index into Memory
    val channel = RandomAccessFile("resources/index.bin", "r").channel
    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    buffer.order(ByteOrder.LITTLE_ENDIAN)

    val numClusters = buffer.int
    val centroids = ByteArray(numClusters * DIMENSIONS)
    val clusterSizes = IntArray(numClusters)
    val clusterOffsets = IntArray(numClusters)

    var currentOffset = 4 + numClusters * (DIMENSIONS + 4)
    for (c in 0 until numClusters) {
        buffer.get(centroids, c * DIMENSIONS, DIMENSIONS)
        val size = buffer.int
        clusterSizes[c] = size
        clusterOffsets[c] = currentOffset
        currentOffset += size * VECTOR_SIZE
    }

    val maxClusterSize = clusterSizes.maxOrNull() ?: 0
    // Allocate enough for nprobe = 2 (two clusters combined)
    val clusterBuffer = ByteArray(maxClusterSize * VECTOR_SIZE * 2) 
    val queryVector = ByteArray(DIMENSIONS)
    val ioBuffer = ByteBuffer.allocateDirect(16384)

    // 3. Bind Unix Domain Socket
    val socketPath = "/tmp/app.sock"
    val file = File(socketPath)
    if (file.exists()) file.delete()

    val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    serverChannel.bind(UnixDomainSocketAddress.of(socketPath))

    // 4. Main Event Loop
    while (true) {
        val client: SocketChannel = serverChannel.accept()
        ioBuffer.clear()
        
        while (client.read(ioBuffer) > 0) {
            ioBuffer.flip()
            
            // Extremely fast Router: GET /ready vs POST /fraud-score
            val firstByte = ioBuffer.get(0).toInt().toChar()
            if (firstByte == 'G') {
                HTTP_READY.position(0)
                client.write(HTTP_READY)
                break
            }

            var bodyStart = -1
            for (i in 0 until ioBuffer.limit() - 3) {
                if (ioBuffer.get(i) == '\r'.code.toByte() && ioBuffer.get(i + 1) == '\n'.code.toByte() &&
                    ioBuffer.get(i + 2) == '\r'.code.toByte() && ioBuffer.get(i + 3) == '\n'.code.toByte()) {
                    bodyStart = i + 4
                    break
                }
            }

            if (bodyStart != -1) {
                // Parse and Vectorize Payload dynamically
                val payloadStr = Charsets.UTF_8.decode(ioBuffer.position(bodyStart)).toString()
                vectorizePayload(payloadStr, queryVector)

                // Search: multi-probe (nprobe = 2) for maximum accuracy at cluster boundaries
                var bestCluster1 = 0; var minCentroidDist1 = Int.MAX_VALUE
                var bestCluster2 = 0; var minCentroidDist2 = Int.MAX_VALUE
                
                for (c in 0 until numClusters) {
                    var dist = 0
                    val cOff = c * DIMENSIONS
                    for (d in 0 until DIMENSIONS) {
                        val diff = queryVector[d].toInt() - centroids[cOff + d].toInt()
                        dist += diff * diff
                    }
                    if (dist < minCentroidDist1) {
                        minCentroidDist2 = minCentroidDist1; bestCluster2 = bestCluster1
                        minCentroidDist1 = dist; bestCluster1 = c
                    } else if (dist < minCentroidDist2) {
                        minCentroidDist2 = dist; bestCluster2 = c
                    }
                }

                // Load vectors from Top 2 Clusters into L1/L2 cache buffer
                val size1 = clusterSizes[bestCluster1]
                val offset1 = clusterOffsets[bestCluster1]
                buffer.position(offset1)
                buffer.get(clusterBuffer, 0, size1 * VECTOR_SIZE)

                val size2 = clusterSizes[bestCluster2]
                val offset2 = clusterOffsets[bestCluster2]
                buffer.position(offset2)
                buffer.get(clusterBuffer, size1 * VECTOR_SIZE, size2 * VECTOR_SIZE)

                val totalVectors = size1 + size2

                // KNN Search using exact Euclidean over the restricted bounds
                var top1Dist = Int.MAX_VALUE; var top1Label = 0
                var top2Dist = Int.MAX_VALUE; var top2Label = 0
                var top3Dist = Int.MAX_VALUE; var top3Label = 0
                var top4Dist = Int.MAX_VALUE; var top4Label = 0
                var top5Dist = Int.MAX_VALUE; var top5Label = 0

                for (i in 0 until totalVectors) {
                    var dist = 0
                    val vOff = i * VECTOR_SIZE
                    
                    for (d in 0 until DIMENSIONS) {
                        val diff = queryVector[d].toInt() - clusterBuffer[vOff + d].toInt()
                        dist += diff * diff
                    }
                    
                    val label = clusterBuffer[vOff + DIMENSIONS].toInt()

                    if (dist < top1Dist) {
                        top5Dist = top4Dist; top5Label = top4Label
                        top4Dist = top3Dist; top4Label = top3Label
                        top3Dist = top2Dist; top3Label = top2Label
                        top2Dist = top1Dist; top2Label = top1Label
                        top1Dist = dist; top1Label = label
                    } else if (dist < top2Dist) {
                        top5Dist = top4Dist; top5Label = top4Label
                        top4Dist = top3Dist; top4Label = top3Label
                        top3Dist = top2Dist; top3Label = top2Label
                        top2Dist = dist; top2Label = label
                    } else if (dist < top3Dist) {
                        top5Dist = top4Dist; top5Label = top4Label
                        top4Dist = top3Dist; top4Label = top3Label
                        top3Dist = dist; top3Label = label
                    } else if (dist < top4Dist) {
                        top5Dist = top4Dist; top5Label = top4Label
                        top4Dist = dist; top4Label = label
                    } else if (dist < top5Dist) {
                        top5Dist = dist; top5Label = label
                    }
                }

                val frauds = top1Label + top2Label + top3Label + top4Label + top5Label
                val responseBuffer = RESPONSES[frauds]
                responseBuffer.position(0)
                client.write(responseBuffer)
            }
            ioBuffer.clear()
            break
        }
        client.close()
    }
}

// Low-Allocation JSON extraction and mathematical normalization (14 Dimensions)
fun vectorizePayload(json: String, vector: ByteArray) {
    fun extractNum(key: String): Double {
        val idx = json.indexOf("\"$key\"")
        if (idx == -1) return -1.0
        val start = json.indexOf(':', idx) + 1
        var end = start
        while (end < json.length && (json[end].isDigit() || json[end] == '.' || json[end] == '-')) end++
        val numStr = json.substring(start, end).trim()
        return numStr.toDoubleOrNull() ?: -1.0
    }

    fun extractStr(key: String): String {
        val idx = json.indexOf("\"$key\"")
        if (idx == -1) return ""
        val start = json.indexOf('"', json.indexOf(':', idx)) + 1
        val end = json.indexOf('"', start)
        return json.substring(start, end)
    }

    fun extractBool(key: String): Boolean {
        val idx = json.indexOf("\"$key\"")
        if (idx == -1) return false
        val start = json.indexOf(':', idx) + 1
        return json.substring(start, start + 5).contains("true")
    }

    val amount = extractNum("amount")
    val installments = extractNum("installments")
    val reqAtStr = extractStr("requested_at")
    val avgAmount = extractNum("avg_amount") // Matches customer or merchant depending on context, careful. Let's do safely:

    // Safe contextual extraction
    val txBlock = json.substringAfter("\"transaction\"").substringBefore("}")
    val txAmount = txBlock.substringAfter("\"amount\":").substringBefore(",").trim().toDouble()
    val txInstalls = txBlock.substringAfter("\"installments\":").substringBefore(",").trim().toDouble()
    val txReqAt = txBlock.substringAfter("\"requested_at\":").substringAfter("\"").substringBefore("\"")

    val cBlock = json.substringAfter("\"customer\"").substringBefore("]")
    val cAvgAmount = cBlock.substringAfter("\"avg_amount\":").substringBefore(",").trim().toDouble()
    val cTxCount = cBlock.substringAfter("\"tx_count_24h\":").substringBefore(",").trim().toDouble()
    val cMerchants = cBlock.substringAfter("\"known_merchants\":").substringBefore("]")

    val mBlock = json.substringAfter("\"merchant\"").substringBefore("}")
    val mId = mBlock.substringAfter("\"id\":").substringAfter("\"").substringBefore("\"")
    val mMcc = mBlock.substringAfter("\"mcc\":").substringAfter("\"").substringBefore("\"")
    val mAvgAmount = mBlock.substringAfter("\"avg_amount\":").substringBefore("}").trim().toDouble()

    val tBlock = json.substringAfter("\"terminal\"").substringBefore("}")
    val tOnline = tBlock.contains("\"is_online\":true") || tBlock.contains("\"is_online\": true")
    val tCard = tBlock.contains("\"card_present\":true") || tBlock.contains("\"card_present\": true")
    val tKmHome = tBlock.substringAfter("\"km_from_home\":").substringBefore("}").trim().toDouble()

    val lBlock = json.substringAfter("\"last_transaction\"").substringBefore("}")
    val isLastTxNull = json.substringAfter("\"last_transaction\":").trim().startsWith("null")

    // Date parsing
    val dtf = DateTimeFormatter.ISO_DATE_TIME
    val reqTime = LocalDateTime.parse(txReqAt, dtf)
    
    vector[0] = clampToByte(txAmount / 10000.0)
    vector[1] = clampToByte(txInstalls / 12.0)
    vector[2] = clampToByte((txAmount / cAvgAmount) / 10.0)
    vector[3] = clampToByte(reqTime.hour / 23.0)
    vector[4] = clampToByte((reqTime.dayOfWeek.value - 1) / 6.0)
    
    if (isLastTxNull) {
        vector[5] = -128 // sentinel -1
        vector[6] = -128 // sentinel -1
    } else {
        val lTimeStr = lBlock.substringAfter("\"timestamp\":").substringAfter("\"").substringBefore("\"")
        val lKmStr = lBlock.substringAfter("\"km_from_current\":").substringBefore("}").trim().toDouble()
        val lTime = LocalDateTime.parse(lTimeStr, dtf)
        val minutesDiff = (reqTime.toEpochSecond(ZoneOffset.UTC) - lTime.toEpochSecond(ZoneOffset.UTC)) / 60.0
        vector[5] = clampToByte(minutesDiff / 1440.0)
        vector[6] = clampToByte(lKmStr / 1000.0)
    }

    // Dim 7: km_from_home
    vector[7] = clampToByte(tKmHome / 1000.0)
    // Dim 8: tx_count_24h
    vector[8] = clampToByte(cTxCount / 20.0)
    // Dim 9: is_online
    vector[9] = if (tOnline) 127.toByte() else 0.toByte()
    // Dim 10: card_present
    vector[10] = if (tCard) 127.toByte() else 0.toByte()
    // Dim 11: unknown_merchant (1 if unknown, 0 if known)
    vector[11] = if (!cMerchants.contains(mId)) 127.toByte() else 0.toByte()
    
    // Dim 12: mcc_risk
    val mccKey = mMcc.toIntOrNull() ?: 0
    vector[12] = if (mccKey in mccRiskMap.indices) mccRiskMap[mccKey].toByte() else 63.toByte()

    // Dim 13: merchant_avg_amount
    vector[13] = clampToByte(mAvgAmount / 10000.0)
}

fun clampToByte(value: Double): Byte {
    val clamped = when {
        value < 0.0 -> 0.0
        value > 1.0 -> 1.0
        else -> value
    }
    return (clamped * 127.0).roundToInt().toByte()
}