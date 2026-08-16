# Zweistufig: bauen mit Node, ausliefern mit nginx.
#
# Das fertige Ergebnis sind ein paar hundert Kilobyte statische Dateien - dafuer
# eine Node-Laufzeit im Betrieb mitzuschleppen waere 200 MB fuer nichts.
FROM node:22-alpine AS bau
WORKDIR /bau

# Erst die Abhaengigkeiten, dann der Quelltext: so bleibt die Zwischenschicht
# mit npm ci erhalten, solange sich package.json nicht aendert. Ohne diese
# Reihenfolge laedt jeder Build alle Pakete neu.
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund 2>/dev/null || npm install --no-audit --no-fund

COPY . .
RUN npm run build

FROM nginx:1.27-alpine
LABEL org.opencontainers.image.title="hoer.jetzt web" \
      org.opencontainers.image.description="Weboberflaeche, getrennt von core"

COPY --from=bau /bau/dist /usr/share/nginx/html

# nginx setzt in seiner Konfiguration keine Umgebungsvariablen ein. Das
# offizielle Abbild bringt dafuer aber einen Schritt beim Start mit: was unter
# /etc/nginx/templates liegt, laeuft durch envsubst und landet in conf.d.
# Ohne diesen Umweg muesste die Adresse von core fest im Abbild stehen.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

ENV HJ_CORE_HOST=core \
    HJ_CORE_PORT=8080 \
    NGINX_ENVSUBST_OUTPUT_DIR=/etc/nginx/conf.d

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=4s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
