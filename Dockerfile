FROM --platform=linux/amd64 alpine:latest
WORKDIR /app

COPY build/native/nativeCompile/server /app/server
COPY resources/ /app/resources/

CMD ["/app/server"]