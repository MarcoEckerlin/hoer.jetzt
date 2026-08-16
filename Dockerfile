# ---------------------------------------------------------------- Bauen
# Getrennte Stufe: das fertige Abbild traegt kein JDK und kein Maven mit sich,
# das spart rund 400 MB und nimmt Angriffsflaeche weg.
FROM maven:3.9-eclipse-temurin-21 AS bau
WORKDIR /bau

# Erst nur die Abhaengigkeiten aufloesen. Solange sich pom.xml nicht aendert,
# kommt diese Schicht aus dem Zwischenspeicher und der Build dauert Sekunden.
COPY pom.xml .
RUN mvn -q -B -e dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# ---------------------------------------------------------------- Laufen
FROM eclipse-temurin:21-jre-noble
LABEL org.opencontainers.image.title="hoer.jetzt core" \
      org.opencontainers.image.description="Discord-Bot und Weboberflaeche"

# Das JRE-Abbild bringt kein curl mit - ohne das schlaegt der Health-Check
# unten immer fehl und der Container gilt dauerhaft als krank.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin bot
WORKDIR /app

COPY --from=bau /bau/target/DiscordBot-alpha-1.0.jar /app/app.jar
RUN mkdir -p /app/config && chown -R bot:bot /app

USER bot
EXPOSE 8080

# Der Bot legt sein Schema selbst an und braucht die Datenbank beim Start.
# Der Health-Check meldet erst gruen, wenn die Weboberflaeche wirklich antwortet.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/ >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
