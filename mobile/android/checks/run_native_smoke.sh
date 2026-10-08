#!/usr/bin/env bash
set -euo pipefail
# Host JNI contract/inference smoke only; this does not prove Android performance or Arabic quality.
model_path=${1:?Usage: GSON_JAR=/path/to/gson.jar JAVA_HOME=/path/to/jdk checks/run_native_smoke.sh /path/to/model.gguf}
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
: "${GSON_JAR:?Set GSON_JAR to the Gson 2.14.0 jar}"
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
output_dir="$project_dir/build/native-smoke"
cmake_args=()
if [[ -n "${SECRETARY_LLAMA_CPP_DIR:-}" ]]; then cmake_args+=("-DLLAMA_CPP_DIR=$SECRETARY_LLAMA_CPP_DIR"); fi
cmake -S "$project_dir/app/src/main/cpp" -B "$output_dir" -G Ninja -DCMAKE_BUILD_TYPE=Release "${cmake_args[@]}"
cmake --build "$output_dir" --target secretary_llama -j2
mkdir -p "$output_dir/classes"
"$JAVA_HOME/bin/javac" -cp "$GSON_JAR" -d "$output_dir/classes" "$project_dir/checks/native/com/alsekretary/app/localmodel/NativeBridge.java" "$project_dir/checks/native/HostSmoke.java"
python3 - "$project_dir" "$output_dir" <<'PY'
import sys,re,textwrap
from pathlib import Path
source=Path(sys.argv[1],'app/src/main/java/com/alsekretary/app/localmodel/ModelToolProtocol.kt').read_text()
Path(sys.argv[2],'action.gbnf').write_text(textwrap.dedent(re.search(r'val grammar="""\n(.*?)\n    """',source,re.S).group(1)))
PY
"$JAVA_HOME/bin/java" "-Djava.library.path=$output_dir" -cp "$output_dir/classes:$GSON_JAR" HostSmoke "$model_path" "$output_dir/action.gbnf"
