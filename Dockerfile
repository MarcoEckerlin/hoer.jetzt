# Lavalink kommt als fertiges Abbild vom Projekt selbst - selbst bauen brächte
# nichts ausser Pflegeaufwand. Hier kommen nur Konfiguration und Plugins dazu.
FROM ghcr.io/lavalink-devs/lavalink:4
LABEL org.opencontainers.image.title="hoer.jetzt lavalink" \
      org.opencontainers.image.description="Audio-Knoten mit Stufenkennzeichnung"

USER root
RUN apt-get update && apt-get install -y --no-install-recommends curl gettext-base \
    && rm -rf /var/lib/apt/lists/*
USER lavalink

COPY application.yml.template /opt/Lavalink/application.yml.template
COPY entrypoint.sh /opt/Lavalink/entrypoint.sh

# Stufe und Kapazitaet stehen nur zur Information im Abbild; verbindlich ist
# der Eintrag in der Datenbank, den der Bot liest.
ENV LAVALINK_TIER=free \
    LAVALINK_PORT=2333 \
    LAVALINK_ADDRESS=0.0.0.0 \
    YOUTUBE_PLUGIN_VERSION=1.18.2 \
    YOUTUBE_PLUGIN_SNAPSHOT=false

EXPOSE 2333

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS -H "Authorization: ${LAVALINK_SERVER_PASSWORD}" \
        http://127.0.0.1:${LAVALINK_PORT}/version >/dev/null || exit 1

ENTRYPOINT ["/bin/sh", "/opt/Lavalink/entrypoint.sh"]
