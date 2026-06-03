FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /app
COPY . .
RUN microdnf install -y findutils
RUN chmod +x gradlew
RUN ./gradlew buildIndex --no-daemon
RUN ./gradlew nativeCompile --no-daemon

FROM oraclelinux:9-slim
WORKDIR /app
COPY --from=builder /app/build/native/nativeCompile/server /app/server
COPY --from=builder /app/resources/ /app/resources/
ENV SOCKET_PATH=/tmp/app.sock
CMD ["/app/server"]