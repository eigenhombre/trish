SOURCES := $(shell find src -name '*.clj')
RESOURCES := $(wildcard resources/*.edn)

trish.jar: $(SOURCES) $(RESOURCES) deps.edn build.clj
	./build.sh

.PHONY: uberjar
uberjar: trish.jar

.PHONY: install
install: trish.jar
	@if [ ! -d "$${BINDIR:-$$HOME/bin}" ]; then \
		echo "Error: Install directory $${BINDIR:-$$HOME/bin} does not exist."; \
		echo "Please create it or set BINDIR to an existing directory."; \
		exit 1; \
	fi
	cp trish trish.jar $${BINDIR:-$$HOME/bin}/

.PHONY: run
run:
	clojure -M:run

.PHONY: clean
clean:
	rm -rf target/ trish trish.jar .cpcache/
