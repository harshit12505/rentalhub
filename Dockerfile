# RentalHub as one container image. Two stages: the first has the JDK and Maven and builds the
# app; the second has only a Java runtime and the built app, so the image that ships holds no
# compiler, no build tools and no source code.
#
#   docker build -t rentalhub .
#   docker run --rm -p 8080:8080 -e DB_HOST=... rentalhub
#
# (Render builds it from render.yaml; see the README.)

# ---------------------------------------------------------------- 1. build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# The Maven wrapper and the pom first, then the dependencies on their own: Docker keeps this
# layer until pom.xml changes, so a code-only change doesn't download the internet again.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw --batch-mode --quiet dependency:go-offline

COPY src src
# The tests need Docker themselves (Testcontainers), which a build container doesn't have. They
# run on your machine before a push; the image build only packages.
# The extracted jar keeps the name it had, so it is renamed first: the run stage starts
# application.jar whatever the project's version is.
RUN ./mvnw --batch-mode --quiet package -DskipTests \
 && mv target/rentalhub-*.jar target/application.jar \
 && java -Djarmode=tools -jar target/application.jar extract --layers --destination target/extracted

# ---------------------------------------------------------------- 2. run
FROM eclipse-temurin:21-jre-alpine

# Not root: if the app were ever broken into, the intruder would get an account that owns
# nothing. The app's files stay owned by root, so it can't rewrite its own code either.
RUN addgroup -S rentalhub && adduser -S -G rentalhub -H -s /sbin/nologin rentalhub
WORKDIR /app

# The jar, taken apart into layers that change at different speeds. Each COPY is an image layer,
# and a new release usually changes only the last one (our own classes, a few hundred KB): the
# ~150 MB of libraries above it are already on the server.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER rentalhub

# Sized for a small container (Render's free instance: 512 MB, 0.1 CPU). The JVM reads the
# container's memory limit itself; these say how to share it out.
#  - MaxRAMPercentage=60: the heap gets 60% of the container. The rest is for what isn't heap
#    (loaded classes, compiled code, threads, network buffers); give the heap more and the
#    container is killed for exceeding its memory instead of the JVM collecting garbage.
#  - UseSerialGC: one collector thread, the least memory. Right for a fraction of a CPU.
#  - TieredStopAtLevel=1: only the quick compiler. Starts much faster on a small CPU, uses less
#    memory for compiled code; long-run speed is a little lower, which a demo never notices.
#  - ExitOnOutOfMemoryError: out of memory, stop, and let the platform start a fresh copy,
#    rather than limp on half-broken.
# Override any of it with -e JAVA_OPTS=... (docker) or an environment variable (Render).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

# The app listens on $PORT (Render sets it), 8080 when unset.
EXPOSE 8080

# exec: java replaces the shell, so it receives the platform's stop signal and shuts down cleanly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]
