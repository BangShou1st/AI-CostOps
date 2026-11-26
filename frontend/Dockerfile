FROM node:24.14.0-alpine AS build

WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

FROM nginx:1.30.7-alpine

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

# Use patched stable nginx 1.30.7 (fixes CVE-2026-113425 / CVE-2026-121810 /
# CVE-2026-123007 / CVE-2026-123633 / CVE-2026-118899 / CVE-2026-123634
# reported against 1.30.6-r1; fixed in 1.30.7-r0) and run non-root.
# nginx:alpine ships user `nginx` (uid 101 / gid 101); <1024 ports require
# non-root bind, so the server listens on 8080 (see nginx/default.conf).
# The pid and cache paths must be writable by that uid.
RUN apk upgrade --no-cache && \
    mkdir -p /var/cache/nginx /var/run && \
    chown -R nginx:nginx /var/cache/nginx /var/log/nginx /etc/nginx/conf.d /usr/share/nginx/html /var/run && \
    chmod -R 755 /var/cache/nginx /var/log/nginx && \
    touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid

USER nginx
EXPOSE 8080
