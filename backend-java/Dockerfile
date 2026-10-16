# La mateixa versió de Gradle que el wrapper (gradle/wrapper): abans la
# imatge en feia servir una altra, la 8.5.
FROM gradle:8.10.2-jdk17 AS build

WORKDIR /app

# Copy gradle files
COPY build.gradle settings.gradle ./

# Copy source
COPY src src

# Build. "assemble" empaqueta el jar sense executar cap verificació.
# Abans hi havia "build -x test", però build depèn de check, i check depèn
# dels tests d'integració: la imatge intentava aixecar un contenidor de
# PostgreSQL des de dins del build de Docker i fallava.
# Els tests s'executen a la integració contínua, no en construir la imatge.
RUN gradle assemble --no-daemon

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Sense root: si algú aconseguís executar res a través del backend, no
# tindria permisos sobre el contenidor.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin budget

# Un sol jar: build.gradle ja no genera el "-plain" (vegeu-hi el perquè).
COPY --from=build /app/build/libs/*.jar app.jar

USER budget

# El port on escolta (server.port a application.properties). Abans deia 8080.
EXPOSE 8000

ENTRYPOINT ["java", "-jar", "app.jar"]
