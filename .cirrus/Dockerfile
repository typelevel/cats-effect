FROM eclipse-temurin:17
RUN apt-get update && apt-get install -y clang
RUN wget -q https://github.com/sbt/sbt/releases/download/v1.9.0/sbt-1.9.0.tgz && tar xvfz sbt-1.9.0.tgz
ENV PATH="$PATH:/sbt/bin"
