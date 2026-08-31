FROM node:24.14.0-alpine AS build

WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.28-alpine

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
# Non-root runtime (uid 101 = nginx); binds 8080 because <1024 ports require
# root. The entrypoint config hooks need write access to conf.d and the runtime
# dirs, so ownership is handed to uid 101 before dropping privileges.
USER 0
# The pinned base image ships Alpine packages with known HIGH/CRITICAL fixes
# (nginx 1.28.2-r1 -> 1.28.3-r1, openssl 3.5.5-r0 -> 3.5.6-r0, musl, curl,
# zlib, libpng, libxml2, libexpat, c-ares). Upgrade the whole distribution so
# the shipped image has no HIGH/CRITICAL findings; apk must run before
# dropping privileges.
RUN apk upgrade --no-cache
RUN chown -R 101:101 /etc/nginx/conf.d /var/cache/nginx /var/log/nginx
USER 101
EXPOSE 8080
