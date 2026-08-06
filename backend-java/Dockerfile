FROM gradle:8.5-jdk17 AS build

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

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
