FROM clojure:temurin-17-tools-deps-noble
RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*

COPY deps.edn build.clj Makefile ./

RUN make deps

COPY src ./src

RUN make uber
