# ETAPA 1: build Angular 17 (salida en dist/qwerys-frontend/browser)
FROM node:20-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci --legacy-peer-deps

COPY . .
RUN npm run build -- --configuration=production

# ETAPA 2: Nginx con SPA + proxy /api
FROM nginx:alpine
COPY --from=build /app/dist/qwerys-frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
