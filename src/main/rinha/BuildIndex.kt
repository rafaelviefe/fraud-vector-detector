package rinha

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.random.Random

const val DIMENSIONS = 14
const val NUM_CLUSTERS = 1024
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
        var currentKey = ""
        var tempFraud = false
        val tempVector = ByteArray(DIMENSIONS)
        var hasVector = false
        
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
                    val sb = StringBuilder()
                    c = reader.read()
                    while (c != -1 && c.toChar() != '"') {
                        sb.append(c.toChar())
                        c = reader.read()
                    }
                    currentKey = sb.toString()
                    insideString = false
                }
            } else if ((currentKey == "fraud" || currentKey == "is_fraud" || currentKey == "isFraud") && char == ':') {
                val sb = StringBuilder()
                c = reader.read()
                while (c != -1 && c.toChar() != ',' && c.toChar() != '}') {
                    if (!c.toChar().isWhitespace()) sb.append(c.toChar())
                    c = reader.read()
                }
                val boolStr = sb.toString()
                tempFraud = (boolStr == "true")
                
                if (c.toChar() == '}') {
                    if (hasVector) {
                        System.arraycopy(tempVector, 0, vectors, count * DIMENSIONS, DIMENSIONS)
                        labels[count] = if (tempFraud) 1 else 0
                        count++
                        hasVector = false
                    }
                }
                continue 
            } else if (currentKey == "vector" && char == '[') {
                var dim = 0
                val sb = StringBuilder()
                c = reader.read()
                while (c != -1 && c.toChar() != ']') {
                    val ch = c.toChar()
                    if (ch == ',' || ch.isWhitespace()) {
                        if (sb.isNotEmpty()) {
                            val f = sb.toString().toFloat()
                            tempVector[dim] = if (f < 0f) -128 else (f * 127f).toInt().toByte()
                            dim++
                            sb.clear()
                        }
                    } else {
                        sb.append(ch)
                    }
                    c = reader.read()
                }
                if (sb.isNotEmpty() && dim < DIMENSIONS) {
                    val f = sb.toString().toFloat()
                    tempVector[dim] = if (f < 0f) -128 else (f * 127f).toInt().toByte()
                }
                hasVector = true
                currentKey = ""
            }
            c = reader.read()
        }
    }

    val centroids = ByteArray(NUM_CLUSTERS * DIMENSIONS)
    val clusterAssignments = IntArray(count)
    val clusterSizes = IntArray(NUM_CLUSTERS)
    
    for (i in 0 until NUM_CLUSTERS) {
        val randIdx = Random.nextInt(count)
        System.arraycopy(vectors, randIdx * DIMENSIONS, centroids, i * DIMENSIONS, DIMENSIONS)
    }
    
    for (iteration in 0 until 15) {
        clusterSizes.fill(0)
        for (i in 0 until count) {
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
            clusterSizes[bestCluster]++
        }
        
        val newCentroidsSum = IntArray(NUM_CLUSTERS * DIMENSIONS)
        for (i in 0 until count) {
            val c = clusterAssignments[i]
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