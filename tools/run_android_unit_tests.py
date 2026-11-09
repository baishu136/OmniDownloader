"""Run JUnit using a Unicode Windows command line (Gradle worker argfile workaround)."""
from pathlib import Path
import os
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
subprocess.run([str(root / 'gradlew.bat'), '-I', str(root / 'tools/test-classpath.init.gradle'), ':app:writeOmniTestClasspath', '--console=plain'], cwd=root, check=True)
classpath = (root / 'app/build/junit-classpath.txt').read_text(encoding='utf-8')
classpath = str(root / 'app/build/tmp/kotlin-classes/debug') + os.pathsep + classpath
classes_dir = root / 'app/build/tmp/kotlin-classes/debugUnitTest'
classes = [str(p.relative_to(classes_dir)).replace('\\', '.').removesuffix('.class') for p in classes_dir.rglob('*Test.class') if '$' not in p.name]
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe') if os.environ.get('JAVA_HOME') else shutil.which('java')
if not java: raise SystemExit('Set JAVA_HOME to a JDK 17+ installation')
raise SystemExit(subprocess.run([java, '-Dfile.encoding=UTF-8', '-cp', classpath, 'org.junit.runner.JUnitCore', *classes], cwd=root).returncode)
