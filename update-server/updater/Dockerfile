# ---------------------------------------------------------------- Bauen
# Wie im core-Zweig zweistufig: das fertige Abbild traegt weder JDK noch
# Maven mit sich.
FROM maven:3.9-eclipse-temurin-21 AS bau
WORKDIR /bau

COPY pom.xml .
RUN mvn -q -B -e dependency:go-offline

COPY src ./src
# Bewusst MIT Tests, anders als im core-Zweig. Es ist eine kleine Suite,
# und sie prueft die Klasse, die ueber den Zugang entscheidet. Ein
# Abbild, das die Zugangskontrolle falsch rechnet, soll gar nicht erst
# entstehen - die paar Sekunden sind dafuer der guenstigste Preis.
RUN mvn -q -B package

# ---------------------------------------------------------------- Laufen
FROM eclipse-temurin:21-jre-noble
LABEL org.opencontainers.image.title="hoer.jetzt updater" \
      org.opencontainers.image.description="Torwaechter und Verwaltung des Update-Servers"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin updater
WORKDIR /app

COPY --from=bau /bau/target/updater.jar /app/updater.jar
RUN mkdir -p /daten && chown -R updater:updater /app /daten

USER updater

# 8080 Torwaechter (nur im internen Netz), 8081 Oberflaeche (privates Netz).
EXPOSE 8080 8081

# Bewusst gegen den Torwaechter-Port und einen Pfad, den es dort gibt: ein
# Health-Check gegen die Oberflaeche wuerde nur die Anmeldeseite pruefen und
# waere auch dann gruen, wenn die Datenbank nicht aufgeht.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsS -o /dev/null -w '%{http_code}' \
        http://127.0.0.1:8080/intern/pruefen | grep -qE '^(204|403)$' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/updater.jar"]
