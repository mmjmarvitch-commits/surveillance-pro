FROM node:20-alpine

RUN apk add --no-cache python3 make g++ build-base

WORKDIR /app

COPY backend/package*.json ./
RUN npm ci --production

COPY backend/ ./
COPY dashboard/ ./dashboard/

EXPOSE 3000
ENV NODE_ENV=production

CMD ["node", "server.js"]
