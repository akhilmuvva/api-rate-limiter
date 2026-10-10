# Multi-stage Dockerfile for Ktor Rate Limiting Gateway
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# Copy gradle wrapper and build files
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY gateway_server ./gateway_server

# Remove Android :app module from settings.gradle.kts so build doesn't require Android SDK
RUN sed -i '/:app/d' settings.gradle.kts

# Fix line endings and ensure execution permission
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Build application distribution
RUN ./gradlew :gateway_server:installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy AS runner
WORKDIR /app

# Default environment configuration
ENV PORT=8000
ENV JWT_SECRET=nt14-gateway-secure-production-jwt-secret-key-2026
ENV ADMIN_EMAILS=akpolylance@gmail.com
ENV TRUSTED_PROXIES=127.0.0.1,::1,localhost
ENV GOOGLE_CLIENT_ID=""
ENV POLYLANCE_BASE_URL=https://polylance-fv-1-45wy.onrender.com
ENV DEMO_TRAFFIC=true

COPY --from=build /app/gateway_server/build/install/gateway_server ./

EXPOSE 8000

CMD ["sh", "-c", "bin/gateway_server"]
