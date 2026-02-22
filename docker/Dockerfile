FROM python:3.12-slim

# Evita buffering y mejora logs
ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# --- Config del daemon k2d ---
# Según documentación:
# K2D_HTTP_HOST default 127.0.0.1 (hay que cambiarlo para Docker)
# K2D_HTTP_PORT default 8311
# K2D_SOCKET_PORT default 3311
ENV K2D_HTTP_HOST=0.0.0.0 \
    K2D_HTTP_PORT=8311 \
    K2D_SOCKET_PORT=3311

# (Opcional) rutas de reglas para evitar warnings y permitir updates/YARA
ENV SYSTEM_RULES_BASE=/var/lib/kicomav/rules \
    USER_RULES_BASE=/var/lib/kicomav/user-rules

# Paquetes mínimos del sistema
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Usuario no-root (mejor práctica)
RUN useradd -m -u 10001 -s /bin/bash kicomav

# Directorios persistibles para reglas/firma
RUN mkdir -p /var/lib/kicomav/rules /var/lib/kicomav/user-rules \
    && chown -R kicomav:kicomav /var/lib/kicomav

# ✅ FIX: directorio para el Unix socket (/var/run/kicomav/k2d.sock)
# k2d intenta crear /var/run/kicomav; si no existe o no tiene permisos -> PermissionError
RUN mkdir -p /var/run/kicomav \
    && chown -R kicomav:kicomav /var/run/kicomav

# Instala kicomav con extras del daemon (fastapi/uvicorn/python-multipart)
RUN pip install "kicomav[daemon]"

USER kicomav
WORKDIR /home/kicomav

# Crea el .env donde KicomAV lo espera por defecto: ~/.kicomav/.env
RUN mkdir -p /home/kicomav/.kicomav \
    && touch /home/kicomav/.kicomav/.env

# Expone puertos REST y socket
EXPOSE 8311 3311

# Healthcheck simple (usa python estándar)
HEALTHCHECK --interval=15s --timeout=3s --retries=10 CMD python -c \
"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8311/ping').read(); print('ok')" || exit 1

# Arranque correcto: k2d lee K2D_HTTP_HOST/K2D_HTTP_PORT del entorno
CMD ["k2d"]