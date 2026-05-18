FROM gradle:8.7-jdk21-alpine AS indexer
WORKDIR /app
COPY . .
RUN kotlinc src/main/rinha/BuildIndex.kt -include-runtime -d builder.jar && java -jar builder.jar

FROM ghcr.io/graalvm/native-image-community:21-muslib AS native-builder
WORKDIR /app
COPY --from=indexer /app .
RUN ./gradlew nativeCompile --no-daemon

FROM alpine:latest
WORKDIR /app
COPY --from=native-builder /app/build/native/nativeCompile/server /app/server
COPY --from=indexer /app/resources/index.bin /app/resources/index.bin
COPY --from=indexer /app/resources/mcc_risk.json /app/resources/mcc_risk.json
CMD ["/app/server"]