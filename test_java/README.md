# Java Benchmark Monitor

Java版の簡易トップ風モニターです。`BenchmarkMonitor` が Windows / Linux (WSL2 を含む) で動作し、C++ 版と同じレイアウトで CPU・メモリ・タスク数などを表示します。

## ビルド／実行方法

Java 17 以降を想定しています。カレントディレクトリを `test_java` に移動してください。

```powershell
cd C:\Users\yoshidak\Desktop\benchmark_tool\test_java
javac -encoding UTF-8 -d out src\main\java\com\campusd68\benchmark\BenchmarkMonitor.java
java -cp out com.campusd68.benchmark.BenchmarkMonitor
```

WSL/Ubuntu の場合は次のとおりです。

```bash
cd /mnt/c/Users/yoshidak/Desktop/benchmark_tool/test_java
javac -encoding UTF-8 -d out src/main/java/com/campusd68/benchmark/BenchmarkMonitor.java
java -cp out com.campusd68.benchmark.BenchmarkMonitor
```

### 注意点
- Windows では PowerShell を利用して ANSI エスケープの許可と OS 起動時刻の取得を試みます。PowerShell が利用できない環境では表示が乱れたり uptime が 0 になる場合があります。
- Linux / WSL2 では `/proc` ファイルシステムから CPU/メモリ/ロードアベレージを取得します。必要に応じて `top` コマンドと同様に `Ctrl+C` で終了してください。
