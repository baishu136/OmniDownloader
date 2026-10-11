from setuptools import find_namespace_packages, setup

setup(
    name="cli-anything-omnidownloader",
    version="1.0.0",
    description="Agent CLI for the OmniDownloader Windows/Web backend",
    packages=find_namespace_packages(include=["cli_anything.*"], exclude=["*.tests"]),
    package_data={"cli_anything.omnidownloader": ["skills/*.md", "README.md"]},
    python_requires=">=3.10",
    install_requires=["click>=8.1", "requests>=2.31", "prompt-toolkit>=3.0"],
    extras_require={"test": ["pytest>=8", "fastapi>=0.110", "uvicorn>=0.28", "yt-dlp", "imageio-ffmpeg", "aiofiles"]},
    entry_points={"console_scripts": ["cli-anything-omnidownloader=cli_anything.omnidownloader.omnidownloader_cli:main"]},
)
