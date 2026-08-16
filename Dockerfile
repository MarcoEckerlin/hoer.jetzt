FROM maven:3.9-eclipse-temurin-21 AS bau
WORKDIR /bau
COPY pom.xml .
RUN mvn -q -B -e dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-noble
LABEL org.opencontainers.image.title="hoer.jetzt ai-radio" \
      org.opencontainers.image.description="Music-Brain: Titelvorschlaege fuer das AI-Radio"

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin brain
WORKDIR /app

COPY --from=bau /bau/target/discordbot-music-brain.jar /app/app.jar
RUN mkdir -p /app/config && chown -R brain:brain /app

USER brain
EXPOSE 8091

# Bewusst klein gehalten: der Dienst haelt nur Vorschlaege vor, nichts Grosses.
ENTRYPOINT ["java", "-Xms128m", "-Xmx384m", "-jar", "/app/app.jar"]
