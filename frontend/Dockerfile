# Stage 1: build the React app into static files.
FROM node:20-alpine AS build
WORKDIR /app
# Copy the lock file first, so the npm ci layer is reused until dependencies change.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY public public
COPY src src
RUN npm run build

# Stage 2: serve the static files with nginx (no Node.js at run time).
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/build /usr/share/nginx/html
