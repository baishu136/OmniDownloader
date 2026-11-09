import os
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ['OMNI_STATE_DIR'] = tempfile.mkdtemp(prefix='omni-test-state-')
os.environ['OMNI_AUTOMATIC_BROWSER'] = 'off'  # Individual capture fixtures explicitly enable it.
