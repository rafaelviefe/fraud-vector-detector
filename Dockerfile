FROM --platform=linux/amd64 gradle:8.7-jdk21 AS indexer
WORKDIR /app
COPY . .

RUN gradle wrapper

RUN gradle buildIndex --no-daemon --no-watch-fs

FROM --platform=linux/amd64 ghcr.io/graalvm/native-image-community:21-muslib AS native-builder
WORKDIR /app
COPY --from=indexer /app .
RUN chmod +x gradlew && ./gradlew nativeCompile --no-daemon

FROM --platform=linux/amd64 alpine:latest
WORKDIR /app
COPY --from=native-builder /app/build/native/nativeCompile/server /app/server
COPY --from=indexer /app/resources/index.bin /app/resources/index.bin
COPY --from=indexer /app/resources/mcc_risk.json /app/resources/mcc_risk.json
CMD ["/app/server"]