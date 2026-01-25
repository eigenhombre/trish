#!/bin/bash
set -e

clojure -T:build uber

cat > trish << 'EOF'
#!/bin/bash
exec java -jar "$(dirname "$0")/trish.jar" "$@"
EOF

chmod +x trish
