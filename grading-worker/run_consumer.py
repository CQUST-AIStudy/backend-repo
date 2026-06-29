"""Launcher script for queue consumer with correct working directory."""
import os
import sys

os.chdir(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from queue_consumer import main

if __name__ == "__main__":
    main()
