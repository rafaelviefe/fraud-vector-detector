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
import java.util.concurrent.Executors
import kotlin.math.roundToInt

const val VECTOR_SIZE = 15

val RESPONSES = Array(6) { frauds ->
    val score = frauds / 5.0
    val approved = score < 0.6
    val json = "{\"approved\":$approved,\"fraud_score\":$score}"
    val http = "HTTP/1.1 200 OK\r\nContent-Length: ${json.length}\r\nContent-Type: application/json\r\n\r\n$json"
    http.toByteArray(Charsets.US_ASCII)
}

val HTTP_READY = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)

val mccRiskMap = IntArray(10000) { 63 }

lateinit var MAPPED_BUFFER: ByteBuffer
var totalClusters = 0
lateinit var centroids: ByteArray
lateinit var clusterSizes: IntArray
lateinit var clusterOffsets: IntArray

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
    loadMccRisk()

    val channel = RandomAccessFile("resources/index.bin", "r").channel
    MAPPED_BUFFER = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    MAPPED_BUFFER.order(ByteOrder.LITTLE_ENDIAN)

    totalClusters = MAPPED_BUFFER.int
    centroids = ByteArray(totalClusters * DIMENSIONS)
    clusterSizes = IntArray(totalClusters)
    clusterOffsets = IntArray(totalClusters)

    var currentOffset = 4 + totalClusters * (DIMENSIONS + 4)
    for (c in 0 until totalClusters) {
        MAPPED_BUFFER.get(centroids, c * DIMENSIONS, DIMENSIONS)
        val size = MAPPED_BUFFER.int
        clusterSizes[c] = size
        clusterOffsets[c] = currentOffset
        currentOffset += size * VECTOR_SIZE
    }

    val socketPath = System.getenv("SOCKET_PATH") ?: "/tmp/app.sock"
    val file = File(socketPath)
    if (file.exists()) file.delete()

    val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    serverChannel.bind(UnixDomainSocketAddress.of(socketPath), 10000)

    val executor = Executors.newVirtualThreadPerTaskExecutor()

    while (true) {
        val client = serverChannel.accept()
        executor.submit { handleClient(client) }
    }
}

