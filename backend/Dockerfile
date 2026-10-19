# One image recipe for all four Java services; SERVICE picks which one.
# Build context is backend/ because every service is a subproject of the same
# Gradle build (delivery depends on :contracts).
#   docker build --build-arg SERVICE=bankService -t shop-bank backend
# No secrets here: passwords arrive as environment variables at run time.

# ---- Stage 1: build the jar with the full JDK + Gradle ----
FROM eclipse-temurin:17-jdk AS build
ARG SERVICE
WORKDIR /src
COPY . .
# A Windows checkout can give gradlew CRLF line endings, which Linux cannot run.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
# Tests need a database, so they run outside the image build (gradlew test).
# The cache mount keeps downloaded dependencies between builds without putting them in a layer.
# sharing=locked: compose builds the four services in parallel, and Gradle's own cache
# locking does not work across containers, so let BuildKit hand the cache to one at a time.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon -q ":${SERVICE}:bootJar" -x test \
 && find "${SERVICE}/build/libs" -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

# ---- Stage 2: run it on the JRE only (no JDK, Gradle or source code) ----
FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
