package rinha

import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.math.roundToInt

const val VECTOR_SIZE = 15

val RESPONSES_BUFFERS = Array(6) { frauds ->
    val score = frauds / 5.0
    val approved = score < 0.6
    val json = "{\"approved\":$approved,\"fraud_score\":$score}"
    val http = "HTTP/1.1 200 OK\r\nContent-Length: ${json.length}\r\nContent-Type: application/json\r\n\r\n$json"
    ByteBuffer.wrap(http.toByteArray(Charsets.US_ASCII))
}

val HTTP_READY_BUFFER = ByteBuffer.wrap("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))

val mccRiskMap = IntArray(10000) { 63 }

var totalClusters = 0
lateinit var indexData: ByteArray
lateinit var centroids: ByteArray
lateinit var clusterSizes: IntArray
lateinit var clusterOffsets: IntArray

val TX_KEY = "\"transaction\"".toByteArray(Charsets.US_ASCII)
val CUST_KEY = "\"customer\"".toByteArray(Charsets.US_ASCII)
val MERCH_KEY = "\"merchant\"".toByteArray(Charsets.US_ASCII)
val TERM_KEY = "\"terminal\"".toByteArray(Charsets.US_ASCII)
val LAST_TX_KEY = "\"last_transaction\"".toByteArray(Charsets.US_ASCII)

val AMT_KEY = "\"amount\":".toByteArray(Charsets.US_ASCII)
val INST_KEY = "\"installments\":".toByteArray(Charsets.US_ASCII)
val REQ_AT_KEY = "\"requested_at\":".toByteArray(Charsets.US_ASCII)
val AVG_AMT_KEY = "\"avg_amount\":".toByteArray(Charsets.US_ASCII)
val TX_COUNT_KEY = "\"tx_count_24h\":".toByteArray(Charsets.US_ASCII)
val ID_KEY = "\"id\":".toByteArray(Charsets.US_ASCII)
val MCC_KEY = "\"mcc\":".toByteArray(Charsets.US_ASCII)
val ONLINE_KEY = "\"is_online\":".toByteArray(Charsets.US_ASCII)
val CARD_KEY = "\"card_present\":".toByteArray(Charsets.US_ASCII)
val KM_HOME_KEY = "\"km_from_home\":".toByteArray(Charsets.US_ASCII)
val TIMESTAMP_KEY = "\"timestamp\":".toByteArray(Charsets.US_ASCII)
val KM_CURR_KEY = "\"km_from_current\":".toByteArray(Charsets.US_ASCII)
val KNOWN_MERCH_KEY = "\"known_merchants\":".toByteArray(Charsets.US_ASCII)
val NULL_KEY = "null".toByteArray(Charsets.US_ASCII)
val BRACE_KEY = "{".toByteArray(Charsets.US_ASCII)
val ARRAY_END = "]".toByteArray(Charsets.US_ASCII)

val CUM_DAYS = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

class Connection {
    val raw = ByteArray(16384)
    val buffer: ByteBuffer = ByteBuffer.wrap(raw)
    var pos = 0
    var processOffset = 0
}

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
    val fileSize = channel.size().toInt()
    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    mapped.order(ByteOrder.LITTLE_ENDIAN)

    indexData = ByteArray(fileSize)
    mapped.get(indexData)

    var offset = 0
    totalClusters = ByteBuffer.wrap(indexData, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    offset += 4

    centroids = ByteArray(totalClusters * 14)
    clusterSizes = IntArray(totalClusters)
    clusterOffsets = IntArray(totalClusters)

    for (c in 0 until totalClusters) {
        System.arraycopy(indexData, offset, centroids, c * 14, 14)
        offset += 14
        val size = ByteBuffer.wrap(indexData, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        offset += 4
        clusterSizes[c] = size
    }

    var currentOffset = offset
    for (c in 0 until totalClusters) {
        clusterOffsets[c] = currentOffset
        currentOffset += clusterSizes[c] * VECTOR_SIZE
    }

    val socketPath = System.getenv("SOCKET_PATH") ?: "/tmp/app.sock"
    val file = File(socketPath)
    if (file.exists()) file.delete()

    val selector = Selector.open()
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    
    server.bind(UnixDomainSocketAddress.of(socketPath), 65535)
    server.configureBlocking(false)
    server.register(selector, SelectionKey.OP_ACCEPT)

    val queryVector = ByteArray(14)

    while (true) {
        selector.select()
        val iter = selector.selectedKeys().iterator()
        
        while (iter.hasNext()) {
            val key = iter.next()
            iter.remove()

            if (key.isAcceptable) {
                while (true) {
                    val client = server.accept() ?: break
                    client.configureBlocking(false)
                    client.register(selector, SelectionKey.OP_READ, Connection())
                }
            } else if (key.isReadable) {
                val client = key.channel() as SocketChannel
                val conn = key.attachment() as Connection
                
                try {
                    val read = client.read(conn.buffer)
                    if (read == -1) {
                        client.close()
                        continue
                    }
                    if (read == 0 && conn.pos == conn.raw.size && conn.processOffset == 0) {
                        client.close()
                        continue
                    }
                    if (read > 0) conn.pos += read

                    while (true) {
                        var headerEnd = -1
                        for (i in conn.processOffset until conn.pos - 3) {
                            if (conn.raw[i] == 13.toByte() && conn.raw[i + 1] == 10.toByte() &&
                                conn.raw[i + 2] == 13.toByte() && conn.raw[i + 3] == 10.toByte()) {
                                headerEnd = i + 4
                                break
                            }
                        }

                        if (headerEnd == -1) break

                        if (conn.raw[conn.processOffset] == 'G'.code.toByte()) {
                            HTTP_READY_BUFFER.clear()
                            var spins = 0
                            while (HTTP_READY_BUFFER.hasRemaining()) {
                                val w = client.write(HTTP_READY_BUFFER)
                                if (w < 0) { client.close(); break }
                                if (w == 0) { spins++; if (spins > 100) { client.close(); break }; Thread.yield() }
                            }
                            conn.processOffset = headerEnd
                            continue
                        }

                        var contentLength = 0
                        var idx = conn.processOffset
                        while (idx < headerEnd - 15) {
                            if ((conn.raw[idx].toInt() or 0x20) == 'c'.code &&
                                (conn.raw[idx + 1].toInt() or 0x20) == 'o'.code &&
                                conn.raw[idx + 14].toInt() == ':'.code) {
                                idx += 15
                                while (idx < headerEnd && conn.raw[idx] <= 32.toByte()) idx++
                                while (idx < headerEnd && conn.raw[idx] in '0'.code.toByte()..'9'.code.toByte()) {
                                    contentLength = contentLength * 10 + (conn.raw[idx] - '0'.code.toByte())
                                    idx++
                                }
                                break
                            }
                            idx++
                        }

                        val requestEnd = headerEnd + contentLength
                        if (conn.pos < requestEnd) break

                        val frauds = processPayload(conn.raw, headerEnd, requestEnd, queryVector)
                        
                        val respBuffer = RESPONSES_BUFFERS[frauds]
                        respBuffer.clear()
                        var spins = 0
                        while (respBuffer.hasRemaining()) {
                            val w = client.write(respBuffer)
                            if (w < 0) { client.close(); break }
                            if (w == 0) { spins++; if (spins > 100) { client.close(); break }; Thread.yield() }
                        }

                        conn.processOffset = requestEnd
                    }

                    if (conn.processOffset > 0) {
                        val remaining = conn.pos - conn.processOffset
                        if (remaining > 0) {
                            System.arraycopy(conn.raw, conn.processOffset, conn.raw, 0, remaining)
                        }
                        conn.pos = remaining
                        conn.processOffset = 0
                        conn.buffer.position(conn.pos)
                    }
                } catch (e: Exception) {
                    try { client.close() } catch (ex: Exception) {}
                }
            }
        }
    }
}

fun processPayload(buffer: ByteArray, start: Int, limit: Int, query: ByteArray): Int {
    vectorizePayload(buffer, start, limit, query)

    val q0 = query[0].toInt(); val q1 = query[1].toInt(); val q2 = query[2].toInt(); val q3 = query[3].toInt()
    val q4 = query[4].toInt(); val q5 = query[5].toInt(); val q6 = query[6].toInt(); val q7 = query[7].toInt()
    val q8 = query[8].toInt(); val q9 = query[9].toInt(); val q10 = query[10].toInt(); val q11 = query[11].toInt()
    val q12 = query[12].toInt(); val q13 = query[13].toInt()

    var bc0 = 0; var bd0 = Int.MAX_VALUE
    var bc1 = 0; var bd1 = Int.MAX_VALUE
    var bc2 = 0; var bd2 = Int.MAX_VALUE
    var bc3 = 0; var bd3 = Int.MAX_VALUE
    var bc4 = 0; var bd4 = Int.MAX_VALUE

    for (c in 0 until totalClusters) {
        var dist = 0
        val cOff = c * 14
        
        var d = q0 - centroids[cOff].toInt(); dist += d * d
        d = q1 - centroids[cOff + 1].toInt(); dist += d * d
        d = q2 - centroids[cOff + 2].toInt(); dist += d * d
        d = q3 - centroids[cOff + 3].toInt(); dist += d * d
        d = q4 - centroids[cOff + 4].toInt(); dist += d * d
        d = q5 - centroids[cOff + 5].toInt(); dist += d * d
        d = q6 - centroids[cOff + 6].toInt(); dist += d * d
        d = q7 - centroids[cOff + 7].toInt(); dist += d * d
        d = q8 - centroids[cOff + 8].toInt(); dist += d * d
        d = q9 - centroids[cOff + 9].toInt(); dist += d * d
        d = q10 - centroids[cOff + 10].toInt(); dist += d * d
        d = q11 - centroids[cOff + 11].toInt(); dist += d * d
        d = q12 - centroids[cOff + 12].toInt(); dist += d * d
        d = q13 - centroids[cOff + 13].toInt(); dist += d * d

        if (dist < bd4) {
            if (dist < bd3) {
                if (dist < bd2) {
                    if (dist < bd1) {
                        if (dist < bd0) {
                            bd4 = bd3; bc4 = bc3
                            bd3 = bd2; bc3 = bc2
                            bd2 = bd1; bc2 = bc1
                            bd1 = bd0; bc1 = bc0
                            bd0 = dist; bc0 = c
                        } else {
                            bd4 = bd3; bc4 = bc3
                            bd3 = bd2; bc3 = bc2
                            bd2 = bd1; bc2 = bc1
                            bd1 = dist; bc1 = c
                        }
                    } else {
                        bd4 = bd3; bc4 = bc3
                        bd3 = bd2; bc3 = bc2
                        bd2 = dist; bc2 = c
                    }
                } else {
                    bd4 = bd3; bc4 = bc3
                    bd3 = dist; bc3 = c
                }
            } else {
                bd4 = dist; bc4 = c
            }
        }
    }

    var top1Dist = Int.MAX_VALUE; var top1Label = 0
    var top2Dist = Int.MAX_VALUE; var top2Label = 0
    var top3Dist = Int.MAX_VALUE; var top3Label = 0
    var top4Dist = Int.MAX_VALUE; var top4Label = 0
    var top5Dist = Int.MAX_VALUE; var top5Label = 0

    fun scanCluster(c: Int) {
        val size = clusterSizes[c]
        var vOff = clusterOffsets[c]
        for (i in 0 until size) {
            var dist = 0
            
            var d = q0 - indexData[vOff].toInt(); dist += d * d
            d = q1 - indexData[vOff + 1].toInt(); dist += d * d
            d = q2 - indexData[vOff + 2].toInt(); dist += d * d
            d = q3 - indexData[vOff + 3].toInt(); dist += d * d
            
            if (dist <= top5Dist) {
                d = q4 - indexData[vOff + 4].toInt(); dist += d * d
                d = q5 - indexData[vOff + 5].toInt(); dist += d * d
                d = q6 - indexData[vOff + 6].toInt(); dist += d * d
                d = q7 - indexData[vOff + 7].toInt(); dist += d * d
                
                if (dist <= top5Dist) {
                    d = q8 - indexData[vOff + 8].toInt(); dist += d * d
                    d = q9 - indexData[vOff + 9].toInt(); dist += d * d
                    d = q10 - indexData[vOff + 10].toInt(); dist += d * d
                    d = q11 - indexData[vOff + 11].toInt(); dist += d * d
                    d = q12 - indexData[vOff + 12].toInt(); dist += d * d
                    d = q13 - indexData[vOff + 13].toInt(); dist += d * d
                    
                    if (dist < top5Dist) {
                        val label = indexData[vOff + 14].toInt()
                        
                        if (dist < top4Dist) {
                            if (dist < top3Dist) {
                                if (dist < top2Dist) {
                                    if (dist < top1Dist) {
                                        top5Dist = top4Dist; top5Label = top4Label
                                        top4Dist = top3Dist; top4Label = top3Label
                                        top3Dist = top2Dist; top3Label = top2Label
                                        top2Dist = top1Dist; top2Label = top1Label
                                        top1Dist = dist; top1Label = label
                                    } else {
                                        top5Dist = top4Dist; top5Label = top4Label
                                        top4Dist = top3Dist; top4Label = top3Label
                                        top3Dist = top2Dist; top3Label = top2Label
                                        top2Dist = dist; top2Label = label
                                    }
                                } else {
                                    top5Dist = top4Dist; top5Label = top4Label
                                    top4Dist = top3Dist; top4Label = top3Label
                                    top3Dist = dist; top3Label = label
                                }
                            } else {
                                top5Dist = top4Dist; top5Label = top4Label
                                top4Dist = dist; top4Label = label
                            }
                        } else {
                            top5Dist = dist; top5Label = label
                        }
                    }
                }
            }
            vOff += 15
        }
    }

    scanCluster(bc0)
    scanCluster(bc1)
    scanCluster(bc2)
    scanCluster(bc3)
    scanCluster(bc4)

    return top1Label + top2Label + top3Label + top4Label + top5Label
}

fun vectorizePayload(json: ByteArray, start: Int, limit: Int, vector: ByteArray) {
    val txBlock = find(json, TX_KEY, start, limit)
    val cBlock = find(json, CUST_KEY, start, limit)
    val mBlock = find(json, MERCH_KEY, start, limit)
    val tBlock = find(json, TERM_KEY, start, limit)
    val lBlock = find(json, LAST_TX_KEY, start, limit)

    val txAmount = parseDouble(json, AMT_KEY, txBlock, limit)
    val txInstalls = parseDouble(json, INST_KEY, txBlock, limit)

    val reqIdx = find(json, REQ_AT_KEY, txBlock, limit)
    var iReq = reqIdx + REQ_AT_KEY.size
    while (iReq < limit && (json[iReq] <= 32.toByte() || json[iReq] == '"'.code.toByte())) iReq++
    val reqY = (json[iReq] - 48) * 1000 + (json[iReq + 1] - 48) * 100 + (json[iReq + 2] - 48) * 10 + (json[iReq + 3] - 48)
    val reqM = (json[iReq + 5] - 48) * 10 + (json[iReq + 6] - 48)
    val reqD = (json[iReq + 8] - 48) * 10 + (json[iReq + 9] - 48)
    val reqH = (json[iReq + 11] - 48) * 10 + (json[iReq + 12] - 48)
    val reqMin = (json[iReq + 14] - 48) * 10 + (json[iReq + 15] - 48)
    val reqDow = getDayOfWeek(reqY, reqM, reqD)
    val reqEpoch = getEpochMinutes(reqY, reqM, reqD, reqH, reqMin)

    val cAvgAmount = parseDouble(json, AVG_AMT_KEY, cBlock, limit)
    val cTxCount = parseDouble(json, TX_COUNT_KEY, cBlock, limit)

    var mIdS = -1
    var mIdE = -1
    val mIdIdx = find(json, ID_KEY, mBlock, limit)
    if (mIdIdx != -1) {
        var i = mIdIdx + ID_KEY.size
        while (i < limit && (json[i] <= 32.toByte() || json[i] == '"'.code.toByte())) i++
        mIdS = i
        while (i < limit && json[i] != '"'.code.toByte()) i++
        mIdE = i
    }
    val mIdLen = mIdE - mIdS

    val mccIdx = find(json, MCC_KEY, mBlock, limit)
    var mMcc = 0
    if (mccIdx != -1) {
        var i = mccIdx + MCC_KEY.size
        while (i < limit && (json[i] <= 32.toByte() || json[i] == '"'.code.toByte())) i++
        while (i < limit && json[i] in '0'.code.toByte()..'9'.code.toByte()) {
            mMcc = mMcc * 10 + (json[i] - '0'.code.toByte())
            i++
        }
    }
    val mAvgAmount = parseDouble(json, AVG_AMT_KEY, mBlock, limit)

    val tOnline = parseBool(json, ONLINE_KEY, tBlock, limit)
    val tCard = parseBool(json, CARD_KEY, tBlock, limit)
    val tKmHome = parseDouble(json, KM_HOME_KEY, tBlock, limit)

    val lNullIdx = find(json, NULL_KEY, lBlock, limit)
    val lBraceIdx = find(json, BRACE_KEY, lBlock, limit)
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
        val lTimeIdx = find(json, TIMESTAMP_KEY, lBlock, limit)
        var iL = lTimeIdx + TIMESTAMP_KEY.size
        while (iL < limit && (json[iL] <= 32.toByte() || json[iL] == '"'.code.toByte())) iL++
        val lY = (json[iL] - 48) * 1000 + (json[iL + 1] - 48) * 100 + (json[iL + 2] - 48) * 10 + (json[iL + 3] - 48)
        val lM = (json[iL + 5] - 48) * 10 + (json[iL + 6] - 48)
        val lD = (json[iL + 8] - 48) * 10 + (json[iL + 9] - 48)
        val lH = (json[iL + 11] - 48) * 10 + (json[iL + 12] - 48)
        val lMin = (json[iL + 14] - 48) * 10 + (json[iL + 15] - 48)
        
        val lKmStr = parseDouble(json, KM_CURR_KEY, lBlock, limit)
        val lEpoch = getEpochMinutes(lY, lM, lD, lH, lMin)
        val minutesDiff = (reqEpoch - lEpoch).toDouble()

        vector[5] = clampToByte(minutesDiff / 1440.0)
        vector[6] = clampToByte(lKmStr / 1000.0)
    }

    vector[7] = clampToByte(tKmHome / 1000.0)
    vector[8] = clampToByte(cTxCount / 20.0)
    vector[9] = if (tOnline) 127.toByte() else 0.toByte()
    vector[10] = if (tCard) 127.toByte() else 0.toByte()

    var unknown = true
    val kIdx = find(json, KNOWN_MERCH_KEY, cBlock, limit)
    if (kIdx != -1) {
        val endIdx = find(json, ARRAY_END, kIdx, limit)
        if (endIdx != -1 && mIdS != -1) {
            var curr = kIdx
            while (curr <= endIdx - mIdLen) {
                if (json[curr] == json[mIdS]) {
                    var match = true
                    for (j in 1 until mIdLen) {
                        if (json[curr + j] != json[mIdS + j]) {
                            match = false
                            break
                        }
                    }
                    if (match && json[curr + mIdLen] == '"'.code.toByte()) {
                        unknown = false
                        break
                    }
                }
                curr++
            }
        }
    }
    vector[11] = if (unknown) 127.toByte() else 0.toByte()

    vector[12] = if (mMcc in mccRiskMap.indices) mccRiskMap[mMcc].toByte() else 63.toByte()
    vector[13] = clampToByte(mAvgAmount / 10000.0)
}

fun find(buf: ByteArray, key: ByteArray, start: Int, limit: Int): Int {
    if (start < 0) return -1
    val first = key[0]
    val max = limit - key.size
    var i = start
    while (i <= max) {
        if (buf[i] == first) {
            var match = true
            for (j in 1 until key.size) {
                if (buf[i + j] != key[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        i++
    }
    return -1
}

fun parseDouble(json: ByteArray, key: ByteArray, start: Int, limit: Int): Double {
    val kIdx = find(json, key, start, limit)
    if (kIdx == -1) return 0.0
    var i = kIdx + key.size
    while (i < limit && (json[i] <= 32.toByte() || json[i] == ':'.code.toByte() || json[i] == '"'.code.toByte())) i++
    var isNegative = false
    if (i < limit && json[i] == '-'.code.toByte()) { isNegative = true; i++ }
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

fun parseBool(json: ByteArray, key: ByteArray, start: Int, limit: Int): Boolean {
    val kIdx = find(json, key, start, limit)
    if (kIdx == -1) return false
    var i = kIdx + key.size
    while (i < limit && (json[i] <= 32.toByte() || json[i] == ':'.code.toByte())) i++
    return json[i] == 't'.code.toByte()
}

fun clampToByte(value: Double): Byte {
    val clamped = if (value != value) 0.0 else if (value < 0.0) 0.0 else if (value > 1.0) 1.0 else value
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
    return (if (dow < 0) dow + 7 else dow + 5) % 7
}

fun getEpochMinutes(year: Int, month: Int, day: Int, hour: Int, min: Int): Long {
    var days = (year - 1970) * 365 + (year - 1969) / 4 - (year - 1901) / 100 + (year - 1601) / 400
    days += CUM_DAYS[month - 1]
    if (month > 2 && ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0))) {
        days += 1
    }
    days += day - 1
    return days * 1440L + hour * 60L + min
}