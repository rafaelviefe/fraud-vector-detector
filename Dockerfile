FROM --platform=linux/amd64 ghcr.io/graalvm/native-image-community:21-muslib AS builder
WORKDIR /app
COPY . .
RUN microdnf install -y findutils
RUN chmod +x gradlew
RUN ./gradlew nativeCompile --no-daemon
FROM --platform=linux/amd64 alpine:latest
WORKDIR /app
COPY --from=builder /app/build/native/nativeCompile/server /app/server
COPY resources/index.bin /app/resources/index.bin
COPY resources/mcc_risk.json /app/resources/mcc_risk.json
CMD ["/app/server"]