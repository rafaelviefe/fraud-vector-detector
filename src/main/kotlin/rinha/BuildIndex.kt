package rinha

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.stream.IntStream
import kotlin.math.roundToInt
import kotlin.random.Random

const val DIMENSIONS = 14
const val NUM_CLUSTERS = 4096
const val MAX_VECTORS = 3_000_000
const val VECTOR_SIZE_BYTES = 15

fun main() {
    val inputFile = File("resources/references.json.gz")
    val outputFile = File("resources/index.bin")

    val vectors = ByteArray(MAX_VECTORS * DIMENSIONS)
    val labels = ByteArray(MAX_VECTORS)

    var count = 0

    GZIPInputStream(FileInputStream(inputFile)).bufferedReader().use { reader ->
        var insideString = false
        var tempFraud = false
        val tempVector = ByteArray(DIMENSIONS)
        var hasVector = false
        
        val keyBuf = CharArray(64)
        var keyLen = 0
        
        var c = reader.read()
        while (c != -1 && count < MAX_VECTORS) {
            val char = c.toChar()
            if (char == '{') {
                hasVector = false
                tempFraud = false
            } else if (char == '}') {
                if (hasVector) {
                    System.arraycopy(tempVector, 0, vectors, count * DIMENSIONS, DIMENSIONS)
                    labels[count] = if (tempFraud) 1 else 0
                    count++
                    hasVector = false
                }
            } else if (char == '"') {
                insideString = !insideString
                if (insideString) {
                    keyLen = 0
                    c = reader.read()
                    while (c != -1 && c.toChar() != '"') {
                        if (keyLen < 64) keyBuf[keyLen++] = c.toChar()
                        c = reader.read()
                    }
                    insideString = false
                    
                    if (keyLen == 5 && keyBuf[0] == 'l' && keyBuf[1] == 'a' && keyBuf[2] == 'b' && keyBuf[3] == 'e' && keyBuf[4] == 'l') {
                        c = reader.read()
                        while (c != -1 && c.toChar() != ':') c = reader.read()
                        c = reader.read()
                        while (c != -1 && (c.toChar().isWhitespace() || c.toChar() == '"')) c = reader.read()
                        
                        tempFraud = (c.toChar() == 'f')
                        
                        while (c != -1 && c.toChar() != ',' && c.toChar() != '}') {
                            c = reader.read()
                        }
                        if (c.toChar() == '}') {
                            if (hasVector) {
                                System.arraycopy(tempVector, 0, vectors, count * DIMENSIONS, DIMENSIONS)
                                labels[count] = if (tempFraud) 1 else 0
                                count++
                                hasVector = false
                            }
                        }
                        continue 
                    } else if (keyLen == 6 && keyBuf[0] == 'v' && keyBuf[1] == 'e' && keyBuf[2] == 'c' && keyBuf[3] == 't' && keyBuf[4] == 'o' && keyBuf[5] == 'r') {
                        c = reader.read()
                        while (c != -1 && c.toChar() != '[') c = reader.read()
                        
                        var dim = 0
                        var isNeg = false
                        var intPart = 0
                        var fracPart = 0f
                        var divisor = 10f
                        var inNumber = false
                        var inFrac = false

                        c = reader.read()
                        while (c != -1 && c.toChar() != ']') {
                            val ch = c.toChar()
                            if (ch == ',' || ch.isWhitespace()) {
                                if (inNumber) {
                                    var f = intPart.toFloat() + fracPart
                                    if (isNeg) f = -f
                                    tempVector[dim] = if (f < 0f) -128 else (f * 127f).roundToInt().toByte()
                                    dim++
                                    isNeg = false; intPart = 0; fracPart = 0f; divisor = 10f; inNumber = false; inFrac = false
                                }
                            } else if (ch == '-') {
                                isNeg = true
                                inNumber = true
                            } else if (ch == '.') {
                                inFrac = true
                                inNumber = true
                            } else if (ch in '0'..'9') {
                                inNumber = true
                                val digit = ch - '0'
                                if (inFrac) {
                                    fracPart += digit / divisor
                                    divisor *= 10f
                                } else {
                                    intPart = intPart * 10 + digit
                                }
                            }
                            c = reader.read()
                        }
                        if (inNumber && dim < DIMENSIONS) {
                            var f = intPart.toFloat() + fracPart
                            if (isNeg) f = -f
                            tempVector[dim] = if (f < 0f) -128 else (f * 127f).roundToInt().toByte()
                        }
                        hasVector = true
                    }
                }
            }
            c = reader.read()
        }
    }

    val centroids = ByteArray(NUM_CLUSTERS * DIMENSIONS)
    val clusterAssignments = IntArray(count)
    val clusterSizes = IntArray(NUM_CLUSTERS)
    
    for (i in 0 until NUM_CLUSTERS) {
        val randIdx = Random(42).nextInt(count)
        System.arraycopy(vectors, randIdx * DIMENSIONS, centroids, i * DIMENSIONS, DIMENSIONS)
    }
    
    for (iteration in 0 until 20) {
        IntStream.range(0, count).parallel().forEach { i ->
            var bestCluster = 0
            var minDist = Int.MAX_VALUE
            val vOffset = i * DIMENSIONS
            
            for (c in 0 until NUM_CLUSTERS) {
                val cOffset = c * DIMENSIONS
                var dist = 0
                for (d in 0 until DIMENSIONS) {
                    val diff = vectors[vOffset + d].toInt() - centroids[cOffset + d].toInt()
                    dist += diff * diff
                }
                if (dist < minDist) {
                    minDist = dist
                    bestCluster = c
                }
            }
            clusterAssignments[i] = bestCluster
        }
        
        clusterSizes.fill(0)
        val newCentroidsSum = IntArray(NUM_CLUSTERS * DIMENSIONS)
        for (i in 0 until count) {
            val c = clusterAssignments[i]
            clusterSizes[c]++
            val vOffset = i * DIMENSIONS
            val cOffset = c * DIMENSIONS
            for (d in 0 until DIMENSIONS) {
                newCentroidsSum[cOffset + d] += vectors[vOffset + d].toInt()
            }
        }
        
        for (c in 0 until NUM_CLUSTERS) {
            val size = clusterSizes[c]
            if (size > 0) {
                val cOffset = c * DIMENSIONS
                for (d in 0 until DIMENSIONS) {
                    centroids[cOffset + d] = (newCentroidsSum[cOffset + d] / size).toByte()
                }
            }
        }
    }
    
    val buffer = ByteBuffer.allocate(4 + NUM_CLUSTERS * (DIMENSIONS + 4) + count * VECTOR_SIZE_BYTES)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    
    buffer.putInt(NUM_CLUSTERS)
    
    for (c in 0 until NUM_CLUSTERS) {
        buffer.put(centroids, c * DIMENSIONS, DIMENSIONS)
        buffer.putInt(clusterSizes[c])
    }
    
    for (c in 0 until NUM_CLUSTERS) {
        for (i in 0 until count) {
            if (clusterAssignments[i] == c) {
                buffer.put(vectors, i * DIMENSIONS, DIMENSIONS)
                buffer.put(labels[i])
            }
        }
    }
    
    BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
        out.write(buffer.array(), 0, buffer.position())
    }
}