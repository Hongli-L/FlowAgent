FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/maven:3.9.9-eclipse-temurin-21-noble AS build

LABEL maintainer="flowagent"
LABEL version="1.0.0"

WORKDIR /app

# Set timezone to Shanghai
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# Copy built JAR file
COPY target/flowagent-engine.jar /app/flowagent-engine.jar

# Create log directory
RUN mkdir -p /app/logs

# Expose port
EXPOSE 7881

# Health check
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:7881/actuator/health || exit 1

# Start application
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Duser.timezone=Asia/Shanghai", \
    "-jar", \
    "/app/flowagent-engine.jar"]
