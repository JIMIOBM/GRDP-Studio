FROM node:20-alpine

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src

ENV FLOWBALANCE_HOST=0.0.0.0
ENV FLOWBALANCE_PORT=8891
EXPOSE 8891

CMD ["node", "src/server.js"]
