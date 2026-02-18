FROM node:20-alpine
WORKDIR /app
COPY backend/package*.json ./
RUN npm ci --production
COPY backend/ ./
COPY dashboard/ ./dashboard/
EXPOSE 3000
ENV NODE_ENV=production
CMD ["node", "server.js"]
