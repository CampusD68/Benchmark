# Benchmark

This project contains a simple cross-platform C++ console application that mimics the basic layout of the Linux `top` command. It collects CPU usage, memory usage, and process counts using native APIs on both Windows and Linux.

## Building

The project is configured with CMake (C++17).

### Windows (Visual Studio or MSVC)
```powershell
cd benchmark_tool
cmake -S . -B build -G "Visual Studio 17 2022"
cmake --build build --config Release
```

To run:

```powershell
.\build\Release\benchmark_tool.exe
```

### Ubuntu / WSL2
```bash
cd benchmark_tool
cmake -S . -B build
cmake --build build
./build/benchmark_tool
```

## Usage

Run the resulting executable from a terminal. It refreshes once per second and clears the screen to present a `top`-style snapshot. Press `Ctrl+C` to exit.

Notes:

- Load averages are only available on Linux; the program prints `N/A` on Windows.
- Task counts include every running process. Detailed per-state counts (sleeping, zombie, etc.) are currently placeholders.
