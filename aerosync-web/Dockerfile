# AeroSync web dashboard: Vite build served by nginx, which also proxies /api
# to the Spring Boot backend (BACKEND_UPSTREAM), so the browser stays same-origin.

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
# Reproducible install once a lockfile is committed; plain install until then.
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# The nginx image renders /etc/nginx/templates/*.template with envsubst at start-up
# (only variables that are set are substituted, so nginx's own $uri etc. stay intact).
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV BACKEND_UPSTREAM=http://backend:8080
EXPOSE 80
