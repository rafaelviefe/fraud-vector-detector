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

object Engine {
    lateinit var treeLeft: IntArray
    lateinit var treeRight: IntArray
    lateinit var treeSplitDim: ByteArray
    lateinit var treeLabel: ByteArray
    lateinit var treeOrigId: IntArray
    lateinit var treeVectors: ShortArray

    val mccRiskMap = DoubleArray(10000) { 0.5 }

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

    val responseBytes = Array(6) { frauds ->
        val score = frauds / 5.0
        val approved = score < 0.6
        val json = "{\"approved\":$approved,\"fraud_score\":$score}"
        "HTTP/1.1 200 OK\r\nContent-Length: ${json.length}\r\nContent-Type: application/json\r\n\r\n$json".toByteArray(Charsets.US_ASCII)
    }
    
    val readyBytes = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)

    fun loadMccRisk() {
        val file = File("resources/mcc_risk.json")
        if (!file.exists()) return
        val content = file.readText()
        val pattern = "\"(\\d{4})\":\\s*([0-9.]+)".toRegex()
        pattern.findAll(content).forEach { matchResult ->
            val mcc = matchResult.groupValues[1].toInt()
            val risk = matchResult.groupValues[2].toDouble()
            mccRiskMap[mcc] = risk
        }
    }

    fun initData() {
        loadMccRisk()
        val file = File("resources/index.bin")
        if (!file.exists()) return
        val channel = RandomAccessFile(file, "r").channel
        val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        mapped.order(ByteOrder.LITTLE_ENDIAN)

        val count = mapped.getInt()
        treeLeft = IntArray(count)
        treeRight = IntArray(count)
        treeSplitDim = ByteArray(count)
        treeLabel = ByteArray(count)
        treeOrigId = IntArray(count)
        treeVectors = ShortArray(count * 14)

        for (i in 0 until count) {
            treeLeft[i] = mapped.getInt()
            treeRight[i] = mapped.getInt()
            treeSplitDim[i] = mapped.get()
            treeLabel[i] = mapped.get()
            treeOrigId[i] = mapped.getInt()
            for (d in 0 until 14) {
                treeVectors[i * 14 + d] = mapped.getShort()
            }
        }
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

    fun clamp(value: Double): Double {
        return if (value != value) 0.0 else if (value < 0.0) 0.0 else if (value > 1.0) 1.0 else value
    }

    fun clampShort(x: Double): Short {
        val c = if (x < 0.0) x else clamp(x)
        return Math.round(c * 10000.0).toInt().toShort()
    }

    fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0

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

    fun vectorizePayload(json: ByteArray, start: Int, limit: Int, vShort: ShortArray, vDouble: DoubleArray) {
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

        vDouble[0] = round4(clamp(txAmount / 10000.0))
        vShort[0] = clampShort(txAmount / 10000.0)
        
        vDouble[1] = round4(clamp(txInstalls / 12.0))
        vShort[1] = clampShort(txInstalls / 12.0)
        
        val c2 = if (cAvgAmount > 0.0) (txAmount / cAvgAmount) / 10.0 else 0.0
        vDouble[2] = round4(clamp(c2))
        vShort[2] = clampShort(c2)
        
        vDouble[3] = round4(clamp(reqH / 23.0))
        vShort[3] = clampShort(reqH / 23.0)
        
        vDouble[4] = round4(clamp(reqDow / 6.0))
        vShort[4] = clampShort(reqDow / 6.0)

        if (isLastTxNull) {
            vDouble[5] = round4(-1.0)
            vShort[5] = -10000
            vDouble[6] = round4(-1.0)
            vShort[6] = -10000
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

            vDouble[5] = round4(clamp(minutesDiff / 1440.0))
            vShort[5] = clampShort(minutesDiff / 1440.0)
            vDouble[6] = round4(clamp(lKmStr / 1000.0))
            vShort[6] = clampShort(lKmStr / 1000.0)
        }

        vDouble[7] = round4(clamp(tKmHome / 1000.0))
        vShort[7] = clampShort(tKmHome / 1000.0)
        
        vDouble[8] = round4(clamp(cTxCount / 20.0))
        vShort[8] = clampShort(cTxCount / 20.0)
        
        vDouble[9] = round4(if (tOnline) 1.0 else 0.0)
        vShort[9] = if (tOnline) 10000.toShort() else 0.toShort()
        
        vDouble[10] = round4(if (tCard) 1.0 else 0.0)
        vShort[10] = if (tCard) 10000.toShort() else 0.toShort()

        var unknown = true
        val kIdx = find(json, KNOWN_MERCH_KEY, cBlock, limit)
        if (kIdx != -1) {
            val endIdx = find(json, ARRAY_END, kIdx, limit)
            if (endIdx != -1 && mIdS != -1) {
                var curr = kIdx
                while (curr <= endIdx - mIdLen) {
                    if (json[curr] == json[mIdS] && json[curr - 1] == '"'.code.toByte()) {
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
        
        vDouble[11] = round4(if (unknown) 1.0 else 0.0)
        vShort[11] = if (unknown) 10000.toShort() else 0.toShort()
        
        val mRisk = if (mMcc in mccRiskMap.indices) mccRiskMap[mMcc] else 0.5
        vDouble[12] = round4(clamp(mRisk))
        vShort[12] = clampShort(mRisk)
        
        vDouble[13] = round4(clamp(mAvgAmount / 10000.0))
        vShort[13] = clampShort(mAvgAmount / 10000.0)
    }
}

class RequestProcessor {
    val queryShort = ShortArray(14)
    val queryDouble = DoubleArray(14)
    
    val topDist = LongArray(5)
    val topNode = IntArray(5)
    
    val poolNode = IntArray(50000)
    var poolSize = 0
    var visits = 0
    
    val exactDists = DoubleArray(5)
    val exactOrig = IntArray(5)
    val exactLabel = ByteArray(5)

    fun processPayload(json: ByteArray, start: Int, limit: Int): Int {
        Engine.vectorizePayload(json, start, limit, queryShort, queryDouble)
        
        for (i in 0 until 5) {
            topDist[i] = Long.MAX_VALUE
            topNode[i] = -1
            exactDists[i] = Double.MAX_VALUE
            exactOrig[i] = Int.MAX_VALUE
            exactLabel[i] = 0
        }
        poolSize = 0
        visits = 0
        
        primeKD()
        searchKD(0)
        
        for (i in 0 until poolSize) {
            val node = poolNode[i]
            var dSq = 0.0
            val off = node * 14
            for (d in 0 until 14) {
                val a = queryDouble[d]
                val b = Engine.treeVectors[off + d].toDouble() / 10000.0
                val diff = a - b
                dSq += diff * diff
            }
            val origId = Engine.treeOrigId[node]
            val label = Engine.treeLabel[node]
            
            var dup = false
            for (k in 0 until 5) {
                if (exactOrig[k] == origId) { dup = true; break }
            }
            if (dup) continue
            
            for (k in 0 until 5) {
                if (dSq < exactDists[k] || (dSq == exactDists[k] && origId < exactOrig[k])) {
                    for (j in 4 downTo k + 1) {
                        exactDists[j] = exactDists[j - 1]
                        exactOrig[j] = exactOrig[j - 1]
                        exactLabel[j] = exactLabel[j - 1]
                    }
                    exactDists[k] = dSq
                    exactOrig[k] = origId
                    exactLabel[k] = label
                    break
                }
            }
        }
        
        var fraudCount = 0
        for (i in 0 until 5) {
            if (exactLabel[i].toInt() == 1) fraudCount++
        }
        return fraudCount
    }

    private fun insertTop(node: Int, dist: Long) {
        for (i in 0 until 5) {
            if (dist < topDist[i]) {
                for (j in 4 downTo i + 1) {
                    topDist[j] = topDist[j - 1]
                    topNode[j] = topNode[j - 1]
                }
                topDist[i] = dist
                topNode[i] = node
                break
            }
        }
    }

    private fun evalNode(node: Int) {
        visits++
        var distSq = 0L
        val off = node * 14
        for (d in 0 until 14) {
            val diff = (queryShort[d] - Engine.treeVectors[off + d]).toLong()
            distSq += diff * diff
        }
        
        if (topDist[4] == Long.MAX_VALUE || distSq <= topDist[4]) {
            insertTop(node, distSq)
            if (poolSize < poolNode.size) {
                poolNode[poolSize++] = node
            }
        }
    }

    private fun primeKD() {
        var bestFar = -1
        var bestFarDeltaSq = Long.MAX_VALUE
        
        var node = 0
        while (node != -1) {
            evalNode(node)
            
            val splitDim = Engine.treeSplitDim[node].toInt()
            val splitVal = Engine.treeVectors[node * 14 + splitDim].toInt()
            val queryVal = queryShort[splitDim].toInt()
            
            val delta = (queryVal - splitVal).toLong()
            val deltaSq = delta * delta
            
            val left = Engine.treeLeft[node]
            val right = Engine.treeRight[node]
            
            val near = if (delta < 0) left else right
            val far = if (delta < 0) right else left
            
            if (far != -1 && deltaSq < bestFarDeltaSq) {
                bestFarDeltaSq = deltaSq
                bestFar = far
            }
            node = near
        }
        
        if (bestFar != -1) {
            node = bestFar
            while (node != -1) {
                evalNode(node)
                
                val splitDim = Engine.treeSplitDim[node].toInt()
                val splitVal = Engine.treeVectors[node * 14 + splitDim].toInt()
                val queryVal = queryShort[splitDim].toInt()
                
                val delta = (queryVal - splitVal).toLong()
                
                val left = Engine.treeLeft[node]
                val right = Engine.treeRight[node]
                
                node = if (delta < 0) left else right
            }
        }
    }

    private fun searchKD(node: Int) {
        if (node == -1) return
        if (visits > 25000) return
        
        evalNode(node)
        
        val splitDim = Engine.treeSplitDim[node].toInt()
        val splitVal = Engine.treeVectors[node * 14 + splitDim].toInt()
        val queryVal = queryShort[splitDim].toInt()
        
        val delta = (queryVal - splitVal).toLong()
        
        val left = Engine.treeLeft[node]
        val right = Engine.treeRight[node]
        
        val near = if (delta < 0) left else right
        val far = if (delta < 0) right else left
        
        searchKD(near)
        
        if (topDist[4] == Long.MAX_VALUE || (delta * delta) <= topDist[4]) {
            searchKD(far)
        }
    }
}

class ConnectionWorker(private val client: SocketChannel) : Runnable {
    private val raw = ByteArray(65536)
    private val buffer = ByteBuffer.wrap(raw)
    private val processor = RequestProcessor()

    override fun run() {
        val readyBuffer = ByteBuffer.wrap(Engine.readyBytes)
        val respBuffers = Array(6) { ByteBuffer.wrap(Engine.responseBytes[it]) }

        var pos = 0
        var processOffset = 0
        try {
            while (true) {
                if (processOffset > 0) {
                    val remaining = pos - processOffset
                    if (remaining > 0) {
                        System.arraycopy(raw, processOffset, raw, 0, remaining)
                    }
                    pos = remaining
                    processOffset = 0
                }

                if (pos == raw.size) break

                buffer.position(pos)
                buffer.limit(raw.size)
                val read = client.read(buffer)
                if (read == -1) break
                pos += read

                while (true) {
                    var headerEnd = -1
                    for (i in processOffset until pos - 3) {
                        if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() &&
                            raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()) {
                            headerEnd = i + 4
                            break
                        }
                    }
                    if (headerEnd == -1) break

                    if (raw[processOffset] == 'G'.code.toByte()) {
                        readyBuffer.clear()
                        while (readyBuffer.hasRemaining()) {
                            if (client.write(readyBuffer) <= 0) break
                        }
                        processOffset = headerEnd
                        continue
                    }

                    var contentLength = 0
                    var idx = processOffset
                    while (idx < headerEnd - 15) {
                        if ((raw[idx].toInt() or 0x20) == 'c'.code &&
                            (raw[idx + 1].toInt() or 0x20) == 'o'.code &&
                            raw[idx + 14].toInt() == ':'.code) {
                            idx += 15
                            while (idx < headerEnd && raw[idx] <= 32.toByte()) idx++
                            while (idx < headerEnd && raw[idx] in '0'.code.toByte()..'9'.code.toByte()) {
                                contentLength = contentLength * 10 + (raw[idx] - '0'.code.toByte())
                                idx++
                            }
                            break
                        }
                        idx++
                    }

                    val requestEnd = headerEnd + contentLength
                    if (pos < requestEnd) break

                    val frauds = processor.processPayload(raw, headerEnd, requestEnd)
                    
                    val respBuffer = respBuffers[frauds]
                    respBuffer.clear()
                    while (respBuffer.hasRemaining()) {
                        if (client.write(respBuffer) <= 0) break
                    }

                    processOffset = requestEnd
                }
            }
        } catch (e: Exception) {
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }
}

fun main() {
    Engine.initData()

    val dummyPayload1 = """{"id": "tx-1","transaction": {"amount": 100.0,"installments": 1,"requested_at": "2026-03-11T20:23:35Z"},"customer": {"avg_amount": 100.0,"tx_count_24h": 1,"known_merchants": ["M1"]},"merchant": {"id": "M1","mcc": "1234","avg_amount": 100.0},"terminal": {"is_online": false,"card_present": true,"km_from_home": 1.0},"last_transaction": null}""".toByteArray(Charsets.US_ASCII)
    val dummyPayload2 = """{"id": "tx-2","transaction": {"amount": 100.0,"installments": 1,"requested_at": "2026-03-11T20:23:35Z"},"customer": {"avg_amount": 100.0,"tx_count_24h": 1,"known_merchants": ["M2"]},"merchant": {"id": "M1","mcc": "1234","avg_amount": 100.0},"terminal": {"is_online": false,"card_present": true,"km_from_home": 1.0},"last_transaction": {"timestamp": "2026-03-11T14:58:35Z","km_from_current": 18.8}}""".toByteArray(Charsets.US_ASCII)
    
    val processor = RequestProcessor()
    for (i in 0 until 500) {
        processor.processPayload(dummyPayload1, 0, dummyPayload1.size)
        processor.processPayload(dummyPayload2, 0, dummyPayload2.size)
    }

    val socketPath = System.getenv("SOCKET_PATH") ?: "/tmp/app.sock"
    val file = File(socketPath)
    if (file.exists()) file.delete()

    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(socketPath), 65535)

    while (true) {
        val client = server.accept()
        Thread.startVirtualThread(ConnectionWorker(client))
    }
}