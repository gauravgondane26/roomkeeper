# syntax=docker/dockerfile:1

# Build stage: compile and package with the Maven wrapper. Tests run in CI, not here,
# because the integration tests need Docker (Testcontainers).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Dependencies first, so this layer is reused until pom.xml changes
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
# Extracted as a plain jar with its dependencies in lib/ (no launcher): the AOT cache below
# needs a classpath of jar files
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package -DskipTests \
    && java -Djarmode=tools -jar target/roomkeeper-*.jar extract --layers --destination extracted \
    && mv extracted/application/roomkeeper-*.jar extracted/application/roomkeeper.jar

# Runtime stage: JRE only, non-root, one layer per Spring Boot jar layer
# (dependencies change rarely, application code changes often)
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

ENV SPRING_PROFILES_ACTIVE=prod

# AOT cache (JEP 483/514/515): a training run creates the context and exits before anything
# starts, and the JVM keeps the classes it loaded and linked, and their profiles, in app.aot.
# Starting from it cuts the time to load and link those classes again, which on a free
# instance's 0.1 CPU is most of the startup. It's made here, by this JRE from these jars,
# because a cache only works with the JVM and classpath that made it.
# The training run has no database or Redis: Flyway is off, and Hibernate neither validates
# the schema nor needs the database's metadata (it tries once to connect, logs a refused
# connection, and carries on). Redis is connected to lazily, so not at all. Its secrets are
# throwaway values, generated here and kept nowhere.
# A JVM that can't load the cache's archived objects doesn't start at all, so two heap
# settings matter. The training run uses a small heap: a cache made with a large one (the
# default here is a quarter of the build machine's memory) can't be loaded into less. And
# the app starts with at least 64 MB of heap (-Xms in ENTRYPOINT, which JAVA_TOOL_OPTIONS
# can't undo): the serial collector copies those objects into the heap, and the default
# initial heap in 512 MB is 8 MB, too small for them. The last step loads the cache with the
# free instance's heap (render.yaml's JAVA_TOOL_OPTIONS give 282 MB at most) and the
# ENTRYPOINT's, so a cache that won't load fails the build instead of the deploy.
RUN SPRING_FLYWAY_ENABLED=false \
    SPRING_JPA_HIBERNATE_DDL_AUTO=none \
    SPRING_JPA_PROPERTIES_HIBERNATE_BOOT_ALLOW_JDBC_METADATA_ACCESS=false \
    SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT=org.hibernate.dialect.PostgreSQLDialect \
    DATABASE_URL=jdbc:postgresql://localhost:5432/training \
    DATABASE_USERNAME=training \
    DATABASE_PASSWORD=training \
    SPRING_DATA_REDIS_URL="redis://:$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')@localhost:6379" \
    JWT_SECRET="$(head -c 32 /dev/urandom | base64)" \
    java -XX:AOTCacheOutput=app.aot -Xmx256m -XX:+UseSerialGC -Xlog:aot=error \
        -Dspring.context.exit=onRefresh -jar roomkeeper.jar \
    && chmod a+r app.aot \
    && java -XX:AOTCache=app.aot -XX:AOTMode=on -Xmx282m -Xms64m -XX:+UseSerialGC -cp roomkeeper.jar -version

USER app
# 8080 is the API; 8081 is Actuator (health, info, prometheus), for the private network only
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -qO- http://localhost:8080/readyz | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-Xms64m", "-jar", "roomkeeper.jar"]
