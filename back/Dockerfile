# 1. Build Stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /builder

COPY gradle gradle
COPY gradlew .
RUN chmod +x gradlew

COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon

# 2. Layer Extraction Stage
FROM bellsoft/liberica-openjre-debian:21-cds AS extractor

WORKDIR /extractor

COPY --from=builder /builder/build/libs/application.jar .

RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# 3. Runtime Stage
FROM bellsoft/liberica-openjre-debian:21-cds

WORKDIR /application

COPY --from=extractor /extractor/extracted/dependencies/ .
COPY --from=extractor /extractor/extracted/spring-boot-loader/ .
COPY --from=extractor /extractor/extracted/snapshot-dependencies/ .
COPY --from=extractor /extractor/extracted/application/ .

ENTRYPOINT [ \
    "java", \
    "-jar", "application.jar" \
]