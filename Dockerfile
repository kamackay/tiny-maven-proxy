FROM maven:3.6.2-jdk-12 as builder

WORKDIR /build
WORKDIR /app

COPY ./ ./

RUN mvn install && \
  cp tiny-maven-proxy/target/tiny-maven-proxy.jar /build/app.jar

FROM alpine:latest

RUN apk --update --no-cache upgrade && apk add \
    openjdk11-jdk

WORKDIR /app/

COPY --from=builder /build/app.jar ./app.jar

ADD tiny-maven-proxy.properties ./

CMD ["java", "-jar", "-Xmx100m", "-Xss20m", "app.jar"]

