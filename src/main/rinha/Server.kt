package rinha

import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

const val DIMENSIONS = 14
const val VECTOR_SIZE = 15 

val RESPONSES = Array(6) { i ->
    val score = i / 5.0
    val json = "{\"fraud_score\":$score}"
    val http = "HTTP/1.1 200 OK\r\nContent-Length: ${json.length}\r\nContent-Type: application/json\r\n\r\n$json"
    ByteBuffer.wrap(http.toByteArray(Charsets.US_ASCII))
}

fun main() {
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
    val clusterBuffer = ByteArray(maxClusterSize * VECTOR_SIZE)
    val queryVector = ByteArray(DIMENSIONS)
    val ioBuffer = ByteBuffer.allocateDirect(4096)

    val socketPath = "/tmp/app.sock"
    val file = java.io.File(socketPath)
    if (file.exists()) file.delete()

    val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    serverChannel.bind(UnixDomainSocketAddress.of(socketPath))

    while (true) {
        val client: SocketChannel = serverChannel.accept()
        ioBuffer.clear()
        
        while (client.read(ioBuffer) > 0) {
            ioBuffer.flip()
            
            var bodyStart = -1
            for (i in 0 until ioBuffer.limit() - 3) {
                if (ioBuffer.get(i) == '\r'.code.toByte() &&
                    ioBuffer.get(i + 1) == '\n'.code.toByte() &&
                    ioBuffer.get(i + 2) == '\r'.code.toByte() &&
                    ioBuffer.get(i + 3) == '\n'.code.toByte()) {
                    bodyStart = i + 4
                    break
                }
            }

            if (bodyStart != -1) {
                parseVectorZeroAlloc(ioBuffer, bodyStart, queryVector)

                var bestCluster = 0
                var minCentroidDist = Int.MAX_VALUE
                
                for (c in 0 until numClusters) {
                    var dist = 0
                    val cOff = c * DIMENSIONS
                    for (d in 0 until DIMENSIONS) {
                        val diff = queryVector[d].toInt() - centroids[cOff + d].toInt()
                        dist += diff * diff
                    }
                    if (dist < minCentroidDist) {
                        minCentroidDist = dist
                        bestCluster = c
                    }
                }

                val size = clusterSizes[bestCluster]
                val offset = clusterOffsets[bestCluster]
                
                buffer.position(offset)
                buffer.get(clusterBuffer, 0, size * VECTOR_SIZE)

                var top1Dist = Int.MAX_VALUE; var top1Label = 0
                var top2Dist = Int.MAX_VALUE; var top2Label = 0
                var top3Dist = Int.MAX_VALUE; var top3Label = 0
                var top4Dist = Int.MAX_VALUE; var top4Label = 0
                var top5Dist = Int.MAX_VALUE; var top5Label = 0

                for (i in 0 until size) {
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

fun parseVectorZeroAlloc(buffer: ByteBuffer, start: Int, vector: ByteArray) {
    var dim = 0
    var i = start
    val limit = buffer.limit()
    var inVector = false
    var currentVal = 0f
    var isNegative = false
    var divisor = 1f
    var decimal = false

    while (i < limit && dim < DIMENSIONS) {
        val c = buffer.get(i).toInt().toChar()
        if (!inVector && c == '[') {
            inVector = true
            i++
            continue
        }
        if (inVector) {
            when (c) {
                '-' -> isNegative = true
                '.' -> decimal = true
                in '0'..'9' -> {
                    val digit = c - '0'
                    if (decimal) {
                        divisor *= 10f
                        currentVal += digit / divisor
                    } else {
                        currentVal = currentVal * 10f + digit
                    }
                }
                ',', ']' -> {
                    if (isNegative) currentVal = -currentVal
                    vector[dim] = if (currentVal < 0f) -128 else (currentVal * 127f).toInt().toByte()
                    dim++
                    currentVal = 0f
                    isNegative = false
                    divisor = 1f
                    decimal = false
                }
            }
        }
        i++
    }
}