fun handleClient(client: SocketChannel) {
    try {
        val ioBuffer = ByteBuffer.allocate(8192)
        val jsonArray = ioBuffer.array()
        var bodyStart = -1
        var contentLength = -1

        while (true) {
            val read = client.read(ioBuffer)
            if (read == -1) return

            val pos = ioBuffer.position()
            if (pos > 0 && jsonArray[0] == 'G'.code.toByte()) {
                client.write(ByteBuffer.wrap(HTTP_READY))
                return
            }

            if (bodyStart == -1) {
                for (i in 0 until pos - 3) {
                    if (jsonArray[i] == '\r'.code.toByte() && jsonArray[i + 1] == '\n'.code.toByte() &&
                        jsonArray[i + 2] == '\r'.code.toByte() && jsonArray[i + 3] == '\n'.code.toByte()
                    ) {
                        bodyStart = i + 4
                        val headerStr = String(jsonArray, 0, bodyStart, Charsets.US_ASCII).lowercase()
                        val clIdx = headerStr.indexOf("content-length:")
                        if (clIdx != -1) {
                            val end = headerStr.indexOf('\r', clIdx)
                            contentLength = headerStr.substring(clIdx + 15, end).trim().toInt()
                        }
                        break
                    }
                }
            }

            if (bodyStart != -1 && contentLength != -1) {
                if (pos >= bodyStart + contentLength) {
                    break
                }
            }
        }

        val limit = bodyStart + contentLength
        val queryVector = ByteArray(DIMENSIONS)
        vectorizePayload(jsonArray, bodyStart, limit, queryVector)

        var bestCluster1 = 0
        var minCentroidDist1 = Int.MAX_VALUE
        var bestCluster2 = 0
        var minCentroidDist2 = Int.MAX_VALUE

        for (c in 0 until totalClusters) {
            var dist = 0
            val cOff = c * DIMENSIONS
            for (d in 0 until DIMENSIONS) {
                val diff = queryVector[d].toInt() - centroids[cOff + d].toInt()
                dist += diff * diff
            }
            if (dist < minCentroidDist1) {
                minCentroidDist2 = minCentroidDist1
                bestCluster2 = bestCluster1
                minCentroidDist1 = dist
                bestCluster1 = c
            } else if (dist < minCentroidDist2) {
                minCentroidDist2 = dist
                bestCluster2 = c
            }
        }

        var top1Dist = Int.MAX_VALUE; var top1Label = 0
        var top2Dist = Int.MAX_VALUE; var top2Label = 0
        var top3Dist = Int.MAX_VALUE; var top3Label = 0
        var top4Dist = Int.MAX_VALUE; var top4Label = 0
        var top5Dist = Int.MAX_VALUE; var top5Label = 0

        fun scanCluster(c: Int) {
            val size = clusterSizes[c]
            val offset = clusterOffsets[c]
            for (i in 0 until size) {
                var dist = 0
                val vOff = offset + i * VECTOR_SIZE
                for (d in 0 until DIMENSIONS) {
                    val diff = queryVector[d].toInt() - MAPPED_BUFFER.get(vOff + d).toInt()
                    dist += diff * diff
                }
                val label = MAPPED_BUFFER.get(vOff + DIMENSIONS).toInt()

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
        }

        scanCluster(bestCluster1)
        scanCluster(bestCluster2)

        val frauds = top1Label + top2Label + top3Label + top4Label + top5Label
        client.write(ByteBuffer.wrap(RESPONSES[frauds]))

    } catch (e: Exception) {
    } finally {
        try { client.close() } catch (e: Exception) {}
    }
}

fun vectorizePayload(json: ByteArray, start: Int, limit: Int, vector: ByteArray) {
    val txBlock = findBlock(json, "\"transaction\"", start, limit)
    val cBlock = findBlock(json, "\"customer\"", start, limit)
    val mBlock = findBlock(json, "\"merchant\"", start, limit)
    val tBlock = findBlock(json, "\"terminal\"", start, limit)
    val lBlock = findBlock(json, "\"last_transaction\"", start, limit)

    val txAmount = parseDouble(json, txBlock, limit, "\"amount\":")
    val txInstalls = parseDouble(json, txBlock, limit, "\"installments\":")
    
    val reqTimeStr = parseString(json, txBlock, limit, "\"requested_at\":")
    val reqY = reqTimeStr.substring(0, 4).toInt()
    val reqM = reqTimeStr.substring(5, 7).toInt()
    val reqD = reqTimeStr.substring(8, 10).toInt()
    val reqH = reqTimeStr.substring(11, 13).toInt()
    val reqMin = reqTimeStr.substring(14, 16).toInt()
    val reqDow = getDayOfWeek(reqY, reqM, reqD)

    val cAvgAmount = parseDouble(json, cBlock, limit, "\"avg_amount\":")
    val cTxCount = parseDouble(json, cBlock, limit, "\"tx_count_24h\":")

    val mId = parseString(json, mBlock, limit, "\"id\":")
    val mMcc = parseString(json, mBlock, limit, "\"mcc\":").toIntOrNull() ?: 0
    val mAvgAmount = parseDouble(json, mBlock, limit, "\"avg_amount\":")

    val tOnline = parseBool(json, tBlock, limit, "\"is_online\":")
    val tCard = parseBool(json, tBlock, limit, "\"card_present\":")
    val tKmHome = parseDouble(json, tBlock, limit, "\"km_from_home\":")

    val lNullIdx = findBlock(json, "null", lBlock, limit)
    val lBraceIdx = findBlock(json, "{", lBlock, limit)
    val isLastTxNull = (lNullIdx != -1 && (lBraceIdx == -1 || lNullIdx < lBraceIdx))

    vector[0] = clampToByte(txAmount / 10000.0)
    vector[1] = clampToByte(txInstalls / 12.0)
    vector[2] = clampToByte(if (cAvgAmount > 0.0) (txAmount / cAvgAmount) / 10.0 else 0.0)
    vector[3] = clampToByte(reqH / 23.0)
    vector[4] = clampToByte(reqDow / 6.0)

    if (isLastTxNull) {
        vector[5] = -128
        vector[6] = -128
    } else {
        val lTimeStr = parseString(json, lBlock, limit, "\"timestamp\":")
        val lKmStr = parseDouble(json, lBlock, limit, "\"km_from_current\":")
        val lY = lTimeStr.substring(0, 4).toInt()
        val lM = lTimeStr.substring(5, 7).toInt()
        val lD = lTimeStr.substring(8, 10).toInt()
        val lH = lTimeStr.substring(11, 13).toInt()
        val lMin = lTimeStr.substring(14, 16).toInt()

        val reqEpoch = getEpochMinutes(reqY, reqM, reqD, reqH, reqMin)
        val lEpoch = getEpochMinutes(lY, lM, lD, lH, lMin)
        val minutesDiff = (reqEpoch - lEpoch).toDouble()

        vector[5] = clampToByte(minutesDiff / 1440.0)
        vector[6] = clampToByte(lKmStr / 1000.0)
    }

    vector[7] = clampToByte(tKmHome / 1000.0)
    vector[8] = clampToByte(cTxCount / 20.0)
    vector[9] = if (tOnline) 127.toByte() else 0.toByte()
    vector[10] = if (tCard) 127.toByte() else 0.toByte()
    vector[11] = if (!containsMerchant(json, cBlock, limit, "\"known_merchants\":", mId)) 127.toByte() else 0.toByte()

    vector[12] = if (mMcc in mccRiskMap.indices) mccRiskMap[mMcc].toByte() else 63.toByte()
    vector[13] = clampToByte(mAvgAmount / 10000.0)
}

fun findBlock(json: ByteArray, key: String, start: Int, limit: Int): Int {
    if (start == -1) return -1
    val keyBytes = key.toByteArray()
    for (i in start..limit - keyBytes.size) {
        var match = true
        for (j in keyBytes.indices) {
            if (json[i + j] != keyBytes[j]) { match = false; break }
        }
        if (match) return i
    }
    return -1
}

fun parseDouble(json: ByteArray, blockStart: Int, limit: Int, key: String): Double {
    val kIdx = findBlock(json, key, blockStart, limit)
    if (kIdx == -1) return 0.0
    var i = kIdx + key.length
    while (i < limit && json[i] == ' '.code.toByte()) i++
    var isNegative = false
    if (json[i] == '-'.code.toByte()) { isNegative = true; i++ }
    var intPart = 0.0
    while (i < limit && json[i] in '0'.code.toByte()..'9'.code.toByte()) {
        intPart = intPart * 10 + (json[i] - '0'.code.toByte())
        i++
    }
    if (i < limit && json[i] == '.'.code.toByte()) {
        i++
        var fraction = 0.0
        var divisor = 10.0
        while (i < limit && json[i] in '0'.code.toByte()..'9'.code.toByte()) {
            fraction += (json[i] - '0'.code.toByte()) / divisor
            divisor *= 10
            i++
        }
        intPart += fraction
    }
    return if (isNegative) -intPart else intPart
}

fun parseString(json: ByteArray, blockStart: Int, limit: Int, key: String): String {
    val kIdx = findBlock(json, key, blockStart, limit)
    if (kIdx == -1) return ""
    var i = kIdx + key.length
    while (i < limit && json[i] == ' '.code.toByte()) i++
    if (json[i] == '"'.code.toByte()) i++
    val start = i
    while (i < limit && json[i] != '"'.code.toByte()) i++
    return String(json, start, i - start, Charsets.US_ASCII)
}

fun parseBool(json: ByteArray, blockStart: Int, limit: Int, key: String): Boolean {
    val kIdx = findBlock(json, key, blockStart, limit)
    if (kIdx == -1) return false
    var i = kIdx + key.length
    while (i < limit && json[i] == ' '.code.toByte()) i++
    return json[i] == 't'.code.toByte()
}

fun containsMerchant(json: ByteArray, blockStart: Int, limit: Int, key: String, mId: String): Boolean {
    val kIdx = findBlock(json, key, blockStart, limit)
    if (kIdx == -1) return false
    val i = kIdx + key.length
    val endIdx = findBlock(json, "]", i, limit)
    if (endIdx == -1) return false
    return findBlock(json, mId, i, endIdx) != -1
}

fun clampToByte(value: Double): Byte {
    val clamped = when {
        value.isNaN() -> 0.0
        value < 0.0 -> 0.0
        value > 1.0 -> 1.0
        else -> value
    }
    return (clamped * 127.0).roundToInt().toByte()
}

fun getDayOfWeek(year: Int, month: Int, day: Int): Int {
    var y = year
    var m = month
    if (m < 3) { m += 12; y -= 1 }
    val k = y % 100
    val j = y / 100
    val f = day + (13 * (m + 1)) / 5 + k + (k / 4) + (j / 4) - 2 * j
    val dow = f % 7
    return (dow + 5) % 7
}

fun getEpochMinutes(year: Int, month: Int, day: Int, hour: Int, min: Int): Long {
    var days = day.toLong() - 1
    for (i in 1970 until year) {
        days += if ((i % 4 == 0 && i % 100 != 0) || (i % 400 == 0)) 366 else 365
    }
    val dim = intArrayOf(31, if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    for (i in 0 until month - 1) days += dim[i]
    return days * 1440L + hour * 60L + min
}