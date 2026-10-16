# Node 22: el 18 ja no rep pegats de seguretat des de l'abril del 2025.
FROM node:22-slim

WORKDIR /app

# Només el que el servidor necessita (express). Tailwind i la resta només
# serveixen per generar public/css i public/vendor, que ja venen fets al
# repositori. "ci" instal·la exactament el que diu package-lock.json.
COPY package*.json ./
RUN npm ci --omit=dev

COPY . .

# Sense root: si algú aconseguís executar res a través del servidor, no
# tindria permisos sobre el contenidor.
USER node

EXPOSE 3000

# node directament i no "npm start": així el contenidor rep el senyal
# d'aturada i es para a l'acte en lloc d'esperar que Docker el mati.
CMD ["node", "index.js"]
